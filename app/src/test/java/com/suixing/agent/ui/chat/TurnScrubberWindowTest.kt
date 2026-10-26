package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航条的窗口：**全局轮号**（1 基，最新那轮 = drawnTurns）下的条数与窗口。
 *
 * 重构前的契约是"窗口内坐标"（`turns` = 窗口里画了几轮），后来发现同一个下标被当两种坐标用 ——
 * 条数按窗口轮数算、标签按全局轮号算、跳转按窗口下标算 —— 于是条数涨不上去、标签与行错位。
 * 现在统一成全局轮号：
 *  · [scrubberRowCount] 条数：常驻 5 条，读到更早的轮次逐步解锁，最多 12 条；
 *  · [scrubberWindow] 画哪几轮：窗口跟着当前轮走，当前轮必在窗口里。
 */
class TurnScrubberWindowTest {

    @Test
    fun `the strip shows five turns by default`() {
        assertEquals(5, scrubberRowCount(totalTurns = 40, currentTurn = 40))
        assertEquals(5, scrubberRowCount(totalTurns = 40, currentTurn = 36))
        // 读到更早的轮次 ⇒ 逐步解锁。
        assertEquals(6, scrubberRowCount(totalTurns = 40, currentTurn = 35))
        assertEquals(11, scrubberRowCount(totalTurns = 40, currentTurn = 30))
        assertEquals(12, scrubberRowCount(totalTurns = 40, currentTurn = 29))
        assertEquals(12, scrubberRowCount(totalTurns = 40, currentTurn = 1))
        // 会话本身不足 5 轮就画实际条数。
        assertEquals(3, scrubberRowCount(totalTurns = 3, currentTurn = 1))
    }

    @Test
    fun `the window ends at the newest turn and holds the current one`() {
        // 会话 40 轮、只加载了最后 12 轮（全局 29~40）⇒ 窗口是最新 5 条：36..40。
        val window = scrubberWindow(
            totalTurns = 40,
            currentTurn = 40,
            drawnTurns = 12,
        )
        assertEquals(5, window.count())
        assertEquals(40, window.last)
        assertEquals(36, window.first)
        assertTrue("当前轮必须在窗口里：$window", 40 in window)
    }

    @Test
    fun `reading an older loaded turn slides the window up to it`() {
        // 已加载 29~40；读到全局第 30 轮（距最新 10 轮，距最早还有 29 轮在盘上）⇒
        // 条数按**全局**距离解锁到 11 条（老实现传的是 `drawnTurns`，`drawn - current` 恒负
        // ⇒ 死锁在 5 条，「逐级解锁」对超过一页的会话从未生效），窗口仍不能越过已加载范围。
        val window = scrubberWindow(totalTurns = 40, currentTurn = 30, drawnTurns = 12)
        assertTrue("当前轮必须在窗口里：$window", 30 in window)
        assertEquals(11, window.count())
        // 窗口围着当前轮 30 往上长：29..39（已加载范围是 29~40，窗口不能越过它）。
        assertEquals(29, window.first)
        assertEquals(39, window.last)
    }

    @Test
    fun `the strip unlocks while earlier turns are still on disk`() {
        // **回归守卫**：分页会话（`drawnTurns < totalTurns`）里，越往回读条数越要多，
        // 不能被"已加载轮数"卡死；同时窗口一格都不能多画没加载的轮次。
        val near = scrubberWindow(totalTurns = 40, currentTurn = 36, drawnTurns = 12)
        val back = scrubberWindow(totalTurns = 40, currentTurn = 30, drawnTurns = 12)
        assertEquals(5, near.count())
        assertEquals(11, back.count())
        assertEquals(40 - 12 + 1, back.first)
        assertTrue("当前轮必须在窗口里：$back", 30 in back)
        for (current in 29..40) {
            val window = scrubberWindow(totalTurns = 40, currentTurn = current, drawnTurns = 12)
            assertTrue(
                "窗口画到了没加载的轮次：current=$current window=$window",
                window.first >= 29 && window.last <= 40,
            )
        }
    }

    @Test
    fun `the turn being read is always on the strip`() {
        // 当前轮必须在已加载范围内。
        for (drawn in listOf(1, 5, 6, 12, 40)) {
            val oldest = 40 - drawn + 1
            for (current in oldest..40) {
                val window = scrubberWindow(
                    totalTurns = 40,
                    currentTurn = current,
                    drawnTurns = drawn,
                )
                assertTrue(
                    "current $current of $drawn missing from $window",
                    current in window,
                )
            }
        }
    }

    @Test
    fun `unlocking slides the window up by exactly that many turns`() {
        val base = scrubberWindow(totalTurns = 40, currentTurn = 40, drawnTurns = 40, unlocked = 0)
        val one = scrubberWindow(totalTurns = 40, currentTurn = 40, drawnTurns = 40, unlocked = 1)
        val five = scrubberWindow(totalTurns = 40, currentTurn = 40, drawnTurns = 40, unlocked = 5)
        assertEquals(base.first - 1, one.first)
        assertEquals(base.first - 5, five.first)
        assertEquals(5, five.count())
    }

    @Test
    fun `the window never slides past the first turn`() {
        val window = scrubberWindow(
            totalTurns = 40,
            currentTurn = 40,
            drawnTurns = 40,
            unlocked = 99,
        )
        assertEquals(1, window.first)
    }

    @Test
    fun `a negative unlock is read as none`() {
        assertEquals(
            scrubberWindow(totalTurns = 40, currentTurn = 40, drawnTurns = 40, unlocked = 0),
            scrubberWindow(totalTurns = 40, currentTurn = 40, drawnTurns = 40, unlocked = -3),
        )
    }

    @Test
    fun `a conversation shorter than the strip draws all of it`() {
        for (turns in 1..SCRUBBER_VISIBLE_MIN) {
            // 全部轮次都在窗口里（会话比常驻条数还短）。
            val window = scrubberWindow(totalTurns = turns, currentTurn = turns, drawnTurns = turns)
            assertEquals(1, window.first)
            assertEquals(turns, window.last)
            assertEquals(turns, window.count())
        }
        assertEquals(
            IntRange.EMPTY,
            scrubberWindow(totalTurns = 0, currentTurn = 1, drawnTurns = 0),
        )
    }

    @Test
    fun `long press forces the strip to show every loaded turn`() {
        val window = scrubberWindow(
            totalTurns = 40,
            currentTurn = 40,
            drawnTurns = 12,
            forceRows = 12,
        )
        assertEquals(12, window.count())
        assertEquals(29, window.first)
        assertEquals(40, window.last)
    }
}
