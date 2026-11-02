package com.suixing.agent.ui.chat

import androidx.compose.foundation.lazy.layout.LazyLayoutScrollScope
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Measure controller time in simulated frames, keeping the lazy-list geometry fixed. */
class NavigationTravelDurationTest {
    @Test fun `simulated baseline covers close and long distances at both refresh rates`() {
        for (refreshRate in REFRESH_RATES) {
            for (direction in listOf(-1, 1)) {
                val byDistance = DISTANCES_IN_VIEWPORTS.associateWith { distance ->
                    measureTravel(distance, refreshRate, direction)
                }
                println(
                    "Navigation simulated durations at ${refreshRate}Hz direction=$direction: " +
                        byDistance.entries.joinToString { (distance, result) ->
                            "${distance}vp=${"%.3f".format(result.elapsedSeconds)}s"
                        },
                )
                assertEquals(DISTANCES_IN_VIEWPORTS, byDistance.keys.toList())
                assertTrue(byDistance.values.zipWithNext().all { (nearer, farther) ->
                    farther.elapsedSeconds >= nearer.elapsedSeconds
                })
            }
        }
    }

    @Test fun `close travel keeps the existing timing profile`() {
        for (refreshRate in REFRESH_RATES) {
            for (distance in listOf(0.6f, 2f)) {
                val measured = measureTravel(distance, refreshRate, direction = 1)
                val baseline = baselineSeconds(refreshRate, distance)
                assertTrue(
                    "${distance}vp at ${refreshRate}Hz changed from $baseline to ${measured.elapsedSeconds}s",
                    abs(measured.elapsedSeconds - baseline) <= 1f / refreshRate + 0.0001f,
                )
            }
        }
    }

    @Test fun `far travel speeds up progressively without an extreme speedup`() {
        for (refreshRate in REFRESH_RATES) {
            val speedupRatios = mutableListOf<Float>()
            for (distance in listOf(50f, 100f, 200f, 300f)) {
                val measured = measureTravel(distance, refreshRate, direction = 1)
                val baseline = PREVIOUS_PROFILE_SECONDS.getValue(refreshRate).getValue(distance)
                val ratio = measured.elapsedSeconds / baseline
                speedupRatios += ratio
                assertTrue(
                    "${distance}vp at ${refreshRate}Hz took ${measured.elapsedSeconds}s; " +
                        "previous profile=$baseline",
                    ratio <= MAX_PROFILE_RATIO.getValue(distance),
                )
                assertTrue(
                    "${distance}vp at ${refreshRate}Hz sped up too far: " +
                        "${measured.elapsedSeconds}s; previous profile=$baseline",
                    ratio >= MIN_PROFILE_RATIO,
                )
            }
            assertTrue(
                "longer distances should use progressively more of the far-speed profile: $speedupRatios",
                speedupRatios.zipWithNext().all { (nearer, farther) -> farther < nearer },
            )
        }
    }

    @Test fun `travel stays directional and lands precisely at both refresh rates`() {
        for (refreshRate in REFRESH_RATES) {
            for (distance in listOf(50f, 100f, 200f, 300f)) {
                for (direction in listOf(-1, 1)) {
                    val result = measureTravel(distance, refreshRate, direction)
                    assertEquals(NavigationFrameResult.Arrived, result.result)
                    assertEquals(0, result.remainingDistance)
                    assertTrue(result.requestedMoves.all { it * direction > 0f })
                    assertTrue(result.remainingDistances.all { it * direction >= 0 })
                    assertTrue(result.snapCalls == 0)
                    assertTrue(abs(result.requestedMoves.last()) <= 1f)
                    assertTrue(result.requestedMoves.takeLast(4).all { abs(it) <= 3f })
                }
            }
        }
    }

    @Test fun `the original twelve viewport speed cap remains for the final two viewports`() {
        for (refreshRate in REFRESH_RATES) {
            for (distance in listOf(5f, 10f, 20f, 50f, 100f, 200f, 300f)) {
                val result = measureTravel(distance, refreshRate, direction = 1)
                assertTrue(
                    "${distance}vp at ${refreshRate}Hz exceeded the close-range cap: " +
                        "${result.maxSpeedWithinLastTwoViewports}px/s",
                    result.maxSpeedWithinLastTwoViewports <=
                        VIEWPORT_HEIGHT * ORIGINAL_VIEWPORTS_PER_SECOND + 0.1f,
                )
            }
        }
    }

    private fun measureTravel(
        distanceInViewports: Float,
        refreshRate: Int,
        direction: Int,
    ): TravelMeasurement {
        val initialDistance = (distanceInViewports * VIEWPORT_HEIGHT).roundToInt() * direction
        val scope = FakeTravelScope(TARGET_INDEX, initialDistance)
        val controller = NavigationLandingController(TARGET_INDEX, targetOffset = 0)
        val frameDeltaNanos = 1_000_000_000L / refreshRate
        var result = NavigationFrameResult.Continue
        var frames = 0
        var maxSpeedWithinLastTwoViewports = 0f

        while (result == NavigationFrameResult.Continue && frames < refreshRate * MAX_MEASUREMENT_SECONDS) {
            val remainingBeforeFrame = scope.distance
            result = controller.scrollFrame(scope, frameDeltaNanos, VIEWPORT_HEIGHT)
            val requested = scope.lastRequested
            if (abs(remainingBeforeFrame) <= 2 * VIEWPORT_HEIGHT) {
                val frameSeconds = frameDeltaNanos / NANOS_PER_SECOND
                maxSpeedWithinLastTwoViewports = maxOf(
                    maxSpeedWithinLastTwoViewports,
                    abs(requested) / frameSeconds,
                )
            }
            frames++
        }

        assertTrue(
            "${distanceInViewports}vp at ${refreshRate}Hz did not finish; remaining=${scope.distance}",
            result != NavigationFrameResult.Continue,
        )
        return TravelMeasurement(
            elapsedSeconds = frames / refreshRate.toFloat(),
            result = result,
            remainingDistance = scope.distance,
            remainingDistances = scope.remainingDistances,
            requestedMoves = scope.requestedMoves,
            snapCalls = scope.snapCalls,
            maxSpeedWithinLastTwoViewports = maxSpeedWithinLastTwoViewports,
        )
    }

    private fun baselineSeconds(refreshRate: Int, distanceInViewports: Float): Float {
        val baseline = BASELINE_SECONDS.getValue(refreshRate)
        return baseline.getValue(distanceInViewports)
    }

    private data class TravelMeasurement(
        val elapsedSeconds: Float,
        val result: NavigationFrameResult,
        val remainingDistance: Int,
        val remainingDistances: List<Int>,
        val requestedMoves: List<Float>,
        val snapCalls: Int,
        val maxSpeedWithinLastTwoViewports: Float,
    )

    private class FakeTravelScope(
        private val targetIndex: Int,
        initialDistance: Int,
    ) : LazyLayoutScrollScope {
        override val firstVisibleItemIndex: Int
            get() = when {
                distance > VIEWPORT_HEIGHT -> targetIndex - 4
                distance < -VIEWPORT_HEIGHT -> targetIndex + 1
                else -> targetIndex - 1
            }
        override val lastVisibleItemIndex: Int
            get() = when {
                distance > VIEWPORT_HEIGHT -> targetIndex - 1
                distance < -VIEWPORT_HEIGHT -> targetIndex + 4
                else -> targetIndex + 1
            }
        override val firstVisibleItemScrollOffset: Int = 0
        override val itemCount: Int = targetIndex + 5

        var distance: Int = initialDistance
            private set
        private var pendingScrollPx = 0f
        var lastRequested = 0f
            private set
        val requestedMoves = mutableListOf<Float>()
        val remainingDistances = mutableListOf<Int>()
        var snapCalls = 0
            private set

        override fun calculateDistanceTo(targetIndex: Int, targetOffset: Int): Int =
            if (targetIndex == this.targetIndex) distance else 0

        override fun snapToItem(index: Int, offset: Int) {
            snapCalls++
        }

        override fun scrollBy(pixels: Float): Float {
            lastRequested = pixels
            requestedMoves += pixels
            pendingScrollPx += pixels
            val applied = pendingScrollPx.roundToInt().toFloat()
            if (applied != 0f) {
                distance -= applied.roundToInt()
                pendingScrollPx -= applied
            }
            remainingDistances += distance
            return pixels
        }
    }

    private companion object {
        const val VIEWPORT_HEIGHT = 1_000
        const val ORIGINAL_VIEWPORTS_PER_SECOND = 12f
        const val TARGET_INDEX = 10
        const val MAX_MEASUREMENT_SECONDS = 12
        const val NANOS_PER_SECOND = 1_000_000_000f
        val REFRESH_RATES = listOf(60, 120)
        val DISTANCES_IN_VIEWPORTS = listOf(0.6f, 2f, 5f, 10f, 20f, 50f, 100f, 200f, 300f)
        const val MIN_PROFILE_RATIO = 0.38f
        val MAX_PROFILE_RATIO = mapOf(
            50f to 0.84f,
            100f to 0.70f,
            200f to 0.52f,
            300f to 0.45f,
        )
        val PREVIOUS_PROFILE_SECONDS = mapOf(
            60 to mapOf(
                50f to 3.517f,
                100f to 6.300f,
                200f to 11.850f,
                300f to 17.400f,
            ),
            120 to mapOf(
                50f to 3.525f,
                100f to 6.300f,
                200f to 11.867f,
                300f to 17.417f,
            ),
        )
        val BASELINE_SECONDS = mapOf(
            60 to mapOf(
                0.6f to 0.700f,
                2f to 0.800f,
                5f to 1.000f,
                10f to 1.433f,
                20f to 2.267f,
                50f to 4.767f,
            ),
            120 to mapOf(
                0.6f to 0.692f,
                2f to 0.792f,
                5f to 1.017f,
                10f to 1.425f,
                20f to 2.258f,
                50f to 4.758f,
            ),
        )
    }
}
