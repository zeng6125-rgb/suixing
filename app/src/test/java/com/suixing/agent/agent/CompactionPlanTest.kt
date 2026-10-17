package com.suixing.agent.agent

import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩的公共切法：尾部保留原文、切点不落在 tool 消息上、工具输出在摘要请求里只取前 2000 字。
 * run 内压缩与无 run 的手动压缩都用它，所以这套边界必须被测住。
 */
class CompactionPlanTest {

    private fun msg(id: String, role: String, text: String = "x") = StoredMessage(id = id, role = role, text = text)

    private fun history(count: Int): List<StoredMessage> = (1..count).map { msg("m$it", Roles.USER, "第 $it 条") }

    @Test
    fun `short history has nothing to compact`() {
        assertNull(planCompaction(history(5)))
    }

    @Test
    fun `the newest messages are never folded`() {
        val plan = planCompaction(history(20), keepRecent = 6, minMessages = 8)!!

        assertEquals("尾部 6 条要留在原文里", 14, plan.coveredIds.size)
        assertTrue("最后一条不能被折进去", "m20" !in plan.coveredIds)
        assertEquals("m1", plan.coveredIds.first())
        assertEquals("m14", plan.coveredIds.last())
    }

    @Test
    fun `the cut never starts on a tool message`() {
        // 让第 15 条（下标 14）是 tool：cut=14 会落在它上面，必须往前挪。
        val rows = history(20).toMutableList()
        rows[14] = msg("m15", Roles.TOOL)

        val plan = planCompaction(rows, keepRecent = 6, minMessages = 8)!!

        assertEquals("切点要挪到 tool 之前", "m13", plan.coveredIds.last())
    }

    @Test
    fun `a tool result is shortened in the summary request`() {
        val rows = history(12).toMutableList()
        rows[2] = StoredMessage(
            id = "m3",
            role = Roles.TOOL,
            text = "",
            toolName = "ui_dump",
            toolOutput = "y".repeat(5000),
        )

        val plan = planCompaction(rows, keepRecent = 2, minMessages = 4)!!

        assertTrue("工具输出只取前 2000 字", plan.transcript.contains("y".repeat(2000)))
        assertTrue("要说明原始长度", plan.transcript.contains("共 5000 字符"))
        assertTrue("不能把整段都塞进去", !plan.transcript.contains("y".repeat(3000)))
    }

    @Test
    fun `the folded count is what the card reports`() {
        val plan = planCompaction(history(30), keepRecent = 6, minMessages = 8)!!

        assertEquals(24, plan.foldedMessages)
        assertEquals(plan.foldedMessages, plan.coveredIds.size)
    }
}
