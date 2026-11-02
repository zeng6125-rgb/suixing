package com.suixing.agent.ui.chat

import androidx.compose.foundation.lazy.layout.LazyLayoutScrollScope
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationLandingTest {
    @Test fun `short positive and negative targets move toward zero without overshoot`() {
        val positive = FakeNavigationScope(targetIndex = 10, targetVisible = true, exactDistance = 8)
        val positiveController = NavigationLandingController(targetIndex = 10, targetOffset = 0)
        assertEquals(NavigationFrameResult.Continue, positiveController.scrollFrame(positive, FRAME_60_HZ, 1_000))
        assertTrue(positive.lastRequested > 0f)
        assertTrue(positive.lastRequested <= 8f)

        val negative = FakeNavigationScope(targetIndex = 10, targetVisible = true, exactDistance = -8)
        val negativeController = NavigationLandingController(targetIndex = 10, targetOffset = 0)
        assertEquals(NavigationFrameResult.Continue, negativeController.scrollFrame(negative, FRAME_60_HZ, 1_000))
        assertTrue(negative.lastRequested < 0f)
        assertTrue(negative.lastRequested >= -8f)
    }

    @Test fun `large errors use the far speed cap and slow frames remain time capped`() {
        val huge = FakeNavigationScope(targetIndex = 20, targetVisible = true, exactDistance = 1_000_000)
        val hugeController = NavigationLandingController(targetIndex = 20, targetOffset = 0)
        assertEquals(NavigationFrameResult.Continue, hugeController.scrollFrame(huge, FRAME_60_HZ, 1_000))
        assertTrue(huge.lastRequested > 0f)
        // Far travel uses the 72 viewport/s cap: 16_666_667 ns is slightly over 1/60 s.
        assertEquals(1_200f, huge.lastRequested, 0.001f)

        val slow = FakeNavigationScope(targetIndex = 20, targetVisible = true, exactDistance = 1_000_000)
        val slowController = NavigationLandingController(targetIndex = 20, targetOffset = 0)
        assertEquals(NavigationFrameResult.Continue, slowController.scrollFrame(slow, 100_000_000L, 1_000))
        // Frame deltas are capped at 32ms, so 72,000px/s permits at most 2,304px here.
        assertEquals(2_304f, slow.lastRequested, 0.001f)
        assertTrue(slow.lastRequested > huge.lastRequested)
    }

    @Test fun `velocity continues across late target measurement instead of restarting`() {
        val target = 20
        val scope = FakeNavigationScope(
            targetIndex = target,
            estimatedDistancePx = 1_000f,
            visibilityThreshold = -1,
        )
        val controller = NavigationLandingController(target, targetOffset = 0)

        assertEquals(NavigationFrameResult.Continue, controller.scrollFrame(scope, FRAME_60_HZ, 1_000))
        assertTrue(scope.lastRequested > 0f)

        // The target becomes visible with a revised measured distance. The same controller keeps
        // the already accumulated velocity through that estimate-to-measure transition.
        scope.measureTarget(exactDistance = 3_000)
        assertEquals(NavigationFrameResult.Continue, controller.scrollFrame(scope, FRAME_60_HZ, 1_000))
        val continuedMotion = scope.lastRequested

        val freshScope = FakeNavigationScope(targetIndex = target, targetVisible = true, exactDistance = 3_000)
        val freshController = NavigationLandingController(target, targetOffset = 0)
        assertEquals(NavigationFrameResult.Continue, freshController.scrollFrame(freshScope, FRAME_60_HZ, 1_000))
        assertTrue("measured handoff should retain positive motion", continuedMotion > 0f)
        assertTrue("carried velocity should avoid a zero-velocity restart", continuedMotion > freshScope.lastRequested)
    }

    @Test fun `offscreen targets are measured and landed from either direction`() {
        val cases = listOf(
            OffscreenCase(targetIndex = 20, firstVisibleIndex = 0, estimatedDistance = 2_500),
            OffscreenCase(targetIndex = 4, firstVisibleIndex = 10, estimatedDistance = -2_500),
        )

        cases.forEach { case ->
            val scope = FakeNavigationScope(
                targetIndex = case.targetIndex,
                initialFirstVisibleItemIndex = case.firstVisibleIndex,
                estimatedDistancePx = case.estimatedDistance.toFloat(),
                visibilityThreshold = 400,
            )
            val controller = NavigationLandingController(case.targetIndex, targetOffset = 0)
            var result = NavigationFrameResult.Continue
            var frames = 0
            while (result == NavigationFrameResult.Continue && frames < 60 * 5) {
                result = controller.scrollFrame(scope, FRAME_60_HZ, 1_000)
                frames++
            }

            assertEquals(NavigationFrameResult.Arrived, result)
            assertTrue(scope.targetVisible)
            assertTrue(scope.firstRequested * case.estimatedDistance > 0f)
            assertEquals(0, scope.exactDistance)
            assertEquals(0, scope.snapCalls)
        }
    }

    @Test fun `zero offscreen estimate keeps moving in item direction until measured`() {
        val cases = listOf(
            OffscreenCase(targetIndex = 20, firstVisibleIndex = 0, estimatedDistance = 0),
            OffscreenCase(targetIndex = 4, firstVisibleIndex = 10, estimatedDistance = 0),
        )

        cases.forEach { case ->
            val direction = if (case.targetIndex > case.firstVisibleIndex + 3) 1 else -1
            val scope = FakeNavigationScope(
                targetIndex = case.targetIndex,
                initialFirstVisibleItemIndex = case.firstVisibleIndex,
                estimatedDistancePx = 0f,
                zeroDistanceUntilVisible = true,
                revealAfterTravelPx = 250f,
                distanceOnRevealPx = direction * 90f,
            )
            val controller = NavigationLandingController(case.targetIndex, targetOffset = 0)
            var result = NavigationFrameResult.Continue
            var frames = 0
            while (result == NavigationFrameResult.Continue && frames < 60 * 5) {
                result = controller.scrollFrame(scope, FRAME_60_HZ, 1_000)
                frames++
            }

            assertEquals(NavigationFrameResult.Arrived, result)
            assertTrue(scope.targetVisible)
            assertTrue(scope.firstRequested * direction > 0f)
            assertTrue("zero estimate must not pretend an unplaced target is visible", frames > 1)
            assertEquals(0, scope.exactDistance)
            assertEquals(0, scope.snapCalls)
        }
    }

    @Test fun `60 and 120 hz frames reach the same measured target at similar elapsed time`() {
        val at60 = settleAt(60)
        val at120 = settleAt(120)

        assertEquals(NavigationFrameResult.Arrived, at60.result)
        assertEquals(NavigationFrameResult.Arrived, at120.result)
        assertEquals(0, at60.remainingDistance)
        assertEquals(0, at120.remainingDistance)
        assertTrue(at60.seconds in 0.2f..2f)
        assertTrue(at120.seconds in 0.2f..2f)
        assertTrue(abs(at60.seconds - at120.seconds) < 0.1f)
        assertEquals(0, at60.snapCalls)
        assertEquals(0, at120.snapCalls)
    }

    @Test fun `content growth changes the live error without reversing or restarting motion`() {
        val target = 10
        val scope = FakeNavigationScope(targetIndex = target, targetVisible = true, exactDistance = 500)
        val controller = NavigationLandingController(target, targetOffset = 0)

        assertEquals(NavigationFrameResult.Continue, controller.scrollFrame(scope, FRAME_60_HZ, 1_000))
        val priorRequest = scope.lastRequested
        scope.growBeforeTarget(120)
        assertEquals(NavigationFrameResult.Continue, controller.scrollFrame(scope, FRAME_60_HZ, 1_000))
        assertTrue(priorRequest > 0f)
        assertTrue(scope.lastRequested > 0f)
        assertTrue(scope.lastRequested <= 192f)
        assertEquals(0, scope.snapCalls)
    }

    @Test fun `end sentinel converts only for estimates and follows dynamic measured bottom error`() {
        assertEquals(0, navigationEstimateOffset(TRANSCRIPT_END_OFFSET))
        assertEquals(-280, navigationEstimateOffset(-280))
        assertEquals(120, navigationEndAlignmentError(
            itemOffset = 500,
            itemSize = 300,
            afterContentPadding = 100,
            viewportEndOffset = 780,
        ))

        val target = 10
        val scope = FakeNavigationScope(targetIndex = target, targetVisible = true, exactDistance = 1_000_000)
        var measuredEndError = 120
        val controller = NavigationLandingController(target, targetOffset = navigationEstimateOffset(TRANSCRIPT_END_OFFSET)) {
            measuredEndError
        }

        assertEquals(NavigationFrameResult.Continue, controller.scrollFrame(scope, FRAME_60_HZ, 1_000))
        val firstMove = scope.lastRequested
        assertTrue(firstMove > 0f)
        measuredEndError = 60
        assertEquals(NavigationFrameResult.Continue, controller.scrollFrame(scope, FRAME_60_HZ, 1_000))
        assertTrue(scope.lastRequested > 0f)

        measuredEndError = 0
        val scrollCallsAtArrival = scope.scrollCalls
        assertEquals(NavigationFrameResult.Arrived, controller.scrollFrame(scope, 0L, 1_000))
        assertEquals(scrollCallsAtArrival, scope.scrollCalls)
        assertEquals(0, scope.snapCalls)
    }

    @Test fun `end clamp and a removed target stop the controller`() {
        val target = 10
        val clamped = FakeNavigationScope(
            targetIndex = target,
            targetVisible = true,
            exactDistance = 800,
            maxConsumedPerFrame = 4f,
        )
        assertEquals(
            NavigationFrameResult.ScrollClamped,
            NavigationLandingController(target, -280).scrollFrame(clamped, FRAME_60_HZ, 1_000),
        )

        val removedBeforeScroll = FakeNavigationScope(targetIndex = target, itemCount = target)
        assertEquals(
            NavigationFrameResult.TargetUnavailable,
            NavigationLandingController(target, -280).scrollFrame(removedBeforeScroll, FRAME_60_HZ, 1_000),
        )
        assertEquals(0, removedBeforeScroll.scrollCalls)

        val removedDuringScroll = FakeNavigationScope(
            targetIndex = target,
            targetVisible = true,
            exactDistance = 800,
            removeOnNextScroll = true,
        )
        assertEquals(
            NavigationFrameResult.TargetUnavailable,
            NavigationLandingController(target, -280).scrollFrame(removedDuringScroll, FRAME_60_HZ, 1_000),
        )
    }

    @Test fun `arrived controller performs no later scroll`() {
        val target = 10
        val scope = FakeNavigationScope(targetIndex = target, targetVisible = true, exactDistance = 0)
        val controller = NavigationLandingController(target, targetOffset = 0)

        assertEquals(NavigationFrameResult.Arrived, controller.scrollFrame(scope, FRAME_60_HZ, 1_000))
        assertEquals(0, scope.scrollCalls)
        assertEquals(NavigationFrameResult.Arrived, controller.scrollFrame(scope, FRAME_60_HZ, 1_000))
        assertEquals(0, scope.scrollCalls)
        assertEquals(0, scope.snapCalls)
    }

    private fun settleAt(framesPerSecond: Int): SettleResult {
        val target = 10
        val scope = FakeNavigationScope(targetIndex = target, targetVisible = true, exactDistance = 600)
        val controller = NavigationLandingController(target, targetOffset = 0)
        val frameNanos = 1_000_000_000L / framesPerSecond
        var result = NavigationFrameResult.Continue
        var frames = 0
        while (result == NavigationFrameResult.Continue && frames < framesPerSecond * 3) {
            result = controller.scrollFrame(scope, frameNanos, 1_000)
            frames++
        }
        return SettleResult(
            result = result,
            remainingDistance = scope.exactDistance,
            seconds = frames / framesPerSecond.toFloat(),
            snapCalls = scope.snapCalls,
        )
    }

    private data class SettleResult(
        val result: NavigationFrameResult,
        val remainingDistance: Int,
        val seconds: Float,
        val snapCalls: Int,
    )

    private data class OffscreenCase(
        val targetIndex: Int,
        val firstVisibleIndex: Int,
        val estimatedDistance: Int,
    )

    private class FakeNavigationScope(
        private val targetIndex: Int,
        private var estimatedDistancePx: Float = 0f,
        var targetVisible: Boolean = false,
        exactDistance: Int = 0,
        override var itemCount: Int = 30,
        private val maxConsumedPerFrame: Float = Float.POSITIVE_INFINITY,
        private val visibilityThreshold: Int = 400,
        private val removeOnNextScroll: Boolean = false,
        private val initialFirstVisibleItemIndex: Int = 0,
        private val zeroDistanceUntilVisible: Boolean = false,
        private val revealAfterTravelPx: Float = Float.POSITIVE_INFINITY,
        private val distanceOnRevealPx: Float = 0f,
    ) : LazyLayoutScrollScope {
        private var exactDistancePx = exactDistance.toFloat()
        private var pendingScrollPx = 0f
        private var totalAppliedScrollPx = 0f

        override val firstVisibleItemIndex: Int
            get() = if (targetVisible) targetIndex - 2 else initialFirstVisibleItemIndex
        override val lastVisibleItemIndex: Int
            get() = if (targetVisible) targetIndex + 2 else initialFirstVisibleItemIndex + 3
        override val firstVisibleItemScrollOffset: Int = 0

        val exactDistance: Int get() = exactDistancePx.roundToInt()

        var lastRequested = 0f
            private set
        var firstRequested = 0f
            private set
        var scrollCalls = 0
            private set
        var snapCalls = 0
            private set

        override fun calculateDistanceTo(targetIndex: Int, targetOffset: Int): Int = when {
            this.targetIndex != targetIndex -> 0
            targetVisible -> exactDistance
            zeroDistanceUntilVisible -> 0
            else -> estimatedDistancePx.roundToInt()
        }

        override fun snapToItem(index: Int, offset: Int) {
            snapCalls++
        }

        override fun scrollBy(pixels: Float): Float {
            scrollCalls++
            lastRequested = pixels
            val consumed = if (abs(pixels) <= maxConsumedPerFrame) pixels
            else kotlin.math.sign(pixels) * maxConsumedPerFrame
            if (scrollCalls == 1) firstRequested = pixels
            pendingScrollPx += consumed
            // LazyListState retains subpixel scroll until it reaches its 0.5px layout threshold.
            val appliedScrollPx = pendingScrollPx.roundToInt().toFloat()
            if (appliedScrollPx != 0f) {
                totalAppliedScrollPx += appliedScrollPx
                if (targetVisible) exactDistancePx -= appliedScrollPx
                else if (!zeroDistanceUntilVisible) estimatedDistancePx -= appliedScrollPx
                pendingScrollPx -= appliedScrollPx
            }
            if (!targetVisible && zeroDistanceUntilVisible &&
                abs(totalAppliedScrollPx) >= revealAfterTravelPx) {
                targetVisible = true
                exactDistancePx = distanceOnRevealPx
            } else if (!targetVisible && !zeroDistanceUntilVisible && visibilityThreshold >= 0 &&
                estimatedDistancePx <= visibilityThreshold && estimatedDistancePx >= -visibilityThreshold) {
                targetVisible = true
                exactDistancePx = estimatedDistancePx
            }
            if (removeOnNextScroll) itemCount = targetIndex
            return consumed
        }

        fun measureTarget(exactDistance: Int) {
            targetVisible = true
            exactDistancePx = exactDistance.toFloat()
        }

        fun growBeforeTarget(pixels: Int) {
            if (targetVisible) exactDistancePx += pixels else estimatedDistancePx += pixels
        }
    }

    private companion object {
        const val FRAME_60_HZ = 16_666_667L
    }
}
