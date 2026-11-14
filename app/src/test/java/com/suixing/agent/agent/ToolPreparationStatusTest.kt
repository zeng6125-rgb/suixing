package com.suixing.agent.agent

import android.content.Context
import com.suixing.agent.core.Core
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolPreparationStatusTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun terminalChildStatesClearPreparationButKeepFailures() = runBlocking {
        val context = mockk<Context> { every { filesDir } returns temporary.root }
        mockkObject(Core)
        every { Core.appContext } returns context
        val parent = "tool-preparation-status-${UUID.randomUUID()}"
        fun create(name: String): String {
            val id = "$parent:$name"
            SubagentRecords.create(parent, "run", SubagentTask(id, name, "inspect", "", 0L))
            SubagentRecords.update(id, SubagentStatus.RUNNING)
            return id
        }
        try {
            for (terminal in listOf(SubagentStatus.CANCELLED, SubagentStatus.FAILED)) {
                val id = create(terminal.name)
                val record = SubagentRecords.record(id)
                SubagentRecords.onEvent(id, AgentEvent.ToolPrepared(0, "write_file"))
                assertTrue(record.value!!.statusLine.startsWith("正在准备调用 "))
                SubagentRecords.update(id, terminal, "interrupted")
                assertEquals(terminal, record.value!!.status)
                assertEquals("a terminal child must not keep waiting for generated arguments", "", record.value!!.statusLine)
            }

            val failed = create("failure-message")
            val record = SubagentRecords.record(failed)
            SubagentRecords.onEvent(failed, AgentEvent.Failed("Connection failed"))
            SubagentRecords.update(failed, SubagentStatus.FAILED, "Connection failed")
            assertEquals("Connection failed", record.value!!.statusLine)
        } finally {
            SubagentRecords.delete(parent)
            unmockkObject(Core)
        }
    }
}
