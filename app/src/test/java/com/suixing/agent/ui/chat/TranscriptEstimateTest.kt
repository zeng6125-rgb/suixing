package com.suixing.agent.ui.chat

import com.suixing.agent.agent.estimateTokens
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ring's floor for a conversation nothing ever priced.
 *
 * The arithmetic belongs to the engine (`AgentEngine.estimateTokens`, shared with the context
 * trimmer): a third of a token per character of text, tool arguments and tool output, plus a flat
 * 1000 per screenshot. The screenshot figure is the important one — priced by its base64 length
 * instead, a single image would read as a few hundred thousand tokens and the card would claim the
 * window was full. These assertions live next to the ring rather than only in the engine's own
 * tests because they are the anti-drift check for the number this card shows.
 *
 * The value is a floor, not a bill: the system prompt and the tool schemas are not in the
 * transcript, so the reader labels it 「约」. What matters is that it is never zero for a
 * conversation with words in it, which is the whole point of having it.
 */
class TranscriptEstimateTest {

    /** What the ring actually computes: the engine's per-message figure, summed. */
    private fun estimate(messages: List<StoredMessage>): Int = messages.sumOf { estimateTokens(it) }

    private fun user(id: String, text: String) = StoredMessage(id = id, role = Roles.USER, text = text)

    @Test
    fun `text is charged a third of a token per character`() {
        assertEquals(100, estimate(listOf(user("u1", "字".repeat(300)))))
    }

    @Test
    fun `tool arguments and tool output are part of the prompt`() {
        val call = StoredMessage(
            id = "a1",
            role = Roles.ASSISTANT,
            text = "字".repeat(300),
            toolArgs = "字".repeat(300),
            toolOutput = "字".repeat(300),
        )
        assertEquals(300, estimate(listOf(call)))
    }

    /** A screenshot is about a thousand tokens, not the hundreds of thousands of base64 chars. */
    @Test
    fun `a screenshot is priced flat, not by its base64 length`() {
        val shot = StoredMessage(
            id = "u1",
            role = Roles.USER,
            text = "",
            imageBase64 = "A".repeat(200_000),
        )
        assertEquals(1_000, estimate(listOf(shot)))
    }

    @Test
    fun `an empty transcript costs nothing`() {
        assertEquals(0, estimate(emptyList()))
        assertEquals(0, estimate(listOf(user("u1", ""))))
    }

    /** Short words still add up: a conversation with any text at all must not read as empty. */
    @Test
    fun `a short conversation still has a number`() {
        val messages = listOf(user("u1", "帮我看看这个文件"), user("u2", "再列一下目录"))
        assertTrue(estimate(messages) > 0)
    }

    @Test
    fun `a long conversation is the sum of its rows`() {
        val one = estimate(listOf(user("u1", "字".repeat(300))))
        val three = estimate(
            listOf(user("u1", "字".repeat(300)), user("u2", "字".repeat(300)), user("u3", "字".repeat(300))),
        )
        assertEquals(one * 3, three)
    }
}
