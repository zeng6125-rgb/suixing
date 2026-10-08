package com.suixing.agent.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpProtocolTest {
    @Test
    fun splitCommandKeepsQuotedArgumentsTogether() {
        assertEquals(
            listOf("python", "/sdcard/my server.py", "--flag"),
            McpProtocol.splitCommand("""python "/sdcard/my server.py" --flag"""),
        )
        assertEquals(
            listOf("su", "-c", "node /data/local/tmp/s.js"),
            McpProtocol.splitCommand("""su -c 'node /data/local/tmp/s.js'"""),
        )
        assertEquals(listOf("npx", "-y", "server"), McpProtocol.splitCommand("  npx   -y  server  "))
        assertEquals(emptyList<String>(), McpProtocol.splitCommand("   "))
    }

    @Test
    fun parseEnvIgnoresCommentsAndBlankLines() {
        val env = McpProtocol.parseEnv(
            """
            # comment
            TOKEN=abc

            PATH=/usr/bin:/bin
            """.trimIndent(),
        )
        assertEquals(mapOf("TOKEN" to "abc", "PATH" to "/usr/bin:/bin"), env)
    }

    @Test
    fun parseHeadersSplitsOnFirstColon() {
        val headers = McpProtocol.parseHeaders("Authorization: Bearer a:b\nX-Trace: 1")
        assertEquals(mapOf("Authorization" to "Bearer a:b", "X-Trace" to "1"), headers)
    }

    @Test
    fun parseToolsReadsNamesDescriptionsAndSchemas() {
        val result = McpProtocol.json.parseToJsonElement(
            """
            {"tools":[
              {"name":"read","description":"Read a file","inputSchema":{"type":"object"}},
              {"name":"noSchema"},
              {"description":"nameless"}
            ]}
            """.trimIndent(),
        )
        val tools = McpProtocol.parseTools(result)
        assertEquals(listOf("read", "noSchema"), tools.map { it.name })
        assertEquals("Read a file", tools[0].description)
        assertEquals("object", (tools[1].inputSchema["type"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun parseCallResultJoinsTextBlocksAndFlagsErrors() {
        val ok = McpProtocol.parseCallResult(
            McpProtocol.json.parseToJsonElement(
                """{"content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}""",
            ),
        )
        assertTrue(ok.ok)
        assertEquals("a\nb", ok.text)

        val failed = McpProtocol.parseCallResult(
            McpProtocol.json.parseToJsonElement(
                """{"content":[{"type":"text","text":"boom"}],"isError":true}""",
            ),
        )
        assertFalse(failed.ok)
        assertEquals("boom", failed.text)
    }

    @Test
    fun errorMessageReadsJsonRpcErrors() {
        val response: JsonObject = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put(
                "error",
                buildJsonObject {
                    put("code", -32601)
                    put("message", "Method not found")
                },
            )
        }
        assertEquals("Method not found（code -32601）", McpProtocol.errorMessage(response))
        assertNull(McpProtocol.errorMessage(buildJsonObject { put("id", 1) }))
    }
}
