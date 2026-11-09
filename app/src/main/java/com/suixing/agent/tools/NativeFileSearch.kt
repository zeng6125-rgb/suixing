package com.suixing.agent.tools

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** JNI has no access to UI state or permissions; all inputs have already passed the file tool. */
internal object NativeFileSearchJni {
    external fun nativeCreate(): Long
    external fun nativeCancel(handle: Long)
    external fun nativeRelease(handle: Long)
    external fun nativeScan(handle: Long, request: ByteArray): ByteArray
}

internal data class NativeSearchRow(val fileIndex: Int, val number: Int, val marker: Int, val snippet: String)

internal data class NativeSearchBatch(
    val processedCount: Int,
    val compatibilityIndex: Int?,
    val rows: List<NativeSearchRow>,
)

/** Exact literal searches use a batched native scanner; unsupported queries keep Kotlin semantics. */
internal object NativeFileSearch {
    const val BATCH_SIZE = 16
    private const val MAGIC = 0x31525853
    private const val MAX_REQUEST_BYTES = 1024 * 1024
    private const val MAX_QUERY_BYTES = 256 * 1024
    private const val MAX_FILE_BYTES = 2 * 1024 * 1024
    internal val available: Boolean by lazy {
        try {
            System.loadLibrary("suixing_search")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun supports(query: String, caseSensitive: Boolean, regex: Boolean): Boolean =
        !regex && query.isNotEmpty() &&
            query.toByteArray(Charsets.UTF_8).let { it.size <= MAX_QUERY_BYTES && it.toString(Charsets.UTF_8) == query &&
                (caseSensitive || it.all { byte -> byte.toInt() >= 0 }) } &&
            available

    /** Library absence and unrepresentable paths fall back before any native work starts. */
    suspend fun scan(
        files: List<File>, query: String, context: Int, filesOnly: Boolean, limit: Int,
        caseSensitive: Boolean = true,
    ): NativeSearchBatch? = withContext(Dispatchers.IO) {
        require(files.size in 1..BATCH_SIZE && context in 0..5 && limit in 1..500)
        val request = encode(files, query, context, filesOnly, limit, caseSensitive) ?: return@withContext null
        if (!available) return@withContext null
        suspendCancellableCoroutine { continuation ->
            val handle = NativeFileSearchJni.nativeCreate()
            if (handle <= 0L) {
                continuation.resumeWithException(IOException("无法创建原生搜索任务"))
                return@suspendCancellableCoroutine
            }
            // Cancellation is delivered before scan returns, rather than waiting for Job completion.
            // A registry ID is safe if cancellation races with release; it is never a raw pointer.
            continuation.invokeOnCancellation { NativeFileSearchJni.nativeCancel(handle) }
            try {
                if (continuation.isActive) {
                    val result = decode(NativeFileSearchJni.nativeScan(handle, request), files.size, filesOnly, limit)
                    continuation.resume(result)
                }
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                // The synchronous native call has exited before its task registration is released.
                NativeFileSearchJni.nativeRelease(handle)
            }
        }
    }

    private fun encode(files: List<File>, query: String, context: Int, filesOnly: Boolean, limit: Int, caseSensitive: Boolean): ByteArray? {
        val strings = (listOf(query) + files.map { it.absolutePath }).map { value ->
            value.toByteArray(Charsets.UTF_8).also {
                if (it.toString(Charsets.UTF_8) != value) return null
            }
        }
        if (strings.first().size > MAX_QUERY_BYTES) return null
        if (!caseSensitive && strings.first().any { it.toInt() < 0 }) return null
        val size = 24L + strings.sumOf { 4L + it.size }
        if (size > MAX_REQUEST_BYTES) return null
        val output = ByteArrayOutputStream(size.toInt())
        fun integer(value: Int) {
            repeat(4) { output.write(value ushr (it * 8) and 0xff) }
        }
        integer(MAGIC)
        integer(files.size)
        integer(limit)
        integer(context)
        integer((if (filesOnly) 1 else 0) or (if (caseSensitive) 0 else 2))
        integer(MAX_FILE_BYTES)
        strings.forEach { integer(it.size); output.write(it) }
        return output.toByteArray()
    }

    private fun decode(bytes: ByteArray, fileCount: Int, filesOnly: Boolean, limit: Int): NativeSearchBatch {
        if (bytes.size !in 16..MAX_REQUEST_BYTES) throw IOException("原生搜索结果大小无效")
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun integer(): Int {
            if (input.remaining() < 4) throw IOException("原生搜索结果不完整")
            return input.int
        }
        fun valid(condition: Boolean) {
            if (!condition) throw IOException("原生搜索结果格式无效")
        }
        valid(integer() == MAGIC)
        val processed = integer()
        val count = integer()
        val compatibility = integer().takeUnless { it == -1 }
        valid(processed in 1..fileCount && count in 0..limit)
        valid(compatibility == null || compatibility == processed - 1)
        val rows = ArrayList<NativeSearchRow>(count)
        var lastFile = -1
        var lastNumber = 0
        repeat(count) {
            val file = integer()
            val number = integer()
            val marker = integer()
            val length = integer()
            valid(file in 0 until processed && file != compatibility && file >= lastFile)
            valid(length in 0..200 && input.remaining() >= length * 2)
            valid(if (filesOnly) marker == 2 && number == 0 && length == 0 && file > lastFile
                else marker in 0..1 && number > 0 && (file > lastFile || number > lastNumber))
            val snippet = CharArray(length) { input.short.toInt().and(0xffff).toChar() }.concatToString()
            rows += NativeSearchRow(file, number, marker, snippet)
            lastFile = file
            lastNumber = number
        }
        valid(!input.hasRemaining())
        return NativeSearchBatch(processed, compatibility, rows)
    }
}
