package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RevealDurationTest {
    @Test
    fun collapseKeepsItsOriginalPixelBasedDurationsAndCap() {
        assertEquals(220, scaledCollapseDurationMs(220, 0))
        assertEquals(236, scaledCollapseDurationMs(220, 120))
        assertEquals(275, scaledCollapseDurationMs(220, 400))
        assertEquals(350, scaledCollapseDurationMs(220, 1200))
        assertEquals(350, scaledCollapseDurationMs(220, 10_000))
    }

    @Test
    fun expansionUsesTheSpecifiedDpSegments() {
        assertEquals(150, revealDurationMsForHeight(0f))
        // 短块区间 0–200dp（原 0–160dp）：240–320dp 那一段是用户觉得"慢了一点点"的地方
        assertEquals(166, revealDurationMsForHeight(80f))
        assertEquals(182, revealDurationMsForHeight(160f))
        assertEquals(190, revealDurationMsForHeight(200f))
        assertEquals(263, revealDurationMsForHeight(320f))
        assertEquals(360, revealDurationMsForHeight(480f))
        assertEquals(547, revealDurationMsForHeight(900f))
        assertEquals(680, revealDurationMsForHeight(1200f))
        assertEquals(680, revealDurationMsForHeight(1600f))
    }

    @Test
    fun expansionDurationIsMonotonicContinuousAndCapped() {
        var previous = revealDurationMsForHeight(0f)
        for (step in 1..1600) {
            val duration = revealDurationMsForHeight(step.toFloat())
            assertTrue("duration moved backward at $step dp", duration >= previous)
            assertTrue("duration exceeded cap at $step dp", duration <= 680)
            previous = duration
        }
        assertTrue(abs(revealDurationMsForHeight(159.99f) - revealDurationMsForHeight(160f)) <= 1)
        assertTrue(abs(revealDurationMsForHeight(479.99f) - revealDurationMsForHeight(480f)) <= 1)
        assertTrue(abs(revealDurationMsForHeight(1199.99f) - revealDurationMsForHeight(1200f)) <= 1)
    }

    @Test
    fun shortExpansionStaysAtOrBelow190Ms() {
        for (heightDp in listOf(0f, 40f, 80f, 120f, 160f)) {
            assertTrue(revealDurationMsForHeight(heightDp) <= 190)
        }
    }

    @Test
    fun typicalLongExpansionsAreFasterThanThePreviousScheduleAtTwoXDensity() {
        assertTrue(revealDurationMsForHeight(480f) < previousPixelBasedRevealDurationAtTwoX(480f))
        assertTrue(revealDurationMsForHeight(840f) < previousPixelBasedRevealDurationAtTwoX(840f))
    }

    @Test
    fun shortExpansionKeepsTheExistingCurve() {
        val shortCurve = revealEasingForHeight(160f)
        for (time in listOf(0f, 0.1f, 0.2f, 0.3f, 0.5f, 1f)) {
            assertEquals(REVEAL_EASING.transform(time), shortCurve.transform(time), 0f)
        }
    }

    @Test
    fun longExpansionIsMoreEvenAtTheStartAndMidpoint() {
        val longCurve = revealEasingForHeight(480f)
        assertTrue(longCurve.transform(0.2f) < 0.15f)
        assertEquals(0.5f, longCurve.transform(0.5f), 0.01f)
    }

    @Test
    fun heightBlendedExpansionCurvesStayMonotonicAndBounded() {
        for (heightDp in listOf(0f, 160f, 240f, 320f, 400f, 480f, 800f)) {
            val easing = revealEasingForHeight(heightDp)
            var previous = -0.001f
            for (step in 0..100) {
                val progress = easing.transform(step / 100f)
                assertTrue("$heightDp dp easing escaped [0, 1]", progress in 0f..1f)
                assertTrue("$heightDp dp easing moved backward at $step", progress >= previous)
                previous = progress
            }
        }
    }

    private fun previousPixelBasedRevealDurationAtTwoX(heightDp: Float): Int {
        val heightPx = heightDp * 2f
        val extra = (heightPx / 400f) * 110f
        return 220 + extra.coerceIn(0f, 680f).toInt()
    }
}
