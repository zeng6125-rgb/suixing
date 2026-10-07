package com.lingshu.agent.mcp

import com.lingshu.agent.data.McpServerConfig
import com.lingshu.agent.data.McpStore
import com.lingshu.agent.tools.TIER_NONE
import com.lingshu.agent.tools.Tool
import com.lingshu.agent.tools.ToolRegistry
import com.lingshu.agent.tools.ToolResult
import com.lingshu.agent.tools.ToolSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.cancellation.CancellationException

enum class McpStatus { DISCONNECTED, CONNECTING, READY, ERROR }

data class McpServerState(
    val config: McpServerConfig,
    val status: McpStatus = McpStatus.DISCONNECTED,
    val toolCount: Int = 0,
    val serverName: String = "",
    val error: String? = null,
)

/**
 * Keeps the configured MCP servers connected and mirrors their tools into the
 * [ToolRegistry] under the `mcp__<server>__<tool>` namespace, so the model sees
 * them exactly like builtin tools and the approval gate treats them the same.
 */
class McpManager(
    private val store: McpStore,
    private val registry: ToolRegistry,
    private val clientVersion: String = APP_VERSION,
) {
    private val mutex = Mutex()
    private val stateLock = Any()
    private val clients = mutableMapOf<String, McpClient>()
    private val toolsByServer = mutableMapOf<String, List<McpToolSpec>>()

    private val _servers = MutableStateFlow<List<McpServerState>>(emptyList())
    val servers: StateFlow<List<McpServerState>> = _servers.asStateFlow()

    /** Reads the saved servers and connects every enabled one. Safe to call twice. */
    suspend fun load() {
        val configs = store.load()
        synchronized(stateLock) {
            _servers.value = configs.map { McpServerState(it) }
        }
        for (config in configs.filter { it.enabled }) connect(config.name)
    }

    suspend fun upsert(config: McpServerConfig) {
        synchronized(stateLock) {
            val list = _servers.value.toMutableList()
            val index = list.indexOfFirst { it.config.name == config.name }
            if (index >= 0) list[index] = McpServerState(config) else list += McpServerState(config)
            _servers.value = list
        }
        persist()
    }

    suspend fun remove(name: String) {
        disconnect(name)
        synchronized(stateLock) {
            _servers.value = _servers.value.filterNot { it.config.name == name }
        }
        persist()
    }

    suspend fun setEnabled(name: String, enabled: Boolean) {
        synchronized(stateLock) {
            _servers.value = _servers.value.map {
                if (it.config.name == name) it.copy(config = it.config.copy(enabled = enabled)) else it
            }
        }
        persist()
        if (enabled) connect(name) else disconnect(name)
    }

    suspend fun connect(name: String) {
        val config = _servers.value.firstOrNull { it.config.name == name }?.config ?: return
        update(name) { it.copy(status = McpStatus.CONNECTING, error = null) }
        val client = McpClient(buildTransport(config))
        try {
            client.connect()
            val specs = client.listTools()
            mutex.withLock {
                clients.put(name, client)?.close()
                toolsByServer[name] = specs
            }
            update(name) {
                it.copy(
                    status = McpStatus.READY,
                    toolCount = specs.size,
                    serverName = client.serverInfo?.name.orEmpty(),
                    error = null,
                )
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            client.close()
            mutex.withLock { toolsByServer.remove(name) }
            update(name) {
                it.copy(
                    status = McpStatus.ERROR,
                    toolCount = 0,
                    serverName = "",
                    error = error.message ?: error::class.java.simpleName,
                )
            }
        }
        publishTools()
    }

    suspend fun disconnect(name: String) {
        val client = mutex.withLock {
            toolsByServer.remove(name)
            clients.remove(name)
        }
        client?.close()
        update(name) {
            it.copy(status = McpStatus.DISCONNECTED, toolCount = 0, serverName = "", error = null)
        }
        publishTools()
    }

    suspend fun reconnect(name: String) {
        disconnect(name)
        connect(name)
    }

    suspend fun closeAll() {
        val all = mutex.withLock {
            val snapshot = clients.toMap()
            clients.clear()
            toolsByServer.clear()
            snapshot
        }
        all.values.forEach { it.close() }
        registry.replaceDynamic(MCP_PREFIX, emptyList())
    }

    /**
     * Connects with a throwaway client and reports what happened, in one line,
     * for the "测试" button. Never registers anything.
     */
    suspend fun test(config: McpServerConfig): String {
        val client = McpClient(buildTransport(config))
        return try {
            client.connect()
            val specs = client.listTools()
            val info = client.serverInfo
            val head = "连接成功：${info?.name.orEmpty()} ${info?.version.orEmpty()}".trim() +
                "（协议 ${info?.protocolVersion.orEmpty()}），可用工具 ${specs.size} 个"
            if (specs.isEmpty()) {
                head
            } else {
                val names = specs.take(10).joinToString("、") { it.name }
                "$head：$names${if (specs.size > 10) "…" else ""}"
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            "连接失败：${error.message ?: error::class.java.simpleName}"
        } finally {
            client.close()
        }
    }

    private suspend fun persist() {
        store.save(_servers.value.map { it.config })
    }

    private fun update(name: String, block: (McpServerState) -> McpServerState) {
        synchronized(stateLock) {
            _servers.value = _servers.value.map { if (it.config.name == name) block(it) else it }
        }
    }

    private suspend fun publishTools() {
        val tools = mutex.withLock {
            toolsByServer.flatMap { (server, specs) ->
                val client = clients[server] ?: return@flatMap emptyList()
                specs.map { spec -> toolFor(server, client, spec) }
            }
        }
        registry.replaceDynamic(MCP_PREFIX, tools)
    }

    private fun toolFor(server: String, client: McpClient, remote: McpToolSpec): Tool = object : Tool {
        override val spec: ToolSpec = ToolSpec(
            name = toolName(server, remote.name),
            description = remote.description.ifBlank { "MCP 服务「$server」提供的工具 ${remote.name}" },
            parameters = remote.inputSchema,
        )

        override val tier: String = TIER_NONE

        override suspend fun run(args: JsonObject): ToolResult {
            val result = client.callTool(remote.name, args)
            return if (result.ok) ToolResult.success(result.text) else ToolResult.failure(result.text)
        }
    }

    private fun buildTransport(config: McpServerConfig): McpTransport = if (config.isStdio) {
        StdioTransport(
            command = McpProtocol.splitCommand(config.command),
            environment = McpProtocol.parseEnv(config.env),
        )
    } else {
        HttpTransport(config.url.trim(), McpProtocol.parseHeaders(config.headers))
    }

    private fun toolName(server: String, tool: String): String {
        val prefix = MCP_PREFIX + sanitize(server) + "__"
        val room = (MAX_TOOL_NAME - prefix.length).coerceAtLeast(8)
        return prefix + sanitize(tool).take(room)
    }

    private fun sanitize(value: String): String = buildString {
        for (ch in value) {
            append(if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_' || ch == '-') ch else '_')
        }
    }.ifEmpty { "_" }

    companion object {
        const val MCP_PREFIX = "mcp__"
        const val APP_VERSION = "0.3.0"

        /** Function names are limited to 64 characters by the OpenAI API. */
        private const val MAX_TOOL_NAME = 64
    }
}
