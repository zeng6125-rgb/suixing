package com.suixing.agent.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PressFeedbackTest {
    @Test fun `a quick press and release confirms a click without waiting`() {
        val feedback = PressFeedback<Any>()
        val press = Any()
        feedback.press(press)
        assertTrue(feedback.release(press))
    }

    @Test fun `a drag cancellation prevents a late release from starting a ripple`() {
        val feedback = PressFeedback<Any>()
        val press = Any()
        feedback.press(press)
        feedback.cancel(press)
        assertFalse(feedback.release(press))
    }

    @Test fun `a release is handled only once`() {
        val feedback = PressFeedback<Any>()
        val press = Any()
        feedback.press(press)
        assertTrue(feedback.release(press))
        assertFalse(feedback.release(press))
    }

    @Test fun `teardown prevents an old release from starting a ripple`() {
        val feedback = PressFeedback<Any>()
        val press = Any()
        feedback.press(press)
        feedback.clear()
        assertFalse(feedback.release(press))
    }

    @Test fun `old release does not consume a newer press`() {
        val feedback = PressFeedback<Any>()
        val old = Any()
        val next = Any()
        feedback.press(old)
        feedback.press(next)
        assertFalse(feedback.release(old))
        assertTrue(feedback.release(next))
    }

    @Test fun `old cancel does not consume a newer press`() {
        val feedback = PressFeedback<Any>()
        val old = Any()
        val next = Any()
        feedback.press(old)
        feedback.press(next)
        feedback.cancel(old)
        assertTrue(feedback.release(next))
    }

    @Test fun `unmatched release cannot start a ripple`() {
        assertFalse(PressFeedback<Any>().release(Any()))
    }
}
