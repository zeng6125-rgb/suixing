package com.suixing.agent.ui.drawer

import com.suixing.agent.core.NativeCore
import com.suixing.agent.data.Conversation
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object NativeDrawerOrderJni {
    external fun nativePlan(request: ByteArray): ByteArray?
}

/** Only numeric ID tokens, timestamps and flags cross JNI; original UI objects stay in Kotlin. */
internal object NativeDrawerOrder {
    private const val MAGIC = 0x314F5853
    private const val MAX_PROTOCOL = 4 * 1024 * 1024
    private const val MAX_RESPONSE = 4 * 1024 * 1024
    private const val MAX_ITEMS = 65_536
    private const val MAX_IDS = 131_072
    private const val MIN_WORK = 128

    fun sections(conversations: List<Conversation>, order: DrawerOrder): DrawerSections? {
        if (conversations.size > MAX_ITEMS || order.pinned.size > MAX_ITEMS || order.normal.size > MAX_ITEMS ||
            conversations.size.toLong() + order.pinned.size + order.normal.size < MIN_WORK) return null
        if (!NativeCore.available) return null
        return fallbackOnBridgeFailure {
            val source = conversations.toList()
            val pinned = order.pinned.toList()
            val normal = order.normal.toList()
            val length = 24L + source.size * 16L + (pinned.size.toLong() + normal.size) * 4
            val builder = Builder(1, source.size, pinned.size, normal.size, length)
            for (conversation in source) {
                if (!builder.reference(conversation.id)) return@fallbackOnBridgeFailure null
                builder.integer((if (conversation.pinned) 1 else 0) or (if (conversation.archived) 2 else 0))
                builder.long(conversation.updatedAt)
            }
            for (id in pinned) if (!builder.reference(id)) return@fallbackOnBridgeFailure null
            for (id in normal) if (!builder.reference(id)) return@fallbackOnBridgeFailure null
            val bytes = NativeDrawerOrderJni.nativePlan(builder.finish()) ?: return@fallbackOnBridgeFailure null
            val reader = Reader(bytes, 1)
            val pinnedCount = reader.integer()
            val normalCount = reader.integer()
            val maximum = source.size.toLong() + pinned.size + normal.size
            reader.valid(pinnedCount >= 0 && normalCount >= 0 && pinnedCount.toLong() + normalCount <= maximum)
            reader.valid((pinnedCount.toLong() + normalCount) * 4 == reader.buffer.remaining().toLong())
            fun section(count: Int, pin: Boolean): List<Conversation> = List(count) {
                val index = reader.integer()
                reader.valid(index in source.indices)
                source[index].also { reader.valid(!it.archived && it.pinned == pin) }
            }
            DrawerSections(section(pinnedCount, true), section(normalCount, false))
        }
    }

    fun prune(order: DrawerOrder, known: Set<String>): DrawerOrder? {
        val work = order.pinned.size.toLong() + order.normal.size
        val ordinarySet = known.javaClass == java.util.HashSet::class.java ||
            known.javaClass == java.util.LinkedHashSet::class.java
        // The original membership filters cost O(order). Avoid scanning a much larger known set,
        // and preserve custom Set membership (e.g. sorted comparators) via the Kotlin fallback.
        if (!ordinarySet || work < MIN_WORK || known.size.toLong() > work * 2 ||
            known.size > MAX_IDS || order.pinned.size > MAX_ITEMS || order.normal.size > MAX_ITEMS) return null
        if (!NativeCore.available) return null
        return fallbackOnBridgeFailure {
            val source = known.toList()
            val pinned = order.pinned.toList()
            val normal = order.normal.toList()
            val length = 24L + (source.size.toLong() + pinned.size + normal.size) * 4
            val builder = Builder(2, source.size, pinned.size, normal.size, length)
            for (id in source) if (!builder.reference(id)) return@fallbackOnBridgeFailure null
            for (id in pinned) if (!builder.reference(id)) return@fallbackOnBridgeFailure null
            for (id in normal) if (!builder.reference(id)) return@fallbackOnBridgeFailure null
            val bytes = NativeDrawerOrderJni.nativePlan(builder.finish()) ?: return@fallbackOnBridgeFailure null
            val reader = Reader(bytes, 2)
            val pinnedCount = reader.integer()
            val normalCount = reader.integer()
            reader.valid(pinnedCount in 0..pinned.size && normalCount in 0..normal.size)
            reader.valid((pinnedCount.toLong() + normalCount) * 4 == reader.buffer.remaining().toLong())
            fun kept(values: List<String>, count: Int): List<String> {
                var previous = -1
                return List(count) {
                    val index = reader.integer()
                    reader.valid(index in values.indices && index > previous)
                    previous = index
                    values[index].also { reader.valid(it in known) }
                }
            }
            DrawerOrder(kept(pinned, pinnedCount), kept(normal, normalCount))
        }
    }

    private inline fun <T> fallbackOnBridgeFailure(block: () -> T?): T? = try { block() }
        catch (_: UnsatisfiedLinkError) { null }
        catch (_: IOException) { null }

    private class Builder(mode: Int, count: Int, pinned: Int, normal: Int, length: Long) {
        private val ids = HashMap<String, Int>()
        private val bytes = if (length in 24L..MAX_PROTOCOL.toLong()) ByteArray(length.toInt())
            else throw IOException("抽屉原生顺序请求超过预算")
        private val output = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        init { integer(MAGIC); integer(mode); integer(0); integer(count); integer(pinned); integer(normal) }
        fun integer(value: Int) { output.putInt(value) }
        fun long(value: Long) { output.putLong(value) }
        fun reference(value: String): Boolean {
            val existing = ids[value]
            val id = existing ?: run {
                if (ids.size >= MAX_IDS) return false
                ids.size.also { ids[value] = it }
            }
            integer(id)
            return true
        }
        fun finish(): ByteArray {
            if (output.hasRemaining()) throw IOException("抽屉原生顺序请求不完整")
            output.putInt(8, ids.size)
            return bytes
        }
    }
    private class Reader(bytes: ByteArray, mode: Int) {
        val buffer: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        init { valid(bytes.size in 16..MAX_RESPONSE); valid(integer() == MAGIC && integer() == mode) }
        fun valid(value: Boolean) { if (!value) throw IOException("抽屉原生顺序结果无效") }
        fun integer(): Int { valid(buffer.remaining() >= 4); return buffer.int }
    }
}
