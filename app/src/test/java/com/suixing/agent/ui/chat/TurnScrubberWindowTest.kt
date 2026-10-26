package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scrubber's window: which turns the strip draws, and how far a finger can pull it up.
 *
 * Two things are being pinned here. First, where a conversation opens — the newest
 * [SCRUBBER_VISIBLE_MIN] turns with nothing unlocked, which is the state every chat is reset to when
 * it is opened and the one the window is rebuilt from after every jump. Second, the unlock
 * itself: sliding to the top line brings the turns above the window within reach, monotonically,
 * so a drag that turns around does not lose what it just reached.
 */
class TurnScrubberWindowTest {

    private val pitchPx = 17f
    private val rowPx = 14f

    @Test
    fun `a conversation opens on the newest window and nothing unlocked`() {
        // 每一次进会话都要重新刷新该状态: the window is computed from turns/current with the
        // unlocked count a conversation starts with, which is none.
        val window = scrubberWindow(turns = 40, current = 40, unlocked = 0)

        assertEquals(35 until 40, window)
        assertEquals(SCRUBBER_VISIBLE_MIN, window.count())
    }

    @Test
    fun `the turn being read is always on the strip`() {
        for (turns in listOf(1, 6, 7, 20, 40)) {
            for (current in 1..turns) {
                val window = scrubberWindow(turns, current, unlocked = 0)
                assertTrue(
                    "turn $current of $turns missing from $window",
                    current - 1 in window,
                )
            }
        }
    }

    @Test
    fun `reading an older turn slides the window up to it`() {
        // Turn 8 of 40 is older than the newest six, so the window has left the newest turns.
        val window = scrubberWindow(turns = 40, current = 8, unlocked = 0)

        assertEquals(7 until 19, window)
        assertTrue(7 in window)
    }

    @Test
    fun `unlocking slides the window up by exactly that many turns`() {
        assertEquals(35 until 40, scrubberWindow(40, 40, unlocked = 0))
        assertEquals(34 until 39, scrubberWindow(40, 40, unlocked = 1))
        assertEquals(30 until 35, scrubberWindow(40, 40, unlocked = 5))
    }

    @Test
    fun `the window never slides past the first turn`() {
        val window = scrubberWindow(turns = 40, current = 40, unlocked = 99)

        assertEquals(0 until SCRUBBER_VISIBLE_MIN, window)
        assertEquals(SCRUBBER_VISIBLE_MIN, window.count())
    }

    @Test
    fun `the strip stays at five turns until the reader goes past them, then grows to twelve`() {
        // 用户定的规则：常驻 5 条；读到更早的轮次逐步解锁，最多 12 条。
        assertEquals(5, scrubberRowCount(turns = 40, current = 40))
        assertEquals(5, scrubberRowCount(turns = 40, current = 36))
        assertEquals(6, scrubberRowCount(turns = 40, current = 35))
        assertEquals(11, scrubberRowCount(turns = 40, current = 30))
        assertEquals(12, scrubberRowCount(turns = 40, current = 29))
        assertEquals(12, scrubberRowCount(turns = 40, current = 1))
        // 会话本身不足 5 轮就画实际条数
        assertEquals(3, scrubberRowCount(turns = 3, current = 1))
    }

    @Test
    fun `long press forces the strip to its widest`() {
        val window = scrubberWindow(turns = 40, current = 40, unlocked = 0, forceRows = SCRUBBER_VISIBLE_MAX)
        assertEquals(SCRUBBER_VISIBLE_MAX, window.count())
        assertEquals(28 until 40, window)
    }

    @Test
    fun `a negative unlock is read as none`() {
        assertEquals(
            scrubberWindow(40, 40, unlocked = 0),
            scrubberWindow(40, 40, unlocked = -3),
        )
    }

    @Test
    fun `a conversation shorter than the strip draws all of it`() {
        for (turns in 1..SCRUBBER_VISIBLE_MIN) {
            assertEquals(0 until turns, scrubberWindow(turns, turns, unlocked = 0))
        }
        assertEquals(IntRange.EMPTY, scrubberWindow(turns = 0, current = 1, unlocked = 0))
    }

    @Test
    fun `reaching the top line unlocks one turn`() {
        // The centre of the top line's own box.
        assertEquals(1, scrubberPull(y = rowPx / 2f, rowPx = rowPx, pitchPx = pitchPx, pull = 0))
        // Its bottom edge is still the top line.
        assertEquals(1, scrubberPull(y = rowPx, rowPx = rowPx, pitchPx = pitchPx, pull = 0))
    }

    @Test
    fun `one row of travel above the rail unlocks one more turn`() {
        assertEquals(2, scrubberPull(y = -pitchPx / 2f, rowPx = rowPx, pitchPx = pitchPx, pull = 0))
        assertEquals(3, scrubberPull(y = -2 * pitchPx, rowPx = rowPx, pitchPx = pitchPx, pull = 0))
    }

    @Test
    fun `a finger below the top line unlocks nothing new`() {
        assertEquals(4, scrubberPull(y = rowPx + 1f, rowPx = rowPx, pitchPx = pitchPx, pull = 4))
        assertEquals(4, scrubberPull(y = 500f, rowPx = rowPx, pitchPx = pitchPx, pull = 4))
    }

    @Test
    fun `what has been unlocked is not taken back by coming down again`() {
        val reached = scrubberPull(y = -3 * pitchPx, rowPx = rowPx, pitchPx = pitchPx, pull = 0)
        val back = scrubberPull(y = rowPx + 1f, rowPx = rowPx, pitchPx = pitchPx, pull = reached)

        assertEquals(4, reached)
        assertEquals(4, back)
    }
}
