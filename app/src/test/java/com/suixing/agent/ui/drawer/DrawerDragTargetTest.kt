package com.suixing.agent.ui.drawer

import androidx.compose.material3.DrawerValue
import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerDragTargetTest {
    @Test fun `an open drawer closes after a deliberate slow swipe past a quarter of its width`() {
        assertEquals(DrawerValue.Closed, drawerDragTarget(true, 0.6f, 0f))
        assertEquals(DrawerValue.Closed, drawerDragTarget(true, 0.6f, -400f))
    }

    @Test fun `opening and closing have mirrored distance thresholds`() {
        for (percent in 0..100) {
            val travel = percent / 100f
            val closes = drawerDragTarget(true, 1f - travel, 0f) == DrawerValue.Closed
            val opens = drawerDragTarget(false, travel, 0f) == DrawerValue.Open
            assertEquals("equal travel $travel must have equal intent in both directions", opens, closes)
        }
    }

    @Test fun `small swipes and exact threshold retain the current anchor`() {
        assertEquals(DrawerValue.Open, drawerDragTarget(true, 0.9f, 0f))
        assertEquals(DrawerValue.Open, drawerDragTarget(true, 0.75f, 0f))
        assertEquals(DrawerValue.Closed, drawerDragTarget(false, 0.1f, 0f))
        assertEquals(DrawerValue.Closed, drawerDragTarget(false, 0.25f, 0f))
    }

    @Test fun `a fast swipe keeps priority over distance and the old anchor`() {
        for (wasOpen in listOf(false, true)) {
            assertEquals(DrawerValue.Closed, drawerDragTarget(wasOpen, 0.9f, -1_000f))
            assertEquals(DrawerValue.Open, drawerDragTarget(wasOpen, 0.1f, 1_000f))
        }
    }
}
