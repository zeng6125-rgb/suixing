package com.suixing.agent.data

import com.suixing.agent.core.NativeCore
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object NativeMemoryPromptJni {
    /** Null requests the complete Kotlin formatter for a budget-limited snapshot. */
    external fun nativeBuild(request: ByteArray): ByteArray?
}

/** Raw UTF-16 preserves Java key ordering and a value clipped between surrogate code units. */
internal object NativeMemoryPrompt {
    private const val MAGIC = 0x31505853
    private const val MAX_REQUEST_BYTES = 4 * 1024 * 1024
    private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    private const val MAX_ENTRIES = 65_536

    fun build(entries: Set<Map.Entry<String, MemoryEntry>>): String? {
        if (!NativeCore.available || entries.size > MAX_ENTRIES) return null
        var size = 8L
        for ((key, entry) in entries) {
            size += 8L + 2L * key.length + 2L * minOf(entry.value.length, 400)
            if (size > MAX_REQUEST_BYTES) return null
        }
        val output = ByteBuffer.allocate(size.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        output.putInt(MAGIC).putInt(entries.size)
        fun string(value: String, units: Int = value.length) {
            output.putInt(units)
            for (index in 0 until units) output.putShort(value[index].code.toShort())
        }
        for ((key, entry) in entries) { string(key); string(entry.value, minOf(entry.value.length, 400)) }
        val bytes = try {
            NativeMemoryPromptJni.nativeBuild(output.array())
        } catch (_: UnsatisfiedLinkError) {
            return null
        } ?: return null
        if (bytes.size !in 8..MAX_RESPONSE_BYTES) throw IOException("原生记忆提示结果大小无效")
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (input.int != MAGIC) throw IOException("原生记忆提示结果格式无效")
        val units = input.int
        if (units < 0 || units.toLong() * 2 != input.remaining().toLong()) {
            throw IOException("原生记忆提示结果不完整")
        }
        // No UTF-8 round trip: unpaired surrogates and take(400)'s exact cut remain intact.
        return CharArray(units) { input.short.toInt().and(0xffff).toChar() }.concatToString()
    }
}
