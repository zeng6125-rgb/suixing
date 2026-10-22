package com.suixing.agent.data

/**
 * 消息 id → 它在日志里的字节偏移的**小缓存**（按会话与代次分槽）。
 *
 * 为什么需要：`ChatStore.offsetOf` 是**从日志开头分块扫描**找 id 的（O(文件大小)）。
 * `readWire` 每次发消息都要找「最后一个 checkpoint 的那条消息」的位置 ——
 * 而同一个 checkpoint 会被连续很多轮复用（直到下一次压缩生成新的）。
 * 30MB 的会话里，这等于**每发一条消息都扫 30MB**。
 *
 * **为什么不是一个槽**（评审 S2）：用户在 A/B 会话之间来回发消息时，单槽会被两边互相踢掉，
 * 结果两个会话都退回全文件扫描 —— 而"来回切会话"恰恰是这个应用最常做的事。
 * 这里按 `(chatId, gen)` 分槽，容量小（[CAPACITY] 条），淘汰最久未用的。
 *
 * 校验靠 `(gen, messageId)` 这一对：换代重写（编辑重答）会改 gen，消息被换掉会改 id，
 * 两种情况都会命中不了 ⇒ 回退到真正的扫描，不会返回错误的偏移。
 */
internal class OffsetCache(private val capacity: Int = CAPACITY) {

    private data class Entry(val gen: Int, val messageId: String, val offset: Long)

    private val entries = object : LinkedHashMap<Pair<String, Int>, Entry>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Int>, Entry>?): Boolean =
            size > capacity
    }

    /** 命中返回偏移；未命中（换代次 / 换消息 / 从没存过）返回 `null`。 */
    fun get(chatId: String, gen: Int, messageId: String): Long? = synchronized(entries) {
        val entry = entries[chatId to gen] ?: return@synchronized null
        if (entry.messageId != messageId) return@synchronized null
        entry.offset.takeIf { it >= 0L }
    }

    fun put(chatId: String, gen: Int, messageId: String, offset: Long) = synchronized(entries) {
        entries[chatId to gen] = Entry(gen, messageId, offset)
    }

    /** 仅供单测断言上界。 */
    internal fun sizeForTest(): Int = synchronized(entries) { entries.size }

    companion object {
        internal const val CAPACITY = 4
    }
}
