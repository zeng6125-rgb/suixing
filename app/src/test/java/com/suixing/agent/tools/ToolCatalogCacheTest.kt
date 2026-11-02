package com.suixing.agent.tools

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ToolCatalogCacheTest {
    private fun registry() = ToolRegistry::class.java.getDeclaredConstructor().let {
        it.isAccessible = true
        it.newInstance()
    }

    private fun tool(name: String, description: String = name) = object : Tool {
        override val spec = ToolSpec(name, description, JsonObject(emptyMap()))
        override val tier = TIER_ACCESSIBILITY
        override suspend fun run(args: JsonObject) = ToolResult.success(name)
    }

    @Test fun `reconnections and discovery order do not reshuffle the prompt catalog`() {
        val registry = registry()
        registry.replaceDynamic("mcp_b_", listOf(tool("mcp_b_z"), tool("mcp_b_a")))
        registry.replaceDynamic("mcp_a_", listOf(tool("mcp_a_z")))
        val initialNames = registry.names()
        val initialPrompt = registry.promptSummary()
        registry.replaceDynamic("mcp_b_", emptyList())
        registry.replaceDynamic("mcp_b_", listOf(tool("mcp_b_a"), tool("mcp_b_z")))
        assertEquals(listOf("mcp_a_z", "mcp_b_a", "mcp_b_z"), registry.names())
        assertEquals(initialNames, registry.names())
        assertEquals(initialPrompt, registry.promptSummary())
    }

    @Test fun `unchanged reads share a snapshot and edits invalidate it without mutating old snapshots`() {
        val registry = registry()
        registry.replaceDynamic("mcp_", listOf(tool("mcp_a")))
        val before = registry.all()
        val prompt = registry.promptSummary()
        assertSame(before, registry.all())
        assertSame(prompt, registry.promptSummary())
        registry.replaceDynamic("mcp_", listOf(tool("mcp_b", "updated")))
        assertNotSame(before, registry.all())
        assertEquals(listOf("mcp_a"), before.map { it.spec.name })
        assertEquals(listOf("mcp_b"), registry.names())
        assertEquals("- mcp_b: updated", registry.promptSummary())
        assertNull(registry.find("mcp_a"))
    }

    @Test fun `builtin priority and excluded snapshots keep their own catalogs`() {
        val registry = registry()
        registry.add(object : ToolGroup() {
            override val specs = listOf(tool("z_builtin").spec, tool("a_builtin").spec)
            override suspend fun execute(name: String, args: JsonObject) = ToolResult.success(name)
        })
        registry.replaceDynamic("mcp_", listOf(tool("mcp_z"), tool("mcp_a")))
        val child = registry.excluding(setOf("z_builtin", "mcp_z"))
        assertEquals(listOf("z_builtin", "a_builtin", "mcp_a", "mcp_z"), registry.names())
        assertEquals(listOf("a_builtin", "mcp_a"), child.names())
        registry.replaceDynamic("mcp_", emptyList())
        assertEquals(listOf("a_builtin", "mcp_a"), child.names())
        assertEquals("- a_builtin: a_builtin\n- mcp_a: mcp_a", child.promptSummary())
        registry.add(object : ToolGroup() {
            override val specs = listOf(tool("new_builtin").spec)
            override suspend fun execute(name: String, args: JsonObject) = ToolResult.success(name)
        })
        assertEquals(listOf("z_builtin", "a_builtin", "new_builtin"), registry.names())
    }
}
