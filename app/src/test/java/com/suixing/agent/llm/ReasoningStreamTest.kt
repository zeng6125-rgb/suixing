package com.suixing.agent.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a gateway puts the thinking, and what actually reaches the UI.
 *
 * The bug these cover: only `delta.reasoning_content` was read, so every other shape produced
 * a thinking block with a running timer and no text — the reasoning delta was simply never
 * emitted, and the stored message carried no reasoning either.
 */
class ReasoningStreamTest {

    private fun chunk(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    /** Feeds one chunk and returns what the callbacks were handed. */
    private fun feed(json: String): Pair<List<String>, List<String>> {
        val deltas = mutableListOf<String>()
        val reasoning = mutableListOf<String>()
        LlmClient.StreamTurn().accept(chunk(json), { deltas += it }, { reasoning += it })
        return deltas to reasoning
    }

    private fun reasoning(json: String): List<String> = feed(json).second

    // ---- the shapes a provider may use ----

    @Test
    fun `deepseek streams reasoning_content on the delta`() {
        val pieces = reasoning("""{"choices":[{"delta":{"reasoning_content":"让我想想"}}]}""")
        assertEquals(listOf("让我想想"), pieces)
    }

    @Test
    fun `the openrouter and anthropic shims use reasoning`() {
        val pieces = reasoning("""{"choices":[{"delta":{"reasoning":"先看代码"}}]}""")
        assertEquals(listOf("先看代码"), pieces)
    }

    /** Some reverse proxies hoist the field out of `delta` entirely. */
    @Test
    fun `reasoning_content at the top level of the chunk is read`() {
        val pieces = reasoning("""{"choices":[{"delta":{}}],"reasoning_content":"顶层思考"}""")
        assertEquals(listOf("顶层思考"), pieces)
    }

    @Test
    fun `reasoning_content on the choice is read`() {
        val pieces = reasoning("""{"choices":[{"delta":{},"reasoning_content":"选择级思考"}]}""")
        assertEquals(listOf("选择级思考"), pieces)
    }

    /** A gateway that answers with whole non-streaming-shaped chunks: no `delta` at all. */
    @Test
    fun `reasoning under message is read`() {
        val pieces = reasoning("""{"choices":[{"message":{"reasoning_content":"完整思考"}}]}""")
        assertEquals(listOf("完整思考"), pieces)
    }

    /** Structured blocks used to be dropped because they are not plain strings. */
    @Test
    fun `a structured reasoning block is flattened`() {
        val pieces = reasoning(
            """{"choices":[{"delta":{"reasoning_content":[{"type":"thinking","thinking":"块内思考"}]}}]}""",
        )
        assertEquals(listOf("块内思考"), pieces)
    }

    @Test
    fun `a structured block with a text field is flattened`() {
        val pieces = reasoning("""{"choices":[{"delta":{"reasoning":[{"type":"text","text":"块二"}]}}]}""")
        assertEquals(listOf("块二"), pieces)
    }

    // ---- the empty block that was reported ----

    /**
     * A whitespace-only piece used to reach the UI, which prices the block as
     * `text.length / 4`: newlines therefore rendered as "thinking, 0 tokens" forever.
     */
    @Test
    fun `a whitespace-only piece is not emitted`() {
        assertTrue(reasoning("""{"choices":[{"delta":{"reasoning_content":"\n\n"}}]}""").isEmpty())
        assertTrue(reasoning("""{"choices":[{"delta":{"reasoning_content":"   "}}]}""").isEmpty())
    }

    @Test
    fun `a chunk without any reasoning emits nothing`() {
        assertTrue(reasoning("""{"choices":[{"delta":{"content":"只有回答"}}]}""").isEmpty())
    }

    @Test
    fun `an empty reasoning string emits nothing`() {
        assertTrue(reasoning("""{"choices":[{"delta":{"reasoning_content":""}}]}""").isEmpty())
    }

    @Test
    fun `a null reasoning field emits nothing`() {
        assertTrue(reasoning("""{"choices":[{"delta":{"reasoning_content":null}}]}""").isEmpty())
    }

    // ---- every emitted piece is usable text ----

    @Test
    fun `every emitted piece is non-blank`() {
        val lines = listOf(
            """{"choices":[{"delta":{"reasoning_content":"第一段"}}]}""",
            """{"choices":[{"delta":{"reasoning_content":"\n"}}]}""",
            """{"choices":[{"delta":{"reasoning":"第二段"}}]}""",
            """{"choices":[{"message":{"reasoning_content":"第三段"}}]}""",
            """{"choices":[{"delta":{"reasoning_content":"  "}}]}""",
        )
        val pieces = lines.flatMap { reasoning(it) }

        assertEquals(listOf("第一段", "第二段", "第三段"), pieces)
        assertTrue(pieces.all { it.isNotBlank() })
    }

    /** The whole point: what the UI shows is the sum of the pieces, and it is not empty. */
    @Test
    fun `the assembled turn carries the thinking text`() {
        val turn = LlmClient.StreamTurn()
        turn.accept(chunk("""{"choices":[{"delta":{"reasoning_content":"先看"}}]}"""), {}, {})
        turn.accept(chunk("""{"choices":[{"delta":{"reasoning_content":"再改"}}]}"""), {}, {})

        assertEquals("先看再改", turn.toTurn().reasoning)
        assertTrue(turn.toTurn().reasoning.isNotBlank())
    }

    // ---- the answer text must not regress ----

    @Test
    fun `answer text streams as before`() {
        val (deltas, _) = feed("""{"choices":[{"delta":{"content":"你好"}}]}""")
        assertEquals(listOf("你好"), deltas)
    }

    /** Blank answer pieces are markdown paragraph spacing and must survive. */
    @Test
    fun `blank answer pieces are kept`() {
        val (deltas, _) = feed("""{"choices":[{"delta":{"content":"\n\n"}}]}""")
        assertEquals(listOf("\n\n"), deltas)
    }

    @Test
    fun `answer text under message is read`() {
        val (deltas, _) = feed("""{"choices":[{"message":{"content":"完整回答"}}]}""")
        assertEquals(listOf("完整回答"), deltas)
    }

    /** A delta that carries the field wins, so a self-repeating chunk is not counted twice. */
    @Test
    fun `the delta wins over a repeated message`() {
        val (deltas, _) = feed("""{"choices":[{"delta":{"content":"增量"},"message":{"content":"完整回答"}}]}""")
        assertEquals(listOf("增量"), deltas)
    }

    // ---- the rest of the chunk still parses ----

    @Test
    fun `a usage-only chunk is still read`() {
        val turn = LlmClient.StreamTurn()
        turn.accept(
            chunk("""{"choices":[],"usage":{"prompt_tokens":1200,"completion_tokens":30,"reasoning_tokens":7}}"""),
            {},
            {},
        )

        val usage = turn.usage
        assertEquals(1200, usage?.promptTokens)
        assertEquals(30, usage?.completionTokens)
        assertEquals(7, usage?.reasoningTokens)
        assertTrue(turn.toTurn().text.isEmpty())
    }

    @Test
    fun `finish_reason is captured`() {
        val turn = LlmClient.StreamTurn()
        turn.accept(chunk("""{"choices":[{"delta":{"content":"完"},"finish_reason":"stop"}]}"""), {}, {})

        assertEquals("stop", turn.finishReason)
    }

    @Test
    fun `tool calls stream from the delta`() {
        val turn = LlmClient.StreamTurn()
        turn.accept(
            chunk(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"run_shell","arguments":"{\"comm"}}]}}]}""",
            ),
            {},
            {},
        )
        turn.accept(
            chunk("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"and\":\"ls\"}"}}]}}]}"""),
            {},
            {},
        )

        val call = turn.toTurn().toolCalls.single()
        assertEquals("c1", call.id)
        assertEquals("run_shell", call.name)
        assertEquals("""{"command":"ls"}""", call.arguments)
    }

    @Test
    fun `tool calls under message are read`() {
        val turn = LlmClient.StreamTurn()
        turn.accept(
            chunk(
                """{"choices":[{"message":{"tool_calls":[{"id":"c9","function":{"name":"ui_tap","arguments":"{}"}}]}}]}""",
            ),
            {},
            {},
        )

        assertEquals("ui_tap", turn.toTurn().toolCalls.single().name)
    }

    @Test
    fun `a chunk with no choices is ignored`() {
        val turn = LlmClient.StreamTurn()
        turn.accept(chunk("""{"id":"x"}"""), {}, {})

        assertNull(turn.finishReason)
        assertTrue(turn.toTurn().text.isEmpty())
    }
}
