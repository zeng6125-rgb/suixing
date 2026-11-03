package com.suixing.agent.service

import android.view.View
import android.view.ViewPropertyAnimator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatBallTouchFeedbackTest {
    private val visual = mockk<View>(relaxed = true)
    private val animator = mockk<ViewPropertyAnimator>(relaxed = true)
    private val feedback = FloatBallTouchFeedback(visual)

    init {
        every { visual.animate() } returns animator
        every { animator.scaleX(any()) } returns animator
        every { animator.scaleY(any()) } returns animator
        every { animator.setDuration(any()) } returns animator
        every { animator.setInterpolator(any()) } returns animator
    }

    @Test fun `tap is ready immediately without waiting for its release animation`() {
        feedback.press()
        assertTrue(feedback.active)
        assertFalse(feedback.dragging)
        verify { visual.isPressed = true }
        verify { animator.scaleX(0.96f) }
        verify { animator.scaleY(0.96f) }

        assertTrue(feedback.release())
        assertFalse(feedback.active)
        assertFalse(feedback.release())
        verify(exactly = 1) { animator.scaleX(1f) }
        verify(exactly = 1) { animator.scaleY(1f) }
        verify { visual.isPressed = false }
    }

    @Test fun `drag releases compression once and cannot become a click`() {
        feedback.press()
        feedback.drag()
        feedback.drag()
        assertTrue(feedback.active)
        assertTrue(feedback.dragging)

        assertFalse(feedback.release())
        assertFalse(feedback.active)
        assertFalse(feedback.dragging)
        verify(exactly = 1) { animator.scaleX(1f) }
        verify(exactly = 1) { animator.scaleY(1f) }
        verify(exactly = 2) { animator.start() }
    }

    @Test fun `cancel releases a press and a late up cannot click`() {
        feedback.press()
        feedback.cancel()
        feedback.drag()

        assertFalse(feedback.active)
        assertFalse(feedback.dragging)
        assertFalse(feedback.release())
        verify(exactly = 1) { animator.scaleX(1f) }
        verify(exactly = 1) { animator.scaleY(1f) }
        verify { visual.isPressed = false }
    }

    @Test fun `detach reset cancels queued feedback and restores the visual immediately`() {
        feedback.press()
        feedback.reset()

        assertFalse(feedback.active)
        assertFalse(feedback.dragging)
        assertFalse(feedback.release())
        verify(exactly = 2) { animator.cancel() }
        verify { visual.isPressed = false }
        verify { visual.scaleX = 1f }
        verify { visual.scaleY = 1f }
        // Reset must not queue a release animation against a detached view.
        verify(exactly = 1) { animator.start() }

        feedback.press()
        assertTrue(feedback.release())
    }

    @Test fun `reset during drag recovery cancels it and prevents a stale click`() {
        feedback.press()
        feedback.drag()
        feedback.reset()

        assertFalse(feedback.active)
        assertFalse(feedback.dragging)
        assertFalse(feedback.release())
        verify(exactly = 3) { animator.cancel() }
        verify(exactly = 2) { animator.start() }
        verify { visual.scaleX = 1f }
        verify { visual.scaleY = 1f }
    }

    @Test fun `a new press retargets recovery without snapping the current scale`() {
        feedback.press()
        assertTrue(feedback.release())
        feedback.press()

        assertTrue(feedback.active)
        verify(exactly = 3) { animator.cancel() }
        verify(exactly = 3) { animator.start() }
        verify(exactly = 2) { animator.scaleX(0.96f) }
        verify(exactly = 0) { visual.scaleX = any() }
        verify(exactly = 0) { visual.scaleY = any() }
    }
}
