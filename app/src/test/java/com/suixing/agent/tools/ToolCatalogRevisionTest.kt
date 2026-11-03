package com.suixing.agent.tools

import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCatalogRevisionTest {

    private fun registry(): ToolRegistry = ToolRegistry::class.java.getDeclaredConstructor().let {
        it.isAccessible = true
        it.newInstance()
    }

    @Test
    fun `publishing a dynamic tool bumps the catalog revision`() {
        val registry = registry()
        val before = registry.catalogRevision.value
        val tool = mockk<Tool> {
            every { spec } returns ToolSpec("mcp_x", "dynamic", Schema.objectOf(emptyList()))
        }

        registry.replaceDynamic("mcp_", listOf(tool))

        assertTrue("the composer recomputes only when this value moves", registry.catalogRevision.value > before)
        assertEquals(listOf("mcp_x"), registry.all().map { it.spec.name })
    }

    @Test
    fun `a builtin group also bumps the revision`() {
        val registry = registry()
        val before = registry.catalogRevision.value

        registry.add(object : ToolGroup() {
            override val specs = listOf(ToolSpec("alpha", "first", Schema.objectOf(emptyList())))
            override suspend fun execute(name: String, args: JsonObject): ToolResult =
                ToolResult.success("ok")
        })

        assertTrue(registry.catalogRevision.value > before)
    }
}
