package com.suixing.agent.data

import com.suixing.agent.data.SecretBox
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One MCP server as the user typed it in. A stdio server is a command line
 * (optionally with extra environment variables); an http server is a URL with
 * optional headers.
 */
@Serializable
data class McpServerConfig(
    val name: String,
    val transport: String = TRANSPORT_STDIO,
    val command: String = "",
    val url: String = "",
    val env: String = "",
    val headers: String = "",
    val enabled: Boolean = true,
) {
    val isStdio: Boolean get() = transport != TRANSPORT_HTTP

    companion object {
        const val TRANSPORT_STDIO = "stdio"
        const val TRANSPORT_HTTP = "http"
    }
}

/** Server definitions live in one small JSON file next to the other app data. */
class McpStore(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val file: File get() = File(context.filesDir, "mcp.json")

    /**
     * The app's own versionName, read from the package. MCP hands it to servers as the client
     * version, and a constant in the source drifts behind the build every release.
     */
    fun appVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "unknown" }

    suspend fun load(): List<McpServerConfig> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        try {
            // env / headers 里常放 Token（issue #58 #4），落盘是密文，这里解回明文；
            // 旧文件里的明文不带 `enc1:` 前缀，`SecretBox.decrypt` 原样返回 ⇒ 无需单独迁移。
            json.decodeFromString(ListSerializer(McpServerConfig.serializer()), file.readText())
                .map { it.copy(env = SecretBox.decrypt(it.env), headers = SecretBox.decrypt(it.headers)) }
        } catch (error: Exception) {
            // Never drop the user's definitions silently: keep the bad file next
            // to the original name so it can be recovered by hand.
            file.renameTo(File(file.parentFile, "mcp.corrupt.json"))
            emptyList()
        }
    }

    suspend fun save(configs: List<McpServerConfig>) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        // Temp file then rename, as in the other stores: a kill mid-write would leave a
        // truncated file, and a truncated file reads back as "no servers at all".
        val stored = configs.map {
            // 加密不可用时**写空**，不写明文（issue #58 #3 的同一条约束）：
            // 这里是 Token 常驻的地方，留明文等于把凭据摊在 `mcp.json` 里。
            // 代价是这台设备上要重填一次；SecretBox 会记一条警告。
            it.copy(
                env = SecretBox.encrypt(it.env) ?: "",
                headers = SecretBox.encrypt(it.headers) ?: "",
            )
        }
        val text = json.encodeToString(ListSerializer(McpServerConfig.serializer()), stored)
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            try {
                file.writeText(text)
            } finally {
                temp.delete()
            }
        }
    }
}
