package com.suixing.agent.ui.chat

import org.junit.Assert.*
import org.junit.Test

class TypewriterBoundaryTest {
    @Test fun surrogatePairIsNeverPartiallyVisible() {
        val text = "A\uD83D\uDE00B"
        assertEquals(1, revealBoundary(text, 2))
        assertEquals(3, revealBoundary(text, 3))
        assertEquals(0, revealBoundary("\uD83D", 1))
    }

    @Test fun batchIsSpreadAcrossFramesButDrainsPromptly() {
        var remaining = 2000
        var frames = 0
        assertTrue(typeStep(remaining) < remaining / 2)
        while (remaining > 0 && frames < 100) {
            remaining = (remaining - typeStep(remaining)).coerceAtLeast(0)
            frames++
        }
        assertEquals(0, remaining)
        assertTrue(frames in 8..40)
    }
}
