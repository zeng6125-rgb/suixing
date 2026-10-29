package com.suixing.agent.ui.settings

import org.junit.Assert.*
import org.junit.Test

class FrameSamplesTest {
    @Test fun `incomplete windows and invalid intervals do not invent a measurement`() {
        val samples = FrameSamples()
        repeat(9) { samples.add(16_666_667) }
        samples.add(0)
        samples.add(-10)
        assertNull(samples.summary())
    }

    @Test fun `a full high refresh window is retained rather than truncated to sixty frames`() {
        val samples = FrameSamples()
        repeat(120) { samples.add(8_333_333) }
        assertEquals(FrameStat(120, 8), samples.summary())
    }

    @Test fun `p90 includes a significant slow tail across the entire sampling window`() {
        val samples = FrameSamples()
        repeat(20) { samples.add(40_000_000) }
        repeat(100) { samples.add(8_000_000) }
        assertEquals(FrameStat(75, 40), samples.summary())
    }

    @Test fun `bounded storage drops oldest intervals on wraparound`() {
        val samples = FrameSamples(capacity = 12)
        repeat(12) { samples.add(200_000_000) }
        repeat(12) { samples.add(10_000_000) }
        assertEquals(FrameStat(100, 10), samples.summary())
    }
}
