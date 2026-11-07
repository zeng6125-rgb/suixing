package com.suixing.agent.agent

import com.suixing.agent.tools.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunLedgerTest {
    private val sameArgs = args("""{"command":"echo same"}""")
    private val sameResult = ToolResult.success("same result")
    private fun args(raw: String) = Json.parseToJsonElement(raw) as JsonObject

    @Test
    fun `finite runs warn at three repeats then upgrade at six and consume each reminder once`() {
        val ledger = RunLedger(maxRounds = 20)
        for (step in 1..2) {
            ledger.record(step, "run_shell", sameArgs, sameResult)
            assertNull(ledger.warning())
        }
        ledger.record(3, "run_shell", sameArgs, sameResult)
        val first = ledger.warning()
        assertNotNull(first)
        assertTrue(first.orEmpty().contains("3 次"))
        assertFalse(first.orEmpty().contains("升级"))
        assertNull(ledger.warning())
        for (step in 4..5) {
            ledger.record(step, "run_shell", sameArgs, sameResult)
            assertNull(ledger.warning())
        }
        ledger.record(6, "run_shell", sameArgs, sameResult)
        assertTrue(ledger.warning().orEmpty().contains("升级"))
        assertNull(ledger.warning())
        for (step in 7..8) ledger.record(step, "run_shell", sameArgs, sameResult)
        assertNull(ledger.warning())
    }

    @Test
    fun `unlimited runs use five and ten repeats without terminating or repeated reminders`() {
        val ledger = RunLedger(maxRounds = 0)
        for (step in 1..4) {
            ledger.record(step, "run_shell", sameArgs, sameResult)
            assertNull(ledger.warning())
        }
        ledger.record(5, "run_shell", sameArgs, sameResult)
        assertTrue(ledger.warning().orEmpty().contains("5 次"))
        assertNull(ledger.warning())
        for (step in 6..9) {
            ledger.record(step, "run_shell", sameArgs, sameResult)
            assertNull(ledger.warning())
        }
        ledger.record(10, "run_shell", sameArgs, sameResult)
        val upgraded = ledger.warning().orEmpty()
        assertTrue(upgraded.contains("升级"))
        assertTrue(upgraded.contains("10 次"))
        assertNull(ledger.warning())
        ledger.record(11, "run_shell", sameArgs, sameResult)
        assertNull(ledger.warning())
        assertTrue(ledger.summary().contains("11 次工具调用"))
    }

    @Test
    fun `key order numeric spelling and top level timeouts do not hide repeats but nested values still matter`() {
        val equivalent = listOf(
            """{"path":"/tmp/demo","values":{"a":1.0,"b":1000,"timeout":1},"items":[1,2],"timeout_seconds":10,"wait_ms":1}""",
            """{"timeout_ms":500,"items":[1,2],"values":{"timeout":1,"b":1e3,"a":1},"path":"/tmp/demo","deadline_ms":900}""",
            """{"values":{"b":1000.00,"a":1.00,"timeout":1},"path":"/tmp/demo","items":[1,2]}""",
        )
        val ledger = RunLedger(maxRounds = 30)
        equivalent.forEachIndexed { index, raw -> ledger.record(index + 1, "write_file", args(raw), sameResult) }
        assertNotNull(ledger.warning())
        assertNull(ledger.warning())

        val meaningful = RunLedger(maxRounds = 30)
        val originals = args("""{"values":{"timeout":1},"items":[1,2]}""")
        val reorderedArray = args("""{"values":{"timeout":1},"items":[2,1]}""")
        val changedNestedTimeout = args("""{"values":{"timeout":2},"items":[2,1]}""")
        meaningful.record(1, "write_file", originals, sameResult)
        meaningful.record(2, "write_file", reorderedArray, sameResult)
        meaningful.record(3, "write_file", changedNestedTimeout, sameResult)
        assertNull(meaningful.warning())
        meaningful.record(4, "write_file", changedNestedTimeout, sameResult)
        meaningful.record(5, "write_file", changedNestedTimeout, sameResult)
        assertNotNull(meaningful.warning())
    }

    @Test
    fun `changed results reset stagnant work and expected waiting or screen polling never warns`() {
        val ledger = RunLedger(maxRounds = 30)
        ledger.record(1, "run_shell", sameArgs, sameResult)
        ledger.record(2, "run_shell", sameArgs, sameResult)
        val progress = ToolResult.success("new result")
        ledger.record(3, "run_shell", sameArgs, progress)
        ledger.record(4, "run_shell", sameArgs, progress)
        assertNull(ledger.warning())
        ledger.record(5, "terminal_read", JsonObject(emptyMap()), ToolResult.success("waiting"))
        ledger.record(6, "terminal_read", JsonObject(emptyMap()), ToolResult.success("new output"))
        ledger.record(7, "run_shell", sameArgs, progress)
        ledger.record(8, "run_shell", sameArgs, progress)
        assertNull(ledger.warning())
        ledger.record(9, "run_shell", sameArgs, progress)
        assertNotNull(ledger.warning())

        val polling = RunLedger(maxRounds = 30)
        val polls = listOf("wait_agents" to JsonObject(emptyMap()), "terminal_read" to JsonObject(emptyMap()),
            "list_apps" to JsonObject(emptyMap()), "get_session_status" to JsonObject(emptyMap()),
            "ui_virtual_screen" to args("""{"action":"status"}"""),
            "ui_virtual_screen" to args("""{"action":"screenshot"}"""))
        polls.forEachIndexed { index, (tool, arguments) ->
            for (iteration in 1..7) polling.record(index * 7 + iteration, tool, arguments, sameResult)
        }
        assertNull(polling.warning())
    }

    @Test
    fun `a stopped run summary retains total successes and failures after the bounded history rolls over`() {
        val ledger = RunLedger(capacity = 2)
        assertEquals("", ledger.summary())
        assertEquals("", ledger.brief())
        val privateArgs = args("""{"content":"argument-secret"}""")
        ledger.record(1, "read_file", privateArgs, ToolResult.success("result-secret"))
        ledger.record(2, "write_file", privateArgs, ToolResult.failure("result-secret"))
        ledger.record(3, "run_shell", privateArgs, ToolResult.success("result-secret"))
        ledger.record(4, "edit_file", privateArgs, ToolResult.failure("result-secret"))
        val summary = ledger.summary()
        assertTrue(summary.contains("4 次工具调用"))
        assertTrue(summary.contains("2 次成功、2 次失败"))
        assertTrue(summary.contains("第 4 步的 edit_file"))
        val brief = ledger.brief()
        assertTrue(brief.contains("已尝试 4 次工具调用，失败 2 次"))
        assertFalse(brief.contains("read_file"))
        for (text in listOf(summary, brief)) {
            assertFalse(text.contains("argument-secret"))
            assertFalse(text.contains("result-secret"))
        }
    }
}
