package com.suixing.agent.service

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiOperationWindowFlagsTest {
    @Test fun visibleControlsUseHardwareRenderingWithoutTakingKeyboardFocus() {
        val flags = uiOperationWindowFlags(captureHidden = false, inputDepth = 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
        assertEquals(0, flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    }

    @Test fun captureAndNestedInputKeepHardwareRenderingWhileReleasingTouch() {
        for ((hidden, depth) in listOf(true to 0, false to 1, false to 2)) {
            val flags = uiOperationWindowFlags(hidden, depth)
            assertTrue(flags and WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED != 0)
            assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        }
    }
}
