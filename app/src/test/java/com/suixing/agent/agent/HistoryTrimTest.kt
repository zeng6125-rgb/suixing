package com.suixing.agent.agent

import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HistoryTrim] 的三条不变量。
 *
 * 它出错**不崩不报错**，只会静默改变"模型这一轮看到什么"：
 * 要么丢了最近的上下文（回答变差），要么把请求撑爆（超预算/多花钱），
 * 要么让转录以工具结果开头（端点直接 400）。
 */
class HistoryTrimTest {

    private fun user(id: String, text: String = "问题") =
        StoredMessage(id = id, role = Roles.USER, text = text)

    private fun answer(id: String, text: String = "回答") =
        StoredMessage(id = id, role = Roles.ASSISTANT, text = text)

    private fun tool(id: String) =
        StoredMessage(id = id, role = Roles.TOOL, text = "结果", toolName = "read_file")

    @Test
    fun `a non-positive budget keeps everything`() {
        val wire = listOf(user("u1"), answer("a1"))
        assertSame("预算 <= 0 表示不裁", wire, HistoryTrim.apply(wire, 0))
        assertSame(wire, HistoryTrim.apply(wire, -5))
    }

    @Test
    fun `an empty transcript is returned as is`() {
        val empty = emptyList<StoredMessage>()
        assertSame(empty, HistoryTrim.apply(empty, 1_000))
    }

    @Test
    fun `a transcript inside the budget is returned as the same instance`() {
        val wire = listOf(user("u1"), answer("a1"))
        assertSame("没裁东西就别复制", wire, HistoryTrim.apply(wire, 100_000))
    }

    @Test
    fun `the newest message is always sent however large it is`() {
        // 预算小到装不下最新那条：也必须发它（只留一条也好过留一堆旧的）。
        val huge = answer("a1", "x".repeat(50_000))
        val wire = listOf(user("u1"), huge)
        val out = HistoryTrim.apply(wire, 1)
        assertEquals("最新那条必须在", listOf("a1"), out.map { it.id })
    }

    @Test
    fun `trimming walks back from the end and keeps whole recent turns`() {
        val wire = listOf(
            user("u1", "a".repeat(2_000)), answer("a1", "b".repeat(2_000)),
            user("u2"), answer("a2"),
        )
        val out = HistoryTrim.apply(wire, 500)
        assertEquals("预算够装最后一轮", listOf("u2", "a2"), out.map { it.id })
    }

    @Test
    fun `the kept tail is moved forward to the first user message`() {
        // 真实形状：最后一条用户消息**太大**装不下，被裁掉了；后缀因此从 assistant 开始，
        // 但后缀后面还有用户消息 ⇒ 起点要挪到那条用户消息（以工具结果/助手开头会被端点拒绝）。
        val wire = listOf(
            user("u1", "问".repeat(3_000)),   // ~1000 tokens：装不下
            answer("a1", "答"),               // ~0 tokens
            user("u2", "问"),                 // ~0 tokens
            answer("a2", "答"),               // ~0 tokens
        )
        // 预算 50、u1 约 1000 tokens ⇒ 后缀是 [a1, u2, a2]；起点挪到 u2。
        val out = HistoryTrim.apply(wire, 50)
        assertEquals(listOf("u2", "a2"), out.map { it.id })
    }

    @Test
    fun `a suffix that would start mid turn keeps the whole suffix`() {
        // 后缀里没有任何 user 时无处可挪 —— 原样返回比丢光好；孤儿工具结果由 `WireSanitize` 收尾。
        val wire = listOf(answer("a1"), tool("t1"))
        assertEquals(listOf("a1", "t1"), HistoryTrim.apply(wire, 5).map { it.id })
    }

    @Test
    fun `a huge assistant answer is the one that gets cut from the front`() {
        // 让最后一条**用户消息**很大：预算装不下它，后缀只能从它后面的 assistant 开始，
        // 于是起点被挪到再往后的那条用户消息上。
        val wire = listOf(
            user("u1", "问".repeat(3_000)),
            answer("a1", "答".repeat(3_000)),
            user("u2", "问".repeat(3_000)),
            answer("a2", "答".repeat(3_000)),
        )
        // 预算只够 a2（1000 tokens 级）：kept=[a2]，没有 user ⇒ 原样返回 [a2]。
        val tight = HistoryTrim.apply(wire, 60)
        assertEquals(listOf("a2"), tight.map { it.id })
    }
}
