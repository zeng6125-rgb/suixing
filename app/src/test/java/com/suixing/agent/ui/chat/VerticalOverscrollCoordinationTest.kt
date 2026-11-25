package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerticalOverscrollCoordinationTest {
    @Test fun `tiny sub-threshold leftover fling velocity produces zero bounce`() {
        assertEquals(0f, coordinateFlingLeftoverVelocityY(0f), 0.001f)
        assertEquals(0f, coordinateFlingLeftoverVelocityY(39f), 0.001f)
        assertEquals(0f, coordinateFlingLeftoverVelocityY(-39f), 0.001f)
        assertEquals(0f, coordinateFlingLeftoverVelocityY(Float.NaN), 0.001f)
        assertEquals(0f, coordinateFlingLeftoverVelocityY(Float.POSITIVE_INFINITY), 0.001f)
    }

    @Test fun `boundary fling leftover lifts moderate impacts above Miuix equilibrium cutoff`() {
        val downward = coordinateFlingLeftoverVelocityY(-200f)
        val springInitial = downward / MIUIX_FLING_VELOCITY_DIVISOR
        assertTrue(downward < 0f)
        assertTrue(springInitial <= -650f)

        val upward = coordinateFlingLeftoverVelocityY(2000f)
        assertEquals(2000f * 1.6f * MIUIX_FLING_VELOCITY_DIVISOR, upward, 0.5f)
    }

    @Test fun `extreme fling velocities are capped safely`() {
        val cappedPos = coordinateFlingLeftoverVelocityY(25_000f)
        val cappedNeg = coordinateFlingLeftoverVelocityY(-25_000f)
        assertEquals(9_500f * MIUIX_FLING_VELOCITY_DIVISOR, cappedPos, 0.5f)
        assertEquals(-9_500f * MIUIX_FLING_VELOCITY_DIVISOR, cappedNeg, 0.5f)
    }

    @Test fun `fling tracker holds active through spring kickoff then drops cleanly when idle`() {
        val tracker = OverscrollFlingTracker()
        tracker.armPendingFrames()
        // Frames 0..1 while Miuix spring coroutine has not updated offsetY yet
        assertTrue(tracker.step(delegateInProgress = false))
        assertTrue(tracker.step(delegateInProgress = false))
        // Frame 2: spring moves offsetY past threshold -> takes over and clears grace counter
        assertTrue(tracker.step(delegateInProgress = true))
        assertEquals(0, tracker.pendingFrames)
        // Settling finishes -> drops active immediately on next frame
        assertFalse(tracker.step(delegateInProgress = false))
    }

    @Test fun `fling tracker self-expires if no spring starts`() {
        val tracker = OverscrollFlingTracker()
        tracker.armPendingFrames(frames = 2)
        assertTrue(tracker.step(delegateInProgress = false))
        assertTrue(tracker.step(delegateInProgress = false))
        assertFalse(tracker.step(delegateInProgress = false))
    }
}
