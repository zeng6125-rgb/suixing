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
 * One OpenAI-compatible endpoint the user set up.
 *
 * Aggregating proxies (one LAN address that fronts many upstream vendors) report
 * model ids like `deepseek/deepseek-chat` or `openai/gpt-4o`, which is why the
 * picker groups by [modelGroupOf].
 */
@Serializable
data class ProviderConfig(
    val id: String,
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    /** Everything `/models` reported for this endpoint. */
    val models: List<String> = emptyList(),
    /** The subset the user ticked. Only these appear in the model switcher. */
    val enabled: List<String> = emptyList(),
) {
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

@Serializable
data class AppSettings(
    val baseUrl: String = "https://api.deepseek.com/v1",
    val apiKey: String = "",
    val model: String = "deepseek-chat",
    val temperature: Double = 0.6,
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
     * Per-model context window in tokens, keyed by model name. 0 (or absent) sends
     * everything; anything else drops the oldest turns until the estimate fits.
     * Whatever the model actually supports is its own business — this is only a
     * ceiling the user sets, per model, because windows differ wildly between them.
     */
    val modelContexts: Map<String, Int> = emptyMap(),
    /**
     * Tool-calling rounds allowed in one turn. 0 means no limit, which is the
     * default: a long task should not be cut off at an arbitrary number.
     */
    val maxToolRounds: Int = 0,
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
    val approvalMode: ApprovalMode = ApprovalMode.ASK,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    /**
     * 0 = the hand-tuned 随行 palette, 1 = follow the system wallpaper (Material You).
     * The brand palette is the default because the whole UI is built around it.
     */
    val paletteMode: Int = 0,
    val themeMode: Int = 0,
    val enableRoot: Boolean = false,
    /**
     * Off by default because the default endpoint (deepseek-chat) is text-only and
     * rejects any request carrying an image. Turn it on for vision models.
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
    /** Keep the thinking block on the transcript once the answer is there. */
    val showReasoning: Boolean = true,
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
    /** 悬浮窗控制台：开启后 agent 运行时显示悬浮球；关闭则永不显示。 */
    val floatingConsole: Boolean = false,
    /** 0 小, 1 标准, 2 大, 3 特大 */
    val fontScale: Int = 1,
    /** 0 窄, 1 标准, 2 宽 —— 助手气泡最多占屏宽的比例，由聊天页读取 */
    val bubbleWidth: Int = 1,
    /** 0 方正, 1 标准, 2 圆润 */
    val cornerScale: Int = 1,
    /** 是否在气泡下方显示时间 */
    val showTimestamps: Boolean = true,
    /** 工具调用卡片默认展开 */
    val expandTools: Boolean = false,
    /** 思考过程默认展开 */
    val expandReasoning: Boolean = false,
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

    /** The context ceiling for [model]; 0 when the user never set one for it. */
    fun contextWindowFor(model: String): Int = modelContexts[model] ?: 0
}

const val DEFAULT_SYSTEM_PROMPT = """你是随行，运行在用户安卓手机上的智能体。

你可以通过工具真实地操作这台手机：读取与写入文件、执行 shell 命令、查看屏幕、点击与滑动、打开应用。

工作方式：
- 先想清楚要做什么，再调用工具，不要凭猜测描述手机状态。
- 每一步只做必要的操作，拿到结果后再决定下一步。
- 需要外部信息时用 web_fetch 或 web_search 查，不要凭记忆编造事实与链接。
- 涉及删除、发送、支付、卸载等不可逆操作时，先向用户说明再执行。
- 用简洁的中文回答，必要时用短列表。"""

class SettingsRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key = stringPreferencesKey("settings_json")

    val flow: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        val raw = prefs[key]
        if (raw.isNullOrBlank()) {
            AppSettings()
        } else {
            runCatching { json.decodeFromString<AppSettings>(raw) }.getOrElse { AppSettings() }
        }
    }.map { it.migrated() }

    suspend fun current(): AppSettings = flow.first()

    suspend fun save(settings: AppSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(AppSettings.serializer(), settings)
        }
    }

    suspend fun update(block: (AppSettings) -> AppSettings) {
        save(block(current()))
    }
}

/**
 * Settings written before providers existed only have the flat `baseUrl` /
 * `apiKey` / `model` triple. Wrap that in a single provider so the picker and the
 * switcher have something to show without the user re-entering anything.
 */
private fun AppSettings.migrated(): AppSettings = withProviders().withAssistants()

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
    return copy(
        systemPrompt = assistant.systemPrompt.ifBlank { systemPrompt },
        model = assistant.model.ifBlank { model },
        baseUrl = provider?.baseUrl?.ifBlank { null } ?: baseUrl,
        apiKey = provider?.apiKey?.ifBlank { null } ?: apiKey,
        temperature = assistant.temperature ?: temperature,
    )
}
