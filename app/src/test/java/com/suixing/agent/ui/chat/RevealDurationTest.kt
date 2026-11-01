package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RevealDurationTest {
    @Test
    fun shortBlocksKeepTheirExistingDurations() {
        assertEquals(220, scaledRevealDurationMs(220, 0))
        assertEquals(253, scaledRevealDurationMs(220, 120))
        assertEquals(330, scaledRevealDurationMs(220, 400))

        assertEquals(220, scaledCollapseDurationMs(220, 0))
        assertEquals(236, scaledCollapseDurationMs(220, 120))
        assertEquals(275, scaledCollapseDurationMs(220, 400))
    }

    @Test
    fun longBlocksKeepGainingDurationUntilTheirNewCaps() {
        assertEquals(550, scaledRevealDurationMs(220, 1200))
        assertEquals(385, scaledCollapseDurationMs(220, 1200))

        assertEquals(900, scaledRevealDurationMs(220, 10_000))
        assertEquals(700, scaledCollapseDurationMs(220, 10_000))
    }

    @Test
    fun collapseDoesNotOutlastExpansion() {
        for (heightPx in listOf(0, 120, 400, 1200, 10_000)) {
            assertTrue(
                "collapse should be no longer than expansion at $heightPx px",
                scaledCollapseDurationMs(220, heightPx) <= scaledRevealDurationMs(220, heightPx),
            )
        }
    }
}
