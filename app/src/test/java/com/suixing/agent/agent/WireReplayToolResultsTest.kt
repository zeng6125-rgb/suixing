package com.suixing.agent.agent

import com.suixing.agent.core.NativeCore
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.PROTOCOL_ANTHROPIC
import com.suixing.agent.data.PROTOCOL_OPENAI
import com.suixing.agent.data.PROTOCOL_RESPONSES
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.StoredToolCall
import com.suixing.agent.llm.ChatMessage
import com.suixing.agent.llm.LlmClient
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WireReplayToolResultsTest {
    @Before fun setUp() {
        mockkObject(NativeCore)
        every { NativeCore.available } returns false
    }

    @After fun tearDown() {
        unmockkObject(NativeCore)
    }

    private fun replay(settings: AppSettings): List<ChatMessage> {
        val sources = listOf(
            StoredMessage(id = "a", role = Roles.ASSISTANT, toolCalls = listOf(
                StoredToolCall("c1", "ui_screenshot"),
                StoredToolCall("c2", "run_shell", """{"command":"pwd"}"""),
            )),
            StoredMessage(id = "r1", role = Roles.TOOL, toolName = "ui_screenshot",
                toolCallId = "c1", toolOutput = "screen ready", imageBase64 = "YQ=="),
            StoredMessage(id = "r2", role = Roles.TOOL, toolName = "run_shell",
                toolCallId = "c2", toolOutput = "/workspace"),
        )
        val wire = mutableListOf<ChatMessage>()
        assertArrayEquals(intArrayOf(1, 2, 1), appendToWireBatch(wire, sources, settings))
        return wire
    }

    private fun payload(protocol: String, imageInput: Boolean): JsonObject = runBlocking {
        val settings = AppSettings(baseUrl = "https://example.test/v1", model = "fixture",
            protocol = protocol, imageInput = imageInput, retryOnFailure = false)
        var captured: JsonObject? = null
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            captured = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(400).message("fixture response")
                .body("""{"error":{"message":"fixture response"}}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        try {
            runCatching { LlmClient(http).complete(settings, replay(settings), emptyList()) }
            captured ?: error("The local interceptor did not receive a request body")
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    private fun chatCompletions(imageInput: Boolean) {
        val messages = (payload(PROTOCOL_OPENAI, imageInput)["messages"] as JsonArray).map { it.jsonObject }
        assertEquals(listOf("assistant", "tool", "tool", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
        assertEquals(listOf("c1", "c2"), messages.slice(1..2).map { it["tool_call_id"]!!.jsonPrimitive.content })
        val content = messages.last()["content"]!!
        if (imageInput) {
            val parts = content as JsonArray
            assertEquals("data:image/jpeg;base64,YQ==", parts.last().jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        } else {
            assertTrue(content.jsonPrimitive.content.contains("当前模型未开启图片输入"))
        }
    }

    private fun responses(imageInput: Boolean) {
        val items = (payload(PROTOCOL_RESPONSES, imageInput)["input"] as JsonArray).map { it.jsonObject }
        assertEquals(listOf("function_call", "function_call", "function_call_output", "function_call_output", "user"),
            items.map { (it["type"] ?: it["role"])!!.jsonPrimitive.content })
        assertEquals(listOf("c1", "c2"), items.slice(2..3).map { it["call_id"]!!.jsonPrimitive.content })
        val content = items.last()["content"]!!
        if (imageInput) {
            val parts = content as JsonArray
            assertEquals("input_image", parts.last().jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("data:image/jpeg;base64,YQ==", parts.last().jsonObject["image_url"]!!.jsonPrimitive.content)
        } else {
            assertTrue(content.jsonPrimitive.content.contains("当前模型未开启图片输入"))
        }
    }

    private fun anthropic(imageInput: Boolean) {
        val messages = (payload(PROTOCOL_ANTHROPIC, imageInput)["messages"] as JsonArray).map { it.jsonObject }
        assertEquals(listOf("assistant", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
        val blocks = (messages.last()["content"] as JsonArray).map { it.jsonObject }
        assertEquals(if (imageInput) listOf("tool_result", "tool_result", "text", "image")
            else listOf("tool_result", "tool_result", "text"), blocks.map { it["type"]!!.jsonPrimitive.content })
        assertEquals(listOf("c1", "c2"), blocks.take(2).map { it["tool_use_id"]!!.jsonPrimitive.content })
        if (imageInput) {
            assertEquals("YQ==", blocks.last()["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        } else {
            assertTrue(blocks.last()["text"]!!.jsonPrimitive.content.contains("当前模型未开启图片输入"))
        }
    }

    @Test fun chatResultsPrecedeTheReplayedScreenshot() = chatCompletions(imageInput = true)
    @Test fun chatResultsPrecedeTheReplayedScreenshotNotice() = chatCompletions(imageInput = false)
    @Test fun responseOutputsPrecedeTheReplayedScreenshot() = responses(imageInput = true)
    @Test fun responseOutputsPrecedeTheReplayedScreenshotNotice() = responses(imageInput = false)
    @Test fun anthropicResultsPrecedeTheReplayedScreenshot() = anthropic(imageInput = true)
    @Test fun anthropicResultsPrecedeTheReplayedScreenshotNotice() = anthropic(imageInput = false)
}
