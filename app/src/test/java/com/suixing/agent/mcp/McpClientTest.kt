package com.suixing.agent.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drives the real stdio transport against [FakeMcpServer] running in a second
 * JVM, so framing, id matching and error handling are all exercised end to end.
 */
class McpClientTest {
    @Test
    fun stdioTransportCompletesAHandshakeAndCallsTools() = runBlocking {
        val client = McpClient(StdioTransport(serverCommand()))
        try {
            client.connect()
            assertEquals("fake", client.serverInfo?.name)
            assertEquals("1.2.3", client.serverInfo?.version)
            assertEquals(McpProtocol.PROTOCOL_VERSION, client.serverInfo?.protocolVersion)

            val tools = client.listTools()
            assertEquals(listOf("echo", "boom"), tools.map { it.name })
            assertEquals("Echo the text back", tools[0].description)

            val ok = client.callTool("echo", buildJsonObject { put("text", "hi") })
            assertTrue(ok.ok)
            assertEquals("echo:hi", ok.text)

            val failed = client.callTool("boom", buildJsonObject { })
            assertFalse(failed.ok)
            assertEquals("boom failed", failed.text)
        } finally {
            client.close()
        }
    }

    @Test
    fun exchangeFailsWhenTheServerNeverAnswers() = runBlocking {
        val transport = StdioTransport(listOf(javaExecutable(), "-version"))
        try {
            transport.start()
            val failure = runCatching {
                transport.exchange(McpProtocol.listTools(1), timeoutMs = 5_000)
            }.exceptionOrNull()
            assertTrue(
                "expected an McpException, got $failure",
                failure is McpException,
            )
        } finally {
            transport.close()
        }
    }

    /**
     * `java -cp <everything> Main` does not fit in a Windows command line, so the
     * arguments go through a Java @argfile with forward slashes (backslashes are
     * escape characters inside an argfile).
     */
    private fun serverCommand(): List<String> {
        val classpath = (System.getProperty("java.class.path") ?: "").replace('\\', '/')
        val argFile = File.createTempFile("Suixing-mcp-test", ".args")
        argFile.deleteOnExit()
        argFile.writeText("-cp \"$classpath\"\ncom.suixing.agent.mcp.FakeMcpServer\n")
        return listOf(javaExecutable(), "@${argFile.absolutePath}")
    }

    private fun javaExecutable(): String {
        val name = if ((System.getProperty("os.name") ?: "").startsWith("Windows", ignoreCase = true)) {
            "java.exe"
        } else {
            "java"
        }
        val home = System.getProperty("java.home") ?: error("java.home 未设置，无法定位 java 可执行文件")
        return File(home, "bin/$name").absolutePath
    }
}
