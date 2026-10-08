package com.lingshu.agent.data

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

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "lingshu_settings")

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
    val maxToolRounds: Int = 16,
    val approvalMode: ApprovalMode = ApprovalMode.ASK,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    /**
     * 0 = the hand-tuned 灵枢 palette, 1 = follow the system wallpaper (Material You).
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
    /**
     * Every endpoint the user set up. [baseUrl] / [apiKey] / [model] above stay the
     * *active* triple, so switching models is a single copy() and nothing else in
     * the app has to know that providers exist.
     */
    val providers: List<ProviderConfig> = emptyList(),
) {
    val configured: Boolean get() = apiKey.isNotBlank() && model.isNotBlank() && baseUrl.isNotBlank()

    /** The provider the active triple points at, matched on address. */
    fun activeProvider(): ProviderConfig? =
        providers.firstOrNull { it.baseUrl.trim().trimEnd('/') == baseUrl.trim().trimEnd('/') }

    /** Every (provider, model) pair the user ticked anywhere, for the switcher. */
    fun enabledModels(): List<Pair<ProviderConfig, String>> =
        providers.flatMap { provider -> provider.enabled.map { provider to it } }

    /** The active model with its provider prefix stripped, for display. */
    val shortModel: String get() = model.substringAfter('/')
}

const val DEFAULT_SYSTEM_PROMPT = """你是灵枢，运行在用户安卓手机上的智能体。

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
private fun AppSettings.migrated(): AppSettings {
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
