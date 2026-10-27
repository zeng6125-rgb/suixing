package com.suixing.agent.agent

import com.suixing.agent.llm.ChatMessage
import java.util.RandomAccess

internal data class PromptSize(val historyChars: Int, val totalChars: Int, val imageMessages: Int)

/** Counts follow every list mutation, including replacements, iterator edits and compression. */
internal class PromptMessages : AbstractMutableList<ChatMessage>(), RandomAccess {
    private val messages = ArrayList<ChatMessage>()
    private var historyChars = 0
    private var totalChars = 0
    private var imageMessages = 0
    val counts: PromptSize get() = PromptSize(historyChars, totalChars, imageMessages)

    override val size: Int get() = messages.size
    override fun get(index: Int): ChatMessage = messages[index]

    override fun add(index: Int, element: ChatMessage) {
        messages.add(index, element)
        account(element, 1)
        modCount++
    }

    override fun removeAt(index: Int): ChatMessage = messages.removeAt(index).also {
        account(it, -1)
        modCount++
    }

    override fun set(index: Int, element: ChatMessage): ChatMessage = messages.set(index, element).also {
        account(it, -1)
        account(element, 1)
    }

    override fun clear() {
        if (messages.isEmpty()) return
        messages.clear()
        historyChars = 0
        totalChars = 0
        imageMessages = 0
        modCount++
    }

    private fun account(message: ChatMessage, direction: Int) {
        val chars = promptMessageChars(message) * direction
        totalChars += chars
        if (message.role != "system") historyChars += chars
        if (message.imageCount > 0) imageMessages += direction
    }
}

internal fun promptMessageChars(message: ChatMessage): Int =
    message.text.length + message.toolCalls.sumOf { it.name.length + it.arguments.length }
