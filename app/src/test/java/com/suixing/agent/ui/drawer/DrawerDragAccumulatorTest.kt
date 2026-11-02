package com.suixing.agent.ui.drawer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerDragAccumulatorTest {
    @Test
    fun `drag deltas accumulate from the captured progress instead of lagging animation state`() {
        val drag = DrawerDragAccumulator()
        val generation = drag.begin(progress = 0.25f, widthPx = 200f)

        assertEquals(0.35f, drag.dragBy(generation, 20f)!!, 0.0001f)
        assertEquals(0.50f, drag.dragBy(generation, 30f)!!, 0.0001f)
        assertEquals(0.45f, drag.dragBy(generation, -10f)!!, 0.0001f)
    }

    @Test
    fun `progress is clamped at both drawer anchors`() {
        val drag = DrawerDragAccumulator()
        val generation = drag.begin(progress = 0.8f, widthPx = 100f)

        assertEquals(1f, drag.dragBy(generation, 100f)!!, 0f)
        assertEquals(0f, drag.dragBy(generation, -250f)!!, 0f)
    }

    @Test
    fun `reversing after overshooting either anchor moves immediately`() {
        val drag = DrawerDragAccumulator()
        val first = drag.begin(progress = 0f, widthPx = 300f)

        assertEquals(1f, drag.dragBy(first, 600f)!!, 0f)
        assertEquals(290f / 300f, drag.dragBy(first, -10f)!!, 0.0001f)

        assertNotNull(drag.finish(first))
        val second = drag.begin(progress = 1f, widthPx = 300f)

        assertEquals(0f, drag.dragBy(second, -600f)!!, 0f)
        assertEquals(10f / 300f, drag.dragBy(second, 10f)!!, 0.0001f)
    }

    @Test
    fun `a new drag invalidates a release still waiting to settle`() {
        val drag = DrawerDragAccumulator()
        val first = drag.begin(progress = 0.2f, widthPx = 200f)
        drag.dragBy(first, 60f)
        val queuedRelease = drag.finish(first)!!
        assertTrue(drag.canSettle(queuedRelease))

        // The previous settle has not acquired its mutation lock when a new drag starts.
        val second = drag.begin(progress = 0.5f, widthPx = 200f)

        assertFalse(drag.canSettle(queuedRelease))
        assertTrue(drag.canApply(drag.frame()!!))
        assertTrue(second != first)
    }

    @Test
    fun `pointer cancellation invalidates only the drag that owns it`() {
        val drag = DrawerDragAccumulator()
        val canceledDrag = drag.begin(progress = 0.3f, widthPx = 200f)
        val canceledFrame = drag.frame()!!

        assertTrue(drag.cancel(canceledDrag))
        assertFalse(drag.canApply(canceledFrame))
        assertNull(drag.frame())

        val currentDrag = drag.begin(progress = 0.6f, widthPx = 200f)
        assertFalse(drag.cancel(canceledDrag))
        assertTrue(drag.canApply(drag.frame()!!))
        assertTrue(currentDrag != canceledDrag)
    }

    @Test
    fun `release invalidates an already queued frame and returns the final pointer position`() {
        val drag = DrawerDragAccumulator()
        val generation = drag.begin(progress = 0.2f, widthPx = 200f)
        drag.dragBy(generation, 40f)
        val frameWaitingForTheMutationLock = drag.frame()!!
        assertTrue(drag.canApply(frameWaitingForTheMutationLock))

        // A final move arrives after the frame snapshot but before pointer-up.
        drag.dragBy(generation, 30f)
        val release = drag.finish(generation)!!

        assertEquals(0.55f, release.progress, 0.0001f)
        assertFalse("a stale frame must not snap after settle begins", drag.canApply(frameWaitingForTheMutationLock))
        assertTrue(drag.canSettle(release))
        assertNull(drag.frame())
    }

    @Test
    fun `a stale finish and a stale delta cannot kill the newer drag`() {
        val drag = DrawerDragAccumulator()
        val first = drag.begin(progress = 0.2f, widthPx = 200f)
        val second = drag.begin(progress = 0.5f, widthPx = 200f)

        // The older detector gets one last callback after the newer gesture began.
        assertNull(drag.dragBy(first, 40f))
        assertNull(drag.finish(first))

        assertEquals("the old finish must not deactivate the new drag", 0.6f, drag.dragBy(second, 20f)!!, 0.0001f)
        val release = drag.finish(second)!!
        assertEquals(0.6f, release.progress, 0.0001f)
        assertTrue(drag.canSettle(release))
        assertNull(drag.frame())
    }
}