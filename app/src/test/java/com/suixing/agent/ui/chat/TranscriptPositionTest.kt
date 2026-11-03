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

    @Test fun `entrance fades monotonically at the restored reading position`() {
        assertEquals(0f, ConversationEntrance.alpha(0f), 0.0001f)
        assertEquals(1f, ConversationEntrance.alpha(1f), 0.0001f)
        // The shared clock now controls only opacity; there is no visible spatial settle.
        assertEquals(0.5f, ConversationEntrance.alpha(0.5f), 0.0001f)
        val opacity = (0..100).map { ConversationEntrance.alpha(it / 100f) }
        assertTrue(opacity.all { it in 0f..1f })
        assertTrue(opacity.zipWithNext().all { (before, after) -> after >= before })
    }
}
