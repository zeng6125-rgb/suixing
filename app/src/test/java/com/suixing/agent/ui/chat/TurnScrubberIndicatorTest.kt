package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnScrubberIndicatorTest {
    @Test fun `unchanged window keeps continuous animation without snapping`() {
        val rows = (1..12).toList()
        assertNull(scrubberIndicatorSnapRow(2, rows, 6, rows, 1f))
    }

    @Test fun `sliding window anchors indicator on previous turn new slot before gliding`() {
        val prevRows = (2..13).toList()
        val curRows = (3..14).toList()
        // Previous active turn 7 sits at index 4 in curRows; target turn 8 sits at index 5.
        assertEquals(4f, scrubberIndicatorSnapRow(7, prevRows, 8, curRows, 5f)!!, 0.001f)
    }

    @Test fun `loading earlier while anchored on same turn snaps cleanly onto that turn new slot`() {
        val prevRows = (13..24).toList()
        val curRows = (8..19).toList()
        // Current turn stayed 13: its slot moved from 0 to 5 inside curRows.
        assertEquals(5f, scrubberIndicatorSnapRow(13, prevRows, 13, curRows, 0f)!!, 0.001f)
    }

    @Test fun `large jump off previous window enters from the travel side`() {
        val prevRows = (1..12).toList()
        val curRows = (20..31).toList()
        assertEquals(0f, scrubberIndicatorSnapRow(3, prevRows, 25, curRows, 2f)!!, 0.001f)
        assertEquals(11f, scrubberIndicatorSnapRow(35, (25..36).toList(), 4, prevRows, 8f)!!, 0.001f)
    }
}
