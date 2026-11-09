package com.suixing.agent.llm

import java.io.IOException
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import com.suixing.agent.core.NativeJsonTree
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

internal object NativeSseStreamJni {
    external fun nativeCreate(): Long
    external fun nativeCancel(handle: Long)
    external fun nativeRelease(handle: Long)
    external fun nativeFeed(handle: Long, bytes: ByteArray, offset: Int, length: Int, mode: Int): ByteArray
}

internal data class NativeSseEvent(
    val consumed: Int,
    val chunk: JsonObject? = null,
    val compatibilityPayload: String? = null,
    val sawDone: Boolean = false,
    val eof: Boolean = false,
)

/** Rust decodes the complete JSON tree; Kotlin only reconstructs elements for StreamTurn. */
internal class NativeSseStream private constructor(id: Long) : Closeable {
    private val handle = AtomicLong(id)

    fun cancel() {
        handle.get().takeIf { it > 0L }?.let(NativeSseStreamJni::nativeCancel)
    }

    override fun close() {
        handle.getAndSet(0L).takeIf { it > 0L }?.let(NativeSseStreamJni::nativeRelease)
    }

    /** Return after at most one event. The caller immediately delivers it before feeding more. */
    fun feed(bytes: ByteArray, offset: Int, length: Int, eof: Boolean = false): NativeSseEvent {
        val id = handle.get()
        if (id <= 0L) throw LlmException("原生流式解析任务已释放")
        val response = decode(NativeSseStreamJni.nativeFeed(id, bytes, offset, length, if (eof) 1 else 0), length)
        if (response.rawLine == null) return response.event
        // readSseLine used the platform's UTF-8 replacement rules. An invalid byte sequence is
        // normalized once here, preserving that behavior without moving ordinary JSON parsing back
        // to Kotlin or replaying any frames already delivered by this native stream.
        val normalized = response.rawLine.toString(Charsets.UTF_8).toByteArray(Charsets.UTF_8)
        val resumed = decode(NativeSseStreamJni.nativeFeed(id, normalized, 0, normalized.size, 2), normalized.size)
        valid(resumed.rawLine == null && resumed.event.consumed == normalized.size)
        return resumed.event.copy(consumed = response.event.consumed)
    }

    private data class Decoded(val event: NativeSseEvent, val rawLine: ByteArray? = null)

    companion object {
        private fun valid(ok: Boolean) = NativeJsonTree.valid(ok)
        private fun decode(bytes: ByteArray, inputLength: Int): Decoded {
            valid(bytes.size in HEADER_BYTES..MAX_RESPONSE_BYTES)
            val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun integer(): Int = NativeJsonTree.integer(input)
            valid(integer() == MAGIC)
            val consumed = integer()
            val flags = integer()
            val kind = integer()
            val length = integer()
            valid(consumed in 0..inputLength && flags in 0..2 && length == input.remaining())
            val base = NativeSseEvent(consumed, sawDone = flags == 1, eof = flags == 2)
            val decoded = when (kind) {
                0 -> Decoded(base)
                1 -> {
                    val chunk = NativeJsonTree.element(input) as? JsonObject
                        ?: throw LlmException("原生流式响应不是 JSON 对象")
                    Decoded(base.copy(chunk = chunk))
                }
                2 -> Decoded(base.copy(compatibilityPayload = NativeJsonTree.string(input)))
                3 -> when (integer()) {
                    1 -> throw LlmException("流式响应无法解析：单行 SSE 数据过大，端点格式可能不兼容")
                    2 -> throw LlmException("流式响应无法解析：单个 SSE 事件超过 1 MiB 字符上限，端点格式可能不兼容")
                    else -> throw LlmException("原生流式响应错误类型无效")
                }
                4 -> {
                    valid(flags == 0 && length <= MAX_SSE_LINE_BYTES)
                    Decoded(base, ByteArray(length).also { input.get(it) })
                }
                else -> throw LlmException("原生流式响应事件类型无效")
            }
            valid(!input.hasRemaining())
            return decoded
        }
        internal fun decodeObject(bytes: ByteArray): JsonObject =
            decode(bytes, 0).event.chunk ?: throw LlmException("原生 JSON 响应不是对象")

        const val READ_BYTES = 8192
        private const val MAGIC = 0x31535853
        private const val HEADER_BYTES = 20
        private const val MAX_FRAME_UNITS = 1_048_576
        private const val MAX_RESPONSE_BYTES = 8 * 1_048_576 + HEADER_BYTES
        private const val MAX_DEPTH = 128
        private const val MAX_NODES = 131_072
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

        /** Fallback is chosen before reading any bytes; a running stream is never replayed. */
        fun open(): NativeSseStream? {
            if (!available) return null
            return try {
                NativeSseStreamJni.nativeCreate().takeIf { it > 0L }?.let(::NativeSseStream)
            } catch (_: UnsatisfiedLinkError) {
                null
            } catch (_: IOException) {
                null
            }
        }
    }
}
