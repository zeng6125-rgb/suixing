package com.suixing.agent.ui.chat

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FRAME_NANOS = 16_000_000L

/**
 * The tick animation used to be reachable only through a full `ChatScreen`. These tests run the
 * extracted helper in a real Compose composition with no node tree: a [Recomposer] whose
 * recomposition loop awaits a test [BroadcastFrameClock], plus an [AbstractApplier] that applies
 * nothing. Animation time only moves when a test sends a frame, so the tests never sleep and
 * cannot leak a pending dispatcher.
 */
class ScrubberHighlightTest {

    private val narrow = ScrubberTickStyle(8f, 2f, Color(0xFF336699))
    private val mid = ScrubberTickStyle(12f, 2f, Color(0xFF663399))
    private val wide = ScrubberTickStyle(20f, 3f, Color(0xFF993366))

    @Test
    fun `restoring keeps tick animations disabled`() {
        assertTrue(scrubberHighlightSettling(restoring = true, presented = true, entrance = 1f))
    }

    @Test
    fun `unpresented view keeps tick animations disabled`() {
        assertTrue(scrubberHighlightSettling(restoring = false, presented = false, entrance = 1f))
    }

    @Test
    fun `unfinished entrance keeps tick animations disabled`() {
        assertTrue(scrubberHighlightSettling(restoring = false, presented = true, entrance = 0f))
        assertTrue(scrubberHighlightSettling(restoring = false, presented = true, entrance = 0.99f))
    }

    @Test
    fun `completed entrance permits tick animations`() {
        assertFalse(scrubberHighlightSettling(restoring = false, presented = true, entrance = 1f))
    }

    @Test
    fun `clearing settling shows the final target without replaying the old tick`() = runBlocking {
        withScrubber(initial = narrow, settling = true) {
            assertStyle(narrow, style, "initial")

            target = wide
            awaitComposition(compositions)
            assertStyle(wide, style, "while settling")

            // The old springs keep running while the rail draws its targets; let them get
            // mid-flight so a release that reads them back would be visible.
            repeat(6) {
                pumpFrame()
                assertStyle(wide, style, "settling frame ${it + 1}")
            }

            settling = false
            awaitComposition(compositions)
            assertStyle(wide, style, "first frame after settling")

            repeat(3) {
                pumpFrame()
                assertStyle(wide, style, "frame ${it + 1} after settling")
            }
        }
    }

    @Test
    fun `targets changed while settling never draw an intermediate tick`() = runBlocking {
        withScrubber(initial = narrow, settling = true) {
            target = wide
            awaitComposition(compositions)
            assertStyle(wide, style, "first target")

            repeat(4) {
                pumpFrame()
                assertStyle(wide, style, "still settling ${it + 1}")
            }

            target = mid
            awaitComposition(compositions)
            assertStyle(mid, style, "second target")

            repeat(4) {
                pumpFrame()
                assertStyle(mid, style, "still settling on second target ${it + 1}")
            }
        }
    }

    @Test
    fun `conversation change starts from its own target`() = runBlocking {
        withScrubber(initial = narrow, settling = false) {
            pumpFrames(2)
            assertStyle(narrow, style, "at rest")

            conversationId = "conversation-b"
            target = wide
            awaitComposition(compositions)
            assertStyle(wide, style, "new conversation")
        }
    }

    @Test
    fun `normal reading resumes animation after settling`() = runBlocking {
        withScrubber(initial = narrow, settling = true) {
            target = wide
            awaitComposition(compositions)
            assertStyle(wide, style, "while settling")

            settling = false
            awaitComposition(compositions)
            assertStyle(wide, style, "after settling")

            target = narrow
            awaitComposition(compositions)
            val first = requireNotNull(style) { "nothing composed after reading resumed" }
            assertTrue("reading snapped instead of animating: ${first.width}",
                first.width > narrow.width + 0.5f)

            pumpFrames(5)
            val midway = requireNotNull(style) { "nothing composed while reading resumed" }
            assertTrue("expected motion, got ${midway.width}", midway.width < wide.width - 0.5f)
            assertTrue("expected motion, got ${midway.width}", midway.width > narrow.width + 0.1f)

            pumpFrames(120)
            assertStyle(narrow, style, "reading settled")
        }
    }

    @Test
    fun `entering settling discards an animation already in flight`() = runBlocking {
        withScrubber(initial = narrow, settling = false) {
            target = wide
            awaitComposition(compositions)
            pumpFrames(5)
            val inFlight = requireNotNull(style) { "nothing composed while animating" }
            assertTrue("animation never started: ${inFlight.width}",
                inFlight.width > narrow.width + 0.5f)
            assertTrue("animation already finished: ${inFlight.width}",
                inFlight.width < wide.width - 0.1f)

            settling = true
            target = mid
            awaitComposition(compositions)
            assertStyle(mid, style, "entering settling")
            repeat(3) {
                pumpFrame()
                assertStyle(mid, style, "settling frame ${it + 1}")
            }

            settling = false
            awaitComposition(compositions)
            assertStyle(mid, style, "leaving settling")
            repeat(3) {
                pumpFrame()
                assertStyle(mid, style, "after settling frame ${it + 1}")
            }
        }
    }

    @Test
    fun `normal reading still animates between targets`() = runBlocking {
        withScrubber(initial = narrow, settling = false) {
            pumpFrames(2)
            assertStyle(narrow, style, "before")

            target = wide
            awaitComposition(compositions)
            val first = requireNotNull(style) { "nothing composed after the target changed" }
            assertTrue(
                "the first frame snapped to the target: ${first.width}",
                first.width < wide.width - 0.5f,
            )

            pumpFrames(5)
            val midway = requireNotNull(style) { "nothing composed while animating" }
            assertTrue("expected motion, got ${midway.width}", midway.width > narrow.width + 0.5f)
            assertTrue("expected motion, got ${midway.width}", midway.width < wide.width - 0.1f)

            pumpFrames(120)
            assertStyle(wide, style, "settled")
        }
    }

    private fun assertStyle(
        expected: ScrubberTickStyle,
        actual: ScrubberTickStyle?,
        where: String,
    ) {
        val observed = requireNotNull(actual) { "$where: nothing composed" }
        assertEquals("$where width", expected.width, observed.width, 0.001f)
        assertEquals("$where height", expected.height, observed.height, 0.001f)
        assertEquals("$where color", expected.color, observed.color)
    }

    private suspend fun CoroutineScope.withScrubber(
        conversationId: String = "conversation-a",
        initial: ScrubberTickStyle,
        settling: Boolean = false,
        block: suspend ScrubberHarness.() -> Unit,
    ) {
        val harness = ScrubberHarness(this, conversationId, initial, settling)
        try {
            harness.start()
            harness.block()
        } finally {
            harness.close()
        }
    }

    private class ScrubberHarness(
        private val scope: CoroutineScope,
        initialConversationId: String,
        initialTarget: ScrubberTickStyle,
        initialSettling: Boolean,
    ) {
        var conversationId by mutableStateOf(initialConversationId)
        var target by mutableStateOf(initialTarget)
        var settling by mutableStateOf(initialSettling)

        var style: ScrubberTickStyle? = null
            private set
        var compositions: Int = 0
            private set

        private lateinit var recomposer: Recomposer
        private lateinit var composition: androidx.compose.runtime.Composition

        /**
         * The clock the recomposition loop awaits. Recomposer drives its own broadcast clock
         * (used by LaunchedEffect/animate*AsState) whenever this parent clock sends a frame.
         */
        private lateinit var parentClock: BroadcastFrameClock
        private var runner: Job? = null
        private var frameNanos = 0L

        suspend fun start() {
            recomposer = Recomposer(scope.coroutineContext)
            parentClock = BroadcastFrameClock()
            composition = androidx.compose.runtime.Composition(UnitApplier(), recomposer)
            runner = scope.launch(parentClock, CoroutineStart.UNDISPATCHED) {
                recomposer.runRecomposeAndApplyChanges()
            }
            composition.setContent {
                style = scrubberTickStyle(conversationId, target, settling)
                compositions++
            }
            awaitComposition(after = 0, maxFrames = 10)
            pumpFrames(2)
        }

        suspend fun pumpFrame(stepNanos: Long = FRAME_NANOS) {
            Snapshot.sendApplyNotifications()
            frameNanos += stepNanos
            parentClock.sendFrame(frameNanos)
            Snapshot.sendApplyNotifications()
            repeat(6) { yield() }
        }

        suspend fun pumpFrames(count: Int, stepNanos: Long = FRAME_NANOS) {
            repeat(count) { pumpFrame(stepNanos) }
        }

        /** Steps the clock only until the composition has run, at most [maxFrames] times. */
        suspend fun awaitComposition(after: Int, maxFrames: Int = 3) {
            Snapshot.sendApplyNotifications()
            repeat(8) { yield() }
            var frames = 0
            while (compositions <= after && frames < maxFrames) {
                pumpFrame()
                frames++
            }
            check(compositions > after) {
                "the composition did not run within $maxFrames frames"
            }
        }

        suspend fun close() {
            if (::composition.isInitialized) composition.dispose()
            if (::recomposer.isInitialized) recomposer.close()
            runner?.cancelAndJoin()
        }
    }

    private class UnitApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
