package com.suixing.agent.data

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `rewrite` 必须**重算统计**（评审 S1）。
 *
 * 五个水位字段里 `totals` / `totalsAt` 原来是唯一没跟着走的两个。后果不是丢数据，而是：
 * - 改写后条数**恰好相等**（同长编辑）时，`sessionTotals` 认为统计仍有效 ⇒ 用**过期值**；
 * - 条数不等时，它又要整份 `store.all` 重扫一遍（30MB 会话可感知）。
 */
class RewriteTotalsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val chatsDir: File get() = File(folder.root, "chats")
    private val store: ChatStore get() = ChatStore(chatsDir)

    private fun messages(n: Int, prefix: String = "m") = (1..n).map { i ->
        StoredMessage(
            id = "$prefix$i",
            role = if (i % 2 == 1) Roles.USER else Roles.ASSISTANT,
            text = "第 $i 条",
            promptTokens = 10,
            completionTokens = 5,
        )
    }

    @Test
    fun `rewriting refreshes the tally instead of keeping the old one`() {
        val first = messages(6)
        val meta = store.append(store.newMeta(Conversation(
            id = "c1", title = "t", createdAt = 1L, updatedAt = 1L, messages = first,
        )), first)
        val before = store.readMeta("c1")!!
        assertEquals("前提：初始统计是全量的", 6, before.totalsAt)

        // 同长改写：条数不变，但内容（与 token）都换了 —— 这一形状最容易让"过期统计"蒙混过关。
        val replaced = messages(6, prefix = "n").map { it.copy(promptTokens = 100) }
        val after = store.rewrite(before, replaced)

        assertEquals("条数没变", 6, after.messageCount)
        assertEquals("统计必须重算（不能留旧的）", 600, after.totals.promptTokens)
        assertEquals("并且统计口径与条数对齐", after.messageCount, after.totalsAt)
    }

    @Test
    fun `a shorter rewrite also refreshes the tally`() {
        val first = messages(8)
        val meta = store.append(store.newMeta(Conversation(
            id = "c2", title = "t", createdAt = 1L, updatedAt = 1L, messages = first,
        )), first)
        val before = store.readMeta("c2")!!
        val after = store.rewrite(before, first.take(2))

        assertEquals(2, after.messageCount)
        assertEquals("缩短之后统计必须跟着缩短", 2, after.totalsAt)
        assertEquals(20, after.totals.promptTokens)
    }
}
