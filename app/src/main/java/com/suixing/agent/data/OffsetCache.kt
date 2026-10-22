package com.suixing.agent.data

/**
 * 消息 id → 它在日志里的字节偏移的**小缓存**（每条会话一个槽）。
 *
 * 为什么需要：`ChatStore.offsetOf` 是**从日志开头分块扫描**找 id 的（O(文件大小)）。
 * `readWire` 每次发消息都要找「最后一个 checkpoint 的那条消息」的位置 ——
 * 而同一个 checkpoint 会被连续很多轮复用（直到下一次压缩生成新的）。
 * 30MB 的会话里，这等于**每发一条消息都扫 30MB**。
 *
 * 校验靠 `(gen, messageId)` 这一对：换代重写（编辑重答）会改 gen，消息被换掉会改 id，
 * 两种情况都会命中不了 ⇒ 回退到真正的扫描，不会返回错误的偏移。
 *
 * 只存一条（不是 LRU）：`readWire` 只会问「最后一个 checkpoint」那一条，
 * 存一条就够了，内存可忽略。
 */
internal class OffsetCache {

    private var chatId: String? = null
    private var gen: Int = -1
    private var messageId: String? = null
    private var offset: Long = -1L

    /** 命中返回偏移；未命中（换会话 / 换代次 / 换消息 / 从没存过）返回 `null`。 */
    fun get(chatId: String, gen: Int, messageId: String): Long? {
        if (this.chatId != chatId) return null
        if (this.gen != gen) return null
        if (this.messageId != messageId) return null
        return offset.takeIf { it >= 0L }
    }

    fun put(chatId: String, gen: Int, messageId: String, offset: Long) {
        this.chatId = chatId
        this.gen = gen
        this.messageId = messageId
        this.offset = offset
    }
}
