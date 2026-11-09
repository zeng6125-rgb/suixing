package com.suixing.agent.agent

import com.suixing.agent.core.NativeCore
import com.suixing.agent.llm.ChatMessage
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object NativeRequestBudgetJni {
    external fun nativeSelect(indices: IntArray, offsets: IntArray, lengths: IntArray, budget: Long): ByteArray?
}

/** Supplies only screenshot carrier indices and lengths; Rust owns accumulation and selection. */
internal object NativeRequestBudget {
    private const val MAGIC = 0x31425853
    private const val MAX_CARRIERS = 65_536
    private const val MAX_IMAGES = 262_144
    private const val HEADER_BYTES = 32

    /** Null chooses Kotlin before any messages are modified; empty means the original wire fits. */
    fun select(wire: List<ChatMessage>, budgetChars: Int): IntArray? {
        if (budgetChars <= 0 || !NativeCore.available) return null
        var carriers = 0
        var images = 0
        for (message in wire) {
            if (!message.toolScreenshot) continue
            val count = message.imageCount
            if (count == 0) continue
            if (carriers == MAX_CARRIERS || count > MAX_IMAGES - images) return null
            carriers++
            images += count
        }
        if (carriers == 0) return IntArray(0)
        val indices = IntArray(carriers)
        val offsets = IntArray(carriers + 1)
        val lengths = IntArray(images)
        var carrier = 0
        var image = 0
        wire.forEachIndexed { index, message ->
            if (message.toolScreenshot && message.imageCount > 0) {
                indices[carrier] = index
                if (message.imagesBase64.isNotEmpty()) {
                    // The plural list takes precedence over the legacy single image, as allImages
                    // does. String lengths are read directly; Base64 contents never cross JNI.
                    for (value in message.imagesBase64) lengths[image++] = value.length
                } else {
                    lengths[image++] = checkNotNull(message.imageBase64).length
                }
                offsets[++carrier] = image
            }
        }
        check(carrier == carriers && image == images) { "请求图片元数据在预算选择期间发生变化" }
        val bytes = try {
            NativeRequestBudgetJni.nativeSelect(indices, offsets, lengths, budgetChars.toLong())
        } catch (_: UnsatisfiedLinkError) {
            // A missing native entry point is unavailable before selection, like a missing library.
            return null
        } ?: return null
        return decode(bytes, indices, images, budgetChars.toLong())
    }

    private fun decode(bytes: ByteArray, indices: IntArray, images: Int, budget: Long): IntArray {
        fun valid(condition: Boolean) {
            if (!condition) throw IOException("原生请求图片预算结果格式无效")
        }
        valid(bytes.size in HEADER_BYTES..HEADER_BYTES + indices.size * 4)
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        valid(input.int == MAGIC && input.int == indices.size && input.int == images)
        val count = input.int
        val total = input.long
        val remaining = input.long
        valid(count in 0..indices.size && input.remaining() == count * 4)
        valid(total in 0..images.toLong() * Int.MAX_VALUE && remaining in 0..total)
        valid(if (total <= budget) count == 0 && remaining == total
            else count > 0 && remaining <= budget && remaining < total)
        return IntArray(count) { selected ->
            input.int.also { valid(it == indices[selected]) }
        }
    }
}
