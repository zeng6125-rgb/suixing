package com.suixing.agent.mcp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class McpHttpTransportTest {
    private fun exchange(body: String, type: String = "text/event-stream"): kotlinx.serialization.json.JsonObject = runBlocking {
        ServerSocket(0).use { server ->
            Thread {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (reader.readLine()?.isNotEmpty() == true) { }
                    val bytes = body.toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }.apply { isDaemon = true; start() }
            val transport = HttpTransport("http://127.0.0.1:${server.localPort}")
            try { transport.exchange(McpProtocol.listTools(7), 5_000) } finally { transport.close() }
        }
    }

    @Test fun skipsNotificationsAndOtherIdsBeforeMatchingResponse() {
        val result = exchange("data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n" +
            "data: {\"jsonrpc\":\"2.0\",\"id\":8,\"result\":{}}\n\n" +
            "data: {\"jsonrpc\":\"2.0\",\"id\":7,\ndata:  \"result\":{\"tools\":[]}}\n\n")
        assertEquals("7", result["id"].toString())
        assertNotNull(result["result"])
    }

    @Test fun rejectsWrongIdInPlainJson() {
        assertTrue(runCatching { exchange("{\"jsonrpc\":\"2.0\",\"id\":8,\"result\":{}}", "application/json") }
            .exceptionOrNull() is McpException)
    }

    @Test fun rejectsOversizedResponseWhileReading() {
        assertTrue(runCatching { exchange("data: " + "x".repeat(2 * 1024 * 1024)) }
            .exceptionOrNull() is McpException)
    }
}
