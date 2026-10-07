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

@Serializable
data class AppSettings(
    val baseUrl: String = "https://api.deepseek.com/v1",
    val apiKey: String = "",
    val model: String = "deepseek-chat",
    val temperature: Double = 0.6,
    val maxToolRounds: Int = 16,
    val approvalMode: ApprovalMode = ApprovalMode.ASK,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val dynamicColor: Boolean = true,
    val themeMode: Int = 0,
    val enableRoot: Boolean = false,
) {
    val configured: Boolean get() = apiKey.isNotBlank() && model.isNotBlank() && baseUrl.isNotBlank()
}

const val DEFAULT_SYSTEM_PROMPT = """你是灵枢，运行在用户安卓手机上的智能体。

你可以通过工具真实地操作这台手机：读取与写入文件、执行 shell 命令、查看屏幕、点击与滑动、打开应用。

工作方式：
- 先想清楚要做什么，再调用工具，不要凭猜测描述手机状态。
- 每一步只做必要的操作，拿到结果后再决定下一步。
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
    }

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
