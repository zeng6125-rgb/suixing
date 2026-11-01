package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextRevealStateTest {
    @Test fun `moving live prose into process retains characters already shown`() {
        val state = TextRevealState()
        state.startIfNeeded(true)
        state.advance(100)
        val shown = state.revealed
        assertTrue(shown in 1 until 100)
        state.startIfNeeded(true)
        assertEquals(shown, state.revealed)
        state.advance(100)
        assertTrue(state.revealed > shown)
    }

    @Test fun `completion continues the existing tail instead of replaying or skipping it`() {
        val state = TextRevealState()
        state.startIfNeeded(true)
        state.advance(100)
        val shown = state.revealed
        state.startIfNeeded(false)
        assertEquals(shown, state.revealed)
        var frames = 0
        while (state.revealed < 100 && frames++ < 100) {
            val before = state.revealed
            state.advance(100)
            assertTrue(state.revealed > before)
        }
        assertEquals(100, state.revealed)
    }

    @Test fun `historical text never starts an animation`() {
        val state = TextRevealState()
        state.startIfNeeded(false)
        assertFalse(state.everStreamed)
        assertFalse(state.startedTyping)
    }
}
