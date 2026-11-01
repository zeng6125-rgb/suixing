package com.suixing.agent.service

import com.suixing.agent.data.Conversation

/**
 * 「这个会话的尾部要不要重新读」的轻量判据（issue #69 P1-A）。
 *
 * 悬浮窗每次会话列表发射都会走一遍渲染；列表只带 meta，正文要读 `readTail`。而列表发射
 * 有很多与正文无关（标题、排序、其它会话的新消息），每次都读一遍尾部就是纯浪费。
 *
 * 键取 `chatId + messageCount + updatedAt`：这三样任一变化才可能有新正文；
 * 同键重复发射只更新标题/状态，不再触发文件读取。
 */
internal class TailReadGate {

    private var chatId: String? = null
    private var stamp: String? = null

    /** 内容签名：会话变了、条数变了、最后更新时间变了，都算需要重读。 */
    private fun stampOf(conversation: Conversation): String =
        conversation.id + '|' + conversation.messageCount + '|' + conversation.updatedAt

    /** true = 需要读尾部；false = 同键重复，可以只更新元数据。 */
    fun shouldRead(conversation: Conversation): Boolean {
        val next = stampOf(conversation)
        if (next == stamp) return false
        chatId = conversation.id
        stamp = next
        return true
    }

    /** 换会话/退出时重置，避免把上一个会话的签名当成命中。 */
    fun reset() {
        chatId = null
        stamp = null
    }
}
