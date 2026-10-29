package com.suixing.agent.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class McpHandshakeRetryTest {
    @Test fun failedHandshakeCanBeRetriedOnSameClient() = runBlocking {
        var attempts = 0
        val transport = object : McpTransport {
            override val label = "test"
            override suspend fun start() { }
            override suspend fun exchange(message: JsonObject, timeoutMs: Long): JsonObject {
                if (++attempts == 1) throw McpException("offline")
                return buildJsonObject { put("result", buildJsonObject {
                    put("protocolVersion", "2024-11-05")
                    put("serverInfo", buildJsonObject { put("name", "ready"); put("version", "1") })
                }) }
            }
            override suspend fun notify(message: JsonObject) { }
            override fun close() { }
        }
        val client = McpClient(transport)
        runCatching { client.connect() }
        client.connect()
        assertEquals(2, attempts)
        assertEquals("ready", client.serverInfo?.name)
    }
}
