package com.suixing.agent.tools

import android.os.ParcelFileDescriptor
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object NativeFileReadJni {
    external fun nativeRead(handle: Long, fd: Int, offset: Int, limit: Int, maxBytes: Int): ByteArray?
}

internal object NativeFileRead {
    suspend fun scan(fd: FileDescriptor, offset: Int, limit: Int, maxBytes: Int): TextWindow? {
        currentCoroutineContext().ensureActive()
        if (!NativeFileSearch.available) return null
        return ParcelFileDescriptor.dup(fd).use { descriptor ->
            suspendCancellableCoroutine { continuation ->
                val handle = NativeFileSearchJni.nativeCreate()
                if (handle <= 0) {
                    continuation.resumeWithException(IOException("无法创建原生文本读取任务"))
                    return@suspendCancellableCoroutine
                }
                continuation.invokeOnCancellation { NativeFileSearchJni.nativeCancel(handle) }
                try {
                    if (continuation.isActive) {
                        val bytes = NativeFileReadJni.nativeRead(handle, descriptor.fd, offset, limit, maxBytes)
                        continuation.resume(bytes?.let { decode(it, offset, limit, maxBytes) })
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally { NativeFileSearchJni.nativeRelease(handle) }
            }
        }
    }

    private fun decode(bytes: ByteArray, offset: Int, limit: Int, maxBytes: Int): TextWindow {
        fun valid(ok: Boolean) { if (!ok) throw IOException("原生文本读取响应无效") }
        valid(bytes.size in 20..(20 + 5000 * 4 + 2 * maxBytes))
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun integer(): Int { valid(input.remaining() >= 4); return input.int }
        valid(integer() == 0x31545853)
        val total = integer()
        val count = integer()
        val length = integer()
        val hash = integer()
        valid(total in 1..(maxBytes + 1) && count in 0..limit && length in 0..maxBytes)
        valid(count == if (offset > total) 0 else minOf(limit, total - offset + 1))
        val lines = List(count) {
            val units = integer()
            valid(units in 0..length && units.toLong() * 2 <= input.remaining())
            CharArray(units) { input.short.toInt().and(0xffff).toChar() }.concatToString()
        }
        valid(!input.hasRemaining())
        return TextWindow(total, lines, "${length.toString(16)}-${hash.toUInt().toString(16)}")
    }
}
