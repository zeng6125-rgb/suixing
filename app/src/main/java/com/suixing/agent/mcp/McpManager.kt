package com.suixing.agent.mcp

import com.suixing.agent.data.McpServerConfig
import com.suixing.agent.data.McpStore
import com.suixing.agent.tools.TIER_NONE
import com.suixing.agent.tools.Tool
import com.suixing.agent.tools.ToolRegistry
import com.suixing.agent.tools.ToolResult
import com.suixing.agent.tools.ToolSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.cancellation.CancellationException

/**
 * DISCONNECTED = enabled-but-not-connected or manually stopped; CONNECTING = a
 * first/manual attempt in flight; RECONNECTING = auto-backoff retry [attempt] of
 * [MCP_MAX_RECONNECT_ATTEMPTS]; READY = connected with tools published; FAILED =
 * backoff exhausted (or a hard error), [McpServerState.error] carries why.
 */
enum class McpStatus { DISCONNECTED, CONNECTING, RECONNECTING, READY, FAILED }

data class McpServerState(
    val config: McpServerConfig,
    val status: McpStatus = McpStatus.DISCONNECTED,
    val toolCount: Int = 0,
    val serverName: String = "",
    val error: String? = null,
    /** Which auto-retry is in flight, 1-based; 0 outside [McpStatus.RECONNECTING]. */
    val attempt: Int = 0,
)

/** Backoff schedule for auto-reconnect: 2s, doubling, capped at 30s. */
internal const val MCP_INITIAL_BACKOFF_MS = 2_000L
internal const val MCP_MAX_BACKOFF_MS = 30_000L

/** Retries before a server is parked as FAILED; a manual 重连 resets this. */
internal const val MCP_MAX_RECONNECT_ATTEMPTS = 5

/**
 * Delay before auto-retry number [attempt] (1-based). Pure so the 2/4/8/16/30
 * sequence and its cap can be pinned down without a device or a real server.
 */
internal fun backoffDelayMs(attempt: Int): Long {
    if (attempt <= 0) return 0L
    var delay = MCP_INITIAL_BACKOFF_MS
    repeat(attempt - 1) { if (delay < MCP_MAX_BACKOFF_MS) delay *= 2 }
    return delay.coerceAtMost(MCP_MAX_BACKOFF_MS)
}

/**
 * Keeps the configured MCP servers connected and mirrors their tools into the
 * [ToolRegistry] under the `mcp__<server>__<tool>` namespace, so the model sees
 * them exactly like builtin tools and the approval gate treats them the same.
 *
 * A dropped or refused connection is retried on a backoff schedule instead of
 * being left dead: the app is long-lived and a Termux box or a laptop server
 * coming back online should reconnect on its own. [scope] defaults to a
 * process-wide one so reconnects survive the screen that started them; Core can
 * pass its own appScope to share the process lifetime.
 */
class McpManager(
    private val store: McpStore,
    private val registry: ToolRegistry,
    private val clientVersion: String = store.appVersionName(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val mutex = Mutex()
    private val stateLock = Any()
    private val jobLock = Any()
    private val clients = mutableMapOf<String, McpClient>()
    private val toolsByServer = mutableMapOf<String, List<McpToolSpec>>()
    private val reconnectJobs = mutableMapOf<String, Job>()

    private val _servers = MutableStateFlow<List<McpServerState>>(emptyList())
    val servers: StateFlow<List<McpServerState>> = _servers.asStateFlow()

    /**
     * Reads the saved servers and connects every enabled one. Each connect runs on
     * its own coroutine, so one server that hangs on a timeout cannot delay the
     * others or the caller — a cold start with a dead server still reaches the UI.
     */
    suspend fun load() {
        val configs = store.load()
        synchronized(stateLock) {
            _servers.value = configs.map { McpServerState(it) }
        }
        for (config in configs.filter { it.enabled }) {
            scope.launch { connect(config.name) }
        }
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

    /**
     * One connect attempt driven from the UI or from [load]: cancels any backoff in
     * flight, resets the retry counter, and on failure hands off to the auto-retry
     * loop rather than parking the server dead.
     */
    suspend fun connect(name: String) {
        val config = configOf(name) ?: return
        cancelReconnect(name)
        setAttempt(name, 0)
        update(name) { it.copy(status = McpStatus.CONNECTING, error = null, attempt = 0) }
        if (attemptConnect(name, config)) return
        scheduleReconnect(name)
    }

    /**
     * Manual 重连: same as [connect] but the intent is explicit, so it always starts
     * from a clean backoff even if the server had already been parked as FAILED.
     */
    suspend fun reconnect(name: String) {
        connect(name)
    }

    suspend fun disconnect(name: String) {
        cancelReconnect(name)
        val client = mutex.withLock {
            toolsByServer.remove(name)
            clients.remove(name)
        }
        client?.close()
        update(name) {
            it.copy(status = McpStatus.DISCONNECTED, toolCount = 0, serverName = "", error = null, attempt = 0)
        }
        publishTools()
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

    /**
     * One connect + tools/list. On success registers the client and publishes tools
     * and returns true; on failure closes the half-open client, drops its tools and
     * records the error, returning false so the caller decides whether to retry.
     */
    private suspend fun attemptConnect(name: String, config: McpServerConfig): Boolean {
        val client = McpClient(buildTransport(config))
        return try {
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
                    attempt = 0,
                )
            }
            publishTools()
            true
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            client.close()
            mutex.withLock { toolsByServer.remove(name) }
            update(name) {
                it.copy(
                    toolCount = 0,
                    serverName = "",
                    error = error.message ?: error::class.java.simpleName,
                )
            }
            publishTools()
            false
        }
    }

    /**
     * Arms the next auto-retry, or parks the server as FAILED once the budget is
     * spent. Each retry is its own short-lived job so a manual connect or a
     * disconnect can cancel the pending delay without waiting it out.
     */
    private fun scheduleReconnect(name: String) {
        val config = configOf(name) ?: return
        if (!config.enabled) {
            update(name) { it.copy(status = McpStatus.DISCONNECTED, attempt = 0) }
            return
        }
        val next = attemptOf(name) + 1
        if (next > MCP_MAX_RECONNECT_ATTEMPTS) {
            update(name) { it.copy(status = McpStatus.FAILED, attempt = 0) }
            return
        }
        setAttempt(name, next)
        update(name) { it.copy(status = McpStatus.RECONNECTING, attempt = next) }
        val job = scope.launch {
            delay(backoffDelayMs(next))
            // A manual connect, a disconnect or a disable may have superseded this
            // retry while it was waiting; only the retry still on the books proceeds.
            val current = stateOf(name)
            if (current?.status != McpStatus.RECONNECTING || current.attempt != next) return@launch
            if (attemptConnect(name, config)) return@launch
            scheduleReconnect(name)
        }
        synchronized(jobLock) { reconnectJobs[name] = job }
    }

    /**
     * A tool call that fails at the transport level means the connection is gone
     * (process died, socket closed, request timed out). Kick off backoff reconnect
     * so the server heals without the user noticing, but only from READY — a loop
     * already running must not be doubled up.
     */
    private fun onTransportFailure(name: String) {
        val status = synchronized(stateLock) { _servers.value.firstOrNull { it.config.name == name }?.status }
        if (status != McpStatus.READY) return
        cancelReconnect(name)
        setAttempt(name, 0)
        scheduleReconnect(name)
    }

    private fun configOf(name: String): McpServerConfig? =
        synchronized(stateLock) { _servers.value.firstOrNull { it.config.name == name }?.config }

    private fun stateOf(name: String): McpServerState? =
        synchronized(stateLock) { _servers.value.firstOrNull { it.config.name == name } }

    private fun attemptOf(name: String): Int =
        synchronized(stateLock) { _servers.value.firstOrNull { it.config.name == name }?.attempt ?: 0 }

    private fun setAttempt(name: String, attempt: Int) {
        update(name) { it.copy(attempt = attempt) }
    }

    private fun cancelReconnect(name: String) {
        synchronized(jobLock) { reconnectJobs.remove(name)?.cancel() }
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
            name = mcpToolName(server, remote.name),
            description = remote.description.ifBlank { "MCP 服务「$server」提供的工具 ${remote.name}" },
            parameters = remote.inputSchema,
        )

        override val tier: String = TIER_NONE

        override suspend fun run(args: JsonObject): ToolResult {
            return try {
                val result = client.callTool(remote.name, args)
                if (result.ok) ToolResult.success(result.text) else ToolResult.failure(result.text)
            } catch (error: CancellationException) {
                throw error
            } catch (error: McpException) {
                // Transport-level, not a tool-level error: the link is down, so start
                // healing it in the background and tell the model this call was lost.
                onTransportFailure(server)
                ToolResult.failure("MCP 连接中断，正在后台重连：${error.message.orEmpty()}")
            }
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

    companion object {
        const val MCP_PREFIX = "mcp__"
    }
}

/** Function names are limited to 64 characters by the OpenAI API. */
internal const val MCP_TOOL_NAME_MAX = 64

/** How much of the server name fits in a tool name; anything longer becomes a short hash. */
private const val MCP_SERVER_SLICE = 24

/**
 * The name the model sees for a server's tool: `mcp__<server>__<tool>`, always within
 * [MCP_TOOL_NAME_MAX]. The server slice is fixed, so a long server name can never eat the tool's
 * own name — which is what used to push the total past the API limit — and a four-hex suffix of
 * the full name keeps two long server names from colliding after the slice.
 */
internal fun mcpToolName(server: String, tool: String): String {
    val clean = sanitizeMcpName(server)
    val serverPart = if (clean.length <= MCP_SERVER_SLICE) {
        clean
    } else {
        clean.take(MCP_SERVER_SLICE - 5) + "_" + clean.hashCode().toUInt().toString(16).take(4)
    }
    val prefix = "${McpManager.MCP_PREFIX}${serverPart}__"
    val room = (MCP_TOOL_NAME_MAX - prefix.length).coerceAtLeast(1)
    return prefix + sanitizeMcpName(tool).take(room)
}

/** Keeps a server or tool name to the characters the API accepts in a function name. */
internal fun sanitizeMcpName(value: String): String = buildString {
    for (ch in value) {
        append(if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_' || ch == '-') ch else '_')
    }
}.ifEmpty { "_" }
