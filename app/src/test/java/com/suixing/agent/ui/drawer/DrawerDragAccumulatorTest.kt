package com.suixing.agent.ui.drawer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerDragAccumulatorTest {
    @Test
    fun `a new short stroke can leave either endpoint without lifting the finger`() {
        for (open in listOf(false, true)) {
            val direction = if (open) -1f else 1f
            for (returnToStart in listOf(false, true)) {
                val drag = DrawerDragAccumulator()
                val generation = drag.begin(if (open) 1f else 0f, 300f)
                drag.dragBy(generation, direction * 330f)
                if (returnToStart) drag.dragBy(generation, -direction * 330f)
                drag.dragBy(generation, if (returnToStart) direction * 90f else -direction * 90f)
                assertEquals(if (returnToStart) !open else open, drag.finish(generation)!!.openByDistance)
            }
        }
    }

    @Test
    fun `drag deltas accumulate from the captured progress instead of lagging animation state`() {
        val drag = DrawerDragAccumulator()
        val generation = drag.begin(progress = 0.25f, widthPx = 200f)

        assertEquals(0.35f, drag.dragBy(generation, 20f), 0.0001f)
        assertEquals(0.50f, drag.dragBy(generation, 30f), 0.0001f)
        assertEquals(0.45f, drag.dragBy(generation, -10f), 0.0001f)
    }

    @Test
    fun `progress is clamped at both drawer anchors`() {
        val drag = DrawerDragAccumulator()
        val generation = drag.begin(progress = 0.8f, widthPx = 100f)

        assertEquals(1f, drag.dragBy(generation, 100f), 0f)
        assertEquals(0f, drag.dragBy(generation, -250f), 0f)
    }

    @Test
    fun `reversing after overshooting either anchor moves immediately`() {
        val drag = DrawerDragAccumulator()
        val first = drag.begin(progress = 0f, widthPx = 300f)

        assertEquals(1f, drag.dragBy(first, 600f), 0f)
        assertEquals(290f / 300f, drag.dragBy(first, -10f), 0.0001f)

        assertNotNull(drag.finish(first))
        val second = drag.begin(progress = 1f, widthPx = 300f)

        assertEquals(0f, drag.dragBy(second, -600f), 0f)
        assertEquals(10f / 300f, drag.dragBy(second, 10f), 0.0001f)
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
        assertEquals(0.5f, drag.dragBy(second, 0f), 0f)
        assertTrue(second != first)
    }

    @Test
    fun `pointer cancellation invalidates only the drag that owns it`() {
        val drag = DrawerDragAccumulator()
        val canceledDrag = drag.begin(progress = 0.3f, widthPx = 200f)
        assertTrue(drag.cancel(canceledDrag))
        assertTrue(drag.dragBy(canceledDrag, 20f).isNaN())

        val currentDrag = drag.begin(progress = 0.6f, widthPx = 200f)
        assertFalse(drag.cancel(canceledDrag))
        assertEquals(0.6f, drag.dragBy(currentDrag, 0f), 0f)
        assertTrue(currentDrag != canceledDrag)
    }

    @Test
    fun `release rejects late pointer movement and returns the final position`() {
        val drag = DrawerDragAccumulator()
        val generation = drag.begin(progress = 0.2f, widthPx = 200f)
        drag.dragBy(generation, 40f)
        drag.dragBy(generation, 30f)
        val release = drag.finish(generation)!!

        assertEquals(0.55f, release.progress, 0.0001f)
        assertTrue(drag.dragBy(generation, 20f).isNaN())
        assertTrue(drag.canSettle(release))
    }

    @Test
    fun `a stale finish and a stale delta cannot kill the newer drag`() {
        val drag = DrawerDragAccumulator()
        val first = drag.begin(progress = 0.2f, widthPx = 200f)
        val second = drag.begin(progress = 0.5f, widthPx = 200f)

        // The older detector gets one last callback after the newer gesture began.
        assertTrue(drag.dragBy(first, 40f).isNaN())
        assertNull(drag.finish(first))

        assertEquals("the old finish must not deactivate the new drag", 0.6f, drag.dragBy(second, 20f), 0.0001f)
        val release = drag.finish(second)!!
        assertEquals(0.6f, release.progress, 0.0001f)
        assertTrue(drag.canSettle(release))
    }
}
