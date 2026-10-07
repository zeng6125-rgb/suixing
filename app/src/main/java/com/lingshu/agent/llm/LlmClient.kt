package com.lingshu.agent.llm

import com.lingshu.agent.data.AppSettings
import com.lingshu.agent.tools.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** One message in the wire format of an OpenAI-compatible chat API. */
data class ChatMessage(
    val role: String,
    val text: String = "",
    val toolCalls: List<ToolCallRequest> = emptyList(),
    val toolCallId: String? = null,
    val imageBase64: String? = null,
) {
    companion object {
        fun system(text: String) = ChatMessage("system", text)

        fun user(text: String, imageBase64: String? = null) = ChatMessage("user", text, imageBase64 = imageBase64)

        fun assistant(text: String, toolCalls: List<ToolCallRequest> = emptyList()) =
            ChatMessage("assistant", text, toolCalls)

        fun tool(callId: String, text: String) = ChatMessage("tool", text, toolCallId = callId)
    }
}

data class ToolCallRequest(val id: String, val name: String, val arguments: String)

/** What the model produced in one round. */
data class AssistantTurn(
    val text: String,
    val toolCalls: List<ToolCallRequest>,
    val finishReason: String?,
)

class LlmException(message: String) : Exception(message)

/**
 * Minimal streaming client for any OpenAI-compatible endpoint
 * (`/chat/completions` with SSE). Supports tool calling and image inputs.
 */
class LlmClient(private val http: OkHttpClient = defaultClient()) {

    suspend fun complete(
        settings: AppSettings,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        onDelta: (String) -> Unit = {},
    ): AssistantTurn = withContext(Dispatchers.IO) {
        val url = endpoint(settings.baseUrl)
        val payload = buildJsonObject {
            put("model", JsonPrimitive(settings.model))
            put("messages", JsonArray(messages.map { encodeMessage(it) }))
            put("stream", JsonPrimitive(true))
            put("temperature", JsonPrimitive(settings.temperature))
            if (tools.isNotEmpty()) {
                put("tools", JsonArray(tools.map { encodeTool(it) }))
                put("tool_choice", JsonPrimitive("auto"))
            }
        }
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("Authorization", "Bearer ${settings.apiKey}")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful || body == null) {
                val detail = body?.string()?.take(600).orEmpty()
                throw LlmException("请求失败 HTTP ${response.code}${if (detail.isBlank()) "" else "：$detail"}")
            }
            val text = StringBuilder()
            val calls = LinkedHashMap<Int, MutableToolCall>()
            var finishReason: String? = null
            val source = body.source()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isEmpty()) continue
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty() || data == "[DONE]") continue
                val chunk = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                chunk["error"]?.let { error ->
                    val message = (error as? JsonObject)?.get("message")?.jsonPrimitive?.content ?: error.toString()
                    throw LlmException("接口返回错误：$message")
                }
                val choice = chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                choice["finish_reason"]?.let { element ->
                    if (element !is JsonNull) finishReason = element.jsonPrimitive.content
                }
                val delta = choice["delta"]?.jsonObject ?: continue
                delta["content"]?.let { element ->
                    if (element !is JsonNull) {
                        val piece = element.jsonPrimitive.content
                        if (piece.isNotEmpty()) {
                            text.append(piece)
                            onDelta(piece)
                        }
                    }
                }
                delta["tool_calls"]?.jsonArray?.forEach { element ->
                    val item = element.jsonObject
                    val index = item["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    val call = calls.getOrPut(index) { MutableToolCall() }
                    item["id"]?.let { if (it !is JsonNull) call.id = it.jsonPrimitive.content }
                    val function = item["function"]?.jsonObject ?: return@forEach
                    function["name"]?.let { if (it !is JsonNull) call.name = it.jsonPrimitive.content }
                    function["arguments"]?.let { if (it !is JsonNull) call.arguments.append(it.jsonPrimitive.content) }
                }
            }
            val requests = calls.toSortedMap().values
                .filter { it.name.isNotBlank() }
                .mapIndexed { index, call ->
                    ToolCallRequest(
                        id = call.id.ifBlank { "call_$index" },
                        name = call.name,
                        arguments = call.arguments.toString().ifBlank { "{}" },
                    )
                }
            AssistantTurn(text.toString(), requests, finishReason)
        }
    }

    private class MutableToolCall {
        var id: String = ""
        var name: String = ""
        val arguments: StringBuilder = StringBuilder()
    }

    private fun encodeMessage(message: ChatMessage): JsonObject = buildJsonObject {
        put("role", JsonPrimitive(message.role))
        val image = message.imageBase64
        when {
            image != null -> put(
                "content",
                buildJsonArray {
                    if (message.text.isNotBlank()) {
                        add(buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(message.text))
                        })
                    }
                    add(buildJsonObject {
                        put("type", JsonPrimitive("image_url"))
                        put(
                            "image_url",
                            buildJsonObject { put("url", JsonPrimitive("data:image/jpeg;base64,$image")) },
                        )
                    })
                },
            )
            else -> put("content", JsonPrimitive(message.text))
        }
        message.toolCallId?.let { put("tool_call_id", JsonPrimitive(it)) }
        if (message.toolCalls.isNotEmpty()) {
            put(
                "tool_calls",
                buildJsonArray {
                    message.toolCalls.forEach { call ->
                        add(buildJsonObject {
                            put("id", JsonPrimitive(call.id))
                            put("type", JsonPrimitive("function"))
                            put(
                                "function",
                                buildJsonObject {
                                    put("name", JsonPrimitive(call.name))
                                    put("arguments", JsonPrimitive(call.arguments))
                                },
                            )
                        })
                    }
                },
            )
        }
    }

    private fun encodeTool(spec: ToolSpec): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("function"))
        put(
            "function",
            buildJsonObject {
                put("name", JsonPrimitive(spec.name))
                put("description", JsonPrimitive(spec.description))
                put("parameters", spec.parameters as JsonElement)
            },
        )
    }

    private fun endpoint(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) throw LlmException("请先在「设置」里填写 API 地址")
        return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(600, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
