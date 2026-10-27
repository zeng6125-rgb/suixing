package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeGeometryTest {
    @Test fun `probe belongs to the preceding row including gaps`() {
        val tops = floatArrayOf(-30f, 100f, 240f)
        for (probe in -40..300) {
            assertEquals(tops.indexOfLast { it <= probe }, probeRowIndex(tops.size, probe.toFloat()) { tops[it] })
        }
    }

    @Test fun `unfilled rows at the beginning do not count as the current row`() {
        val tops = floatArrayOf(Float.NaN, Float.NaN, 0f, 50f)
        assertEquals(-1, probeRowIndex(tops.size, -1f) { tops[it] })
        assertEquals(2, probeRowIndex(tops.size, 20f) { tops[it] })
        assertEquals(3, probeRowIndex(tops.size, 50f) { tops[it] })
    }

    @Test fun `thousands of rows need only logarithmic coordinate reads`() {
        var reads = 0
        assertEquals(4_321, probeRowIndex(10_000, 43_215f) { reads++; it * 10f })
        assertTrue(reads <= 15)
    }

    @Test fun `reverse layout jump moves the chosen top exactly onto the probe`() {
        for (top in listOf(-1_000f, 0f, 400f)) {
            assertEquals(200f, top + probeJumpDelta(top, 200f), 0f)
        }
    }
}
