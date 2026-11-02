package com.suixing.agent.ui.chat

import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.lazy.LazyLayoutScrollScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutScrollScope as LazyScrollSession
import io.mockk.every
import io.mockk.mockk
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keep Foundation's real estimate-to-measure calculation in the regression path. */
class NavigationBrakingTest {
    @Test fun `upward handoff from a tall row finishes with a braking tail`() {
        for (refreshRate in listOf(60, 120)) {
            val heights = IntArray(20) { 400 }.also { it[4] = 80; it[5] = 2_000 }
            val transcript = TranscriptGeometry(heights, initialScroll = 3_600)
            val result = runNavigation(transcript, targetIndex = 4, refreshRate = refreshRate)

            assertEquals(NavigationFrameResult.Arrived, result)
            assertEquals(transcript.starts[4] - PROBE_OFFSET, transcript.scrollPosition)
            assertTrue("upward motion overshot its target", transcript.positions.all { it >= transcript.scrollPosition })
            assertTrue("upward motion reversed", transcript.positions.zipWithNext().all { (a, b) -> b <= a })
            assertGentleLastFrame(transcript, refreshRate)
        }
    }

    @Test fun `downward handoff uses the same braking rule`() {
        for (refreshRate in listOf(60, 120)) {
            val heights = IntArray(20) { 400 }.also { it[7] = 2_000; it[8] = 80 }
            val transcript = TranscriptGeometry(heights, initialScroll = 2_800)
            val result = runNavigation(transcript, targetIndex = 8, refreshRate = refreshRate)

            assertEquals(NavigationFrameResult.Arrived, result)
            assertEquals(transcript.starts[8] - PROBE_OFFSET, transcript.scrollPosition)
            assertTrue("downward motion overshot its target", transcript.positions.all { it <= transcript.scrollPosition })
            assertTrue("downward motion reversed", transcript.positions.zipWithNext().all { (a, b) -> b >= a })
            assertGentleLastFrame(transcript, refreshRate)
        }
    }

    @Test fun `unreachable probe offset brakes at the physical history start`() {
        for (refreshRate in listOf(60, 120)) {
            val transcript = TranscriptGeometry(IntArray(20) { 400 }, initialScroll = 1_500)
            val result = runNavigation(transcript, targetIndex = 0, refreshRate = refreshRate)

            // A negative offset cannot place the first row below the probe at physical start.
            assertEquals(NavigationFrameResult.ScrollClamped, result)
            assertEquals(0, transcript.scrollPosition)
            assertGentleLastFrame(transcript, refreshRate)
        }
    }

    private fun assertGentleLastFrame(transcript: TranscriptGeometry, refreshRate: Int) {
        assertTrue(
            "abrupt last frame at $refreshRate Hz; tail=${transcript.moves.takeLast(6)}",
            abs(transcript.moves.last()) <= 1f,
        )
        assertTrue(
            "braking must span several frames; tail=${transcript.moves.takeLast(6)}",
            transcript.moves.takeLast(4).all { abs(it) <= 3f },
        )
    }

    private fun runNavigation(
        transcript: TranscriptGeometry,
        targetIndex: Int,
        refreshRate: Int,
    ): NavigationFrameResult {
        val controller = NavigationLandingController(targetIndex, -PROBE_OFFSET)
        var result = NavigationFrameResult.Continue
        repeat(refreshRate * 5) {
            result = controller.scrollFrame(transcript.scope, 1_000_000_000L / refreshRate, VIEWPORT_HEIGHT)
            if (result != NavigationFrameResult.Continue) return result
        }
        throw AssertionError("navigation did not finish; tail=${transcript.moves.takeLast(6)}")
    }

    private class TranscriptGeometry(heights: IntArray, initialScroll: Int) {
        val starts = IntArray(heights.size)
        private val totalHeight: Int
        var scrollPosition = initialScroll
            private set
        private var fractionalScroll = 0f
        val moves = mutableListOf<Float>()
        val positions = mutableListOf<Int>()
        val scope: LazyScrollSession

        init {
            var next = 0
            for (index in heights.indices) {
                starts[index] = next
                next += heights[index]
            }
            totalHeight = next
            val items = heights.indices.map { index ->
                mockk<LazyListItemInfo>(relaxed = true).also { item ->
                    every { item.index } returns index
                    every { item.size } returns heights[index]
                    every { item.offset } answers { starts[index] - scrollPosition }
                }
            }
            fun visibleItems() = items.filterIndexed { index, _ ->
                starts[index] + heights[index] > scrollPosition &&
                    starts[index] < scrollPosition + VIEWPORT_HEIGHT
            }
            val state = mockk<LazyListState>(relaxed = true)
            val layout = mockk<LazyListLayoutInfo>(relaxed = true)
            every { state.layoutInfo } returns layout
            every { layout.totalItemsCount } returns heights.size
            every { layout.visibleItemsInfo } answers { visibleItems() }
            every { layout.mainAxisItemSpacing } returns 0
            every { state.firstVisibleItemIndex } answers { visibleItems().first().index }
            every { state.firstVisibleItemScrollOffset } answers {
                scrollPosition - starts[visibleItems().first().index]
            }
            scope = LazyLayoutScrollScope(state, object : ScrollScope {
                override fun scrollBy(pixels: Float): Float {
                    fractionalScroll += pixels
                    val roundedDelta = fractionalScroll.roundToInt()
                    val nextPosition = (scrollPosition + roundedDelta)
                        .coerceIn(0, totalHeight - VIEWPORT_HEIGHT)
                    val appliedDelta = nextPosition - scrollPosition
                    scrollPosition = nextPosition
                    fractionalScroll -= appliedDelta
                    val consumed = if (appliedDelta == roundedDelta) pixels else {
                        (pixels - fractionalScroll).also { fractionalScroll = 0f }
                    }
                    moves += consumed
                    positions += scrollPosition
                    return consumed
                }
            })
        }
    }

    private companion object {
        const val VIEWPORT_HEIGHT = 1_000
        const val PROBE_OFFSET = 280
    }
}
