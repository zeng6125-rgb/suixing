package com.suixing.agent.ui.drawer

import androidx.compose.material3.DrawerValue
import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerDragTargetTest {
    @Test fun `a drag caught mid-animation settles at the visible midpoint at low speed`() {
        for (percent in 0..100) {
            val drag = DrawerDragAccumulator()
            val generation = drag.begin(0.5f, 100f)
            drag.dragBy(generation, percent - 50f)
            val release = drag.finish(generation)!!
            for (speed in listOf(-399f, 0f, 399f)) {
                val expected = if (percent > 50) DrawerValue.Open else DrawerValue.Closed
                assertEquals(expected, drawerDragTarget(release.openByDistance, speed))
            }
        }
    }

    @Test fun `a fling at or above the threshold takes priority over distance`() {
        for (speed in listOf(400f, 1000f)) {
            assertEquals(DrawerValue.Closed, drawerDragTarget(true, -speed))
            assertEquals(DrawerValue.Open, drawerDragTarget(false, speed))
        }
    }
}
