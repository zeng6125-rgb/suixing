package com.lingshu.agent.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicLong

/**
 * The client half of MCP: one initialize handshake, then tools/list and
 * tools/call. Transport and protocol errors both surface as [McpException] so
 * the UI can show the server's own wording.
 */
class McpClient(
    private val transport: McpTransport,
    private val clientName: String = "lingshu",
    private val clientVersion: String = "0.1.2",
) {
    private val ids = AtomicLong(1)
    private var started = false

    var serverInfo: McpServerInfo? = null
        private set

    suspend fun connect() {
        if (started) return
        transport.start()
        started = true
        val response = transport.exchange(
            McpProtocol.initialize(ids.getAndIncrement(), clientName, clientVersion),
        )
        serverInfo = McpProtocol.parseServerInfo(resultOf(response))
        transport.notify(McpProtocol.initializedNotification())
    }

    suspend fun listTools(): List<McpToolSpec> =
        McpProtocol.parseTools(resultOf(transport.exchange(McpProtocol.listTools(ids.getAndIncrement()))))

    suspend fun callTool(name: String, arguments: JsonObject): McpCallResult {
        val response = transport.exchange(
            McpProtocol.callTool(ids.getAndIncrement(), name, arguments),
        )
        McpProtocol.errorMessage(response)?.let { return McpCallResult(false, it) }
        return McpProtocol.parseCallResult(response["result"])
    }

    fun close() {
        started = false
        transport.close()
    }

    private fun resultOf(response: JsonObject): JsonElement? {
        McpProtocol.errorMessage(response)?.let { throw McpException(it) }
        return response["result"]
    }
}
