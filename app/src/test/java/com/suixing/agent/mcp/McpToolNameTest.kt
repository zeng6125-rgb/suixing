package com.suixing.agent.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tool names are what the model sees and what the API caps at 64 characters. Both halves come
 * from user-typed server and tool names, so the cap has to hold for any input — a name over the
 * limit is rejected by the provider, which would take the whole request down with it.
 */
class McpToolNameTest {

    @Test
    fun `short names are kept whole`() {
        assertEquals("mcp__github__create_issue", mcpToolName("github", "create_issue"))
    }

    @Test
    fun `long names are cut to the api limit`() {
        val name = mcpToolName("s".repeat(200), "t".repeat(200))
        assertTrue("长度 ${name.length} 超限：$name", name.length <= MCP_TOOL_NAME_MAX)
        assertTrue(name.startsWith("mcp__"))
    }

    @Test
    fun `a long tool name keeps its own characters`() {
        val name = mcpToolName("github", "t".repeat(200))
        assertTrue(name.length <= MCP_TOOL_NAME_MAX)
        // The server slice is fixed, so the tool's name still has room rather than being cut away.
        assertTrue(name.startsWith("mcp__github__"))
    }

    @Test
    fun `two long server names do not collide after the slice`() {
        val a = mcpToolName("server" + "a".repeat(60), "tool")
        val b = mcpToolName("server" + "b".repeat(60), "tool")
        assertNotEquals(a, b)
    }

    @Test
    fun `an empty tool name still yields a usable name`() {
        val name = mcpToolName("srv", "")
        assertTrue(name.length <= MCP_TOOL_NAME_MAX)
        assertTrue(name.startsWith("mcp__srv__"))
    }

    @Test
    fun `separators in a server name are sanitized`() {
        assertEquals("mcp__my_server__run", mcpToolName("my server", "run"))
    }
}
