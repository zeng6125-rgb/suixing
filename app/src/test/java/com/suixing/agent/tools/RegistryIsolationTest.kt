package com.suixing.agent.tools

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class RegistryIsolationTest {
    @Test fun `nested tool snapshots retain parent restrictions`() {
        val registry = ToolRegistry::class.java.getDeclaredConstructor().let {
            it.isAccessible = true
            it.newInstance()
        }
        registry.add(object : ToolGroup() {
            override val specs = listOf("allowed", "forbidden", "todo_write").map {
                ToolSpec(it, "test", JsonObject(emptyMap()))
            }
            override suspend fun execute(name: String, args: JsonObject) = ToolResult.success(name)
        })
        val parent = registry.excluding(setOf("forbidden", "spawn_agent"))
        val child = parent.excluding(setOf("todo_write", "wait_agents"))
        assertEquals(listOf("allowed"), child.all().map { it.spec.name })
        assertNull(child.find("forbidden"))
        assertTrue(child.isHidden("spawn_agent"))
        assertTrue(child.isHidden("wait_agents"))
        assertEquals(3, registry.all().size)
        assertEquals(2, parent.all().size)
    }
}
