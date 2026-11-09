package com.suixing.agent.tools

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine

internal object NativeFileEditsJni {
    external fun nativeApply(
        handle: Long, text: String, old: String, replacement: String, replaceAll: Boolean,
        oversizedReplacement: Boolean, maxUnits: Int,
    ): ByteArray?
}

internal data class NativeFileEditResult(
    val count: Int,
    val lines: List<Int>,
    val changed: String?,
    val tooLarge: Boolean,
    val revision: String?,
)

/** Pure UTF-16 literal matching; file access, revision checks and commits stay in FileTools. */
internal object NativeFileEdits {
    private const val MAGIC = 0x31455853
    private const val MAX_UNITS = 4 * 1024 * 1024
    private const val SUMMARY_LIMIT = 8
    private const val HEADER_BYTES = 24
    private const val SUMMARY_ONLY = 0
    private const val CHANGED = 1
    private const val TOO_LARGE = 2

    /** The caller runs on IO. Null means bounded-input compatibility or library absence only. */
    suspend fun apply(
        text: String, old: String, replacement: String, replaceAll: Boolean, maxUnits: Int,
    ): NativeFileEditResult? {
        require(old.isNotEmpty() && maxUnits in 1..MAX_UNITS)
        val context = currentCoroutineContext()
        context.ensureActive()
        if (text.length > maxUnits || !NativeFileSearch.available) return null
        // An impossible needle does not need a potentially very large JNI string copy.
        if (old.length > text.length) {
            context.ensureActive()
            return NativeFileEditResult(0, emptyList(), null, false, null)
        }
        val oversizedReplacement = replacement.length > maxUnits
        return suspendCancellableCoroutine { continuation ->
            val handle = NativeFileSearchJni.nativeCreate()
            if (handle <= 0) {
                continuation.resumeWithException(IOException("无法创建原生文本编辑任务"))
                return@suspendCancellableCoroutine
            }
            // Registry IDs remain safe if cancellation races with release after the native call.
            continuation.invokeOnCancellation { NativeFileSearchJni.nativeCancel(handle) }
            try {
                if (continuation.isActive) {
                    val bytes = NativeFileEditsJni.nativeApply(
                        handle, text, old, if (oversizedReplacement) "" else replacement,
                        replaceAll, oversizedReplacement, maxUnits,
                    )
                    continuation.resume(bytes?.let {
                        decode(it, text.length, old.length, replacement.length, replaceAll, maxUnits, context)
                    })
                }
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally {
                NativeFileSearchJni.nativeRelease(handle)
            }
        }
    }

    private fun decode(
        bytes: ByteArray, textUnits: Int, oldUnits: Int, replacementUnits: Int,
        replaceAll: Boolean, maxUnits: Int, context: CoroutineContext,
    ): NativeFileEditResult {
        fun valid(condition: Boolean) {
            if (!condition) throw IOException("原生文本编辑响应无效")
        }
        context.ensureActive()
        valid(bytes.size in HEADER_BYTES..(HEADER_BYTES + SUMMARY_LIMIT * 4 + maxUnits * 2))
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun integer(): Int {
            valid(input.remaining() >= 4)
            return input.int
        }
        valid(integer() == MAGIC)
        val kind = integer()
        val count = integer()
        val summaryCount = integer()
        val changedUnits = integer()
        val hash = integer()
        valid(kind in SUMMARY_ONLY..TOO_LARGE && count in 0..(textUnits / oldUnits))
        valid(summaryCount == minOf(SUMMARY_LIMIT, count) && changedUnits in 0..maxUnits)
        valid(input.remaining().toLong() == summaryCount * 4L + changedUnits * 2L)
        val canReplace = count > 0 && (replaceAll || count == 1)
        valid((kind == SUMMARY_ONLY) == !canReplace)
        valid(kind == CHANGED || (changedUnits == 0 && hash == 0))
        val lines = List(summaryCount) { integer().also { valid(it in 1..(textUnits + 1)) } }
        valid(lines.zipWithNext().all { (before, after) -> before <= after })
        if (kind != CHANGED) {
            valid(!input.hasRemaining())
            context.ensureActive()
            return NativeFileEditResult(count, lines, null, kind == TOO_LARGE, null)
        }
        valid(replacementUnits <= maxUnits)
        val expectedUnits = textUnits.toLong() + count.toLong() * (replacementUnits.toLong() - oldUnits)
        valid(changedUnits.toLong() == expectedUnits && (changedUnits != 0 || hash == 0))
        val changed = CharArray(changedUnits) { at ->
            if ((at and 8191) == 0) context.ensureActive()
            input.short.toInt().and(0xffff).toChar()
        }.concatToString()
        valid(!input.hasRemaining())
        context.ensureActive()
        return NativeFileEditResult(
            count, lines, changed, false, "${changedUnits.toString(16)}-${hash.toUInt().toString(16)}",
        )
    }
}
