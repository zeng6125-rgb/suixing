package com.suixing.agent.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "Suixing_settings")

/** How the agent is allowed to act on the phone. */
enum class ApprovalMode {
    /** Run every tool without asking. */
    AUTO,

    /** Ask the user before each tool call. */
    ASK,

    /** Refuse every tool call; the model can only talk. */
    DENY,
}

/**
 * The three modes as the user sees them — one source of truth for the settings page and for the
 * composer's quick picker. Two screens naming the same state differently is how a user ends up
 * believing they are two settings; the wording here is the user's own.
 */
val ApprovalMode.label: String
    get() = when (this) {
        ApprovalMode.AUTO -> "自动运行"
        ApprovalMode.ASK -> "每次询问"
        ApprovalMode.DENY -> "禁止"
    }

/**
 * One OpenAI-compatible endpoint the user set up.
 *
 * Aggregating proxies (one LAN address that fronts many upstream vendors) report
 * model ids like `deepseek/deepseek-chat` or `openai/gpt-4o`, which is why the
 * picker groups by [modelGroupOf].
 */
/**
 * 全局默认上下文窗口：**上游没给出这个模型的窗口时**才用它（用户：「全局的模型上下文窗口设置为
 * 256k，也就是说上游没有给出模型上下文窗口时才使用此值」）。
 *
 * 取值理由：256k 是当前主流长上下文模型的下界附近，压缩只在估算超过它的 80% 时触发，所以短会话
 * 完全不受影响；而它又足以让压缩阈值在超长会话里真正生效（0 会让阈值失效、永远不压）。
 */
const val DEFAULT_CONTEXT_WINDOW = 256_000

/**
 * The three wires an endpoint may speak. [PROTOCOL_OPENAI] is the empty string so that a
 * settings file written before the field existed — which deserialises to "" — keeps taking
 * the path it always took, and so that `protocol` being blank is never ambiguous.
 */
const val PROTOCOL_OPENAI = ""
const val PROTOCOL_ANTHROPIC = "anthropic"
const val PROTOCOL_RESPONSES = "openai-responses"

@Serializable
data class ProviderConfig(    val id: String,
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    /** Everything `/models` reported for this endpoint. */
    val models: List<String> = emptyList(),
    /** The subset the user ticked. Only these appear in the model switcher. */
    val enabled: List<String> = emptyList(),
    /** Per-endpoint sampling overrides; null inherits the global knob. */
    val temperature: Double? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val reasoningEffort: String? = null,
    /** When false the four overrides above are ignored and the globals apply. */
    val ownSampling: Boolean = false,
    /** Display names for model ids; the wire id sent to the API never changes. */
    val aliases: Map<String, String> = emptyMap(),
    /**
     * Which wire this endpoint speaks: [PROTOCOL_OPENAI] (blank, the default that every
     * settings file written before this field existed means), [PROTOCOL_ANTHROPIC] or
     * [PROTOCOL_RESPONSES].
     *
     * Blank rather than "openai" on purpose: an old file deserialises to "" and keeps
     * behaving exactly as it did, with no migration step to get wrong.
     */
    val protocol: String = "",
) {
    fun displayModel(id: String): String = aliases[id]?.takeIf { it.isNotBlank() } ?: id
    val label: String get() = name.ifBlank { baseUrl.ifBlank { "未命名" } }

    /** Host-only label, short enough for a chip: `192.168.0.50:8914`. */
    val shortLabel: String get() = name.ifBlank {
        baseUrl.substringAfter("://").substringBefore('/').ifBlank { "未命名" }
    }
}

/**
 * Which bucket a model id belongs to. An aggregating proxy prefixes ids with the
 * upstream vendor (`deepseek/deepseek-chat`), so the prefix is the provider. Bare
 * ids have no owner and land in 其他.
 */
fun modelGroupOf(modelId: String): String =
    modelId.substringBefore('/', "").trim().ifBlank { "其他" }

/** Groups ids by [modelGroupOf], keeping the original order of both levels. */
fun groupModels(ids: List<String>): List<Pair<String, List<String>>> =
    ids.groupBy(::modelGroupOf).toList()

/**
 * What the composer's model picker offers: every endpoint with ticked models, in the
 * order the user set them up, each with its own list. The model in use is always
 * present — a provider list that lost it must still be able to re-pick it.
 */
fun modelOffers(
    providers: List<ProviderConfig>,
    model: String,
    active: ProviderConfig?,
): List<Pair<ProviderConfig, List<String>>> {
    val list = providers
        .filter { it.enabled.isNotEmpty() }
        .map { it to it.enabled }
        .toMutableList()
    if (model.isNotBlank() && list.none { (_, ids) -> model in ids }) {
        // A stale reference (an endpoint that is no longer in the list) must not invent a
        // group of its own: the first endpoint is the one that can actually be switched to.
        val owner = active?.takeIf { a -> providers.any { it.id == a.id } } ?: providers.firstOrNull()
        val at = list.indexOfFirst { (p, _) -> p.id == owner?.id }
        if (at >= 0) {
            list[at] = list[at].first to (list[at].second + model).distinct()
        } else if (owner != null) {
            list += owner to listOf(model)
        }
    }
    return list
}

@Serializable
data class AppSettings(
    val baseUrl: String = "https://api.deepseek.com/v1",
    val apiKey: String = "",
    /**
     * 模型名**没有出厂默认**（用户：「不需要出厂默认模型，模型只由用户设置」）。
     *
     * 空串＝还没选模型：此时 `configured` 为 false，应用不会拿空模型名去发请求。
     * 引导页与设置页都会让用户自己挑（引导页的供应商预设已按各家官方文档填好现名）。
     *
     * 老配置里存过的模型名照旧读得出来（这个字段一直有值），所以改默认不影响老用户。
     */
    val model: String = "",
    val temperature: Double = 0.6,
    /**
     * **是否把 `temperature` 发进请求**（默认 false = 不发）。
     *
     * 用户 2026-10-23：「感觉默认不加了吧，可以在高级调节进入有开关供打开」。
     *
     * 为什么默认不发：现在的模型大多带思考/推理档，其中一批（OpenAI 推理系等）
     * **直接拒收** `temperature`（400），另一批忽略它。而 agent 场景本身也不需要
     * 采样多样性 —— 工具调用要的是确定性。所以默认「不说」，需要时在高级参数里打开。
     *
     * 注意：值本身保留（滑杆仍然可调），只是默认不发送。
     */
    val sendTemperature: Boolean = false,
    /**
     * 是否把 `top_p` 发进请求（默认 false = 不发）。
     *
     * 用户 2026-10-23：「只保留滑块就行了，不用独立两个不发的开关，俩滑块左侧或者右侧
     * 就是不发的档次」—— 所以**没有 UI 开关**：滑杆最左那一档就是「不发送」，
     * 这两个布尔只记录「用户把滑杆停在哪一档」，不暴露成控件。
     */
    val sendTopP: Boolean = false,
    /**
     * Sampling knobs. `maxTokens = 0` and `topP = 1.0` mean "say nothing about it",
     * which is what most endpoints want; a value only goes on the wire when the user
     * actually moved the slider.
     */
    val maxTokens: Int = 0,
    val topP: Float = 1f,
    /**
     * How hard a reasoning model should think: `none` / `low` / `medium` / `high`, or
     * empty to leave it to the server. `none` is sent as-is so a model that supports
     * turning thinking off can be told to; endpoints that do not know the field
     * ignore it either way.
     */
    val reasoningEffort: String = "",
    /**
     * The wire the resolved endpoint speaks — see [ProviderConfig.protocol], which is where
     * the user sets it. It lives here too because [forAssistant] and the chat's per-conversation
     * pick both resolve to a plain [AppSettings] before the client ever sees a request.
     */
    val protocol: String = "",
    /**
     * Per-model context window in tokens, keyed by model name. 0 (or absent) sends
     * everything; anything else drops the oldest turns until the estimate fits.
     * Whatever the model actually supports is its own business — this is only a
     * ceiling the user sets, per model, because windows differ wildly between them.
     */
    val modelContexts: Map<String, Int> = emptyMap(),
    /**
     * 最大输出 tokens **按模型**（同 [modelContexts] 的模式）：0/缺席＝不说、模型默认。
     * 全局 [maxTokens] 与 provider 级已退役，仅老文件回退（[withMaxTokensMigration] 搬值）。
     */
    val modelMaxTokens: Map<String, Int> = emptyMap(),
    /**
     * 自定义请求头（用户：「模型与生成要加入请求头的设置，加到高级参数」）：`Name: Value`
     * 一行一条，随每个请求发出（覆盖同名默认头由 OkHttp 的最后写入决定，故在默认头之后加）。
     */
    val requestHeaders: Map<String, String> = emptyMap(),
    /**
     * Tool-calling rounds allowed in one turn. 0 means no limit, which is the
     * default: a long task should not be cut off at an arbitrary number.
     */
    val maxToolRounds: Int = 0,
    /**
     * 一轮里**最多几条工具调用并发跑**。1＝串行（今天的默认行为，也是唯一零风险的值）；
     * N>1 时一轮内最多 N 条同时执行，结果仍按模型请求的顺序回写 wire（端点对 tool 消息顺序敏感）。
     *
     * 两个刻意的限制：① **只在 AUTO 审批档生效** —— ASK 档的授权弹窗每个会话只有一个待决位
     * （ApprovalGate 的 waiters 按 chatId 单槽），两条调用同时求授权会互相覆盖、死等；② shell 类
     * 工具并发可能互相踩（同时写一个文件），所以默认保持 1，由用户按需开。
     */
    val toolParallel: Int = 1,
    /**
     * 插话落在哪里。0 排队：等这一轮模型说完，在下一轮请求前注入，模型把它当成
     * 「用户在我干活时说的」；1 立即：掐断在途的流，让模型马上看到这句话——代价是
     * 被打断的那一轮输出作废。默认排队，因为打断是有损的。
     */
    val steerMode: Int = 0,
    /**
     * 上下文接近窗口上限时自动把旧消息压成摘要。
     *
     * 默认开：超窗是硬失败（接口直接 400），而压缩最坏只是损失一些细节，两害相权
     * 取其轻。只在当前模型设了窗口（[modelContexts]）时才可能触发。
     */
    val autoCompress: Boolean = true,
    /**
     * 失败后自动重试一轮。
     *
     * 默认开：它覆盖的失败——连接被掐、超时、网关抽风、被限流——本来就是偶发的，而另一种做法
     * 是让用户自己再按一次发送。但这不是「一律重试」：具体规则在 `LlmClient.shouldRetry`，
     * 对端点给出的真实错误（认证、参数）、用户主动停止、以及**任何已经吐出过文字的尝试**都
     * 不重试——最后这条最关键，重发会把同一段回答给用户看两遍，还多计一次费。
     */
    val retryOnFailure: Boolean = true,
    /**
     * 一轮失败后最多再试几次（0 = 不重试）。总尝试次数 = 这个数 + 最初的第一次。
     */
    val retryMaxAttempts: Int = 2,
    /**
     * 两次尝试之间等多少秒（服务端没明说时）。
     *
     * 服务端给了 `Retry-After` 就以它为准：什么时候准备好，端点自己最清楚。读取处统一
     * 夹到 1–60 秒。
     */
    val retryDelaySeconds: Int = 5,
    val approvalMode: ApprovalMode = ApprovalMode.ASK,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    /**
     * 0 = the hand-tuned 随行 palette, 1 = follow the system wallpaper (Material You).
     * The brand palette is the default because the whole UI is built around it.
     */
    val paletteMode: Int = 0,
    val themeMode: Int = 0,
    /**
     * Off by default because most endpoints are text-only and reject any request
     * carrying an image. Turn it on for vision models.
     */
    val imageInput: Boolean = false,
    /**
     * Accept any TLS certificate, including self-signed ones.
     *
     * Needed for the common setup of a reverse proxy on the LAN (`https://192.168.x.x:port/v1`)
     * whose certificate is not signed by a public CA. Android refuses those with
     * `CertPathValidatorException: Trust anchor for certification path not found`.
     * Turning this on disables certificate and hostname verification for model
     * requests only, so it is a deliberate trade-off, never a default.
     */
    val allowInsecureTls: Boolean = false,
    /**
     * Optional Tavily-compatible search endpoint. Empty means the built-in
     * DuckDuckGo fallback, which needs no key but is easier to rate-limit.
     */
    val searchEndpoint: String = "",
    val searchApiKey: String = "",
    /** Read every finished reply out loud through the system TTS engine. */
    val speakReplies: Boolean = false,
    /**
     * 语音输入：随行自己录音 + 走已配置的服务商转写。
     *
     * **默认关**，关掉时输入框那排里连麦克风按钮都不画。开着才用自己的录音链路 —— 它完全不碰
     * `SpeechRecognizer`，所以不会再把系统/ROM 的识别面板弹到屏幕上（那个面板被用户否了两次）。
     */
    val voiceInput: Boolean = false,
    /**
     * 语音转写模型，例如 `whisper-1`。
     *
     * 空＝没配：开关可以打开，但点麦克风不会开始录音，只会提示去设置里填。填了才用
     * [baseUrl] / [apiKey] 那对服务商凭据 POST [asrPath]。
     */
    val asrModel: String = "",
    /** 转写接口的路径，拼在 [baseUrl] 后面；换服务商时这里通常不用动。 */
    val asrPath: String = "/audio/transcriptions",
    /** Keep the thinking block on the transcript once the answer is there. */
    val showReasoning: Boolean = true,
    /**
     * 浮层提示条多少秒后自己消失；**0 ＝ 不自动消失**（只能手点 ×）。
     *
     * 只影响浮层那种全局提示：回答卡片里的原因（中断/截断）跟着卡片走，不归这个开关管。
     */
    val bannerSeconds: Int = 10,
    /**
     * Every endpoint the user set up. [baseUrl] / [apiKey] / [model] above stay the
     * *active* triple, so switching models is a single copy() and nothing else in
     * the app has to know that providers exist.
     */
    val providers: List<ProviderConfig> = emptyList(),
    /** The personas the user built. Never empty once [migrated] has run. */
    val assistants: List<Assistant> = emptyList(),
    val activeAssistantId: String = "",
    /**
     * Result of the last root probe. `su` is cheap but not free (the superuser app
     * shows a toast, and on some ROMs a dialog), so the answer is written down and
     * reused on the next launch instead of being thrown away with the process.
     * [rootCheckedAt] = 0 means "never probed on this install".
     */
    val rootAvailable: Boolean = false,
    val rootCheckedAt: Long = 0L,
    /**
     * Tools the user answered 「总是允许」 for. Persisted because an approval that
     * evaporates on restart is not a memory, it is a nuisance.
     */
    val alwaysAllowedTools: List<String> = emptyList(),
    /**
     * Tools the user answered 「总是拒绝」 for. The mirror of [alwaysAllowedTools]:
     * the same reasoning applies, and a refusal that has to be re-tapped every
     * launch is the more annoying of the two.
     */
    val alwaysDeniedTools: List<String> = emptyList(),
    /** 聊天：列表在底部时自动跟随新消息。 */
    val autoFollow: Boolean = true,
    /**
     * 悬浮窗控制台：抽屉那行的开关，打开＝常驻——只要随行不在前台，不管是空闲还是正在跑，球都在。
     *
     * 前台一律不显示（聊天页已经有同一套停止与输入控件），所以这个开关只在离开随行之后生效。
     */
    val floatingConsole: Boolean = false,
    /**
     * 悬浮窗：不在前台时，只有正在跑任务才显示悬浮球，任务一结束就自动收起。
     *
     * 与 [floatingConsole] 独立：那一个是「常驻」，这个只管「有没有任务在跑」。仅当常驻关掉时
     * 它才有话说；两个都关，后台就什么都不显示。前台同样一律不显示。
     */
    val consoleWhileRunning: Boolean = true,
    /** 悬浮窗展开面板的宽度，dp；0 表示用默认宽度。右下角的拖拽手柄写这里。 */
    val consoleWidthDp: Int = 0,
    /** 悬浮窗展开面板的高度，dp；0 表示随内容自适应（默认）。手柄向上拖写这里。 */
    val consoleHeightDp: Int = 0,
    /** 0 小, 1 标准, 2 大, 3 特大 */
    val fontScale: Int = 1,
    /** 0 窄, 1 标准, 2 宽 —— 助手气泡最多占屏宽的比例，由聊天页读取 */
    val bubbleWidth: Int = 1,
    /** 0 方正, 1 标准, 2 圆润 */
    val cornerScale: Int = 1,
    /** 是否在气泡下方显示时间 */
    val showTimestamps: Boolean = true,
    /**
     * 边缘光特效：agent 在操作这台手机时，屏幕四周那一圈彩虹光（见 `service/TouchFx.kt`）。
     *
     * 默认开。关掉之后只是不再显示 —— 驱动判定、自动返回、`fx-trace.log` 全都照旧，
     * 所以关掉不等于停掉自动化。带默认值放在类中间：老 JSON 没有这个键时读出来就是 true。
     */
    val edgeLight: Boolean = true,
    /**
     * 边缘光**在前台是否显示**（用户 2026-10-23：「加一个前台是否显示的开关，不独立，
     * 只在边缘光开启后才起作用」）。
     *
     * - 开（默认）：随行自己在前台时也显示 —— 这是 issue #6 定下的行为，保持兼容。
     * - 关：随行在前台时**不显示**；退到后台操作别的 app 时照常显示。
     *
     * **不独立**：`edgeLight` 为 false 时这一项无效（一律不显示）。
     */
    val edgeLightInForeground: Boolean = true,
    /**
     * 打字机效果：**正在流式**的正文与思考逐字放出来，而不是每 80ms 整块跳一次
     * （节拍见 ChatViewModel 的 `DELTA_PUBLISH_MS`、放字逻辑见 ui/chat/Typewriter.kt）。
     *
     * 只影响显示：已经结束的一轮、以及落盘的消息，都是一次性给全文。默认开——关掉时
     * 文本一收到就整块出现。放在类中间并带默认值，老 JSON 没有这个键时读出来就是 true。
     */
    val typewriter: Boolean = true,
    /**
     * 首次启动引导是否已看过（**走完或跳过都算看过**）。
     *
     * 引导页是可选的：没配 Key 也能先逛逛，聊天会等用户配好接口。所以这个标记只管
     * 「要不要在下次冷启动时再弹一次引导」，不管能不能用。
     * 设置页有「重新看一遍引导」，随时能再看。
     */
    val onboardingSeen: Boolean = false,
    /** 工具调用卡片默认展开 */
    val expandTools: Boolean = false,
    /**
     * 历史思考块默认展开。
     *
     * 正在流式的那一块**总是**展开（看它怎么想是调用思考模型的目的），这个开关管的是**已经结束
     * 的**思考块：开＝默认展开，关＝折叠成一行标题，用户点标题仍可逐块展开（按会话记住）。默认
     * false，即关掉时对话流保持「一段回复 + 一行思考标题」的密度。
     *
     * 字段名与默认值保持不变：老配置里的键照旧读得出来，变的只是它影响的显示行为。
     */
    val expandReasoning: Boolean = false,
    /**
     * 展开的思考块内滚高度上限（dp）：180 矮 / 240 中 / 320 高。
     *
     * A chain of thought is context, not the answer: at 320dp it pushed the reply off the
     * screen, so it opens at 180 and the user decides in 外观 how much room it may take.
     */
    val reasoningHeight: Int = 180,
    /**
     * 用户在「能力」页自选的首选执行档，取值是 tools/Tool.kt 里那套档位名
     * （[com.suixing.agent.tools.TIER_NONE] / `accessibility` / `shell` / `root`），
     * 空串 = 自动（按当前已开启的最高档）。
     *
     * 只是「首选」：该档不可用时执行链回落到原来的自动判定，所以一个过期或还没开出来
     * 的选择不会把操作卡死。空串也是老设置的语义——老 JSON 没有这个键时，自动就是它
     * 原本的行为，所以 [migrated] 不需要动它。
     */
    val preferredCapability: String = "",
    /**
     * 自动压缩的触发阈值：上下文估算占到窗口的百分之几时就压缩。
     *
     * 放在类末尾并有默认值，所以老 JSON 里没有这个键时读出来就是 80——也就是它
     * 从前的固定值，行为不变。真正生效的是 [compressAtFraction]，越界的值在那里兜底。
     */
    val compressAtPercent: Int = DEFAULT_COMPRESS_AT_PERCENT,
    /**
     * 让模型定期从对话里自己总结用户画像（`filesDir/profile.md`）。
     *
     * 与记忆是两回事：记忆是模型在任务里主动记下的键值（`memory.json`），这一项管的是它
     * 隔几轮读一遍最近的对话、写出一份「关于用户这个人」的滚动摘要。默认开——画像的价值
     * 就在于用户不用自己填。
     */
    val autoProfile: Boolean = true,
    /**
     * 每多少轮对话总结一次画像，见 [profileTurnThreshold]。
     *
     * 放在类末尾并有默认值，所以老 JSON 里没有这个键时读出来就是 8——也就是它本来该有的
     * 行为，升级上来的人不会因为缺键就每轮都总结一次。
     */
    val profileEveryTurns: Int = DEFAULT_PROFILE_EVERY_TURNS,
    /**
     * 思考块（展开的思考过程）在做「跟随最新」：流式增长时内滚自动到底，
     * 用户一上手就停，滚回底部自动恢复跟随。
     *
     * 放在类末尾并有默认值，所以老 JSON 里没有这个键时读出来就是 true——也就是它
     * 本来的行为（在设置项出现之前，思考块一直在追尾）。
     */
    val followReasoning: Boolean = true,
    /**
     * 输入框里按回车是直接发送，还是换行。
     *
     * 放在类末尾并有默认值，所以老 JSON 里没有这个键时读出来就是 true——也就是它本来的行为
     * （回车一直是发送，见 ChatScreen 的 `ImeAction.Send`），升级上来的人不会因为缺键就把
     * 回车变成换行。false 时回车只换行，提交交给发送键。
     */
    val enterKeySends: Boolean = true,
) {
    val configured: Boolean get() = apiKey.isNotBlank() && model.isNotBlank() && baseUrl.isNotBlank()

    /** 助手气泡最多占屏宽的比例。 */
    val bubbleWidthFraction: Float
        get() = when (bubbleWidth) {
            0 -> 0.82f
            2 -> 1f
            else -> 0.94f
        }

    /** The persona new conversations start with; falls back to the first one. */
    fun activeAssistant(): Assistant? =
        assistants.firstOrNull { it.id == activeAssistantId } ?: assistants.firstOrNull()

    fun assistant(id: String): Assistant? = assistants.firstOrNull { it.id == id }

    /** The provider the active triple points at, matched on address. */
    fun activeProvider(): ProviderConfig? =
        providers.firstOrNull { it.baseUrl.trim().trimEnd('/') == baseUrl.trim().trimEnd('/') }

    /** Every (provider, model) pair the user ticked anywhere, for the switcher. */
    fun enabledModels(): List<Pair<ProviderConfig, String>> =
        providers.flatMap { provider -> provider.enabled.map { provider to it } }

    /** The active model with its provider prefix stripped, for display. */
    val shortModel: String get() = model.substringAfter('/')

    /**
     * The context ceiling for [model].
     *
     * 顺序：用户为这个模型设的值 → 上游 `/models` 报过并落进 [modelContexts] 的值 → 全局默认
     * [DEFAULT_CONTEXT_WINDOW]。用户要求：「全局的模型上下文窗口设置为 256k，也就是说上游没有
     * 给出模型上下文窗口时才使用此值」。所以它**不再是 0**：0 会让压缩阈值失效，而 256k 是一个
     * 保守的上界 —— 压缩只在估算超过 80% 时才触发，短会话完全不受影响。
     */
    fun contextWindowFor(model: String): Int =
        modelContexts[model]?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW

    /**
     * 最大输出 tokens **按模型**（用户：「取消全局性的最大输出，改用各个模型默认的，然后可以
     * 自定义，跟上下文窗口一样可设置」）。0 或缺席＝不说、由模型/端点默认决定。全局与
     * provider 级字段已退役（[migrated] 把旧值搬进这张表），只作老文件的回退。
     */
    fun maxTokensFor(model: String): Int = modelMaxTokens[model] ?: 0

    /**
     * 实际使用的压缩阈值，0.20–0.95。
     *
     * 只有这个范围里的值算数：老文件、手改过的 JSON 或将来改窄了范围，都会落到默认的
     * 80%，而不是按 0% 或 300% 去压。
     */
    val compressAtFraction: Float
        get() = (compressAtPercent.takeIf { it in COMPRESS_MIN_PERCENT..COMPRESS_MAX_PERCENT }
            ?: DEFAULT_COMPRESS_AT_PERCENT) / 100f

    /**
     * 实际使用的画像总结间隔，[PROFILE_MIN_TURNS]–[PROFILE_MAX_TURNS] 轮。
     *
     * 和 [compressAtFraction] 同样的道理：只有这个范围里的值算数，老文件、手改过的 JSON 或
     * 将来改窄了范围，都落到默认的 8 轮，而不是按 0 轮去每轮总结一次。
     */
    val profileTurnThreshold: Int
        get() = profileEveryTurns.takeIf { it in PROFILE_MIN_TURNS..PROFILE_MAX_TURNS }
            ?: DEFAULT_PROFILE_EVERY_TURNS
}

/** 压缩阈值的取值边界、步长与默认值：设置页和引擎共用一套，免得两边各写一份。 */
const val COMPRESS_MIN_PERCENT = 20
const val COMPRESS_MAX_PERCENT = 95
const val COMPRESS_STEP_PERCENT = 5
const val DEFAULT_COMPRESS_AT_PERCENT = 80

/** 画像总结间隔的取值边界、步长与默认值，同样是设置页与引擎共用的一套。 */
const val PROFILE_MIN_TURNS = 2
const val PROFILE_MAX_TURNS = 40
const val PROFILE_STEP_TURNS = 2
const val DEFAULT_PROFILE_EVERY_TURNS = 8

/**
 * The values [AppSettings.steerMode] holds: what a line typed while a run is going does.
 *
 * Public because the decision is made in two places that must agree — the engine (where the line
 * would land in the wire) and the chat screen (where it decides whether to queue a new turn
 * instead). A literal `1` in either one is how they drift apart.
 */
const val STEER_QUEUE = 0
const val STEER_IMMEDIATE = 1

const val DEFAULT_SYSTEM_PROMPT = """你是随行，运行在用户安卓手机上的智能体。

你可以通过工具真实地操作这台手机：读取与写入文件、执行 shell 命令、查看屏幕、点击与滑动、打开应用。

工作方式：
- 先想清楚要做什么，再调用工具，不要凭猜测描述手机状态。
- 每一步只做必要的操作，拿到结果后再决定下一步。
- 需要外部信息时用 web_fetch 或 web_search 查，不要凭记忆编造事实与链接。
- 涉及删除、发送、支付、卸载等不可逆操作时，先向用户说明再执行。
- 用简洁的中文回答，必要时用短列表。"""

class SettingsRepository(private val context: Context) {
    /**
     * `coerceInputValues` is what keeps one unknown value from costing the user everything:
     * an enum this build does not know (a file written by a newer version, or a hand-edited
     * one) would otherwise throw, and [decode] answers a throw by resetting *every* setting
     * to its default. Coerced, the single bad field falls back to its own default and the
     * rest of the file survives.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }
    private val key = stringPreferencesKey("settings_json")

    val flow: Flow<AppSettings> = context.settingsDataStore.data.map { prefs -> decode(prefs[key]) }

    suspend fun current(): AppSettings = flow.first()

    suspend fun save(settings: AppSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(AppSettings.serializer(), settings.encrypted())
        }
    }

    suspend fun update(block: (AppSettings) -> AppSettings) {
        // The read has to happen inside edit{}: DataStore runs these blocks one at a
        // time, so two concurrent updates (a slider drag and a run writing its own
        // field) can no longer both start from the same snapshot and have the later
        // one drop the earlier one's change.
        context.settingsDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(
                AppSettings.serializer(),
                block(decode(prefs[key])).encrypted(),
            )
        }
    }

    /**
     * One decoder for [flow] and [update] both: what counts as a valid settings file,
     * and how it migrates, must not depend on which path happened to read it.
     */
    private fun decode(raw: String?): AppSettings {
        val parsed = if (raw.isNullOrBlank()) {
            AppSettings()
        } else {
            runCatching { json.decodeFromString<AppSettings>(raw) }.getOrElse { AppSettings() }
        }
        // **这里不解密**（2026-10-21 起）：AndroidKeyStore 每次冷启动要 ~150ms（provider 每次
        // 进程重载），解密放在读路径上会连累**设置的首个 emit** ⇒ 会话列表迟迟不出
        // （真机 A/B：冷启动 165ms → 340ms）。落盘里存的是密文，读出来也保持密文；
        // 真要用明文的地方自己调 [SecretBox.decrypt]（发请求见 llm/LlmClient.kt，
        // 显示 Key 见设置页与引导页）。
        //
        // 迁移把顶层的 Key 复制进第一个 provider：两个字段都是密文，复制过去是对的。
        return parsed.migrated()
    }

    /**
     * 一次性把老文件里的**明文 Key** 换成密文（顺带把明文从文件里抹掉）。
     *
     * 不做这一步的话：老用户的 `settings_json` 里一直躺着明文 Key，直到他碰巧改一次设置
     * 才会被重新加密。启动时跑一遍就干净了。
     */
    suspend fun migrateSecrets() {
        // **先看有没有明文要迁**，没有就整段不做（用户：「之前会话好像是秒加载的呀」）。
        // 原来每次启动都走一遍 `edit`：① 要解密/加密 ⇒ 碰 Keystore（每次进程首次 ~150ms）；
        // ② `edit` 会**写**一次 DataStore ⇒ 设置流多发一次 ⇒ 聊天页整体重组一次
        // （每条消息的 Markdown 重新渲染）。老用户早就迁完了，却天天付这两笔。
        val raw = context.settingsDataStore.data.first()[key] ?: return
        if (!hasPlainSecret(raw)) return
        context.settingsDataStore.edit { prefs ->
            val current = prefs[key] ?: return@edit
            val encrypted = json.encodeToString(
                AppSettings.serializer(),
                decode(current).encrypted(),
            )
            if (encrypted != current) prefs[key] = encrypted
        }
    }
}

/**
 * 这个文件里还有没有**明文**的 Key（没迁过的老文件）。
 *
 * 只看字符串，不解密、不碰 Keystore：迁移是一次性的准备工作，不该在每次冷启动的路径上。
 * 判定标准与 [SecretBox.isEncrypted] 一致：`apiKey` 的值非空且不以 `enc1:` 开头。
 */
private fun hasPlainSecret(raw: String): Boolean =
    Regex("""apiKey"\s*:\s*"([^"]*)""").findAll(raw)
        .any { m -> m.groupValues[1].isNotEmpty() && !m.groupValues[1].startsWith("enc1:") }

/**
 * 落盘前：把 Key 换成密文。**内存里始终是明文**，只有这一层是密文。
 *
 * 两处都要盖：顶层 [AppSettings.apiKey]（老格式迁移过来的）与每个 [ProviderConfig.apiKey]。
 */
private fun AppSettings.encrypted(): AppSettings = copy(
    apiKey = SecretBox.encrypt(apiKey),
    providers = providers.map { it.copy(apiKey = SecretBox.encrypt(it.apiKey)) },
)

/**
 * 读回后：把密文换回明文。解不开（换机 / 密钥失效）⇒ 当成「没填」，让用户重填。
 *
 * **不在设置流的读取路径上调用**（见 [SettingsRepository.decode] 的说明）：Keystore 每次冷
 * 启动要 ~150ms，放在读路径上会拖住整个首屏。要用明文的地方自己调 [SecretBox.decrypt]。
 */
internal fun AppSettings.decrypted(): AppSettings = copy(
    apiKey = SecretBox.decrypt(apiKey),
    providers = providers.map { it.copy(apiKey = SecretBox.decrypt(it.apiKey)) },
)

/**
 * Settings written before providers existed only have the flat `baseUrl` /
 * `apiKey` / `model` triple. Wrap that in a single provider so the picker and the
 * switcher have something to show without the user re-entering anything.
 */
private fun AppSettings.migrated(): AppSettings =
    withProviders().withAssistants().withMaxTokensMigration()

/**
 * 老文件的 provider 级最大输出搬进按模型的表（一次性）：provider 填过数、而它名下的模型在
 * [modelMaxTokens] 里还没有值时，把那个数写给这些模型 —— 用户设过的不丢；已经按模型设过的
 * 键不被覆盖（自定义赢过迁移）。provider 级字段本身保留作回退，不再有新写入。
 */
private fun AppSettings.withMaxTokensMigration(): AppSettings {
    val moved = providers.fold(modelMaxTokens) { acc, provider ->
        val value = provider.maxTokens ?: return@fold acc
        if (value <= 0) return@fold acc
        provider.enabled.fold(acc) { inner, model ->
            if (model.isBlank() || model in inner) inner else inner + (model to value)
        }
    }
    return if (moved === modelMaxTokens) this else copy(modelMaxTokens = moved)
}

private fun AppSettings.withProviders(): AppSettings {
    if (providers.isNotEmpty() || baseUrl.isBlank()) return this
    val seeded = ProviderConfig(
        id = "default",
        name = "默认",
        baseUrl = baseUrl,
        apiKey = apiKey,
        models = listOf(model).filter { it.isNotBlank() },
        enabled = listOf(model).filter { it.isNotBlank() },
    )
    return copy(providers = listOf(seeded))
}

/**
 * Settings written before assistants existed carry a single global prompt. Wrap it
 * in the house persona so the picker has a card to show and the chat header has a
 * face, without the user re-typing anything.
 */
private fun AppSettings.withAssistants(): AppSettings {
    if (assistants.isEmpty()) {
        val seeded = defaultAssistant(systemPrompt)
        return copy(assistants = listOf(seeded), activeAssistantId = seeded.id)
    }
    if (assistants.any { it.id == activeAssistantId }) return this
    return copy(activeAssistantId = assistants.first().id)
}

/**
 * [this] with the persona's overrides applied. Blank fields inherit, so an
 * assistant that only sets a name changes nothing about how the agent runs.
 */
fun AppSettings.forAssistant(assistant: Assistant?): AppSettings {
    if (assistant == null) return this
    val provider = providers.firstOrNull { it.id == assistant.providerId }
    val own = provider?.takeIf { it.ownSampling }
    return copy(
        systemPrompt = assistant.systemPrompt.ifBlank { systemPrompt },
        model = assistant.model.ifBlank { model },
        baseUrl = provider?.baseUrl?.ifBlank { null } ?: baseUrl,
        apiKey = provider?.apiKey?.ifBlank { null } ?: apiKey,
        // The wire, not a sampling knob: it follows the provider unconditionally, the same way
        // the address and the key do. A persona pointing at an Anthropic endpoint must not be
        // sent OpenAI-shaped JSON because the provider did not tick 独立采样参数.
        protocol = provider?.protocol?.ifBlank { null } ?: protocol,
        temperature = assistant.temperature ?: own?.temperature ?: temperature,
        topP = own?.topP ?: topP,
        // 最大输出**按模型**（用户：「取消全局性的，改用各模型默认＋可自定义，跟上下文窗口一样」）：
        // 本模型填了数就用它；没填才回退 provider 级/全局（老文件的值，迁移后通常为空/0）。
        maxTokens = maxTokensFor(model).takeIf { it > 0 } ?: provider?.maxTokens ?: maxTokens,
        reasoningEffort = own?.reasoningEffort ?: reasoningEffort,
    )
}

/** Where one turn goes: the endpoint and the model id, already resolved. */
data class LlmChoice(
    val providerId: String?,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /**
     * The wire this pick speaks. Last and defaulted so every existing positional
     * `LlmChoice(...)` keeps compiling and meaning exactly what it meant.
     */
    val protocol: String = "",
)

/**
 * The three places a model can be chosen, in the order that wins: the conversation's own
 * pick first, then the assistant's pin, and only then the global default. A conversation
 * that never picked anything therefore starts on the default, and a pick made in one
 * conversation cannot move another — which is what the picker shows, too.
 */
fun AppSettings.choiceFor(
    conversationProviderId: String?,
    conversationModel: String?,
    assistant: Assistant?,
): LlmChoice {
    val own = conversationProviderId?.let { id -> providers.firstOrNull { it.id == id } }
    if (own != null && !conversationModel.isNullOrBlank()) {
        return LlmChoice(own.id, own.baseUrl, own.apiKey, conversationModel, own.protocol)
    }
    val pinned = assistant?.providerId?.takeIf { it.isNotBlank() }
        ?.let { id -> providers.firstOrNull { it.id == id } }
    val pinnedModel = assistant?.model?.takeIf { it.isNotBlank() }
    if (pinned != null) {
        return LlmChoice(pinned.id, pinned.baseUrl, pinned.apiKey, pinnedModel ?: model, pinned.protocol)
    }
    if (pinnedModel != null) {
        return LlmChoice(activeProvider()?.id, baseUrl, apiKey, pinnedModel, activeProvider()?.protocol.orEmpty())
    }
    return LlmChoice(activeProvider()?.id, baseUrl, apiKey, model, activeProvider()?.protocol.orEmpty())
}
