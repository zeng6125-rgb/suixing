package com.suixing.agent.ui.chat

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

class RevealDurationTest {
    @Test
    fun expandAndCollapseUseFiveResponsiveContentScales() {
        val samples = listOf(
            "ultra-short" to Triple(48f, 142, 120),
            "short" to Triple(160f, 170, 142),
            "medium" to Triple(320f, 257, 214),
            "large" to Triple(640f, 432, 365),
            "huge" to Triple(1600f, 680, 580),
        )

        for ((scale, sample) in samples) {
            val (heightDp, expectedExpandMs, expectedCollapseMs) = sample
            assertEquals("$scale expansion", expectedExpandMs, revealDurationMsForHeight(heightDp))
            assertEquals(
                "$scale collapse",
                expectedCollapseMs,
                scaledCollapseDurationMs(220, heightDp),
            )
        }
    }

    @Test
    fun bothDurationSchedulesAreContinuousMonotonicAndCapped() {
        var previousExpand = revealDurationMsForHeight(0f)
        var previousCollapse = scaledCollapseDurationMs(220, 0f)
        for (step in 1..2400) {
            val heightDp = step.toFloat()
            val expand = revealDurationMsForHeight(heightDp)
            val collapse = scaledCollapseDurationMs(220, heightDp)
            assertTrue("expansion moved backward at $step dp", expand >= previousExpand)
            assertTrue("collapse moved backward at $step dp", collapse >= previousCollapse)
            assertTrue("expansion exceeded cap at $step dp", expand <= 680)
            assertTrue("collapse exceeded cap at $step dp", collapse <= 580)
            previousExpand = expand
            previousCollapse = collapse
        }

        for (breakpoint in listOf(200f, 480f, 900f, 1200f)) {
            assertTrue(
                "expansion jumped at $breakpoint dp",
                abs(revealDurationMsForHeight(breakpoint - 0.01f) -
                    revealDurationMsForHeight(breakpoint + 0.01f)) <= 1,
            )
            assertTrue(
                "collapse jumped at $breakpoint dp",
                abs(scaledCollapseDurationMs(220, breakpoint - 0.01f) -
                    scaledCollapseDurationMs(220, breakpoint + 0.01f)) <= 1,
            )
        }
    }

    @Test
    fun equalLogicalHeightsKeepTheSameDurationsAcrossDensities() {
        val densityScales = listOf(1f, 1.5f, 2f, 3f)
        for (heightDp in listOf(160f, 320f, 480f, 900f)) {
            val durations = densityScales.map { density ->
                val measuredHeightPx = (heightDp * density).roundToInt()
                val measuredHeightDp = revealHeightDpForDuration(measuredHeightPx, density)
                revealDurationMsForHeight(measuredHeightDp) to
                    scaledCollapseDurationMs(220, measuredHeightDp)
            }
            assertTrue("duration changed with density at $heightDp dp", durations.distinct().size == 1)
        }
    }

    @Test
    fun reversalDurationTracksRemainingTravelAndHasASmallFloor() {
        assertEquals(200, revealTransitionDurationMs(400, 0.5f, 1f))
        assertEquals(300, revealTransitionDurationMs(400, 0.75f, 0f))
        assertEquals(200, revealTransitionDurationMs(400, 0.5f, 0f))
        assertEquals(80, revealTransitionDurationMs(400, 0.05f, 0f))
        assertEquals(0, revealTransitionDurationMs(400, 0.5f, 0.5f))
        assertEquals(0, revealTransitionDurationMs(0, 0f, 1f))
        assertEquals(200, revealTransitionDurationMs(400, Float.NaN, 0.5f))
    }

    @Test
    fun reversalSpringProjectsAndCapsVelocityWithoutOvershooting() {
        assertNull(revealInterruptionPolicy(0f, 0.5f, 0f, 400, heightPx = 5000))
        assertNull(revealInterruptionPolicy(Float.NaN, 0.5f, 0f, 400, heightPx = 5000))
        assertNull(revealInterruptionPolicy(4f, 0.001f, 0f, 400, heightPx = 20))

        // An opening reversed toward 0 carries positive velocity away from its new target.
        val openingToClosing = revealInterruptionPolicy(4f, 0.5f, 0f, 400, heightPx = 5000)!!
        assertEquals(0f, openingToClosing.initialVelocity, 0f)
        assertCriticalSpringPathIsMonotonic(0.5f, 0f, openingToClosing)

        // A closing reversed toward 1 carries negative velocity away from its new target.
        val closingToOpening = revealInterruptionPolicy(-4f, 0.5f, 1f, 400, heightPx = 5000)!!
        assertEquals(0f, closingToOpening.initialVelocity, 0f)
        assertCriticalSpringPathIsMonotonic(0.5f, 1f, closingToOpening)

        // Even velocity aimed at the target is capped at omega * distance for either direction.
        val fastOpening = revealInterruptionPolicy(100f, 0.5f, 1f, 400, heightPx = 5000)!!
        val fastClosing = revealInterruptionPolicy(-100f, 0.5f, 0f, 400, heightPx = 5000)!!
        val maxAdmissibleSpeed = sqrt(fastOpening.stiffness) * 0.5f
        assertEquals(maxAdmissibleSpeed, fastOpening.initialVelocity, 0.0001f)
        assertEquals(-maxAdmissibleSpeed, fastClosing.initialVelocity, 0.0001f)
        assertCriticalSpringPathIsMonotonic(0.5f, 1f, fastOpening)
        assertCriticalSpringPathIsMonotonic(0.5f, 0f, fastClosing)

        val quickReversal = revealInterruptionPolicy(-4f, 0.5f, 1f, 100, heightPx = 5000)!!
        assertTrue("short remaining travel should settle faster", quickReversal.stiffness > closingToOpening.stiffness)
    }

    @Test
    fun largeCardComposeSpringStaysWithinOnePixelAtSixtyAndOneHundredTwentyHertz() {
        val heightPx = 5000
        val start = 0.5f
        val reversals = listOf(
            Triple(0f, 4f, "opening reversed to closing"),
            Triple(1f, -4f, "closing reversed to opening"),
        )
        val frameIntervals = listOf(
            60 to (1_000_000_000L / 60),
            120 to (1_000_000_000L / 120),
        )

        for ((target, velocity, direction) in reversals) {
            val policy = revealInterruptionPolicy(
                velocity = velocity,
                currentFraction = start,
                targetFraction = target,
                transitionDurationMs = 400,
                heightPx = heightPx,
            )!!
            assertEquals(0f, policy.initialVelocity, 0f)

            val animation = TargetBasedAnimation(
                animationSpec = spring<Float>(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = policy.stiffness,
                    visibilityThreshold = policy.visibilityThreshold,
                ),
                typeConverter = Float.VectorConverter,
                initialValue = start,
                targetValue = target,
                initialVelocityVector = AnimationVector1D(policy.initialVelocity),
            )
            val durationNanos = animation.durationNanos
            assertTrue("$direction spring should take measurable time", durationNanos > 20_000_000L)
            val durationMs = durationNanos / 1_000_000L
            assertTrue("$direction spring shifted its 400 ms window to $durationMs ms", abs(durationMs - 400L) <= 2L)
            val targetHeightPx = (target * heightPx).roundToInt()

            for ((refreshRate, frameNanos) in frameIntervals) {
                val lastFrameNanos = durationNanos - frameNanos
                assertFalse(animation.isFinishedFromNanos(lastFrameNanos))
                val fraction = animation.getValueFromNanos(lastFrameNanos)
                val renderedHeightPx = (fraction * heightPx).roundToInt()
                assertTrue(
                    "$direction at $refreshRate Hz rendered $renderedHeightPx px before target $targetHeightPx px",
                    abs(renderedHeightPx - targetHeightPx) <= 1,
                )
            }
        }
    }

    @Test
    fun springVisibilityThresholdTracksMeasuredPixelHeight() {
        assertEquals(0.01f, revealFractionVisibilityThreshold(0), 0f)
        assertEquals(0.01f, revealFractionVisibilityThreshold(20), 0f)
        assertEquals(0.00005f, revealFractionVisibilityThreshold(5000), 0.0000001f)
    }

    private fun assertCriticalSpringPathIsMonotonic(
        start: Float,
        target: Float,
        policy: RevealInterruptionPolicy,
    ) {
        val low = minOf(start, target) - 0.00001f
        val high = maxOf(start, target) + 0.00001f
        var previous = start
        for (step in 0..2000) {
            val value = criticalSpringValueAtSeconds(
                start = start,
                target = target,
                initialVelocity = policy.initialVelocity,
                stiffness = policy.stiffness,
                seconds = step / 1000.0,
            )
            assertTrue("spring left [$start, $target] at ${step}ms: $value", value in low..high)
            if (target > start) {
                assertTrue("spring reversed at ${step}ms", value + 0.00001f >= previous)
            } else {
                assertTrue("spring reversed at ${step}ms", value - 0.00001f <= previous)
            }
            previous = value
        }
    }

    private fun criticalSpringValueAtSeconds(
        start: Float,
        target: Float,
        initialVelocity: Float,
        stiffness: Float,
        seconds: Double,
    ): Float {
        val omega = sqrt(stiffness.toDouble())
        val initialError = (start - target).toDouble()
        val linearTerm = initialVelocity.toDouble() + omega * initialError
        return (target.toDouble() +
            (initialError + linearTerm * seconds) * exp(-omega * seconds)).toFloat()
    }

    @Test
    fun alphaStaysContinuousWhenExpansionOrCollapseIsReversed() {
        val openingAlpha = revealAlphaForFraction(fraction = 0.2f, alphaFloor = 0f)
        assertEquals(0.5f, openingAlpha, 0f)

        val closingFloor = revealAlphaFloorAfterDirectionChange(
            wasExpanding = true,
            willExpand = false,
            currentFraction = 0.2f,
            alphaFloor = 0f,
        )
        assertEquals(openingAlpha, revealAlphaForFraction(0.15f, closingFloor), 0f)

        val reopeningFloor = revealAlphaFloorAfterDirectionChange(
            wasExpanding = false,
            willExpand = true,
            currentFraction = 0.15f,
            alphaFloor = closingFloor,
        )
        assertEquals(
            revealAlphaForFraction(0.15f, closingFloor),
            revealAlphaForFraction(0.15f, reopeningFloor),
            0f,
        )
        assertEquals(0.75f, revealAlphaForFraction(0.3f, reopeningFloor), 0f)

        val freshOpenFloor = revealAlphaFloorAfterDirectionChange(
            wasExpanding = false,
            willExpand = true,
            currentFraction = -0.01f,
            alphaFloor = closingFloor,
        )
        assertEquals(0f, revealAlphaForFraction(0f, freshOpenFloor), 0f)
    }

    @Test
    fun onlyAClosedReopenInvalidatesTheMeasuredHeight() {
        assertTrue(revealNeedsFreshMeasurement(visible = true, wasExpanding = false, currentFraction = 0f))
        assertTrue(revealNeedsFreshMeasurement(visible = true, wasExpanding = false, currentFraction = -0.01f))
        assertFalse(revealNeedsFreshMeasurement(visible = true, wasExpanding = false, currentFraction = 0.4f))
        assertFalse(revealNeedsFreshMeasurement(visible = true, wasExpanding = true, currentFraction = 0f))
        assertFalse(revealNeedsFreshMeasurement(visible = false, wasExpanding = false, currentFraction = 0f))
    }

    @Test
    fun invalidHeightAndDensityFallBackWithoutInvalidDurations() {
        assertEquals(130, revealDurationMsForHeight(Float.NaN))
        assertEquals(130, revealDurationMsForHeight(Float.NEGATIVE_INFINITY))
        assertEquals(680, revealDurationMsForHeight(Float.POSITIVE_INFINITY))
        assertEquals(0f, revealHeightDpForDuration(400, Float.NaN), 0f)
        assertEquals(130, revealDurationMsForHeight(revealHeightDpForDuration(400, 0f)))
    }

    @Test
    fun heightBlendedEasingStaysMonotonicAndBoundedAtEveryScale() {
        for (heightDp in listOf(48f, 160f, 320f, 640f, 1600f)) {
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

    @Test
    fun shortAndLongEasingKeepTheirIntendedShapes() {
        val shortCurve = revealEasingForHeight(160f)
        for (time in listOf(0f, 0.1f, 0.2f, 0.3f, 0.5f, 1f)) {
            assertEquals(REVEAL_EASING.transform(time), shortCurve.transform(time), 0f)
        }

        val longCurve = revealEasingForHeight(480f)
        assertTrue(longCurve.transform(0.2f) < 0.15f)
        assertEquals(0.5f, longCurve.transform(0.5f), 0.01f)
    }
}
