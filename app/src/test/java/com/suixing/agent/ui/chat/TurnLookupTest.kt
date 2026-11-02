package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnLookupTest {
    @Test
    fun `chronological lookup preserves global turns at every row boundary`() {
        for (offset in 0..1) {
            val starts = listOf(0, 2, 5, 6, 10).map { it + offset }
            for (row in -2..15) {
                val inWindow = starts.indexOfLast { it <= row }.coerceAtLeast(0)
                assertEquals("offset=$offset row=$row", 36 + inWindow,
                    TurnMarks.currentAtRow(row, starts, drawnTurns = 5, totalTurns = 40))
            }
        }
    }

    @Test
    fun `empty windows and duplicate starts retain the previous fallback`() {
        assertEquals(1, TurnMarks.currentAtRow(0, emptyList(), 0, 0))
        assertEquals(40, TurnMarks.currentAtRow(0, listOf(1), 0, 40))
        assertEquals(39, TurnMarks.currentAtRow(2, listOf(1, 2, 2, 5), 4, 40))
        assertEquals(40, TurnMarks.currentAtRow(Int.MAX_VALUE, listOf(1, 2, 5), 3, 40))
        assertEquals(38, TurnMarks.currentAtRow(Int.MIN_VALUE, listOf(1, 2, 5), 3, 40))
    }

    @Test
    fun `reading old turns uses logarithmic list accesses in a large loaded history`() {
        val starts = CountingStarts(10_000)
        for (row in listOf(-1, 0, 1, 2, 100, 9_999, 19_998, Int.MAX_VALUE)) {
            starts.reads = 0
            val expected = (row / 2 + 1).coerceIn(1, starts.size)
            assertEquals(expected, TurnMarks.currentAtRow(row, starts, starts.size, starts.size))
            assertTrue("row=$row read ${starts.reads} entries", starts.reads <= 15)
        }
    }

    private class CountingStarts(override val size: Int) : AbstractList<Int>() {
        var reads = 0
        override fun get(index: Int): Int {
            reads++
            return index * 2
        }
    }
}
