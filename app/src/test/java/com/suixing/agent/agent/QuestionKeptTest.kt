package com.suixing.agent.agent

import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.StoredToolCall
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **预算裁剪之后，请求里必须还有人问的那句话**。
 *
 * 可达链条（不是假想）：
 * 1. 一轮里工具输出极大（例如一次 `ui_dump` 几十万字符）；
 * 2. `HistoryTrim` 只留下最新那一条（它"无论多大都发"）；
 * 3. 那条是 `tool` 结果，而它的 `assistant`（带 tool_calls）已经被裁掉；
 * 4. `WireSanitize` 把孤儿工具结果丢掉 ⇒ **wire 里只剩 checkpoint 摘要**（system），
 *    模型看不到用户的问题，回答会跑题。
 *
 * 这里锁的是"裁剪之后仍能找回那句问话"，而不是"不裁剪"。
 */
class QuestionKeptTest {

    private fun user(id: String, text: String) = StoredMessage(id = id, role = Roles.USER, text = text)
    private fun askWithTool(id: String, callId: String) = StoredMessage(
        id = id, role = Roles.ASSISTANT, text = "",
        toolCalls = listOf(StoredToolCall(id = callId, name = "ui_dump", arguments = "{}")),
    )
    private fun hugeToolResult(id: String, callId: String) = StoredMessage(
        id = id, role = Roles.TOOL, text = "x".repeat(400_000), toolCallId = callId, toolName = "ui_dump",
    )

    @Test
    fun `the question survives even when a huge tool result is all that fits`() {
        val history = listOf(
            user("u1", "帮我看下这个界面"),
            askWithTool("a1", "c1"),
            hugeToolResult("t1", "c1"),
        )
        val wire = HistoryTrim.apply(history, budget = 1)
        assertTrue("前提：预算 1 时只剩最新那条（工具结果）", wire.none { it.role == Roles.USER })

        val kept = ensureQuestionKept(wire, history)
        assertTrue("裁剪之后必须仍有人问的那句话", kept.any { it.role == Roles.USER })
    }

    @Test
    fun `a wire that already has a question is returned as is`() {
        val history = listOf(user("u1", "问题"), StoredMessage(id = "a1", role = Roles.ASSISTANT, text = "回答"))
        val wire = HistoryTrim.apply(history, budget = 1_000)
        assertTrue(ensureQuestionKept(wire, history).map { it.id } == wire.map { it.id })
    }

    @Test
    fun `no user anywhere means nothing is invented`() {
        val history = listOf(StoredMessage(id = "a1", role = Roles.ASSISTANT, text = "只有回答"))
        val wire = HistoryTrim.apply(history, budget = 1)
        // 历史上确实没有用户消息 ⇒ 什么都不补（**不能凭空造一条问题**）。
        assertEquals(listOf("a1"), ensureQuestionKept(wire, history).map { it.id })
    }
}
