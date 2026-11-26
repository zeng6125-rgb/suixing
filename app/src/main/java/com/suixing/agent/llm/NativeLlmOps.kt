package com.suixing.agent.llm

import com.suixing.agent.core.NativeCore
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object NativeLlmOpsJni {
    external fun nativeIsJsonObject(text: String): Int
    external fun nativeParseModels(body: String, details: Boolean, outStatus: IntArray): ByteArray?
    external fun nativeExtractError(body: String): String?
}

internal object NativeLlmOps {
    private const val MAX_OBJECT_UNITS = 1024 * 1024
    private const val MAX_PAYLOAD_UNITS = 4 * 1024 * 1024
    private const val STATUS_OK = 0
    private const val STATUS_EMPTY = 10
    private const val STATUS_MISSING_FIELD = 11
    private const val STATUS_NO_MODELS = 12

    fun isJsonObject(text: String): Boolean? {
        if (!NativeCore.available || text.length > MAX_OBJECT_UNITS) return null
        return try {
            when (NativeLlmOpsJni.nativeIsJsonObject(text)) {
                0 -> false
                1 -> true
                else -> null
            }
        } catch (_: UnsatisfiedLinkError) {
            null
        }
    }

    fun parseModels(body: String): List<String>? = parseModelsRaw(body)

    fun parseModelDetails(body: String): List<ProviderModel>? = parseModelEntriesRaw(body)

    private fun parseModelsRaw(body: String): List<String>? {
        val buf = readModelsBuffer(body, details = false) ?: return null
        val count = buf.int
        if (count <= 0) return null
        val out = ArrayList<String>(count)
        repeat(count) {
            if (buf.remaining() < 8) return null
            buf.int // ctx unused
            val unitLen = buf.int
            if (unitLen <= 0 || buf.remaining() < unitLen * 2) return null
            val chars = CharArray(unitLen) { buf.short.toInt().and(0xffff).toChar() }
            out += chars.concatToString()
        }
        return if (buf.hasRemaining()) null else out
    }

    private fun parseModelEntriesRaw(body: String): List<ProviderModel>? {
        val buf = readModelsBuffer(body, details = true) ?: return null
        val count = buf.int
        if (count <= 0) return null
        val out = ArrayList<ProviderModel>(count)
        repeat(count) {
            if (buf.remaining() < 8) return null
            val ctx = buf.int
            val unitLen = buf.int
            if (unitLen <= 0 || buf.remaining() < unitLen * 2) return null
            val chars = CharArray(unitLen) { buf.short.toInt().and(0xffff).toChar() }
            out += ProviderModel(chars.concatToString(), ctx.takeIf { it > 0 })
        }
        return if (buf.hasRemaining()) null else out
    }

    private fun readModelsBuffer(body: String, details: Boolean): ByteBuffer? {
        if (!NativeCore.available || body.length > MAX_PAYLOAD_UNITS) return null
        val status = IntArray(1) { 4 }
        val bytes = try {
            NativeLlmOpsJni.nativeParseModels(body, details, status)
        } catch (_: UnsatisfiedLinkError) {
            return null
        }
        when (status[0]) {
            STATUS_EMPTY -> throw LlmException("服务器返回了空内容")
            STATUS_MISSING_FIELD -> throw LlmException("返回里没有 data / models 字段")
            STATUS_NO_MODELS -> throw LlmException("服务器没有返回任何模型")
            STATUS_OK -> {}
            else -> return null
        }
        if (bytes == null || bytes.size < 4) return null
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    }

    fun extractApiError(body: String): String? {
        if (!NativeCore.available || body.length > MAX_PAYLOAD_UNITS) return null
        return try {
            NativeLlmOpsJni.nativeExtractError(body)
        } catch (_: UnsatisfiedLinkError) {
            null
        }
    }
}
