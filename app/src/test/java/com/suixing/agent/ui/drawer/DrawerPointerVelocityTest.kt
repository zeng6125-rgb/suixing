package com.suixing.agent.ui.drawer

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.HistoricalChange
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerPointerVelocityTest {
    @Test fun `a reversal decision discards the earlier opening impulse`() {
        val tracker = DrawerPointerVelocity()
        val positions = listOf(100f, 140f, 180f, 220f, 216f, 212f, 208f, 204f)
        positions.forEachIndexed { index, x ->
            tracker.trackDrawerPointer(pointerChange(1L, index * 16L, x,
                if (index == 0) x else positions[index - 1], previousPressed = index != 0))
        }
        tracker.trackDrawerPointer(pointerChange(1L, 128L, 204f, 204f, pressed = false))

        val velocity = tracker.calculateVelocity().x
        assertTrue("64ms of closing movement must not retain an opening velocity: $velocity", velocity < 0f)
    }

    @Test fun `a left swipe keeps its closing velocity when the shield moves with the finger`() {
        val velocity = sampleSwipe(localStep = 0f, pointerStep = -20f)
        assertTrue("the moving shield must not erase a closing fling: $velocity", velocity < -800f)
    }

    @Test fun `a faster moving shield cannot reverse the finger velocity`() {
        val velocity = sampleSwipe(localStep = 10f, pointerStep = -20f)
        assertTrue("local positions move right but the finger still swipes left: $velocity", velocity < -800f)
    }

    @Test fun `a stationary host keeps the direction of opening and closing swipes`() {
        assertTrue(sampleSwipe(localStep = -20f, pointerStep = -20f) < -800f)
        assertTrue(sampleSwipe(localStep = 20f, pointerStep = 20f) > 800f)
    }

    @Test fun `a surviving pointer does not inherit the distance between two fingers`() {
        val tracker = DrawerPointerVelocity()
        tracker.trackDrawerPointer(pointerChange(1L, 0L, 300f, 300f, previousPressed = false))
        tracker.trackDrawerPointer(pointerChange(1L, 16L, 280f, 300f))
        tracker.trackDrawerPointer(pointerChange(1L, 32L, 260f, 280f))
        tracker.trackDrawerPointer(pointerChange(2L, 48L, 980f, 1_000f))
        tracker.trackDrawerPointer(pointerChange(2L, 64L, 960f, 980f))

        assertTrue("both fingers move left; changing fingers must not create an opening fling",
            tracker.calculateVelocity().x < -800f)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `batched motion retains the final acceleration`() {
        val tracker = DrawerPointerVelocity()
        tracker.trackDrawerPointer(pointerChange(1L, 0L, 100f, 100f, previousPressed = false))
        tracker.trackDrawerPointer(pointerChange(1L, 32L, 140f, 100f).copy(
            historical = listOf(HistoricalChange(16L, Offset(100f, 0f))),
        ))

        assertTrue("the final 40px movement happens in 16ms, not across the whole batch",
            tracker.calculateVelocity().x > 2_000f)
    }

    @Test fun `stationary horizontal moves discard the old fling on release`() {
        val tracker = DrawerPointerVelocity()
        tracker.trackDrawerPointer(pointerChange(1L, 0L, 100f, 100f, previousPressed = false))
        tracker.trackDrawerPointer(pointerChange(1L, 16L, 120f, 100f))
        for (time in listOf(32L, 48L, 64L)) {
            tracker.trackDrawerPointer(pointerChange(1L, time, 120f, 120f))
        }
        tracker.trackDrawerPointer(pointerChange(1L, 80L, 120f, 120f, pressed = false))

        assertEquals("64ms without horizontal movement must not retain an opening fling",
            0f, tracker.calculateVelocity().x, 0.0001f)
    }

    private fun pointerChange(
        id: Long,
        time: Long,
        x: Float,
        previousX: Float,
        previousPressed: Boolean = true,
        pressed: Boolean = true,
    ): PointerInputChange = PointerInputChange(
        id = PointerId(id),
        uptimeMillis = time,
        position = Offset(x, 0f),
        pressed = pressed,
        previousUptimeMillis = (time - 16L).coerceAtLeast(0L),
        previousPosition = Offset(previousX, 0f),
        previousPressed = previousPressed,
        isInitiallyConsumed = true,
    )

    private fun sampleSwipe(localStep: Float, pointerStep: Float): Float {
        val tracker = DrawerPointerVelocity()
        for (sample in 0..5) {
            val position = Offset(50f + localStep * sample, 0f)
            // Compose expresses both positions in the node's current coordinate system. The
            // delta is physical motion even when this event's local position matches the last.
            val previous = if (sample == 0) position else position - Offset(pointerStep, 0f)
            tracker.trackDrawerPointer(PointerInputChange(
                id = PointerId(1L),
                uptimeMillis = sample * 16L,
                position = position,
                pressed = true,
                previousUptimeMillis = ((sample - 1) * 16L).coerceAtLeast(0),
                previousPosition = previous,
                previousPressed = sample != 0,
                isInitiallyConsumed = true,
            ))
        }
        return tracker.calculateVelocity().x
    }
}
