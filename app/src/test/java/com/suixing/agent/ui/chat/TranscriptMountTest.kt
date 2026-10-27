package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptMountTest {
    @Test fun `same row count in another chat never reveals its unprepared content`() {
        assertEquals(0, mountedRowCount("new", "old", "old", false, 2, 2))
    }

    @Test fun `loading older keeps every resident row until the new prefix is ready`() {
        assertEquals(12, mountedRowCount("a", "a", "a", true, 12, 24))
        assertEquals(24, mountedRowCount("a", "a", "a", true, 24, 24))
    }

    @Test fun `new live rows do not hide the oldest resident row`() {
        assertEquals(13, mountedRowCount("a", "a", "a", false, 12, 13))
    }

    @Test fun `an incomplete first open is bounded by prepared rows`() {
        assertEquals(2, mountedRowCount("a", "a", "", false, 2, 12))
        assertEquals(0, mountedRowCount("a", "a", "", false, -1, 12))
    }
}
