package com.suixing.agent.llm

import com.suixing.agent.data.PROTOCOL_ANTHROPIC
import com.suixing.agent.data.PROTOCOL_RESPONSES
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two non-OpenAI wires, read from their documented event shapes.
 *
 * These are the fixtures that would have caught the mistakes this change could plausibly make:
 * a Claude turn reported as cut (no finish reason is ever named `stop` there), an `input_json_delta`
 * that never reaches the tool call, and Anthropic's split usage being summed instead of resolved.
 */
class ProtocolStreamTest {

    private fun chunk(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun feed(protocol: String, vararg lines: String): Triple<String, String, AssistantTurn> {
        val deltas = mutableListOf<String>()
        val reasoning = mutableListOf<String>()
        val turn = LlmClient.StreamTurn(protocol)
        lines.forEach { turn.accept(chunk(it), { deltas += it }, { reasoning += it }) }
        return Triple(deltas.joinToString(""), reasoning.joinToString(""), turn.toTurn())
    }

    // ---- Anthropic ----

    @Test
    fun `anthropic text arrives as text_delta`() {
        val (text, _, turn) = feed(
            PROTOCOL_ANTHROPIC,
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"，世界"}}""",
        )
        assertEquals("你好，世界", text)
        assertEquals("你好，世界", turn.text)
    }

    @Test
    fun `anthropic thinking arrives as thinking_delta`() {
        val (_, reasoning, turn) = feed(
            PROTOCOL_ANTHROPIC,
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"先想"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"一下"}}""",
        )
        assertEquals("先想一下", reasoning)
        assertEquals("先想一下", turn.reasoning)
    }

    /**
     * The one that matters most: Anthropic never sends `[DONE]` and never sends
     * `finish_reason`, so without mapping `message_stop` every single turn would look cut.
     */
    @Test
    fun `anthropic message_stop ends the turn`() {
        val (_, _, turn) = feed(
            PROTOCOL_ANTHROPIC,
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"完了"}}""",
            """{"type":"message_stop"}""",
        )
        assertEquals("stop", turn.finishReason)
    }

    @Test
    fun `anthropic max_tokens is reported as length`() {
        val (_, _, turn) = feed(
            PROTOCOL_ANTHROPIC,
            """{"type":"message_delta","delta":{"stop_reason":"max_tokens"}}""",
        )
        assertEquals("length", turn.finishReason)
    }

    @Test
    fun `anthropic tool_use builds a call from start plus input_json_delta`() {
        val (_, _, turn) = feed(
            PROTOCOL_ANTHROPIC,
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"run_shell","input":{}}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"comm"}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"and\":\"ls\"}"}}""",
        )
        val call = turn.toolCalls.single()
        assertEquals("toolu_1", call.id)
        assertEquals("run_shell", call.name)
        assertEquals("""{"command":"ls"}""", call.arguments)
    }

    @Test
    fun `anthropic usage is resolved across the two events, not summed`() {
        val turn = LlmClient.StreamTurn(PROTOCOL_ANTHROPIC)
        // input on message_start, then the cumulative output on message_delta.
        turn.accept(chunk("""{"type":"message_start","message":{"usage":{"input_tokens":100,"output_tokens":1}}}"""), {}, {})
        turn.accept(chunk("""{"type":"message_delta","usage":{"output_tokens":25}}"""), {}, {})

        assertEquals(100, turn.usage?.promptTokens)
        // 25, not 26 and not 126: message_delta's count is cumulative.
        assertEquals(25, turn.usage?.completionTokens)
    }

    @Test
    fun `anthropic content_block_start with text emits it`() {
        val (text, _, _) = feed(
            PROTOCOL_ANTHROPIC,
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"开头"}}""",
        )
        assertEquals("开头", text)
    }

    // ---- Responses ----

    @Test
    fun `responses text arrives as output_text delta`() {
        val (text, _, turn) = feed(
            PROTOCOL_RESPONSES,
            """{"type":"response.output_text.delta","delta":"你好"}""",
            """{"type":"response.output_text.delta","delta":"呀"}""",
        )
        assertEquals("你好呀", text)
        assertEquals("你好呀", turn.text)
    }

    @Test
    fun `responses function call assembles by item id`() {
        val (_, _, turn) = feed(
            PROTOCOL_RESPONSES,
            """{"type":"response.output_item.added","item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"ui_tap","arguments":""}}""",
            """{"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"{\"x\":1"}""",
            """{"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"}"}""",
        )
        val call = turn.toolCalls.single()
        // call_id is what the API wants echoed back, not the item id.
        assertEquals("call_1", call.id)
        assertEquals("ui_tap", call.name)
        assertEquals("""{"x":1}""", call.arguments)
    }

    @Test
    fun `responses completed ends the turn with usage`() {
        val (_, _, turn) = feed(
            PROTOCOL_RESPONSES,
            """{"type":"response.completed","response":{"usage":{"input_tokens":30,"output_tokens":12,"output_tokens_details":{"reasoning_tokens":4}}}}""",
        )
        assertEquals("stop", turn.finishReason)
        assertEquals(30, turn.usage?.promptTokens)
        assertEquals(12, turn.usage?.completionTokens)
    }

    @Test
    fun `responses incomplete with max_output_tokens is a length stop`() {
        val (_, _, turn) = feed(
            PROTOCOL_RESPONSES,
            """{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"}}}""",
        )
        assertEquals("length", turn.finishReason)
    }

    // ---- the default must stay OpenRouter-compatible ----

    @Test
    fun `a turn with no protocol still reads chat completions`() {
        val (text, _, turn) = feed(
            "",
            """{"choices":[{"delta":{"content":"老样子"},"finish_reason":"stop"}]}""",
        )
        assertEquals("老样子", text)
        assertEquals("stop", turn.finishReason)
        assertTrue(turn.toolCalls.isEmpty())
        assertNull(turn.usage)
    }
}
