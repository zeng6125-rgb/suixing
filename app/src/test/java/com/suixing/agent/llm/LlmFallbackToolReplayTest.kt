package com.suixing.agent.llm

import com.suixing.agent.agent.appendToWireBatch
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.PROTOCOL_ANTHROPIC
import com.suixing.agent.data.PROTOCOL_OPENAI
import com.suixing.agent.data.PROTOCOL_RESPONSES
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.StoredToolCall
import com.suixing.agent.data.WireSanitize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class LlmFallbackToolReplayTest {
    private fun fallbackCall(): ToolCallRequest {
        val stream = LlmClient.StreamTurn()
        stream.accept(
            Json.parseToJsonElement(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"read_file","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
            ).jsonObject,
            {},
            {},
        )
        return stream.toTurn().toolCalls.single()
    }

    private fun assertReplay(protocol: String) {
        val first = fallbackCall()
        val interrupted = fallbackCall()
        fun assistant(id: String, call: ToolCallRequest) = StoredMessage(
            id = id,
            role = Roles.ASSISTANT,
            toolCalls = listOf(StoredToolCall(call.id, call.name, call.arguments)),
        )
        val history = listOf(
            StoredMessage(id = "u1", role = Roles.USER, text = "Read the first file"),
            assistant("a1", first),
            StoredMessage(
                id = "t1", role = Roles.TOOL, toolCallId = first.id,
                toolName = first.name, toolOutput = "First file contents",
            ),
            StoredMessage(id = "u2", role = Roles.USER, text = "Read the next file"),
            assistant("a2", interrupted),
        )
        val settings = AppSettings(model = "test", protocol = protocol)
        val wire = mutableListOf<ChatMessage>()
        appendToWireBatch(wire, WireSanitize.apply(history), settings)
        val encoder = LlmClient::class.java.getDeclaredMethod(
            "requestBody", AppSettings::class.java, List::class.java, List::class.java, String::class.java,
        ).apply { isAccessible = true }
        val body = encoder.invoke(LlmClient(), settings, wire, emptyList<Any>(), protocol) as JsonObject
        val (calls, results) = when (protocol) {
            PROTOCOL_ANTHROPIC -> {
                val blocks = body.getValue("messages").jsonArray.flatMap {
                    it.jsonObject.getValue("content").jsonArray
                }.map { it.jsonObject }
                blocks.filter { it.getValue("type").jsonPrimitive.content == "tool_use" }
                    .map { it.getValue("id").jsonPrimitive.content } to
                    blocks.filter { it.getValue("type").jsonPrimitive.content == "tool_result" }
                        .map { it.getValue("tool_use_id").jsonPrimitive.content }
            }
            PROTOCOL_RESPONSES -> {
                val items = body.getValue("input").jsonArray.map { it.jsonObject }
                items.filter { it["type"]?.jsonPrimitive?.content == "function_call" }
                    .map { it.getValue("call_id").jsonPrimitive.content } to
                    items.filter { it["type"]?.jsonPrimitive?.content == "function_call_output" }
                        .map { it.getValue("call_id").jsonPrimitive.content }
            }
            else -> {
                val messages = body.getValue("messages").jsonArray.map { it.jsonObject }
                messages.flatMap { it["tool_calls"]?.jsonArray.orEmpty() }
                    .map { it.jsonObject.getValue("id").jsonPrimitive.content } to
                    messages.filter { it.getValue("role").jsonPrimitive.content == Roles.TOOL }
                        .map { it.getValue("tool_call_id").jsonPrimitive.content }
            }
        }
        assertEquals("Only the completed tool call belongs in the request", listOf(first.id), calls)
        assertEquals("The first result must remain attached to its completed call", listOf(first.id), results)
    }

    @Test
    fun `openai replay does not borrow a prior fallback result for an interrupted round`() =
        assertReplay(PROTOCOL_OPENAI)

    @Test
    fun `anthropic replay does not borrow a prior fallback result for an interrupted round`() =
        assertReplay(PROTOCOL_ANTHROPIC)

    @Test
    fun `responses replay does not borrow a prior fallback result for an interrupted round`() =
        assertReplay(PROTOCOL_RESPONSES)
}
