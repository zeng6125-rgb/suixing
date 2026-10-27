package com.suixing.agent.agent

import com.suixing.agent.llm.ChatMessage
import com.suixing.agent.llm.ToolCallRequest
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class PromptMessagesTest {
    private fun assertCounts(messages: PromptMessages) {
        val sizes = messages.map { message ->
            message.text.length + message.toolCalls.sumOf { it.name.length + it.arguments.length }
        }
        assertEquals(sizes.sum(), messages.counts.totalChars)
        assertEquals(sizes.indices.filter { messages[it].role != "system" }.sumOf { sizes[it] }, messages.counts.historyChars)
        assertEquals(messages.count { it.imageCount > 0 }, messages.counts.imageMessages)
    }

    @Test fun `system history tool arguments and image message counts keep the old estimate`() {
        val messages = PromptMessages().apply {
            add(ChatMessage("system", "base"))
            add(ChatMessage("user", "hi", imagesBase64 = listOf("a", "b")))
            add(ChatMessage("assistant", "ok", toolCalls = listOf(ToolCallRequest("id", "foo", "{}"))))
        }
        assertEquals(PromptSize(historyChars = 9, totalChars = 13, imageMessages = 1), messages.counts)
    }

    @Test fun `tool result replacement subtracts obsolete text and screenshots`() {
        val messages = PromptMessages().apply { add(ChatMessage("tool", "long output", imageBase64 = "image")) }
        messages[0] = ChatMessage("tool", "short")
        assertEquals(PromptSize(5, 5, 0), messages.counts)
    }

    @Test fun `list iterator additions replacements and removals update totals`() {
        val messages = PromptMessages().apply { addAll(listOf(ChatMessage("system", "a"), ChatMessage("user", "b"))) }
        val iterator = messages.listIterator()
        iterator.next()
        iterator.set(ChatMessage("system", "summary"))
        iterator.add(ChatMessage("user", "steer"))
        iterator.next()
        iterator.remove()
        assertEquals(PromptSize(5, 12, 0), messages.counts)
        assertCounts(messages)
    }

    @Test fun `compression range removal and rebuilding cannot retain stale totals`() {
        val messages = PromptMessages().apply {
            add(ChatMessage("system", "base"))
            repeat(10) { add(ChatMessage("user", "question$it")) }
        }
        messages.subList(1, 8).clear()
        messages.add(1, ChatMessage("system", "summary"))
        assertCounts(messages)
        messages.clear()
        assertEquals(PromptSize(0, 0, 0), messages.counts)
        messages.add(ChatMessage("assistant", "restart"))
        assertCounts(messages)
    }

    @Test fun `mixed random mutations always equal a fresh recount`() {
        val random = Random(532)
        val messages = PromptMessages()
        repeat(2_000) { step ->
            val message = ChatMessage(if (step % 3 == 0) "system" else "user", "v".repeat(step % 37), imageBase64 = if (step % 7 == 0) "image" else null)
            when (random.nextInt(5)) {
                0, 1 -> messages.add(random.nextInt(messages.size + 1), message)
                2 -> if (messages.isNotEmpty()) messages[random.nextInt(messages.size)] = message
                3 -> if (messages.isNotEmpty()) messages.removeAt(random.nextInt(messages.size))
                4 -> if (messages.size > 20) messages.subList(0, random.nextInt(messages.size)).clear()
            }
            assertCounts(messages)
        }
    }
}
