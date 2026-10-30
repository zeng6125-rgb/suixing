package com.suixing.agent.ui.chat

import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩记录挂行（`placeCheckpoints`）的等价性测试。
 *
 * 这段逻辑原来内联在 `ChatScreen` 的组合体里，本轮抽成纯函数并把记忆键收窄到「行形状」；
 * 对抗评审指出初版有**三处不满足等价**，这里逐条守住：
 * 1. 消息 id 优先于轮次 id（合并成一张表会把优先级弄反）；
 * 2. 锚点非空但没命中时**不回退**到 `toMessageId`；
 * 3. 夹具必须满足生产不变式 —— `rounds.last().id == message.id`（对账见 `ChatRows.kt` 的
 *    `rounds: List<StoredMessage> = listOf(message)`）。
 */
class CheckpointPlacementTest {

    private fun msg(id: String) = StoredMessage(
        id = id,
        role = Roles.ASSISTANT,
        text = id,
        runId = id,
    )

    /** 生产形态：`message` 是最后一轮，`rounds` 是这一行折叠的全部轮次。 */
    private fun row(vararg ids: String): ChatRow {
        require(ids.isNotEmpty()) { "生产里一行至少一条消息" }
        val rounds = ids.map(::msg)
        return ChatRow(message = rounds.last(), showTimeLabel = false, rounds = rounds)
    }

    private fun checkpoint(
        id: String,
        anchor: String = "",
        to: String = "",
    ) = ContextCheckpoint(
        id = id,
        at = 0L,
        fromMessageId = "",
        toMessageId = to,
        summary = "s",
        messages = 1,
        promptTokens = 0,
        anchorId = anchor,
    )

    @Test
    fun `锚点命中消息本身时挂在那一行`() {
        val rows = listOf(row("r1"), row("r2"), row("r3"))
        val placed = placeCheckpoints(rows, listOf(checkpoint("c1", anchor = "r2")))
        assertEquals(listOf("r2"), placed.keys.toList())
        assertEquals(listOf("c1"), placed.getValue("r2").map { it.id })
    }

    @Test
    fun `没有 anchorId 时回落到 toMessageId`() {
        val rows = listOf(row("r1"), row("r2"))
        val placed = placeCheckpoints(rows, listOf(checkpoint("c1", to = "r1")))
        assertEquals(setOf("r1"), placed.keys)
    }

    @Test
    fun `锚点优先于 toMessageId`() {
        // 两个都非空且都命中不同行 ⇒ 必须按锚点。初版用例里两者从不同时非空，
        // 把实现写成 `toMessageId 优先` 也能全绿（评审指出的假通过）。
        val rows = listOf(row("r1"), row("r2"))
        val placed = placeCheckpoints(rows, listOf(checkpoint("c1", anchor = "r2", to = "r1")))
        assertEquals(listOf("r2"), placed.keys.toList())
    }

    @Test
    fun `锚点非空但没命中时不回退到 toMessageId`() {
        // 回退会把卡片挂到 r2；不回退则当孤儿挂到本页最旧一行（r1）。
        val rows = listOf(row("r1"), row("r2"))
        val placed = placeCheckpoints(rows, listOf(checkpoint("c1", anchor = "gone", to = "r2")))
        assertEquals(listOf("r1"), placed.keys.toList())
    }

    @Test
    fun `锚点是某一行的旧轮次时仍挂在这一行`() {
        val rows = listOf(row("r1"), row("old1", "newest"))
        val placed = placeCheckpoints(rows, listOf(checkpoint("c1", anchor = "old1")))
        assertEquals(listOf("newest"), placed.keys.toList())
    }

    @Test
    fun `跨行重复 id 时消息 id 优先于轮次 id`() {
        // 历史转录里存在重复 message id（仓库按"两次独立出现"处理）。第 0 行的消息 id 是 X，
        // 第 1 行把 X 当作自己的旧轮次 ⇒ 锚点 X 必须归到第 0 行（消息 id 优先）。
        // 把两张表合并成一张会挂到第 1 行（评审给了这个反例）。
        val rows = listOf(row("X"), row("X", "Y"))
        val placed = placeCheckpoints(rows, listOf(checkpoint("c1", anchor = "X")))
        assertEquals(listOf("X"), placed.keys.toList())
    }

    @Test
    fun `锚点落在窗口外时挂到最旧一行`() {
        val rows = listOf(row("r1"), row("r2"))
        val placed = placeCheckpoints(rows, listOf(checkpoint("c1", anchor = "gone")))
        assertEquals(listOf("r1"), placed.keys.toList())
        assertEquals(listOf("c1"), placed.getValue("r1").map { it.id })
    }

    @Test
    fun `同一行下多条记录保持输入顺序`() {
        val rows = listOf(row("r1"))
        val placed = placeCheckpoints(
            rows,
            listOf(checkpoint("c1", anchor = "r1"), checkpoint("c2", anchor = "r1")),
        )
        assertEquals(listOf("c1", "c2"), placed.getValue("r1").map { it.id })
    }

    @Test
    fun `窗口外的记录排在该行已有记录之后`() {
        // 改前是 `placed[id] = placed[id].orEmpty() + orphanToTop`，顺序不能反。
        val rows = listOf(row("r1"), row("r2"))
        val placed = placeCheckpoints(
            rows,
            listOf(checkpoint("in", anchor = "r1"), checkpoint("out", anchor = "nowhere")),
        )
        assertEquals(listOf("in", "out"), placed.getValue("r1").map { it.id })
    }

    @Test
    fun `整行消息 id 重复时后者覆盖前者`() {
        // 与改前 `groupBy().mapKeys{}` 的覆盖语义一致：不合并、不多画卡片。
        val rows = listOf(row("dup"), row("dup"))
        val placed = placeCheckpoints(rows, listOf(checkpoint("c1", anchor = "dup")))
        assertEquals(1, placed.size)
        assertEquals(listOf("c1"), placed.getValue("dup").map { it.id })
    }

    @Test
    fun `空输入不建表`() {
        assertTrue(placeCheckpoints(emptyList(), listOf(checkpoint("c1", anchor = "r1"))).isEmpty())
        assertTrue(placeCheckpoints(listOf(row("r1")), emptyList()).isEmpty())
    }
}
