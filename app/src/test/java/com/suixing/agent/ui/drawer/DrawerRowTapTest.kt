package com.suixing.agent.ui.drawer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerRowTapTest {
    @Test fun `a normal row tap can open its conversation`() {
        assertTrue(drawerRowTapCanContinue(consumed = false, travel = 0f, touchSlop = 8f))
    }

    @Test fun `menu button consumed down cannot start a row tap`() {
        assertFalse(drawerRowTapCanContinue(consumed = true, travel = 0f, touchSlop = 8f))
    }

    @Test fun `child consuming the release cancels the pending row click`() {
        assertTrue(drawerRowTapCanContinue(consumed = false, travel = 0f, touchSlop = 8f))
        assertFalse(drawerRowTapCanContinue(consumed = true, travel = 0f, touchSlop = 8f))
    }

    @Test fun `child taking over movement cancels the row even below touch slop`() {
        assertFalse(drawerRowTapCanContinue(consumed = true, travel = 2f, touchSlop = 8f))
    }

    @Test fun `small unconsumed finger movement remains a tap`() {
        assertTrue(drawerRowTapCanContinue(consumed = false, travel = 2f, touchSlop = 8f))
        assertTrue(drawerRowTapCanContinue(consumed = false, travel = 8f, touchSlop = 8f))
    }

    @Test fun `dragging beyond touch slop stays with the list or drawer`() {
        assertFalse(drawerRowTapCanContinue(consumed = false, travel = 9f, touchSlop = 8f))
    }
}
