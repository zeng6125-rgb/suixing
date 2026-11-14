package com.suixing.agent.service

import com.suixing.agent.data.Conversation
import com.suixing.agent.data.StoredMessage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    private val lock = Mutex()
    private var stamp: Triple<String, Int, Long>? = null
    private var messages: List<StoredMessage> = emptyList()

    suspend fun read(
        conversation: Conversation,
        readTail: suspend (String) -> List<StoredMessage>,
    ): List<StoredMessage> = lock.withLock {
        val next = Triple(conversation.id, conversation.messageCount, conversation.updatedAt)
        if (conversation.messages.isNotEmpty()) {
            messages = conversation.messages
            stamp = next
        } else if (next != stamp) {
            val loaded = readTail(conversation.id)
            currentCoroutineContext().ensureActive()
            // Publish the key with its body, only after the suspended read succeeds.
            messages = loaded
            stamp = next
        }
        messages
    }
}
