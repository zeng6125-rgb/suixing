package com.suixing.agent.ui.drawer

import androidx.compose.material3.DrawerValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerHomeShieldTest {

    @Test
    fun `opening and open both shield home`() {
        val opening = drawerMotionLifecycle(DrawerValue.Open)
        assertEquals(DrawerHomeLifecycle.Opening, opening)
        assertTrue(opening.shieldsHome)

        val open = drawerSettledLifecycle(DrawerValue.Open)
        assertEquals(DrawerHomeLifecycle.Open, open)
        assertTrue(open.shieldsHome)
    }

    @Test
    fun `closing and closed separate the transition from rest`() {
        val closing = drawerMotionLifecycle(DrawerValue.Closed)
        assertEquals(DrawerHomeLifecycle.Closing, closing)
        assertTrue(closing.shieldsHome)

        val closed = drawerSettledLifecycle(DrawerValue.Closed)
        assertEquals(DrawerHomeLifecycle.Closed, closed)
        assertFalse(closed.shieldsHome)
    }

    @Test
    fun `a released half drag and a reverse drag remain shielded`() {
        val dragging = DrawerHomeLifecycle.Dragging
        assertTrue(dragging.shieldsHome)
        assertTrue(drawerMotionLifecycle(DrawerValue.Open).shieldsHome)
        assertTrue(drawerSettledLifecycle(DrawerValue.Open).shieldsHome)

        val reverse = drawerMotionLifecycle(DrawerValue.Closed)
        assertEquals(DrawerHomeLifecycle.Closing, reverse)
        assertTrue(reverse.shieldsHome)
        assertFalse(drawerSettledLifecycle(DrawerValue.Closed).shieldsHome)
    }

    @Test
    fun `cancelling a half-open drag keeps home shielded`() {
        val exposed = drawerIdleLifecycle(0.4f)
        assertEquals(DrawerHomeLifecycle.Exposed, exposed)
        assertTrue(exposed.shieldsHome)

        val closed = drawerIdleLifecycle(0f)
        assertEquals(DrawerHomeLifecycle.Closed, closed)
        assertFalse(closed.shieldsHome)
    }

    @Test
    fun `a system back close stays shielded until settled closed`() {
        val back = drawerMotionLifecycle(DrawerValue.Closed)
        assertEquals(DrawerHomeLifecycle.Closing, back)
        assertTrue(back.shieldsHome)
        assertFalse(drawerSettledLifecycle(DrawerValue.Closed).shieldsHome)
    }

    @Test
    fun `the hit region starts at the panel width and excludes drawer rows`() {
        val panelWidth = 300f
        assertFalse(drawerHomeShieldContains(0f, panelWidth))
        assertFalse(drawerHomeShieldContains(299.9f, panelWidth))
        assertTrue(drawerHomeShieldContains(300f, panelWidth))
        assertTrue(drawerHomeShieldContains(300.1f, panelWidth))
    }

    @Test
    fun `the shield follows the panel edge while the drawer opens or closes`() {
        val panelWidth = 300f
        assertEquals(0f, drawerHomeShieldStartPx(panelWidth, 0f), 0f)
        assertEquals(75f, drawerHomeShieldStartPx(panelWidth, 0.25f), 0f)
        assertEquals(150f, drawerHomeShieldStartPx(panelWidth, 0.5f), 0f)
        assertEquals(300f, drawerHomeShieldStartPx(panelWidth, 1f), 0f)

        // The fixed-300dp version missed this whole strip at half open.
        val half = drawerHomeShieldStartPx(panelWidth, 0.5f)
        assertFalse(drawerHomeShieldContains(149.9f, half))
        assertTrue(drawerHomeShieldContains(150f, half))
        assertTrue(drawerHomeShieldContains(299.9f, half))
    }

    @Test
    fun `state keeps the shield through an owned drag and a cancelled half open`() = runBlocking {
        val state = SuixingDrawerState(DrawerValue.Closed)
        assertFalse(state.homeShieldActive)

        val session = state.beginGesture()
        assertTrue("an owned gesture counts even before progress moves", state.homeShieldActive)
        state.applyGestureFrame(session, 0.4f)
        assertTrue(state.homeShieldActive)

        assertTrue(state.cancelGesture(session))
        assertTrue("the leftover half-open panel still exposes home", state.homeShieldActive)
        assertFalse(state.isOpen)
    }

    @Test
    fun `state drops the shield only when snap closes the panel`() = runBlocking {
        val state = SuixingDrawerState(DrawerValue.Open)
        assertTrue(state.homeShieldActive)

        state.snapTo(DrawerValue.Closed)
        assertFalse(state.homeShieldActive)

        state.snapTo(DrawerValue.Open)
        assertTrue(state.homeShieldActive)
        state.progress.snapTo(1.05f)
        assertEquals(1f, state.visualProgress.value, 0f)
        state.progress.snapTo(-0.05f)
        assertEquals(0f, state.visualProgress.value, 0f)
    }
}
