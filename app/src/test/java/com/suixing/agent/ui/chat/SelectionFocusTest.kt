package com.suixing.agent.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionFocusTest {
    @Test fun `focus handover cannot be cleared by a late old owner callback`() {
        val tracker = SelectionFocusTracker()
        val old = Any()
        val next = Any()
        tracker.update(old, true)
        tracker.update(next, true)
        tracker.update(old, false)
        assertTrue(tracker.active)
        tracker.remove(next)
        assertFalse(tracker.active)
    }

    @Test fun `unrelated owners and repeated disposal do not invent selection focus`() {
        val tracker = SelectionFocusTracker()
        val owner = Any()
        tracker.update(owner, false)
        tracker.remove(owner)
        tracker.remove(owner)
        assertFalse(tracker.active)
    }
}
