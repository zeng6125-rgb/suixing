package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnScrubberWindowTest {

    @Test fun `navigation uses the same padded viewport probe as highlighting`() {
        assertEquals(-180, TurnMarks.navigationScrollOffset(-100, 900))
        assertEquals(180f, TurnMarks.probeOffset(-100, 900), 0.001f)
        assertEquals(-280, TurnMarks.navigationScrollOffset(0, 1000))
    }

    @Test fun `target stays on the highlighted side of a fractional probe`() {
        for (height in 1..1000) {
            val probe = TurnMarks.probeOffset(-100, height - 100)
            val placed = -TurnMarks.navigationScrollOffset(-100, height - 100)
            assertTrue(placed <= probe)
            assertTrue(probe - placed < 1f)
        }
    }

    @Test fun `a large top inset can put the probe before the content origin`() {
        assertEquals(72, TurnMarks.navigationScrollOffset(-100, 0))
    }
    @Test fun `rail has twelve slots for every current position`() {
        for (current in 1..40) {
            assertEquals(12, scrubberRowCount(40, current))
            assertEquals(12, scrubberSlots(scrubberWindow(40, current, 40)).size)
        }
    }

    @Test fun `short conversations show only existing turns without placeholders`() {
        assertEquals(listOf(1, 2, 3), scrubberSlots(scrubberWindow(3, 2, 3)))
        assertEquals(listOf(1), scrubberSlots(scrubberWindow(1, 1, 1)))
        assertEquals(emptyList<Int>(), scrubberSlots(IntRange.EMPTY))
    }

    @Test fun `newest window covers twelve consecutive loaded turns`() {
        assertEquals(29..40, scrubberWindow(40, 40, 12))
        assertEquals(29..40, scrubberWindow(40, 30, 12))
    }

    @Test fun `historical current turn leaves targets on both sides when available`() {
        assertEquals(14..25, scrubberWindow(40, 20, 40))
        assertEquals(1..12, scrubberWindow(40, 1, 40))
        assertEquals(29..40, scrubberWindow(40, 40, 40))
    }

    @Test fun `current turn always has exactly one real slot`() {
        for (drawn in listOf(1, 3, 6, 12, 40)) {
            for (current in (41 - drawn)..40) {
                val slots = scrubberSlots(scrubberWindow(40, current, drawn))
                assertEquals(minOf(12, drawn), slots.size)
                assertEquals(1, slots.count { it == current })
                assertTrue(slots.filter { it > 0 }.all { it in (41 - drawn)..40 })
            }
        }
    }

    @Test fun `pull slides twelve real targets without changing rail height`() {
        val base = scrubberWindow(40, 40, 40)
        val pulled = scrubberWindow(40, 40, 40, unlocked = 5)
        assertEquals(base.first - 5, pulled.first)
        assertEquals(12, pulled.count())
        assertEquals(1..12, scrubberWindow(40, 40, 40, unlocked = 99))
    }

    @Test fun `large loaded history cannot expand the twelve-slot rail`() {
        assertEquals(29..40, scrubberWindow(40, 40, 40))
    }

    @Test fun `unloaded turns never become navigation targets`() {
        assertEquals(listOf(39, 40), scrubberSlots(scrubberWindow(40, 40, 2)))
        assertEquals(IntRange.EMPTY, scrubberWindow(40, 40, 0))
        assertEquals(IntRange.EMPTY, scrubberWindow(0, 1, 0))
        assertEquals(scrubberWindow(40, 40, 40), scrubberWindow(40, 40, 40, unlocked = -3))
    }

    @Test fun `hit testing follows the actual short rail and clamps both ends`() {
        for (count in 1..12) {
            val slots = scrubberSlots(scrubberWindow(count, count, count))
            assertEquals(count, slots.size)
            for (row in slots.indices) {
                assertEquals(row, scrubberRowFor(4f + row * 11f, 8f, 11f, slots.size))
            }
            assertEquals(0, scrubberRowFor(-50f, 8f, 11f, slots.size))
            assertEquals(count - 1, scrubberRowFor(1000f, 8f, 11f, slots.size))
        }
    }

    @Test fun `normal physical row mapping includes earlier prefix once`() {
        val turns = listOf(1, 4, 7, 10)
        assertEquals(37, TurnMarks.currentAtRow(1, turns, 4, 40))
        assertEquals(38, TurnMarks.currentAtRow(5, turns, 4, 40))
        assertEquals(39, TurnMarks.currentAtRow(9, turns, 4, 40))
        assertEquals(40, TurnMarks.currentAtRow(10, turns, 4, 40))
        assertEquals(37, TurnMarks.currentAtRow(0, turns, 4, 40))
        assertEquals(40, TurnMarks.currentAtRow(99, turns, 4, 40))
        assertEquals(40, TurnMarks.currentAtRow(0, emptyList(), 0, 40))
    }
}
