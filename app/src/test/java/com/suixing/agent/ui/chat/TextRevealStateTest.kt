package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TextRevealStateTest {
    @Test fun `moving live prose into process retains characters already shown`() {
        val state = TextRevealState()
        state.startIfNeeded(true)
        state.advance(100)
        assertEquals(96, state.revealed)
        // A new composition group reconnects to this same run/part state.
        state.startIfNeeded(true)
        assertEquals(96, state.revealed)
        state.advance(100)
        assertEquals(98, state.revealed)
    }

    @Test fun `completion continues the existing tail instead of replaying or skipping it`() {
        val state = TextRevealState()
        state.startIfNeeded(true)
        state.advance(100)
        state.startIfNeeded(false)
        assertEquals(96, state.revealed)
        state.advance(100)
        assertEquals(98, state.revealed)
        state.advance(100)
        assertEquals(100, state.revealed)
    }

    @Test fun `historical text never starts an animation`() {
        val state = TextRevealState()
        state.startIfNeeded(false)
        assertFalse(state.everStreamed)
        assertFalse(state.startedTyping)
    }
}
