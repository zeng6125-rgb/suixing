package com.suixing.agent.ui.drawer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerPointerVelocityTest {
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

    private fun sampleSwipe(localStep: Float, pointerStep: Float): Float {
        val tracker = VelocityTracker()
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
            ).withDrawerOriginalPosition(Offset(350f + pointerStep * sample, 0f)))
        }
        return tracker.calculateVelocity().x
    }
}

/**
 * Real input fills this internal field before HitPathTracker converts positions to node-local
 * coordinates. The public synthetic constructor leaves it at zero; Compose 1.12's native
 * velocity sampler reads it, so a fixture must provide it as the platform event processor does.
 */
internal fun PointerInputChange.withDrawerOriginalPosition(position: Offset): PointerInputChange {
    val field = PointerInputChange::class.java.getDeclaredField("originalEventPosition")
        .apply { isAccessible = true }
    val unbox = Offset::class.java.getDeclaredMethod("unbox-impl").apply { isAccessible = true }
    field.setLong(this, unbox.invoke(position) as Long)
    return this
}
