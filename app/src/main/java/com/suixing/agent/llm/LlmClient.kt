package com.suixing.agent.llm

import com.suixing.agent.data.AppSettings
import com.suixing.agent.tools.ToolSpec
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

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

/**
 * What one round cost, as the endpoint reported it. The final SSE chunk carries
 * this when `stream_options.include_usage` is honoured, and most Chinese gateways
 * send it unprompted; a few send nothing at all, hence the null in [AssistantTurn].
 */
data class Usage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    /** The slice of [completionTokens] that was thinking, when broken out. */
    val reasoningTokens: Int = 0,
    /** Prompt tokens served from the endpoint's own cache. */
    val cachedTokens: Int = 0,
) {
    val totalTokens: Int get() = promptTokens + completionTokens

    operator fun plus(other: Usage) = Usage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        reasoningTokens = reasoningTokens + other.reasoningTokens,
        cachedTokens = cachedTokens + other.cachedTokens,
    )

    companion object {
        val ZERO = Usage()
    }
}

/** What the model produced in one round. */
data class AssistantTurn(
    val text: String,
    val toolCalls: List<ToolCallRequest>,
    val finishReason: String?,
    /** The thinking the endpoint streamed apart from the answer, if any. */
    val reasoning: String = "",
    val usage: Usage? = null,
)

class LlmException(message: String) : Exception(message)

/**
 * Minimal streaming client for any OpenAI-compatible endpoint
 * (`/chat/completions` with SSE). Supports tool calling and image inputs.
 */
class LlmClient(private val http: OkHttpClient = defaultClient()) {

    /** Built on first use: only endpoints that need it pay for the relaxed socket factory. */
    private val relaxedHttp: OkHttpClient by lazy { insecureClient() }

    /**
     * Endpoints on the LAN usually carry a self-signed certificate, which Android
     * rejects outright with `Trust anchor for certification path not found`.
     * `allowInsecureTls` opts those endpoints into the relaxed client.
     */
    private fun clientFor(settings: AppSettings): OkHttpClient =
        if (settings.allowInsecureTls) relaxedHttp else http

    /**
     * Ask the endpoint which models it serves (`GET {base}/models`).
     *
     * The key is optional here because some local proxies do not check it; when the
     * server does reject the call the mapped message says so.
     */
    suspend fun listModels(settings: AppSettings): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(modelsEndpoint(settings.baseUrl))
            .header("Accept", "application/json")
            .apply { if (settings.apiKey.isNotBlank()) header("Authorization", "Bearer ${settings.apiKey}") }
            .get()
            .build()
        clientFor(settings).newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw LlmException(friendlyHttp(response.code, body))
            parseModels(body)
        }
    }

    suspend fun complete(
        settings: AppSettings,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        onDelta: (String) -> Unit = {},
        onReasoning: (String) -> Unit = {},
        /**
         * Handed the live call just before it goes on the wire, so the caller can
         * `cancel()` it. Coroutine cancellation alone cannot interrupt a blocking
         * `execute()`; an OkHttp call cancel can, and that is what makes 「点停即停」
         * real instead of "stops when the model gets around to it".
         */
        onCall: (Call) -> Unit = {},
    ): AssistantTurn = withContext(Dispatchers.IO) {
        val url = endpoint(settings.baseUrl)
        val payload = buildJsonObject {
            put("model", JsonPrimitive(settings.model))
            put("messages", JsonArray(messages.map { encodeMessage(it) }))
            put("stream", JsonPrimitive(true))
            put("temperature", JsonPrimitive(settings.temperature))
            // Ask for the usage block on the last chunk; endpoints that do not know
            // the flag ignore it, and the ones that do stop guessing.
            put("stream_options", buildJsonObject { put("include_usage", JsonPrimitive(true)) })
            if (settings.maxTokens > 0) put("max_tokens", JsonPrimitive(settings.maxTokens))
            if (settings.topP in 0.01f..0.99f) put("top_p", JsonPrimitive(settings.topP))
            if (settings.reasoningEffort.isNotBlank()) {
                put("reasoning_effort", JsonPrimitive(settings.reasoningEffort))
            }
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

        val call = clientFor(settings).newCall(request)
        onCall(call)
        call.execute().use { response ->
            val body = response.body
            if (!response.isSuccessful || body == null) {
                throw LlmException(friendlyHttp(response.code, body?.string().orEmpty()))
            }
            val stream = StreamTurn()
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
                stream.accept(chunk, onDelta, onReasoning)
            }
            stream.toTurn()
        }
    }

    private class MutableToolCall {
        var id: String = ""
        var name: String = ""
        val arguments: StringBuilder = StringBuilder()
    }

    /**
     * One streamed turn, assembled from the chunks of a `stream: true` response.
     *
     * Split out of the read loop because *where* a gateway puts the thinking is the part that
     * kept going wrong. Reading only `delta.reasoning_content` — which is what this used to
     * do — silently produced a thinking block with a timer and no text on every endpoint that
     * instead uses `reasoning`, hoists it to the top level of the chunk, or answers with whole
     * non-streaming-shaped `message` chunks. Here the shape handling is a function of a chunk,
     * so it can be tested against fixtures instead of against a live provider.
     */
    internal class StreamTurn {
        private val text = StringBuilder()
        private val thinking = StringBuilder()
        private val calls = LinkedHashMap<Int, MutableToolCall>()

        var finishReason: String? = null
            private set
        var usage: Usage? = null
            private set

        fun accept(chunk: JsonObject, onDelta: (String) -> Unit, onReasoning: (String) -> Unit) {
            // The usage block rides on a chunk whose `choices` array is empty, so it has to be
            // read before the choice lookup bails out.
            chunk["usage"]?.let { element ->
                (element as? JsonObject)?.let { usage = parseUsage(it) }
            }
            val choice = chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return
            choice["finish_reason"]?.let { element ->
                if (element !is JsonNull) finishReason = element.jsonPrimitive.content
            }
            // A gateway streams `delta` chunks, whole `message` chunks, or both; the delta wins
            // when it carries the field, so a chunk that repeats itself is not counted twice.
            val delta = choice["delta"]?.jsonObject
            val message = choice["message"]?.jsonObject

            reasoningOf(chunk, choice, delta, message)?.let { piece ->
                thinking.append(piece)
                onReasoning(piece)
            }

            // Blank pieces are kept here: a streamed "\n\n" is markdown paragraph spacing, and
            // dropping it would run the answer together.
            val content = if (delta != null && delta.containsKey("content")) {
                delta["content"].textOf()
            } else {
                message?.get("content").textOf()
            }
            if (content != null) {
                text.append(content)
                onDelta(content)
            }

            val rawCalls = if (delta != null && delta.containsKey("tool_calls")) {
                delta["tool_calls"]
            } else {
                message?.get("tool_calls")
            }
            rawCalls?.jsonArray?.forEach { element ->
                val item = element.jsonObject
                val index = item["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val call = calls.getOrPut(index) { MutableToolCall() }
                item["id"]?.let { if (it !is JsonNull) call.id = it.jsonPrimitive.content }
                val function = item["function"]?.jsonObject ?: return@forEach
                function["name"]?.let { if (it !is JsonNull) call.name = it.jsonPrimitive.content }
                function["arguments"]?.let { if (it !is JsonNull) call.arguments.append(it.jsonPrimitive.content) }
            }
        }

        fun toTurn(): AssistantTurn {
            val requests = calls.toSortedMap().values
                .filter { it.name.isNotBlank() }
                .mapIndexed { index, call ->
                    ToolCallRequest(
                        id = call.id.ifBlank { "call_$index" },
                        name = call.name,
                        arguments = call.arguments.toString().ifBlank { "{}" },
                    )
                }
            return AssistantTurn(text.toString(), requests, finishReason, thinking.toString(), usage)
        }
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
        if (trimmed.isEmpty()) throw LlmException("请先在「设置」里填写接口地址")
        return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
    }

    private fun modelsEndpoint(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) throw LlmException("请先在「设置」里填写接口地址")
        return when {
            trimmed.endsWith("/models") -> trimmed
            trimmed.endsWith("/chat/completions") -> trimmed.removeSuffix("/chat/completions") + "/models"
            else -> "$trimmed/models"
        }
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

        /**
         * Same timeouts, but accepts any certificate and any hostname.
         *
         * Only reachable through `AppSettings.allowInsecureTls`, which the user turns on
         * themselves for a self-signed endpoint on their own network.
         */
        fun insecureClient(): OkHttpClient {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
            return defaultClient().newBuilder()
                .sslSocketFactory(context.socketFactory, trustAll)
                .hostnameVerifier { _, _ -> true }
                .build()
        }
    }
}

/**
 * Reads a usage block, tolerating the four spellings of "thinking tokens" and
 * "cached prompt tokens" that are in circulation.
 *
 * Returns null when the object carries no token counts at all, so a gateway that
 * sends `"usage": null` or an empty object does not show up as a row of zeros.
 */
internal fun parseUsage(usage: JsonObject): Usage? {
    fun number(vararg paths: String): Int {
        for (path in paths) {
            var cursor: JsonElement = usage
            for (step in path.split('.')) {
                cursor = (cursor as? JsonObject)?.get(step) ?: break
            }
            val parsed = ((cursor as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content)
                ?.toDoubleOrNull()
                ?.toInt()
            if (parsed != null) return parsed
        }
        return 0
    }

    val prompt = number("prompt_tokens", "input_tokens")
    val completion = number("completion_tokens", "output_tokens")
    val reasoning = number(
        "completion_tokens_details.reasoning_tokens",
        "completion_thinking_tokens",
        "reasoning_tokens",
        "thinking_tokens",
    )
    val cached = number(
        "prompt_tokens_details.cached_tokens",
        "prompt_cache_hit_tokens",
        "cache_read_input_tokens",
        "cached_tokens",
    )
    if (prompt == 0 && completion == 0 && reasoning == 0) return null
    return Usage(prompt, completion, reasoning, cached)
}

/** Reads the model ids out of an OpenAI-style `GET /models` response. */
internal fun parseModels(body: String): List<String> {    val trimmed = body.trim()
    if (trimmed.isEmpty()) throw LlmException("服务器返回了空内容")
    val root = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull()
        ?: throw LlmException("服务器返回的不是 JSON：${trimmed.take(160)}")
    val array = when (root) {
        is JsonArray -> root
        is JsonObject -> root["data"] as? JsonArray
            ?: root["models"] as? JsonArray
            ?: root["result"] as? JsonArray
            ?: throw LlmException("返回里没有 data / models 字段")
        else -> throw LlmException("返回格式不认识：${trimmed.take(160)}")
    }
    val ids = array.mapNotNull { element ->
        when (element) {
            is JsonPrimitive -> element.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }
            is JsonObject -> {
                val field = element["id"] ?: element["name"] ?: element["model"]
                (field as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }
            }
            else -> null
        }
    }.distinct()
    if (ids.isEmpty()) throw LlmException("服务器没有返回任何模型")
    return ids
}

/** Pulls a human-readable reason out of an error body, whatever shape it has. */
internal fun extractApiError(body: String): String {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return ""
    val parsed = runCatching { Json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
    if (parsed != null) {
        val message = when (val error = parsed["error"]) {
            is JsonObject -> error["message"].asText()
            is JsonPrimitive -> error.takeIf { it !is JsonNull }?.content
            else -> null
        } ?: parsed["message"].asText()
            ?: parsed["detail"].asText()
            ?: parsed["msg"].asText()
        if (!message.isNullOrBlank()) return message.take(300)
    }
    return trimmed.take(200).replace(Regex("\\s+"), " ")
}

private fun JsonElement?.asText(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }

/**
 * The text of a streamed field, whatever shape it arrived in.
 *
 * Plain strings pass through untouched — including blank ones, because a streamed "\n\n" is
 * markdown paragraph spacing. Some gateways send a structured block instead
 * (`[{"type":"thinking","thinking":"…"}]`, or `{"text":"…"}`), which used to be dropped
 * because it is not a `JsonPrimitive`; those are flattened here.
 */
private fun JsonElement?.textOf(): String? = when (this) {
    null -> null
    is JsonPrimitive -> contentOrNull
    is JsonArray -> mapNotNull { it.textOf() }.joinToString("").ifEmpty { null }
    is JsonObject -> (this["text"] ?: this["thinking"] ?: this["content"]).textOf()
}

/**
 * The thinking text of one chunk, or null when the chunk carries none worth showing.
 *
 * Where this goes is not standardised: DeepSeek and most Chinese gateways put
 * `reasoning_content` on the delta, OpenRouter and the Anthropic-compatible shims use
 * `reasoning`, some reverse proxies hoist either to the top level of the chunk or onto the
 * choice, and a few answer with whole non-streaming-shaped chunks that carry it under
 * `message`.
 *
 * Blank pieces are dropped on purpose. An endpoint that streams only whitespace used to fill
 * the UI's thinking block with newlines, which reads as "thinking, 0 tokens" next to a running
 * timer — the empty block users reported. Real reasoning keeps its paragraph breaks because
 * those arrive attached to text.
 */
private fun reasoningOf(
    chunk: JsonObject,
    choice: JsonObject,
    delta: JsonObject?,
    message: JsonObject?,
): String? {
    val candidates = listOf(
        delta?.get("reasoning_content"),
        delta?.get("reasoning"),
        message?.get("reasoning_content"),
        message?.get("reasoning"),
        choice["reasoning_content"],
        choice["reasoning"],
        chunk["reasoning_content"],
        chunk["reasoning"],
    )
    return candidates.firstNotNullOfOrNull { it.textOf()?.takeIf { text -> text.isNotBlank() } }
}

/** Turns an HTTP status into advice, not just a number. */
internal fun friendlyHttp(code: Int, body: String): String {
    val hint = when (code) {
        400 -> "服务器说请求格式不对"
        401 -> "API Key 不对或已失效"
        403 -> "服务器拒绝了这个 Key（可能是地区或权限限制）"
        404 -> "路径不对：接口地址要以 /v1 结尾，而不是完整的 /chat/completions"
        408 -> "服务器等超时了"
        413 -> "请求体太大（多半是截图太多）"
        429 -> "请求太频繁或额度用完，稍后再试"
        in 500..599 -> "服务器内部出错"
        else -> "请求失败"
    }
    val detail = extractApiError(body)
    return "$hint（HTTP $code）${if (detail.isBlank()) "" else "：$detail"}"
}

/**
 * Maps the exceptions users actually hit onto something actionable.
 *
 * The certificate branch matters most: a reverse proxy on the LAN hands out a
 * self-signed certificate and Android reports it as
 * `CertPathValidatorException: Trust anchor for certification path not found`,
 * which says nothing about what to do next.
 */
internal fun describeError(error: Throwable): String {
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    val text = chain.joinToString(" | ") { "${it::class.java.simpleName}: ${it.message.orEmpty()}" }
    val certProblem = chain.any {
        it is CertPathValidatorException ||
            it is CertificateException ||
            it is SSLHandshakeException ||
            it is SSLPeerUnverifiedException
    }
    return when {
        certProblem && (text.contains("Trust anchor", true) || text.contains("certification path", true)) ->
            "服务器证书不被信任（多半是自签名证书）。打开「模型」里的『允许自签名证书』再试，" +
                "或把地址换成 http://，或在手机上安装这张证书。"
        certProblem || text.contains("SSL", true) || text.contains("certificate", true) ->
            "TLS 握手失败：服务器证书有问题。可试『允许自签名证书』，或改用 http://。"
        chain.any { it is UnknownHostException } ->
            "找不到这个域名：检查接口地址的拼写，以及手机有没有连上网。"
        chain.any { it is SocketTimeoutException } ->
            "连接超时：地址或端口可能不对，也可能手机和服务器不在同一个网络。"
        chain.any { it is ConnectException } ->
            "连不上服务器：确认端口是开着的，而且手机和服务器在同一个网络。"
        chain.any { it is LlmException } ->
            chain.first { it is LlmException }.message.orEmpty().ifBlank { "请求失败" }
        else -> "${error::class.java.simpleName}：${error.message ?: "没有更多信息"}"
    }
}
