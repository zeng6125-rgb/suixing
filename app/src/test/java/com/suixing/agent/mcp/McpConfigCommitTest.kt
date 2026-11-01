package com.suixing.agent.mcp

import com.suixing.agent.data.McpServerConfig
import com.suixing.agent.data.McpStore
import com.suixing.agent.tools.ToolRegistry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class McpConfigCommitTest {
    @Test fun failedSaveLeavesAllPublishedConfigurationsIntact() = runBlocking {
        val store = mockk<McpStore>()
        val manager = McpManager(store, mockk<ToolRegistry>(relaxed = true), clientVersion = "test")
        coEvery { store.save(any()) } returns Unit
        val original = McpServerConfig("server", enabled = false)
        manager.upsert(original)
        coEvery { store.save(any()) } throws IOException("disk unavailable")
        for (operation in listOf<suspend () -> Unit>(
            { manager.upsert(original.copy(command = "changed")) },
            { manager.setEnabled("server", true) },
            { manager.remove("server") },
        )) {
            try { operation(); fail("Save must fail") } catch (_: IOException) { }
            assertEquals(listOf(original), manager.servers.value.map { it.config })
        }
        manager.close()
    }
}
