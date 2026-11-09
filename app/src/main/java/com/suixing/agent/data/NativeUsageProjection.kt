package com.suixing.agent.data

import android.os.ParcelFileDescriptor
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object NativeUsageProjectionJni {
    external fun nativeCreate(): Long
    external fun nativeCancel(handle: Long)
    external fun nativeRelease(handle: Long)
    /** Null requests a complete Kotlin rescan of this same committed descriptor. */
    external fun nativeProject(handle: Long, fd: Int, start: Long, end: Long): ByteArray?
}

/** Only the fields needed for usage; transcript bodies never cross JNI. */
internal data class UsageProjectedMessage(
    val index: Int,
    val timestamp: Long,
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val reasoningTokens: Int,
    val cachedTokens: Int,
) {
    val usageKnown: Boolean get() = promptTokens > 0 || completionTokens > 0
    fun tokens(): TokenTally = TokenTally(
        promptTokens.coerceAtLeast(0).toLong(), completionTokens.coerceAtLeast(0).toLong(),
        reasoningTokens.coerceAtLeast(0).toLong(), cachedTokens.coerceAtLeast(0).toLong(),
    )
}

internal data class UsageProjection(
    /** Includes user and tool messages; assistant indices refer to this complete sequence. */
    val messageCount: Int,
    val endOffset: Long?,
    val assistants: List<UsageProjectedMessage>,
)

/** Kotlin owns the committed file, cache, date rules and all mutations. Rust only reads a range. */
internal object NativeUsageProjection {
    private const val MAGIC = 0x31555853
    private const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024
    private val available: Boolean by lazy {
        try {
            System.loadLibrary("suixing_search")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    suspend fun project(fd: FileDescriptor, start: Long, end: Long): UsageProjection? =
        withContext(Dispatchers.IO) {
            require(start in 0..end)
            if (start == end) return@withContext UsageProjection(0, null, emptyList())
            if (!available) return@withContext null
            // Duplication pins this generation throughout native scan AND cancellation. Native
            // pread borrows it without changing the RandomAccessFile's seek position.
            ParcelFileDescriptor.dup(fd).use { descriptor ->
                suspendCancellableCoroutine { continuation ->
                    val handle = NativeUsageProjectionJni.nativeCreate()
                    if (handle <= 0) {
                        continuation.resumeWithException(IOException("无法创建原生用量读取任务"))
                        return@suspendCancellableCoroutine
                    }
                    continuation.invokeOnCancellation { NativeUsageProjectionJni.nativeCancel(handle) }
                    try {
                        if (continuation.isActive) {
                            val bytes = NativeUsageProjectionJni.nativeProject(handle, descriptor.fd, start, end)
                            continuation.resume(bytes?.let { decode(it, start, end) })
                        }
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    } finally {
                        // The synchronous reader has exited before either owner is released.
                        NativeUsageProjectionJni.nativeRelease(handle)
                    }
                }
            }
        }

    internal fun decode(bytes: ByteArray, start: Long, end: Long): UsageProjection {
        if (bytes.size !in 20..MAX_RESPONSE_BYTES) throw IOException("原生用量结果大小无效")
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun requireBytes(size: Int) {
            if (input.remaining() < size) throw IOException("原生用量结果不完整")
        }
        fun integer(): Int { requireBytes(4); return input.int }
        fun long(): Long { requireBytes(8); return input.long }
        fun valid(condition: Boolean) {
            if (!condition) throw IOException("原生用量结果格式无效")
        }
        valid(integer() == MAGIC)
        val count = integer()
        val rowCount = integer()
        val offset = long()
        valid(count >= 0 && rowCount in 0..count && rowCount <= input.remaining() / 32)
        valid(if (count == 0) offset == start else offset == end)
        val rows = ArrayList<UsageProjectedMessage>(rowCount)
        var previous = -1
        repeat(rowCount) {
            val index = integer()
            val timestamp = long()
            val prompt = integer()
            val completion = integer()
            val reasoning = integer()
            val cached = integer()
            val length = integer()
            valid(index in 0 until count && index > previous && length >= 0 && length <= input.remaining())
            val modelBytes = ByteArray(length)
            input.get(modelBytes)
            val model = modelBytes.toString(Charsets.UTF_8)
            valid(model.toByteArray(Charsets.UTF_8).contentEquals(modelBytes))
            rows += UsageProjectedMessage(index, timestamp, model, prompt, completion, reasoning, cached)
            previous = index
        }
        valid(!input.hasRemaining())
        return UsageProjection(count, offset.takeIf { count > 0 }, rows)
    }
}
