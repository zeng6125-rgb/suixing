package com.suixing.agent.tools

import android.os.SystemClock
import androidx.annotation.Keep
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal object NativeFileFindJni {
    external fun nativeScan(handle: Long, request: ByteArray, predicate: NativeFindPredicate): ByteArray?
}

/** Only the predicates needing Java's Unicode or unusual glob behavior come back to Kotlin. */
@Keep
internal class NativeFindPredicate(
    root: File, private val name: String, pattern: String?, private val context: CoroutineContext,
) {
    private val matcher = pattern?.let { Glob.pathMatcher(it, root) }
    private val descend = Glob.directoryMatcher(pattern, root)

    @Keep
    fun matches(path: String, mode: Int): Boolean {
        context.ensureActive()
        val file = File(path)
        val value = when (mode) {
            0 -> matcher?.invoke(file) ?: file.name.contains(name, ignoreCase = true)
            1 -> descend(file)
            else -> throw IOException("原生文件查找谓词无效")
        }
        context.ensureActive()
        return value
    }
}

internal data class NativeFindResult(val visited: Int, val reason: Int, val paths: List<String>)

internal object NativeFileFind {
    private const val MAGIC = 0x31465853
    private const val MAX_REQUEST = 1024 * 1024
    private const val MAX_RESPONSE = 4 * 1024 * 1024
    private const val TIME_BUDGET_OFFSET = 20

    suspend fun scan(
        root: File, name: String, pattern: String?, exclude: String?, kind: String,
        maxDepth: Int, limit: Int, maxVisited: Int, timeBudgetMs: Int, startedAt: Long,
    ): NativeFindResult? = withContext(Dispatchers.IO) {
        if (!NativeFileSearch.available) return@withContext null
        fun remainingBudget(): Int =
            (timeBudgetMs.toLong() - (SystemClock.uptimeMillis() - startedAt)).coerceAtLeast(0L).toInt()
        val context = currentCoroutineContext()
        context.ensureActive()
        if (remainingBudget() == 0) return@withContext NativeFindResult(0, 3, emptyList())
        val values = listOf(root.absolutePath, name, pattern.orEmpty(), exclude.orEmpty())
        val strings = values.map { value ->
            value.toByteArray(Charsets.UTF_8).also { if (it.toString(Charsets.UTF_8) != value) return@withContext null }
        }
        val size = 24L + strings.sumOf { 4L + it.size }
        if (size > MAX_REQUEST || '\u0000' in root.absolutePath) return@withContext null
        val output = ByteArrayOutputStream(size.toInt())
        fun integer(value: Int) { repeat(4) { output.write(value ushr (it * 8) and 0xff) } }
        integer(MAGIC)
        integer(when (kind) { "dir" -> 1; "any" -> 2; else -> 0 })
        integer(maxDepth)
        integer(limit)
        integer(maxVisited)
        integer(0) // Filled immediately before JNI, after encoding and predicate construction.
        strings.forEach { integer(it.size); output.write(it) }
        val request = output.toByteArray()
        context.ensureActive()
        if (remainingBudget() == 0) return@withContext NativeFindResult(0, 3, emptyList())
        val predicate = NativeFindPredicate(root, name, pattern, context)
        context.ensureActive()
        if (remainingBudget() == 0) return@withContext NativeFindResult(0, 3, emptyList())
        suspendCancellableCoroutine { continuation ->
            val handle = NativeFileSearchJni.nativeCreate()
            if (handle <= 0) {
                continuation.resumeWithException(IOException("无法创建原生文件查找任务"))
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { NativeFileSearchJni.nativeCancel(handle) }
            try {
                if (continuation.isActive) {
                    val remaining = remainingBudget()
                    if (remaining == 0) {
                        continuation.resume(NativeFindResult(0, 3, emptyList()))
                    } else {
                        ByteBuffer.wrap(request).order(ByteOrder.LITTLE_ENDIAN).putInt(TIME_BUDGET_OFFSET, remaining)
                        val bytes = NativeFileFindJni.nativeScan(handle, request, predicate)
                        continuation.resume(bytes?.let { decode(it, limit, maxVisited) })
                    }
                }
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                NativeFileSearchJni.nativeRelease(handle)
            }
        }
    }

    private fun decode(bytes: ByteArray, limit: Int, maxVisited: Int): NativeFindResult {
        if (bytes.size !in 16..MAX_RESPONSE) throw IOException("原生文件查找结果大小无效")
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun integer(): Int {
            if (input.remaining() < 4) throw IOException("原生文件查找结果不完整")
            return input.int
        }
        fun valid(condition: Boolean) { if (!condition) throw IOException("原生文件查找结果格式无效") }
        valid(integer() == MAGIC)
        val visited = integer()
        val reason = integer()
        val count = integer()
        valid(visited in 0..maxVisited && reason in 0..3 && count in 0..limit && count <= visited)
        val paths = ArrayList<String>(count)
        repeat(count) {
            val length = integer()
            valid(length > 0 && length <= input.remaining())
            val encoded = ByteArray(length)
            input.get(encoded)
            val path = encoded.toString(Charsets.UTF_8)
            valid(File(path).isAbsolute && '\u0000' !in path && path.toByteArray(Charsets.UTF_8).contentEquals(encoded))
            paths += path
        }
        valid(!input.hasRemaining())
        return NativeFindResult(visited, reason, paths)
    }
}
