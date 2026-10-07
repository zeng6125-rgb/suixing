package com.lingshu.agent.data

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

    suspend fun load(): List<McpServerConfig> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        try {
            json.decodeFromString(ListSerializer(McpServerConfig.serializer()), file.readText())
        } catch (error: Exception) {
            // Never drop the user's definitions silently: keep the bad file next
            // to the original name so it can be recovered by hand.
            file.renameTo(File(file.parentFile, "mcp.corrupt.json"))
            emptyList()
        }
    }

    suspend fun save(configs: List<McpServerConfig>) = withContext(Dispatchers.IO) {
        file.writeText(json.encodeToString(ListSerializer(McpServerConfig.serializer()), configs))
    }
}
