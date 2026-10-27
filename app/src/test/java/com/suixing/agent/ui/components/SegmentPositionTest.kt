package com.suixing.agent.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.roundToInt

class SegmentPositionTest {
    @Test fun `two choices follow the pointer continuously between centers`() {
        assertEquals(0f, segmentPositionAt(50f, 200f, 2, false)!!, 0f)
        assertEquals(0.5f, segmentPositionAt(100f, 200f, 2, false)!!, 0f)
        assertEquals(1f, segmentPositionAt(150f, 200f, 2, false)!!, 0f)
    }

    @Test fun `release selects the nearest of three choices`() {
        assertEquals(0, segmentPositionAt(60f, 300f, 3, false)!!.roundToInt())
        assertEquals(1, segmentPositionAt(150f, 300f, 3, false)!!.roundToInt())
        assertEquals(2, segmentPositionAt(280f, 300f, 3, false)!!.roundToInt())
    }

    @Test fun `rtl reverses visual position without reversing option identity`() {
        assertEquals(2f, segmentPositionAt(50f, 300f, 3, true)!!, 0f)
        assertEquals(1f, segmentPositionAt(150f, 300f, 3, true)!!, 0f)
        assertEquals(0f, segmentPositionAt(250f, 300f, 3, true)!!, 0f)
    }

    @Test fun `two choice midpoint follows switch direction in both layouts`() {
        assertEquals(0.48f, segmentPositionAt(98f, 200f, 2, false)!!, 0.0001f)
        assertEquals(0.52f, segmentPositionAt(102f, 200f, 2, false)!!, 0.0001f)
        assertEquals(0.52f, segmentPositionAt(98f, 200f, 2, true)!!, 0.0001f)
        assertEquals(0.48f, segmentPositionAt(102f, 200f, 2, true)!!, 0.0001f)
    }

    @Test fun `dragging outside the track clamps to the end choices`() {
        assertEquals(0f, segmentPositionAt(-40f, 200f, 2, false)!!, 0f)
        assertEquals(1f, segmentPositionAt(240f, 200f, 2, false)!!, 0f)
        assertEquals(1f, segmentPositionAt(-40f, 200f, 2, true)!!, 0f)
    }

    @Test fun `a single choice stays in place`() {
        assertEquals(0f, segmentPositionAt(80f, 100f, 1, false)!!, 0f)
        assertEquals(0f, segmentPositionAt(80f, 100f, 1, true)!!, 0f)
    }

    @Test fun `empty or unmeasured controls have no selection`() {
        assertNull(segmentPositionAt(10f, 200f, 0, false))
        assertNull(segmentPositionAt(10f, 0f, 2, false))
        assertNull(segmentPositionAt(Float.NaN, 200f, 2, false))
        assertNull(segmentPositionAt(10f, Float.POSITIVE_INFINITY, 2, false))
    }
}
