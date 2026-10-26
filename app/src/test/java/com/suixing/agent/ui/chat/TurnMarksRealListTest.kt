package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 用**接近真实的一页转录**验证「当前读到第几轮」的整条换算：
 * 12 轮、每轮 2~3 行、总共 30 行，行高不等（思考块/工具行更高）。
 *
 * 目的：把 `probeFedIndex`（像素反查）→ `TurnMarks.current`（喂序→轮号）串起来跑，
 * 断言「探针所在的那一行」换算出来的轮号，必须等于那一行真实所属的轮。
 * 之前只测过 `current` 的边界，没有测过这两段接在一起的结果。
 */
class TurnMarksRealListTest {

    /** 一页 30 行：12 轮，每轮 = 用户行 + 1~2 条助手/工具行。 */
    private val rowsPerTurn = listOf(2, 3, 2, 3, 2, 3, 2, 3, 2, 3, 2, 3) // 合计 30
    private val rowHeightPx = listOf(180, 260, 420, 220, 300, 200, 380, 240, 260, 320, 200, 300,
        240, 180, 300, 260, 420, 220, 300, 200, 380, 240, 260, 320, 200, 300, 240, 180, 300, 260)

    /** 第 i 行属第几轮（1 基）。 */
    private fun turnOfRow(i: Int): Int {
        var acc = 0
        for ((turn, n) in rowsPerTurn.withIndex()) {
            acc += n
            if (i < acc) return turn + 1
        }
        return rowsPerTurn.size
    }

    /** 轮首行下标（旧→新），即 ChatScreen 里的 turnIndices。 */
    private fun turnStarts(): List<Int> {
        val out = ArrayList<Int>()
        var acc = 0
        for (n in rowsPerTurn) { out.add(acc); acc += n }
        return out
    }

    private fun fedIndexOfRow(row: Int, size: Int) = size - 1 - row

    @Test
    fun `探针行换算出它真实的轮号`() {
        val size = rowHeightPx.size
        val heights = rowHeightPx.toIntArray()
        val starts = turnStarts()
        val drawn = rowsPerTurn.size
        val total = drawn
        // 视口 2000px；从"完全贴底"到"往上翻一屏半"，逐档检查探针行与换算结果是否一致。
        for (offset in 0..3000 step 25) {
            val fed = TurnMarks.probeFedIndex(
                offsetFromBottom = offset,
                viewport = 2000,
                heightsOldestFirst = heights,
            )
            val probeRow = size - 1 - fed          // 喂序 → 可见行（旧→新）
            val expected = turnOfRow(probeRow)
            val actual = TurnMarks.current(
                firstVisibleFed = fed,
                feedPrefix = 0,
                visibleSize = size,
                rowOffset = 0,
                turnIndices = starts,
                drawnTurns = drawn,
                totalTurns = total,
            )
            assertEquals(
                "offset=$offset fed=$fed probeRow=$probeRow",
                expected,
                actual,
            )
        }
    }

    @Test
    fun `有加载更早那一行时 rowOffset 不能把轮号带偏`() {
        val size = rowHeightPx.size
        val heights = rowHeightPx.toIntArray()
        val starts = turnStarts().map { it + 1 }   // 含 rowOffset
        for (offset in 0..2000 step 25) {
            val fed = TurnMarks.probeFedIndex(offset, 2000, heights)
            val probeRow = size - 1 - fed
            val expected = turnOfRow(probeRow)
            val actual = TurnMarks.current(
                firstVisibleFed = fed,
                feedPrefix = 0,
                visibleSize = size,
                rowOffset = 1,
                turnIndices = starts,
                drawnTurns = rowsPerTurn.size,
                totalTurns = rowsPerTurn.size,
            )
            assertEquals("offset=$offset", expected, actual)
        }
    }

    @Test
    fun `已加载 12 轮但总轮数是 40 时，轮号要落到全局坐标`() {
        // 已加载最后 12 轮（全局 29~40）。探针在最后一行 ⇒ 全局第 40 轮。
        val size = rowHeightPx.size
        val heights = rowHeightPx.toIntArray()
        val starts = turnStarts()
        val fed = fedIndexOfRow(size - 1, size) // 最底那一行
        val actual = TurnMarks.current(
            firstVisibleFed = fed,
            feedPrefix = 0,
            visibleSize = size,
            rowOffset = 0,
            turnIndices = starts,
            drawnTurns = 12,
            totalTurns = 40,
        )
        assertEquals(40, actual)
    }

    @Test
    fun `内容不满一屏时当前轮必须是最新那轮`() {
        // 只有 2 轮、4 行，总高 900px < 视口 2000px ⇒ 探针线在内容之上方。
        val heights = intArrayOf(200, 250, 200, 250)
        val fed = TurnMarks.probeFedIndex(0, 2000, heights)
        val actual = TurnMarks.current(
            firstVisibleFed = fed,
            feedPrefix = 0,
            visibleSize = 4,
            rowOffset = 0,
            turnIndices = listOf(0, 2),
            drawnTurns = 2,
            totalTurns = 2,
        )
        assertEquals("不满一屏时应显示最新一轮", 2, actual)
    }
}
