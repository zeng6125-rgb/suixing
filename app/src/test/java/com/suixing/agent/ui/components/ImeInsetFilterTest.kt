package com.suixing.agent.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ImeInsetFilterTest {
    @Test fun `opening and closing retain every pixel and reach zero`() {
        val filter = ImeInsetFilter()
        for (px in 0..100) assertEquals(px, filter.update(px, 100, false))
        for (px in 100 downTo 0) assertEquals(px, filter.update(px, 0, false))
    }

    @Test fun `rebound cannot overshoot the target or reverse an animation`() {
        val filter = ImeInsetFilter()
        assertEquals(90, filter.update(90, 100, false))
        assertEquals(90, filter.update(88, 100, false))
        assertEquals(100, filter.update(105, 100, false))
        assertEquals(100, filter.update(98, 100, false))
        assertEquals(95, filter.update(95, 0, false))
        assertEquals(95, filter.update(98, 0, false))
        assertEquals(0, filter.update(0, 0, false))
    }

    @Test fun `a quick reversal accepts the first pixel instead of waiting for a threshold`() {
        val filter = ImeInsetFilter()
        assertEquals(80, filter.update(80, 100, false))
        assertEquals(79, filter.update(79, 0, false))
        assertEquals(80, filter.update(80, 120, false))
    }

    @Test fun `selection freeze retains the inset then follows the current target`() {
        val filter = ImeInsetFilter()
        assertEquals(100, filter.update(100, 100, false))
        assertEquals(100, filter.update(300, 300, true))
        assertEquals(99, filter.update(99, 0, false))
    }
}
