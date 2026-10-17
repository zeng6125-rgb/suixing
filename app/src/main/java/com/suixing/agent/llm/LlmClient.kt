package com.suixing.agent.llm

import com.suixing.agent.data.AppSettings
import com.suixing.agent.tools.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InterruptedIOException
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
    /** 多附件：这条消息携带的全部图片；wire 上每张一个 image_url 项。见 [allImages]。 */
    val imagesBase64: List<String> = emptyList(),
    /**
     * 这条消息里的图片是**工具截的**（ui_screenshot），不是用户上传的附件。
     *
     * 只有截图会在超预算时被降级（见 AgentEngine.pruneRequestImages）：用户自己传的图是他给的
     * 输入，静默省掉等于篡改；工具截的图随时可以重截。
     */
    val toolScreenshot: Boolean = false,
) {
    /** 单值与列表的聚合读侧：旧调用点（工具截图）与新调用点（多附件）都不丢。 */
    val allImages: List<String> get() = imagesBase64.ifEmpty { listOfNotNull(imageBase64) }

    /** 去掉图片并补一行说明；[toolScreenshot] 保留，降级过的条目仍认得出是截图。 */
    fun omitImages(note: String): ChatMessage = copy(
        text = (text + note).trim(),
        imageBase64 = null,
        imagesBase64 = emptyList(),
    )

    companion object {
        fun system(text: String) = ChatMessage("system", text)

        fun user(text: String, imageBase64: String? = null) = ChatMessage("user", text, imageBase64 = imageBase64)

        /** 多附件变体：一条用户消息带全部图片。 */
        fun user(text: String, imagesBase64: List<String>) =
            ChatMessage("user", text, imagesBase64 = imagesBase64)

        /** 工具截图：带上 [toolScreenshot]，超预算时是可以被降级的那一类。 */
        fun screenshot(text: String, imagesBase64: List<String>) =
            ChatMessage("user", text, imagesBase64 = imagesBase64, toolScreenshot = true)

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

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The shortest and longest wait between two attempts, whatever the settings or a header says. */
internal const val MIN_RETRY_DELAY_SECONDS = 1
internal const val MAX_RETRY_DELAY_SECONDS = 60

/** The most retries the settings can ask for; the settings page offers 0–5. */
internal const val MAX_RETRY_ATTEMPTS = 5

/**
 * Whether a failed attempt may be tried again.
 *
 * One function, so the rule is one place and can be tested without a server. The rule, in order:
 *
 *  - **the user stopped it** — never retry. A cancelled call is an instruction, not a failure;
 *  - **anything already arrived** — never retry. The endpoint may have billed for the part it sent,
 *    and resending would hand the user the same paragraph twice. This is the line that keeps a
 *    retry from turning into duplicated output;
 *  - **out of attempts** — no. [attempt] counts attempts already made (1 after the first try), and
 *    [maxAttempts] is how many *retries* are allowed, so `attempt <= maxAttempts` is the budget;
 *  - **no status** — a transport failure (connection reset, DNS, timeout) and worth another go;
 *  - **429** — rate limited, worth another go (the caller prefers the server's `Retry-After`);
 *  - **5xx** — the server's own fault, worth another go;
 *  - **any other status** — a real answer: 400 means the request is wrong, 401/403 means the key is,
 *    and repeating either just repeats the error.
 */
internal fun shouldRetry(
    status: Int?,
    attempt: Int,
    maxAttempts: Int,
    receivedAnyOutput: Boolean,
    cancelled: Boolean,
): Boolean {
    if (cancelled) return false
    if (receivedAnyOutput) return false
    if (attempt > maxAttempts) return false
    return when {
        status == null -> true
        status == 429 -> true
        status in 500..599 -> true
        else -> false
    }
}

/**
 * `Retry-After` in seconds, or null when the header is absent or unreadable.
 *
 * A readable number is honoured as the server's own timing and clamped into
 * [MIN_RETRY_DELAY_SECONDS]..[MAX_RETRY_DELAY_SECONDS]: `0` ("retry now") becomes the one-second
 * floor rather than the configured delay, because the server did say something and it said "no
 * wait"; an hour becomes a minute, because a wait that long reads as a hang.
 *
 * Only the delta-seconds form is read. The HTTP-date form, a negative number and plain garbage are
 * all treated as unreadable and return null, which leaves the caller with the configured delay —
 * the safe direction, since guessing a date wrong could park the run far longer than the user asked
 * for.
 */
internal fun parseRetryAfter(header: String?): Int? = header
    ?.trim()
    ?.toIntOrNull()
    ?.takeIf { it >= 0 }
    ?.coerceIn(MIN_RETRY_DELAY_SECONDS, MAX_RETRY_DELAY_SECONDS)

/**
 * The finish reason of a round that delivered output and then had its stream cut short.
 *
 * Not `length` (which would claim a token limit was hit) and not a failure: the content is real and
 * is kept, the round counts as finished, and the caller may say the answer looks unfinished.
 */
internal const val FINISH_CUT = "cut"

/** Handed to [LlmClient.complete]'s `onRetry` just before it waits. */
data class RetryNotice(
    /** Which attempt just failed (1 = the first try). */
    val attempt: Int,
    /** How many retries the settings allow, so the caller can say "第 2 次重试". */
    val maxAttempts: Int,
    val delayMs: Long,
    /** Short, user-facing: why this attempt is being repeated. */
    val reason: String,
    /**
     * The HTTP status that failed, or null for a transport failure (no status ever arrived).
     *
     * Carried so the caller's trace can say *what* is being retried: "429 twice" and "connection
     * reset twice" are the same to the retry rule and completely different to whoever reads the log.
     */
    val code: Int? = null,
)

/**
 * A failed attempt, carrying what the retry decision needs. Never escapes [LlmClient.complete]:
 * the original failure is what gets thrown once the retries are used up.
 *
 * The field is [failure] and not `cause`: `Throwable.cause` already means something here (it is set
 * by the constructor below), and a same-named property would shadow it.
 */
private class AttemptFailure(
    val code: Int?,
    val retryAfterSeconds: Int?,
    val failure: Throwable,
) : Exception(failure.message, failure)

/** Short, user-facing reason for a retryable status. */
private fun retryReason(code: Int?): String = when {
    code == 429 -> "服务器限流（429）"
    code != null -> "服务器出错（$code）"
    else -> "网络中断"
}

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
            // 自定义头同样要带（高级参数）：网关若靠特殊头鉴权，/models 不带就是 401。
            .apply { settings.requestHeaders.forEach { (n, v) -> if (n.isNotBlank()) header(n, v) } }
            .get()
            .build()
        clientFor(settings).newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw LlmException(friendlyHttp(response.code, body))
            parseModels(body)
        }
    }

    /**
     * The same `GET {base}/models`, keeping the context window each entry advertises next to
     * its id. Kept apart from [listModels] on purpose: the id list is used in several places
     * and its shape must not move, while the window is a bonus a caller may or may not want.
     */
    suspend fun listModelDetails(settings: AppSettings): List<ProviderModel> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(modelsEndpoint(settings.baseUrl))
            .header("Accept", "application/json")
            .apply { if (settings.apiKey.isNotBlank()) header("Authorization", "Bearer ${settings.apiKey}") }
            .apply { settings.requestHeaders.forEach { (n, v) -> if (n.isNotBlank()) header(n, v) } }
            .get()
            .build()
        clientFor(settings).newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw LlmException(friendlyHttp(response.code, body))
            parseModelDetails(body)
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
        /**
         * Told before each retry wait, so the caller can show something instead of going quiet.
         *
         * Never called for the final failure — that travels as the thrown error, the same way it
         * always has.
         */
        onRetry: (RetryNotice) -> Unit = {},
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
        val builder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("Authorization", "Bearer ${settings.apiKey}")
        // 自定义请求头最后加（设置→高级参数）：同名时用户的值赢过上面的默认头。
        settings.requestHeaders.forEach { (name, value) ->
            if (name.isNotBlank()) builder.header(name, value)
        }
        val request = builder
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        val maxRetries = if (settings.retryOnFailure) {
            settings.retryMaxAttempts.coerceIn(0, MAX_RETRY_ATTEMPTS)
        } else {
            0
        }
        var attempt = 0
        while (true) {
            attempt++
            // Whether *this* attempt delivered anything. The retry decision needs it per attempt:
            // text that arrived on a previous try does not matter (nothing was kept), but text that
            // arrived on this one does — the endpoint has already been paid for it and the user has
            // already seen it, so resending would duplicate both.
            var received = false
            val call = clientFor(settings).newCall(request)
            onCall(call)
            // The turn this attempt has assembled so far, kept outside the block below: a stream
            // that breaks after delivering text still has to hand that text back — see `received`.
            var partial: StreamTurn? = null
            val outcome = runCatching {
                call.execute().use { response ->
                    val body = response.body
                    if (!response.isSuccessful || body == null) {
                        // 取证（真机 0.8.116 报障：压缩后首轮被网关 400，错误体只说「request was
                        // rejected by an internal MaaS component」，本地 A/B 把形状/体量/模型假设全
                        // 证伪后仍不知真因）：非 2xx 时把**完整请求体**与响应体落盘，下次复现拉文件
                        // 就能对着真 payload 找差异。截断到 2MB 防撑爆私有目录；只留最近一次。
                        val errBody = body?.string().orEmpty()
                        runCatching {
                            val app = com.suixing.agent.core.Core.appContext
                            val dump = buildJsonObject {
                                put("code", JsonPrimitive(response.code))
                                put("response", JsonPrimitive(errBody.take(8_000)))
                                put("request", JsonPrimitive(payload.toString().take(2_000_000)))
                            }
                            java.io.File(app.filesDir, "last-http-error.json").writeText(dump.toString())
                        }
                        // The status and the header are what the retry decision runs on, so they ride
                        // along in the failure instead of being flattened into a message here.
                        throw AttemptFailure(
                            code = response.code,
                            retryAfterSeconds = parseRetryAfter(response.header("Retry-After")),
                            failure = LlmException(friendlyHttp(response.code, errBody)),
                        )
                    }
                    val stream = StreamTurn().also { partial = it }
                    val source = body.source()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isEmpty()) continue
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data.isEmpty()) continue
                        // `[DONE]` ends the stream, so stop reading here. A gateway that keeps the
                        // connection open afterwards would otherwise leave this loop blocked until the
                        // read timeout — or the 10-minute call cap — with the answer already in hand.
                        if (data == "[DONE]") break
                        val chunk = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                        chunk["error"]?.let { error ->
                            val message = (error as? JsonObject)?.get("message")?.jsonPrimitive?.content ?: error.toString()
                            throw LlmException("接口返回错误：$message")
                        }
                        stream.accept(
                            chunk,
                            { piece -> received = true; onDelta(piece) },
                            { piece -> received = true; onReasoning(piece) },
                        )
                        // Tool-call deltas do not travel through the two callbacks above, so a round
                        // that only asked for a tool would look like "nothing arrived" — and be
                        // retried, which is a second request for a turn the endpoint already
                        // answered, and a second round of tool calls on top of it.
                        if (stream.hasToolCalls()) received = true
                    }
                    stream.toTurn()
                }
            }
            outcome.getOrNull()?.let { return@withContext it }
            val failure = outcome.exceptionOrNull() ?: error("runCatching without a value")
            // Output already arrived, so this round ends as **success with what came in** — never as
            // a failure, and never as a retry. The endpoint has been paid for those tokens and the
            // user is already reading them: reporting a failure would end the run on an answer that
            // is on screen, and resending would print it twice. A stream that stops early is marked
            // with [FINISH_CUT] so the caller can say the answer looks unfinished without calling
            // the whole round a failure.
            if (received) {
                partial?.toTurn()?.let { return@withContext it.copy(finishReason = FINISH_CUT) }
            }
            val code = (failure as? AttemptFailure)?.code
            // OkHttp reports a cancelled call as an IOException, so the call's own flag is the
            // reliable way to tell "the user stopped this" from "the network broke".
            val cancelled = call.isCanceled() || !isActive
            if (!shouldRetry(code, attempt, maxRetries, received, cancelled)) {
                // The original failure is what callers have always seen: an LlmException with the
                // friendly message for a status, or the raw IOException for a transport failure.
                throw (failure as? AttemptFailure)?.failure ?: failure
            }
            val serverSeconds = (failure as? AttemptFailure)?.retryAfterSeconds
            val seconds = serverSeconds
                ?: settings.retryDelaySeconds.coerceIn(MIN_RETRY_DELAY_SECONDS, MAX_RETRY_DELAY_SECONDS)
            onRetry(
                RetryNotice(
                    attempt = attempt,
                    maxAttempts = maxRetries,
                    delayMs = seconds * 1000L,
                    reason = retryReason(code),
                    code = code,
                ),
            )
            // Cancellable on purpose: a stop during the wait must end the run at once rather than
            // after the sleep, and `delay` throws CancellationException exactly for that.
            delay(seconds * 1000L)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
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
            // `as?` rather than `jsonArray`/`jsonObject`, which throw on a JsonNull: gateways do send
            // `"choices": null`, `"delta": null` or `"tool_calls": null` on some frames, and an
            // IllegalArgumentException here would abort an answer that is otherwise arriving fine.
            val choice = ((chunk["choices"] as? JsonArray)?.firstOrNull() as? JsonObject) ?: return
            choice["finish_reason"]?.let { element ->
                if (element !is JsonNull) finishReason = element.jsonPrimitive.content
            }
            // A gateway streams `delta` chunks, whole `message` chunks, or both; the delta wins
            // when it carries the field, so a chunk that repeats itself is not counted twice.
            val delta = choice["delta"] as? JsonObject
            val message = choice["message"] as? JsonObject

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
            (rawCalls as? JsonArray)?.forEach { element ->
                val item = element as? JsonObject ?: return@forEach
                val index = item["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val call = calls.getOrPut(index) { MutableToolCall() }
                item["id"]?.let { if (it !is JsonNull) call.id = it.jsonPrimitive.content }
                val function = item["function"] as? JsonObject ?: return@forEach
                function["name"]?.let { if (it !is JsonNull) call.name = it.jsonPrimitive.content }
                function["arguments"]?.let { if (it !is JsonNull) call.arguments.append(it.jsonPrimitive.content) }
            }
        }

        /** Whether this attempt has asked for a tool yet — output the retry decision must count. */
        fun hasToolCalls(): Boolean = calls.values.any { it.name.isNotBlank() }

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
        val images = message.allImages
        when {
            // 多附件：content 数组里每张图一个 image_url 项（OpenAI 兼容格式本就允许并列多项）。
            images.isNotEmpty() -> put(
                "content",
                buildJsonArray {
                    if (message.text.isNotBlank()) {
                        add(buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(message.text))
                        })
                    }
                    images.forEach { image ->
                        add(buildJsonObject {
                            put("type", JsonPrimitive("image_url"))
                            put(
                                "image_url",
                                buildJsonObject { put("url", JsonPrimitive("data:image/jpeg;base64,$image")) },
                            )
                        })
                    }
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
                                    // 空参数一律发 `{}`（用户报的 HTTP 400「invalid function call
                                    // parameters」）：网关会校验历史里每个 tool_call 的 arguments
                                    // 是不是合法 JSON 对象，空字符串（无参调用、旧存档）会被直接拒。
                                    put("arguments", JsonPrimitive(call.arguments.ifBlank { "{}" }))
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
                // 出口兜底（取证：网关 11129「工具定义不符合模型要求」会拒掉**整个请求**，一个
                // 工具的 schema 缺 type/properties 就连坐全部工具）：这里再确认一次它是合法的
                // JSON Schema 对象。源头已在 PluginRuntime.normalizeSchema 补齐，这一层保证
                // 任何工具族（内置/插件/将来新增）都不会把整批请求拖死。
                put("parameters", spec.parameters.normalizedSchema() as JsonElement)
            },
        )
    }

    /** `{"type":"object","properties":{…}}`：缺什么补什么，已有的关键字原样保留。 */
    private fun JsonObject.normalizedSchema(): JsonObject {
        val props = this["properties"] as? JsonObject
        val typed = (this["type"] as? JsonPrimitive)?.content == "object"
        if (typed && props != null) return this
        return buildJsonObject {
            forEach { (key, value) -> if (key != "type" && key != "properties") put(key, value) }
            put("type", JsonPrimitive("object"))
            put("properties", props ?: JsonObject(emptyMap()))
        }
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
            // 600s, not 180（用户：「为什么老是会断？你是不是默认设置了什么输出时间」——是，
            // 就是这里）：深度思考的轮可以三分钟没有一个字节（真机截图 227s/294s 的 run），180s
            // 的 read timeout 把这种轮切成「连接中断」。流式响应有连续数据、真死连接仍会在这
            // 里暴露；长思考无字节不是死连接。上限交给 10 分钟的 callTimeout 一条。
            .readTimeout(600, TimeUnit.SECONDS)
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

/** One entry of `GET /models`: its id, plus the context window the endpoint advertises. */
data class ProviderModel(val id: String, val contextLength: Int? = null)

/**
 * The same response as [parseModels], read for the context window as well.
 *
 * The field is not standardised. `context_window` is what the gateway this app was tested
 * against sends (verified against a real response); the rest are the names other
 * OpenAI-compatible servers use for the same number, tried in order. Nothing is guessed from
 * the shape of the value: an entry that advertises no usable window simply carries null, and
 * the caller decides what to do about that.
 */
internal fun parseModelDetails(body: String): List<ProviderModel> {
    val trimmed = body.trim()
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
    val models = array.mapNotNull { element ->
        when (element) {
            is JsonPrimitive -> element.takeIf { it !is JsonNull }?.content
                ?.takeIf { it.isNotBlank() }
                ?.let { ProviderModel(it) }
            is JsonObject -> {
                val field = element["id"] ?: element["name"] ?: element["model"]
                val id = (field as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }
                id?.let { ProviderModel(it, contextLengthOf(element)) }
            }
            else -> null
        }
    }.distinctBy { it.id }
    if (models.isEmpty()) throw LlmException("服务器没有返回任何模型")
    return models
}

/** The context window an entry advertises, or null when it advertises none. */
private fun contextLengthOf(element: JsonObject): Int? {
    val candidates = listOf(
        element["context_window"],
        element["context_length"],
        (element["top_provider"] as? JsonObject)?.get("context_length"),
        element["max_context_length"],
        element["max_model_len"],
        element["max_input_tokens"],
    )
    return candidates.firstNotNullOfOrNull { value ->
        (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toIntOrNull()?.takeIf { it > 0 }
    }
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
        // OkHttp's own call timeout arrives as a plain InterruptedIOException("timeout"), which is
        // NOT a SocketTimeoutException — so without this branch the user was shown the raw class
        // name. The cap is [defaultClient]'s 10-minute callTimeout: one round that never finished.
        chain.any { it is InterruptedIOException } ->
            "这一轮请求超过 10 分钟被中断（回答太长，或服务器一直没结束）。" +
                "可以再发一次，或把要求拆成几步。"
        chain.any { it is ConnectException } ->
            "连不上服务器：确认端口是开着的，而且手机和服务器在同一个网络。"
        chain.any { it is LlmException } ->
            chain.first { it is LlmException }.message.orEmpty().ifBlank { "请求失败" }
        // 兜底行带上 **cause 链**：ExceptionInInitializerError 这类异常的 message 是 null，真因在
        // cause 里（哪个类的静态初始化炸了）。只打类名时回执写「没有更多信息」，真机复现也定不了位
        // （用户截图报过）。链取到第一个有 message 的为止，够定位又不刷屏。
        else -> buildString {
            append(error::class.java.simpleName)
            append("：")
            append(error.message ?: "没有更多信息")
            var cause = error.cause
            var depth = 0
            while (cause != null && depth < 3) {
                append(" ← ")
                append(cause::class.java.simpleName)
                cause.message?.let { append("(").append(it).append(")") }
                cause = cause.cause
                depth++
            }
        }
    }
}
