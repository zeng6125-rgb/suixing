package com.suixing.agent.llm

import com.suixing.agent.agent.AgentEngine
import com.suixing.agent.agent.ApprovalGate
import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.capability.CapabilityState
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.MemoryEntry
import com.suixing.agent.data.MemoryStore
import com.suixing.agent.data.PROTOCOL_ANTHROPIC
import com.suixing.agent.data.PROTOCOL_OPENAI
import com.suixing.agent.data.PROTOCOL_RESPONSES
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.UserProfileStore
import com.suixing.agent.skills.Skill
import com.suixing.agent.skills.SkillStore
import com.suixing.agent.tools.ToolGroup
import com.suixing.agent.tools.ToolRegistry
import com.suixing.agent.tools.ToolResult
import com.suixing.agent.tools.ToolSpec
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmMalformedToolReplayTest {
    private fun firstTurn(protocol: String, raw: String): String {
        val arguments = JsonPrimitive(raw)
        val events = when (protocol) {
            PROTOCOL_ANTHROPIC -> listOf(
                """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"bad-call","name":"echo","input":{}}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":$arguments}}""",
                """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""",
                """{"type":"message_stop"}""",
            )
            PROTOCOL_RESPONSES -> listOf(
                """{"type":"response.output_item.added","item":{"type":"function_call","id":"item-1","call_id":"bad-call","name":"echo","arguments":""}}""",
                """{"type":"response.function_call_arguments.delta","item_id":"item-1","delta":$arguments}""",
                """{"type":"response.completed","response":{}}""",
            )
            else -> listOf(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"bad-call","function":{"name":"echo","arguments":$arguments}}]},"finish_reason":"tool_calls"}]}""",
                "[DONE]",
            )
        }
        return events.joinToString("") { "data: $it\n\n" }
    }

    private fun finalTurn(protocol: String): String {
        val events = when (protocol) {
            PROTOCOL_ANTHROPIC -> listOf(
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"done"}}""",
                """{"type":"message_stop"}""",
            )
            PROTOCOL_RESPONSES -> listOf(
                """{"type":"response.output_text.delta","delta":"done"}""",
                """{"type":"response.completed","response":{}}""",
            )
            else -> listOf("""{"choices":[{"delta":{"content":"done"},"finish_reason":"stop"}]}""", "[DONE]")
        }
        return events.joinToString("") { "data: $it\n\n" }
    }

    private fun replay(protocol: String, raw: String, rejected: Boolean = true): Unit = runBlocking {
        var executions = 0
        val registry = ToolRegistry::class.java.getDeclaredConstructor().let {
            it.isAccessible = true
            it.newInstance()
        }.also {
            it.add(object : ToolGroup() {
                override val specs = listOf(ToolSpec("echo", "fixture", JsonObject(emptyMap())))
                override suspend fun execute(name: String, args: JsonObject): ToolResult {
                    executions++
                    return ToolResult.success("executed")
                }
            })
        }
        val capabilities = mockk<CapabilityHub> { every { state } returns MutableStateFlow(CapabilityState()) }
        val skills = mockk<SkillStore> {
            coEvery { listHeaders() } returns emptyList<Skill>()
            every { catalog(any()) } returns ""
        }
        val memory = mockk<MemoryStore> { every { entries } returns MutableStateFlow(emptyMap<String, MemoryEntry>()) }
        val profile = mockk<UserProfileStore> { every { text } returns MutableStateFlow("") }
        val requests = mutableListOf<JsonObject>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            requests += Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val body = if (requests.size == 1) firstTurn(protocol, raw) else finalTurn(protocol)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        try {
            val produced = AgentEngine(llm = LlmClient(http)).startRun(
                "malformed", "Malformed", listOf(StoredMessage("question", Roles.USER, "execute echo")),
                AppSettings(baseUrl = "https://example.test/v1", model = "test", protocol = protocol,
                    approvalMode = ApprovalMode.AUTO, subagentsEnabled = false, maxToolRounds = 3, retryOnFailure = false),
                registry, capabilities, skills, memory, profile, ApprovalGate(), {},
            )
            assertEquals(2, requests.size)
            assertEquals(if (rejected) 0 else 1, executions)
            val tool = produced.single { it.role == Roles.TOOL }
            assertEquals(raw, tool.toolArgs)
            assertEquals(!rejected, tool.toolOk)
            assertEquals("done", produced.last { it.role == Roles.ASSISTANT }.text)
            val body = requests.last()
            val (arguments, output) = when (protocol) {
                PROTOCOL_ANTHROPIC -> {
                    val blocks = body.getValue("messages").jsonArray.flatMap {
                        it.jsonObject.getValue("content").jsonArray
                    }.map { it.jsonObject }
                    val call = blocks.single { it["type"]?.jsonPrimitive?.content == "tool_use" }
                    val result = blocks.single { it["type"]?.jsonPrimitive?.content == "tool_result" }
                    assertEquals("bad-call", call.getValue("id").jsonPrimitive.content)
                    assertEquals("bad-call", result.getValue("tool_use_id").jsonPrimitive.content)
                    call.getValue("input").toString() to result.getValue("content").toString()
                }
                PROTOCOL_RESPONSES -> {
                    val items = body.getValue("input").jsonArray.map { it.jsonObject }
                    val call = items.single { it["type"]?.jsonPrimitive?.content == "function_call" }
                    val result = items.single { it["type"]?.jsonPrimitive?.content == "function_call_output" }
                    assertEquals("bad-call", call.getValue("call_id").jsonPrimitive.content)
                    assertEquals("bad-call", result.getValue("call_id").jsonPrimitive.content)
                    call.getValue("arguments").jsonPrimitive.content to result.getValue("output").jsonPrimitive.content
                }
                else -> {
                    val messages = body.getValue("messages").jsonArray.map { it.jsonObject }
                    val call = messages.flatMap { it["tool_calls"]?.jsonArray.orEmpty() }.single().jsonObject
                    val result = messages.single { it["role"]?.jsonPrimitive?.content == Roles.TOOL }
                    assertEquals("bad-call", call.getValue("id").jsonPrimitive.content)
                    assertEquals("bad-call", result.getValue("tool_call_id").jsonPrimitive.content)
                    call.getValue("function").jsonObject.getValue("arguments").jsonPrimitive.content to
                        result.getValue("content").jsonPrimitive.content
                }
            }
            assertTrue(output, output.contains(if (rejected) "合法 JSON 对象" else "executed"))
            assertEquals("Only the request projection repairs rejected arguments; the transcript keeps the raw call",
                if (rejected) "{}" else raw, arguments)
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test fun openaiMalformedArgumentsKeepTheirFailedResult(): Unit = replay(PROTOCOL_OPENAI, "{broken")
    @Test fun openaiArrayArgumentsKeepTheirFailedResult(): Unit = replay(PROTOCOL_OPENAI, "[]")
    @Test fun responsesMalformedArgumentsKeepTheirFailedResult(): Unit = replay(PROTOCOL_RESPONSES, "{broken")
    @Test fun responsesArrayArgumentsKeepTheirFailedResult(): Unit = replay(PROTOCOL_RESPONSES, "[]")
    @Test fun anthropicMalformedArgumentsKeepTheirFailedResult(): Unit = replay(PROTOCOL_ANTHROPIC, "{broken")
    @Test fun anthropicArrayArgumentsKeepTheirFailedResult(): Unit = replay(PROTOCOL_ANTHROPIC, "[]")
    @Test fun openaiValidArgumentsKeepWhitespaceAndFieldOrder(): Unit =
        replay(PROTOCOL_OPENAI, " { \"z\": 1, \"a\": 2 } ", rejected = false)
    @Test fun responsesValidArgumentsKeepWhitespaceAndFieldOrder(): Unit =
        replay(PROTOCOL_RESPONSES, " { \"z\": 1, \"a\": 2 } ", rejected = false)
}
