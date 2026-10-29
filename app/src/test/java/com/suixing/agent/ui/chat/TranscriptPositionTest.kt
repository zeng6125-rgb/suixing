package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptPositionTest {
    @Test fun `repeated phrases keep the actual reading occurrence`() {
        val text = "repeat ".repeat(100)
        assertEquals(350, readingCharacterOffset(text, 350, "repeat "))
    }

    @Test fun `formatting changes resolve to the nearest matching text`() {
        val text = "read ".repeat(100)
        assertEquals(250, readingCharacterOffset(text, 252, "read "))
        assertEquals(5, readingCharacterOffset("text read here", 9, "read"))
    }

    @Test fun `removed text falls back within the new text bounds`() {
        assertEquals(4, readingCharacterOffset("text", 200, "missing"))
        assertEquals(0, readingCharacterOffset("", 200, "missing"))
    }

    @Test fun `reading can cross the previous lazy item boundary`() {
        assertEquals(-25, readingScrollOffset(5, -30))
        assertEquals(Int.MAX_VALUE, readingScrollOffset(Int.MAX_VALUE, 30))
    }

    @Test fun `entrance settles monotonically without overshooting and fades early`() {
        assertEquals(1f, ConversationEntrance.remainingTravel(0f), 0.0001f)
        assertEquals(0f, ConversationEntrance.remainingTravel(1f), 0.0001f)
        assertEquals(1f, ConversationEntrance.alpha(1f / 3f), 0.0001f)
        val positions = (0..100).map { ConversationEntrance.remainingTravel(it / 100f) }
        assertTrue(positions.all { it in 0f..1f })
        assertTrue(positions.zipWithNext().all { (before, after) -> after <= before })
    }
}
