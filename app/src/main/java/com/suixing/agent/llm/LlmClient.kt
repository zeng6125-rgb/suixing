package com.suixing.agent.llm

import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.SecretBox
import com.suixing.agent.data.PROTOCOL_ANTHROPIC
import com.suixing.agent.data.PROTOCOL_OPENAI
import com.suixing.agent.data.PROTOCOL_RESPONSES
import com.suixing.agent.data.Roles
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

    /** 图片张数，**不分配对象**（`allImages` 每次访问都新建一个 List）。只数张数时代替它。 */
    val imageCount: Int
        get() = if (imagesBase64.isNotEmpty()) imagesBase64.size else if (imageBase64 != null) 1 else 0

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
    /**
     * 流**在已经收到内容之后**断掉（局域网重启、上游抖动、连接被掐）。
     *
     * 与"什么都没收到就失败"不同：这种情况下重试要先撤回已经流出去的那半截（调用方通过
     * `onRestart` 清掉这一轮），否则屏幕上会出现两遍同样的开头。用户明确要的是"断了就自动重试"，
     * 而不是停在中途那个节点上不动。
     */
    midStream: Boolean = false,
): Boolean {
    if (cancelled) return false
    if (receivedAnyOutput && !midStream) return false
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

/**
 * 流在说完之前就断了：既没有 `[DONE]`，也没有 `finish_reason`。
 *
 * 它不代表端点报错，只代表连接没了（局域网重启、上游抖动、网关掐流）。重试规则对"没有状态码"
 * 的处理正是它该走的那条（见 [shouldRetry]）；之所以单独一个类型，是为了让重试提示能说
 * 「连接中断」而不是笼统的「网络中断」。
 */
private class CutFailure : Exception("连接中断，这一轮的回答不完整。")

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
/**
 * 采样参数要不要进请求体 —— **默认两个都不进**（用户 2026-10-23：
 * 「温度和 top-p 都默认不发」）。
 *
 * * `temperature`：只有调用方显式把 `sendTemperature` 打开才发。默认关，而且**没有 UI
 *   开关** —— 推理型端点（OpenAI 推理系等）会直接拒收它（400），其余的多半忽略，
 *   而 agent 场景要的是确定性而不是采样多样性。字段留着是为了将来真要开时不用改结构。
 * * `top_p`：只有 `sendTopP` 打开才发。**没有 UI 开关** —— 滑杆最左那一档就是
 *   「不发送」，停在那里这个布尔就是 false（默认值），所以升级后一律不发。
 *
 * 抽成纯函数是为了能单测：三个构造器（OpenAI / Anthropic / Responses）都调它，
 * 断言它是空列表就等于断言三个请求体都不含这两个字段。
 */
internal fun samplingFields(settings: AppSettings): List<Pair<String, kotlinx.serialization.json.JsonElement>> =
    buildList {
        if (settings.sendTemperature) add("temperature" to JsonPrimitive(settings.temperature))
        if (settings.sendTopP) add("top_p" to JsonPrimitive(settings.topP))
    }

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
        val key = settings.plainApiKey()
        val request = Request.Builder()
            .url(modelsEndpoint(settings.baseUrl, settings.protocol))
            .header("Accept", "application/json")
            .apply {
                if (settings.protocol == PROTOCOL_ANTHROPIC) {
                    if (key.isNotBlank()) header("x-api-key", key)
                    header("anthropic-version", ANTHROPIC_VERSION)
                } else if (key.isNotBlank()) {
                    header("Authorization", "Bearer $key")
                }
            }
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
        val key = settings.plainApiKey()
        val request = Request.Builder()
            .url(modelsEndpoint(settings.baseUrl, settings.protocol))
            .header("Accept", "application/json")
            .apply {
                if (settings.protocol == PROTOCOL_ANTHROPIC) {
                    if (key.isNotBlank()) header("x-api-key", key)
                    header("anthropic-version", ANTHROPIC_VERSION)
                } else if (key.isNotBlank()) {
                    header("Authorization", "Bearer $key")
                }
            }
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
        /**
         * 这一轮要**重新生成**：已经流给屏幕的内容作废，调用方把它从界面和缓冲里撤掉。
         *
         * 只在"收到内容之后流断了、而且还能重试"时调用（见 [shouldRetry] 的 `midStream`）。
         * 没有它，重发就会把同一段开头打印两遍。
         */
        onRestart: () -> Unit = {},
    ): AssistantTurn = withContext(Dispatchers.IO) {
        val protocol = settings.protocol
        val url = endpoint(settings.baseUrl, protocol)
        val payload = requestBody(settings, messages, tools, protocol)
        val builder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .apply { authHeaders(this, settings, protocol) }
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
            // 这一轮是否真的见到了流结束标记（`[DONE]`）。没见到就说明连接是被掐断的，
            // 而不是模型说完了 —— 见下面 `stream.toTurn()` 之后的判定。
            var sawDone = false
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
                    val stream = StreamTurn(protocol).also { partial = it }
                    val source = body.source()
                    // **SSE 事件可以跨多行 `data:`**（规范：同一事件的多行用 "\n" 连接，空行结束）。
                    // 旧代码把每一行 `data:` 都当成完整 JSON、解析失败就 `?: continue` 静默丢掉 ——
                    // 网关把一条事件拆成两行（长思考/长正文时很常见）时，那段内容会凭空消失，
                    // 表现就是「思考块一结束就停住」（用户判断：是 SSE 的问题）。
                    // 现在两派都兼容：单行能解析就当场解析；解析不了就先攒着，碰到空行再拼起来。
                    val pending = StringBuilder()
                    // 多行 JSON 的配平状态：**必须和 pending 同寿命** ——
                    // 放在读行循环里会每行重置，多行 JSON 永远配不平、整条事件丢失。
                    val frame = SseFrames.State()
                    var unparsed = 0
                    /**
                     * 已经解析好的 chunk 交给 stream。
                     *
                     * **单独拆出来**（优化清单 llm-P0）：热路径（单行 data 帧，每 delta 一条）原来
                     * 先做一次"探针解析"判断能否当场处理，成功后又把同一个字符串交给
                     * `acceptPayload` 再解析一遍 —— 每条 delta 两次全量 JSON 解析。
                     * 现在探针的解析结果直接复用。
                     */
                    fun acceptChunk(chunk: JsonObject) {
                        chunk["error"]?.let { error ->
                            val message = (error as? JsonObject)?.get("message")?.jsonPrimitive?.content
                                ?: error.toString()
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

                    /** 字符串入口（多行事件拼接后走这里），解析失败计入 [unparsed]。 */
                    fun acceptPayload(payload: String) {
                        val chunk = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull()
                        if (chunk == null) {
                            unparsed++
                            return
                        }
                        acceptChunk(chunk)
                    }
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isEmpty()) {
                            // 事件边界：把攒着的多行 data 拼成一条再解析。
                            if (pending.isNotEmpty()) {
                                acceptPayload(pending.toString())
                                pending.clear()
                                frame.reset()
                            }
                            continue
                        }
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data.isEmpty()) continue
                        // `[DONE]` ends the stream, so stop reading here. A gateway that keeps the
                        // connection open afterwards would otherwise leave this loop blocked until the
                        // read timeout — or the 10-minute call cap — with the answer already in hand.
                        if (data == "[DONE]") {
                            sawDone = true
                            break
                        }
                        if (pending.isEmpty()) {
                            // 单行即完整事件的常见情况：解析一次、直接交给 stream（不再二次解析）。
                            val quick = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull()
                            if (quick != null) {
                                acceptChunk(quick)
                                continue
                            }
                        }
                        if (pending.isNotEmpty()) pending.append('\n')
                        pending.append(data)
                        // **不要每加一行就重新 parse 整个 pending**：网关把一个 JSON 拆成多行时，
                        // 那是 O(行数2) 的字符解析（30KB 的 chunk 约等于 600 行，十几万字符被反复解析）。
                        // 改用花括号配平判断「这个 JSON 完整了」，每行只扫本行；完整时仍会 parse 一次。
                        if (SseFrames.feed(frame, data)) {
                            acceptPayload(pending.toString())
                            pending.clear()
                            frame.reset()
                        }
                    }
                    // 流尾没有空行收尾的那条事件也别丢。
                    if (pending.isNotEmpty()) {
                        acceptPayload(pending.toString())
                        pending.clear()
                        frame.reset()
                    }
                    // 一个字符都没收到、却有解析不了的载荷：那是端点 SSE 格式不兼容，
                    // 不是"模型没说话"——明确报出来，别再静默收场。
                    if (!received && unparsed > 0 && !sawDone && stream.finishReason == null) {
                        throw LlmException("流式响应无法解析（$unparsed 段），端点返回的 SSE 格式不兼容")
                    }
                    val turn = stream.toTurn()
                    // 结束判定：见到 `[DONE]`，或端点给了 `finish_reason`，才算这一轮正常说完。
                    // 两者都没有 ⇒ 流是被掐断的（网关抖动、局域网断、上游掐流）。
                    //
                    // 这条必须保留：它正是「运行中被掐流就重试」的唯一判据，去掉就等于把半截
                    // 回答当完整回答交出去（用户：「那上游掐流又不行了？你这不是扯淡吗」）。
                    //
                    // 那 d1api 为什么以前会停？**不是因为这条太严，是因为解析漏了 chunk**：
                    // 旧代码把每个 `data:` 行都当完整 JSON、解析失败 `?: continue` 静默丢掉，
                    // 端点把长事件拆成多行时，最后那个带 `finish_reason` 的块就被吞了 ⇒
                    // 这里判定"没结束" ⇒ 重试 ⇒ onRestart 清空 ⇒ 看起来"思考完就停"。
                    // 修的是上面那段多行拼接，不是把这里的判据拆掉。
                    // DSH 用的 pi-ai 也是同一条规矩（openai-completions.js: "Stream ended
                    // without finish_reason" 直接抛错）。
                    if (!sawDone && stream.finishReason == null) throw CutFailure()
                    turn
                }
            }
            outcome.getOrNull()?.let { return@withContext it }
            val failure = outcome.exceptionOrNull() ?: error("runCatching without a value")
            val code = (failure as? AttemptFailure)?.code
            // OkHttp reports a cancelled call as an IOException, so the call's own flag is the
            // reliable way to tell "the user stopped this" from "the network broke".
            val cancelled = call.isCanceled() || !isActive
            val serverSeconds = (failure as? AttemptFailure)?.retryAfterSeconds
            val seconds = serverSeconds
                ?: settings.retryDelaySeconds.coerceIn(MIN_RETRY_DELAY_SECONDS, MAX_RETRY_DELAY_SECONDS)

            // 内容已经到手、流却断了：**重新生成这一轮**。
            // 先让调用方撤回已经流到屏幕上的那半截（[onRestart]），再按同一份重试预算重发 ——
            // 用户要的是"断了就自动重试"，而不是停在中途那个节点不动。重试用尽时仍把收到的
            // 内容交回去（标 [FINISH_CUT]），不会白丢。
            if (received) {
                if (
                    shouldRetry(
                        status = code,
                        attempt = attempt,
                        maxAttempts = maxRetries,
                        receivedAnyOutput = true,
                        cancelled = cancelled,
                        midStream = true,
                    )
                ) {
                    onRestart()
                    onRetry(
                        RetryNotice(
                            attempt = attempt,
                            maxAttempts = maxRetries,
                            delayMs = seconds * 1000L,
                            reason = if (failure is CutFailure) "连接中断" else retryReason(code),
                            code = code,
                        ),
                    )
                    delay(seconds * 1000L)
                    continue
                }
                partial?.toTurn()?.let { return@withContext it.copy(finishReason = FINISH_CUT) }
            }
            if (!shouldRetry(code, attempt, maxRetries, received, cancelled)) {
                // The original failure is what callers have always seen: an LlmException with the
                // friendly message for a status, or the raw IOException for a transport failure.
                throw (failure as? AttemptFailure)?.failure ?: failure
            }
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
    internal class StreamTurn(private val protocol: String = PROTOCOL_OPENAI) {
        private val text = StringBuilder()
        private val thinking = StringBuilder()
        private val calls = LinkedHashMap<Int, MutableToolCall>()
        /** Responses addresses its argument deltas by item id, not by list index. */
        private val callsByItem = HashMap<String, Int>()

        var finishReason: String? = null
            private set
        var usage: Usage? = null
            private set

        fun accept(chunk: JsonObject, onDelta: (String) -> Unit, onReasoning: (String) -> Unit) {
            when (protocol) {
                PROTOCOL_ANTHROPIC -> acceptAnthropic(chunk, onDelta, onReasoning)
                PROTOCOL_RESPONSES -> acceptResponses(chunk, onDelta, onReasoning)
                else -> acceptChatCompletions(chunk, onDelta, onReasoning)
            }
        }

        // ---- the accumulator every protocol writes into (one copy, three readers) ----

        private fun emitText(piece: String?, onDelta: (String) -> Unit) {
            if (piece.isNullOrEmpty()) return
            text.append(piece)
            onDelta(piece)
        }

        private fun emitThinking(piece: String?, onReasoning: (String) -> Unit) {
            if (piece.isNullOrBlank()) return
            thinking.append(piece)
            onReasoning(piece)
        }

        /**
         * One tool call under construction. Id and name are only overwritten by a non-blank
         * value: several gateways repeat `""` on every argument chunk, and blanking the name
         * there is what used to erase the call entirely (see the note on the chat-completions
         * reader below).
         */
        private fun appendCall(index: Int, id: String?, name: String?, arguments: String?) {
            val call = calls.getOrPut(index) { MutableToolCall() }
            id?.takeIf { it.isNotBlank() }?.let { call.id = it }
            name?.takeIf { it.isNotBlank() }?.let { call.name = it }
            arguments?.let { call.arguments.append(it) }
        }

        private fun acceptChatCompletions(
            chunk: JsonObject,
            onDelta: (String) -> Unit,
            onReasoning: (String) -> Unit,
        ) {
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

            reasoningOf(chunk, choice, delta, message)?.let { piece -> emitThinking(piece, onReasoning) }

            // Blank pieces are kept here: a streamed "\n\n" is markdown paragraph spacing, and
            // dropping it would run the answer together.
            val content = if (delta != null && delta.containsKey("content")) {
                delta["content"].textOf()
            } else {
                message?.get("content").textOf()
            }
            if (content != null) emitText(content, onDelta)

            val rawCalls = if (delta != null && delta.containsKey("tool_calls")) {
                delta["tool_calls"]
            } else {
                message?.get("tool_calls")
            }
            (rawCalls as? JsonArray)?.forEach { element ->
                val item = element as? JsonObject ?: return@forEach
                val index = item["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val function = item["function"] as? JsonObject ?: return@forEach
                // **只在非空时覆盖**。d1api（以及不少 OpenAI 兼容端点）在后续每个参数增量块里
                // 都会带上 `"name": ""`、`"id": ""` —— 无条件赋值会把第一个块里真正的函数名
                // 抹成空串 ⇒ `hasToolCalls()` 为 false、`toTurn()` 把这个调用过滤掉 ⇒
                // 这一轮变成"只有思考、没有正文、也没有工具调用"⇒ 界面停在思考块。
                // 真机抓包确认过：第一个块 name=run_shell，随后 80+ 个参数块 name=""。
                // 这条规则现在收在 appendCall 里，三种协议共用。
                appendCall(
                    index = index,
                    id = (item["id"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
                    name = (function["name"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
                    arguments = (function["arguments"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content,
                )
            }
        }

        /**
         * Anthropic Messages (`/v1/messages`). Every payload carries its own `type`, so the
         * reader switches on that rather than on the SSE `event:` line — proxies drop or
         * rewrite `event:` often, and the body is still self-describing.
         *
         * The thinking is `thinking_delta` (blank ones dropped, same as everywhere else) and a
         * tool call is opened by `content_block_start` (`type: tool_use`, carrying id+name) then
         * fed by `input_json_delta.partial_json`. `stop_reason` is mapped onto the OpenAI
         * spelling because that is what the engine branches on ("length" in particular).
         */
        private fun acceptAnthropic(
            chunk: JsonObject,
            onDelta: (String) -> Unit,
            onReasoning: (String) -> Unit,
        ) {
            when ((chunk["type"] as? JsonPrimitive)?.content) {
                "message_start" -> usageOf(chunk["message"] as? JsonObject)?.let { usage = it }
                "content_block_start" -> {
                    val block = (chunk["content_block"] as? JsonObject) ?: return
                    if ((block["type"] as? JsonPrimitive)?.content == "tool_use") {
                        val index = chunk["index"].intOrNull() ?: 0
                        appendCall(
                            index = index,
                            id = block["id"].stringOrNull(),
                            name = block["name"].stringOrNull(),
                            // A tool with no parameters is announced as `{}` here rather than
                            // as deltas; keeping it means toTurn() has real arguments.
                            arguments = block["input"]?.takeIf { it !is JsonNull && it.toString() != "{}" }?.toString(),
                        )
                    }
                    emitText(block["text"].stringOrNull(), onDelta)
                }
                "content_block_delta" -> {
                    val delta = (chunk["delta"] as? JsonObject) ?: return
                    when ((delta["type"] as? JsonPrimitive)?.content) {
                        "text_delta" -> emitText(delta["text"].stringOrNull(), onDelta)
                        "thinking_delta" -> emitThinking(delta["thinking"].stringOrNull(), onReasoning)
                        // signature_delta is bookkeeping for replaying thinking; it is not prose.
                        "input_json_delta" -> appendCall(
                            index = chunk["index"].intOrNull() ?: 0,
                            id = null,
                            name = null,
                            arguments = delta["partial_json"].stringOrNull(),
                        )
                    }
                }
                "message_delta" -> {
                    ((chunk["delta"] as? JsonObject)?.get("stop_reason")).stringOrNull()
                        ?.let { finishReason = it.toFinishReason() }
                    // The output count and the reasoning split ride here, after the turn is done;
                    // the input count already arrived on message_start, so the two are added.
                    (chunk["usage"] as? JsonObject)?.let { mergeUsage(it) }
                }
                // `message_stop` is the end of the turn: Anthropic never sends `[DONE]`, so
                // without a finish reason here every turn would look cut and be retried.
                "message_stop" -> if (finishReason == null) finishReason = "stop"
            }
        }

        /**
         * OpenAI Responses (`/responses`). Deltas name their own event, and a function call is
         * matched by `item_id` rather than by list index, which is why [callsByItem] exists.
         */
        private fun acceptResponses(
            chunk: JsonObject,
            onDelta: (String) -> Unit,
            onReasoning: (String) -> Unit,
        ) {
            val type = (chunk["type"] as? JsonPrimitive)?.content
            when (type) {
                "response.output_item.added" -> {
                    val item = (chunk["item"] as? JsonObject) ?: return
                    if ((item["type"] as? JsonPrimitive)?.content != "function_call") return
                    val itemId = item["id"].stringOrNull() ?: item["call_id"].stringOrNull() ?: return
                    val index = callsByItem.getOrPut(itemId) { calls.size }
                    appendCall(
                        index = index,
                        id = item["call_id"].stringOrNull() ?: itemId,
                        name = item["name"].stringOrNull(),
                        arguments = item["arguments"].stringOrNull(),
                    )
                }
                "response.function_call_arguments.delta" -> {
                    // Only the arguments arrive here. The id must **not** be overwritten with
                    // `item_id`: `output_item.added` already stored the `call_id`, and that is
                    // the handle the API expects echoed back on the following function_call_output.
                    val itemId = chunk["item_id"].stringOrNull() ?: return
                    val index = callsByItem.getOrPut(itemId) { calls.size }
                    appendCall(index = index, id = null, name = null, arguments = chunk["delta"].stringOrNull())
                }
                "response.output_text.delta" -> emitText(chunk["delta"].stringOrNull(), onDelta)
                "response.reasoning_summary_text.delta",
                "response.reasoning_text.delta",
                -> emitThinking(chunk["delta"].stringOrNull(), onReasoning)
                "response.completed", "response.incomplete" -> {
                    val response = chunk["response"] as? JsonObject
                    usageOf(response)?.let { usage = it }
                    // `incomplete_details.reason` is where a token ceiling shows up; the engine
                    // only distinguishes "length" from everything else.
                    val incomplete = ((response?.get("incomplete_details") as? JsonObject)
                        ?.get("reason")).stringOrNull()
                    finishReason = if (incomplete != null) {
                        incomplete.toFinishReason()
                    } else if (calls.values.any { it.name.isNotBlank() }) {
                        "tool_calls"
                    } else {
                        "stop"
                    }
                }
            }
        }

        /** `message_start` nests its usage under `message`; Responses nests it under `response`. */
        private fun usageOf(container: JsonObject?): Usage? =
            (container?.get("usage") as? JsonObject)?.let { parseUsage(it) }

        /** Anthropic splits the counts across two events, so a later block is added, not set. */
        private fun mergeUsage(block: JsonObject) {
            val parsed = parseUsage(block) ?: return
            val existing = usage
            if (existing == null) {
                usage = parsed
                return
            }
            // **Latest non-zero wins, never a sum.** Anthropic splits the counts across two
            // events — message_start carries input_tokens, message_delta carries output_tokens —
            // but the latter is *cumulative*, so adding the two would report the answer twice.
            // Each field therefore takes the newer value when the newer event has one, and keeps
            // what it had otherwise.
            fun pick(newest: Int, previous: Int) = if (newest > 0) newest else previous
            usage = Usage(
                promptTokens = pick(parsed.promptTokens, existing.promptTokens),
                completionTokens = pick(parsed.completionTokens, existing.completionTokens),
                reasoningTokens = pick(parsed.reasoningTokens, existing.reasoningTokens),
                cachedTokens = pick(parsed.cachedTokens, existing.cachedTokens),
            )
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

    /**
     * The request body for one turn, in the shape [protocol] speaks.
     *
     * The three **must not share a builder**: they disagree on where the system prompt goes,
     * whether `max_tokens` is optional, and what a tool definition looks like. What they do
     * share is every message converter below, and the accumulator the answers land in.
     */
    private fun requestBody(
        settings: AppSettings,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        protocol: String,
    ): JsonObject = when (protocol) {
        PROTOCOL_ANTHROPIC -> anthropicBody(settings, messages, tools)
        PROTOCOL_RESPONSES -> responsesBody(settings, messages, tools)
        else -> chatCompletionsBody(settings, messages, tools)
    }

    private fun chatCompletionsBody(
        settings: AppSettings,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
    ): JsonObject = buildJsonObject {
        put("model", JsonPrimitive(settings.model))
        put("messages", JsonArray(messages.map { encodeMessage(it) }))
        put("stream", JsonPrimitive(true))
        // 采样参数（温度 / top_p）**默认都不发**，见 [samplingFields]。
        samplingFields(settings).forEach { (name, value) -> put(name, value) }
        // Ask for the usage block on the last chunk; endpoints that do not know
        // the flag ignore it, and the ones that do stop guessing.
        put("stream_options", buildJsonObject { put("include_usage", JsonPrimitive(true)) })
        if (settings.maxTokens > 0) put("max_tokens", JsonPrimitive(settings.maxTokens))
        if (settings.reasoningEffort.isNotBlank()) {
            put("reasoning_effort", JsonPrimitive(settings.reasoningEffort))
        }
        if (tools.isNotEmpty()) {
            put("tools", JsonArray(tools.map { encodeTool(it) }))
            put("tool_choice", JsonPrimitive("auto"))
        }
    }

    /**
     * Anthropic Messages.
     *
     * Three differences that matter: the system prompt is a **top-level `system` string**, not
     * a message (Anthropic rejects a `system` role); `max_tokens` is **required**, so a caller
     * that left it at 0 gets [DEFAULT_ANTHROPIC_MAX_TOKENS] rather than a 400; and a tool result
     * travels as a `user` message whose content is a `tool_result` block, not as a `tool` role.
     */
    private fun anthropicBody(
        settings: AppSettings,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
    ): JsonObject = buildJsonObject {
        put("model", JsonPrimitive(settings.model))
        put("max_tokens", JsonPrimitive(if (settings.maxTokens > 0) settings.maxTokens else DEFAULT_ANTHROPIC_MAX_TOKENS))
        put("stream", JsonPrimitive(true))
        // 采样参数（温度 / top_p）**默认都不发**，见 [samplingFields]。
        samplingFields(settings).forEach { (name, value) -> put(name, value) }
        val system = messages.filter { it.role == Roles.SYSTEM }
            .joinToString("\n\n") { it.text }
            .trim()
        if (system.isNotEmpty()) put("system", JsonPrimitive(system))
        put("messages", anthropicMessages(messages))
        if (tools.isNotEmpty()) {
            put("tools", JsonArray(tools.map { encodeAnthropicTool(it) }))
            put("tool_choice", buildJsonObject { put("type", JsonPrimitive("auto")) })
        }
    }

    /**
     * OpenAI Responses. `instructions` carries the system prompt; the rest is `input` items.
     *
     * The `store: false` is deliberate: these runs carry the user's whole conversation, and
     * the Responses API otherwise keeps it server-side for 30 days by default.
     */
    private fun responsesBody(
        settings: AppSettings,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
    ): JsonObject = buildJsonObject {
        put("model", JsonPrimitive(settings.model))
        put("stream", JsonPrimitive(true))
        put("store", JsonPrimitive(false))
        // 采样参数（温度 / top_p）**默认都不发**，见 [samplingFields]。
        samplingFields(settings).forEach { (name, value) -> put(name, value) }
        if (settings.maxTokens > 0) put("max_output_tokens", JsonPrimitive(settings.maxTokens))
        if (settings.reasoningEffort.isNotBlank()) {
            put("reasoning", buildJsonObject { put("effort", JsonPrimitive(settings.reasoningEffort)) })
        }
        val instructions = messages.filter { it.role == Roles.SYSTEM }
            .joinToString("\n\n") { it.text }
            .trim()
        if (instructions.isNotEmpty()) put("instructions", JsonPrimitive(instructions))
        put(
            "input",
            JsonArray(messages.filter { it.role != Roles.SYSTEM }.flatMap { encodeResponseItems(it) }),
        )
        if (tools.isNotEmpty()) {
            put("tools", JsonArray(tools.map { encodeResponseTool(it) }))
            put("tool_choice", JsonPrimitive("auto"))
        }
    }

    /**
     * The conversation as Anthropic accepts it: **consecutive messages of the same role are
     * merged into one**, because Anthropic requires them to alternate and rejects the request
     * otherwise.
     *
     * This is not a nicety. The engine appends one `tool` message per call — so two tools used
     * in one round produce two `tool` messages in a row, and a tool screenshot appends a `user`
     * message straight after. Both are ordinary OpenAI history and both are a 400 here. Merging
     * turns them into a single `user` turn holding several `tool_result` blocks, which is the
     * shape Anthropic documents for parallel tool use.
     */
    private fun anthropicMessages(messages: List<ChatMessage>): JsonArray {
        val merged = mutableListOf<JsonObject>()
        // Role and already-collected blocks of the message currently being extended.
        var role = ""
        var blocks = mutableListOf<JsonElement>()
        fun flush() {
            if (role.isEmpty()) return
            merged += buildJsonObject {
                put("role", JsonPrimitive(role))
                put("content", JsonArray(blocks))
            }
            blocks = mutableListOf<JsonElement>()
        }
        for (message in messages) {
            if (message.role == Roles.SYSTEM) continue
            val encoded = encodeAnthropicMessage(message)
            val nextRole = encoded["role"]?.stringOrNull() ?: continue
            val content = encoded["content"] as? JsonArray
            if (nextRole != role) {
                flush()
                role = nextRole
            }
            // addAll, not `+=`: JsonArray is both a JsonElement and a List<JsonElement>, and
            // `+=` resolves to the single-element overload — the whole array would be nested.
            if (content != null) blocks.addAll(content)
        }
        flush()
        return JsonArray(merged)
    }

    /**
     * One message as Anthropic content blocks.
     *
     * The shape Anthropic requires, and the reason this is not [encodeMessage]: an assistant
     * turn that used tools must carry `tool_use` blocks (with the id echoed back verbatim),
     * and the reply to each must be a `tool_result` block in a **user** message.
     */
    private fun encodeAnthropicMessage(message: ChatMessage): JsonObject = buildJsonObject {
        when (message.role) {
            Roles.TOOL -> {
                put("role", JsonPrimitive(Roles.USER))
                put(
                    "content",
                    buildJsonArray {
                        add(buildJsonObject {
                            put("type", JsonPrimitive("tool_result"))
                            put("tool_use_id", JsonPrimitive(message.toolCallId.orEmpty()))
                            put("content", JsonPrimitive(message.text))
                        })
                    },
                )
                return@buildJsonObject
            }
        }
        put("role", JsonPrimitive(if (message.role == Roles.ASSISTANT) Roles.ASSISTANT else Roles.USER))
        val images = message.allImages
        val blocks = buildJsonArray {
            if (images.isNotEmpty()) {
                if (message.text.isNotBlank()) {
                    add(buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive(message.text))
                    })
                }
                images.forEach { image ->
                    add(buildJsonObject {
                        put("type", JsonPrimitive("image"))
                        put(
                            "source",
                            buildJsonObject {
                                put("type", JsonPrimitive("base64"))
                                put("media_type", JsonPrimitive("image/jpeg"))
                                put("data", JsonPrimitive(image))
                            },
                        )
                    })
                }
            } else if (message.text.isNotEmpty() || message.toolCalls.isEmpty()) {
                add(buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive(message.text))
                })
            }
            message.toolCalls.forEach { call ->
                add(buildJsonObject {
                    put("type", JsonPrimitive("tool_use"))
                    put("id", JsonPrimitive(call.id))
                    put("name", JsonPrimitive(call.name))
                    put("input", call.arguments.toJsonObjectOrEmpty())
                })
            }
        }
        put("content", blocks)
    }

    /**
     * One message as Responses input items. Unlike chat completions, a tool call and its result
     * are **top-level items** (`function_call` / `function_call_output`), not fields on a
     * message, so one message may expand to several items.
     */
    private fun encodeResponseItems(message: ChatMessage): List<JsonObject> {
        if (message.role == Roles.TOOL) {
            return listOf(
                buildJsonObject {
                    put("type", JsonPrimitive("function_call_output"))
                    put("call_id", JsonPrimitive(message.toolCallId.orEmpty()))
                    put("output", JsonPrimitive(message.text))
                },
            )
        }
        val items = mutableListOf<JsonObject>()
        // A screenshot is an `input_image` item with a data URL; text rides beside it.
        val images = message.allImages
        if (images.isNotEmpty()) {
            val content = buildJsonArray {
                if (message.text.isNotBlank()) {
                    add(buildJsonObject {
                        put("type", JsonPrimitive("input_text"))
                        put("text", JsonPrimitive(message.text))
                    })
                }
                images.forEach { image ->
                    add(buildJsonObject {
                        put("type", JsonPrimitive("input_image"))
                        put("image_url", JsonPrimitive("data:image/jpeg;base64,$image"))
                    })
                }
            }
            items += buildJsonObject {
                put("role", JsonPrimitive(message.role))
                put("content", content)
            }
        } else if (message.text.isNotEmpty() || message.toolCalls.isEmpty()) {
            items += buildJsonObject {
                put("role", JsonPrimitive(message.role))
                put("content", JsonPrimitive(message.text))
            }
        }
        message.toolCalls.forEach { call ->
            items += buildJsonObject {
                put("type", JsonPrimitive("function_call"))
                put("call_id", JsonPrimitive(call.id))
                put("name", JsonPrimitive(call.name))
                put("arguments", JsonPrimitive(call.arguments.ifBlank { "{}" }))
            }
        }
        return items
    }

    /** `{name, description, input_schema}` — Anthropic's tool shape, schema not wrapped. */
    private fun encodeAnthropicTool(spec: ToolSpec): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(spec.name))
        put("description", JsonPrimitive(spec.description))
        put("input_schema", spec.parameters.normalizedSchema() as JsonElement)
    }

    /** Responses takes the flat function shape, with the schema under `parameters`. */
    private fun encodeResponseTool(spec: ToolSpec): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("function"))
        put("name", JsonPrimitive(spec.name))
        put("description", JsonPrimitive(spec.description))
        put("parameters", spec.parameters.normalizedSchema() as JsonElement)
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

    /**
     * Where one turn is posted, per wire.
     *
     * The address rule stays what the settings page teaches ("填到 /v1 即可") and what every
     * existing config already holds: the path suffix is appended to the base. A user who pasted
     * the *full* endpoint instead is not punished for it — each branch accepts its own complete
     * path — and a baseUrl that already ends in `/v1` gets `/messages` rather than `/v1/messages`
     * twice, which is the difference between a 200 and a 404 on Anthropic.
     */
    private fun endpoint(baseUrl: String, protocol: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) throw LlmException("请先在「设置」里填写接口地址")
        return when (protocol) {
            PROTOCOL_ANTHROPIC -> when {
                trimmed.endsWith("/messages") -> trimmed
                // Anthropic's path lives *under* /v1; a bare host or a custom prefix gets "/v1".
                trimmed.endsWith("/v1") -> "$trimmed/messages"
                else -> "$trimmed/v1/messages"
            }
            PROTOCOL_RESPONSES -> when {
                trimmed.endsWith("/responses") -> trimmed
                else -> "$trimmed/responses"
            }
            else -> if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
        }
    }

    /**
     * The auth headers for [protocol].
     *
     * Anthropic does not read `Authorization`; it wants `x-api-key` plus a version pin, and it
     * rejects a request that carries neither. The OpenAI branches are unchanged.
     */
    private fun authHeaders(builder: Request.Builder, settings: AppSettings, protocol: String) {
        val key = settings.plainApiKey()
        if (protocol == PROTOCOL_ANTHROPIC) {
            if (key.isNotBlank()) builder.header("x-api-key", key)
            builder.header("anthropic-version", ANTHROPIC_VERSION)
        } else {
            builder.header("Authorization", "Bearer $key")
        }
    }

    /**
     * 真要用明文 Key 的地方（这里是唯一的请求出口）才解密。
     *
     * 为什么不放在设置流的读取路径上：AndroidKeyStore 每次冷启动要 ~150ms，放在那里会连累
     * 设置的首个 emit ⇒ 会话列表迟迟不出（真机 A/B：冷启动 165ms → 340ms）。
     * 解不开（换机 / 密钥失效）时 [SecretBox.decrypt] 返回空串 ⇒ 当作「没填」。
     */
    private fun AppSettings.plainApiKey(): String = SecretBox.decrypt(apiKey)

    private fun modelsEndpoint(baseUrl: String, protocol: String = PROTOCOL_OPENAI): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) throw LlmException("请先在「设置」里填写接口地址")
        // Anthropic serves the list next to the messages path; the other two use /models.
        if (protocol == PROTOCOL_ANTHROPIC) {
            val root = when {
                trimmed.endsWith("/messages") -> trimmed.removeSuffix("/messages")
                trimmed.endsWith("/v1") -> trimmed
                else -> "$trimmed/v1"
            }
            return "$root/models"
        }
        return when {
            trimmed.endsWith("/models") -> trimmed
            trimmed.endsWith("/chat/completions") -> trimmed.removeSuffix("/chat/completions") + "/models"
            trimmed.endsWith("/responses") -> trimmed.removeSuffix("/responses") + "/models"
            else -> "$trimmed/models"
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** Anthropic requires `max_tokens`; this is what an unset budget becomes there. */
        internal const val DEFAULT_ANTHROPIC_MAX_TOKENS = 4096

        /** The wire version Anthropic pins every request to. */
        internal const val ANTHROPIC_VERSION = "2023-06-01"

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
        // Anthropic's spelling: the thinking share sits under output_tokens_details.
        "output_tokens_details.thinking_tokens",
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
    return trimmed.take(200).replace(WHITESPACE_RUN, " ")
}

/** 连续空白折叠：错误信息清洗用它，原来每次失败都现场编译一遍正则。 */
private val WHITESPACE_RUN = Regex("\\s+")

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
 * The string this element holds, or null when it holds nothing usable.
 *
 * Null-tolerant on purpose: Anthropic and Responses both send explicit `null`s (a
 * `stop_reason: null` on the first delta, an empty `content_block`), and `jsonPrimitive` on a
 * JsonNull throws — which would abort an answer that is otherwise arriving fine.
 */
private fun JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun JsonElement?.intOrNull(): Int? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.toIntOrNull()

/**
 * A tool's arguments as a JSON object, which is what Anthropic's `tool_use.input` must be.
 *
 * A stored call that is blank or is not an object falls back to `{}`: Anthropic rejects the
 * whole request over a malformed historical tool call, and sending an empty object is what a
 * no-argument tool means anyway.
 */
private fun String.toJsonObjectOrEmpty(): JsonObject =
    runCatching { Json.parseToJsonElement(ifBlank { "{}" }) as? JsonObject }.getOrNull()
        ?: JsonObject(emptyMap())

/**
 * Both non-OpenAI wires name a token-limit stop differently from OpenAI, and the engine
 * branches on the OpenAI spelling: `length` is what draws the "跑满了" note. Mapping here
 * keeps that comparison in one place instead of teaching the engine three vocabularies.
 */
private fun String.toFinishReason(): String = when (this) {
    "max_tokens", "max_output_tokens" -> "length"
    // Anthropic names the two ordinary endings differently; `end_turn` is exactly OpenAI's
    // `stop`, and `tool_use` is `tool_calls`. Neither affects the engine's tool dispatch (it
    // runs off `turn.toolCalls`), but the transcript note and the "跑满了" branch read the name.
    "end_turn" -> "stop"
    "tool_use" -> "tool_calls"
    else -> this
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
internal fun reasoningOf(
    chunk: JsonObject,
    choice: JsonObject,
    delta: JsonObject?,
    message: JsonObject?,
): String? {
    // **不建 list**：这段每个 delta 都会跑（思考流式），原来每次分配一个 8 元素列表。
    // 直接按优先级顺序早返回，零分配。
    fun of(source: JsonObject?, key: String): String? =
        source?.get(key).textOf()?.takeIf { it.isNotBlank() }

    of(delta, "reasoning_content")?.let { return it }
    of(delta, "reasoning")?.let { return it }
    of(message, "reasoning_content")?.let { return it }
    of(message, "reasoning")?.let { return it }
    of(choice, "reasoning_content")?.let { return it }
    of(choice, "reasoning")?.let { return it }
    of(chunk, "reasoning_content")?.let { return it }
    return of(chunk, "reasoning")
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
