package com.suixing.agent.ui.drawer

import androidx.compose.animation.core.tween
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FRAME_NANOS = 16_000_000L

/**
 * Interaction-level drawer regressions. These drive the same state transitions as the two real
 * pointer loops, but with a real [SuixingDrawerState] (and therefore Animatable's mutator), real
 * coroutine cancellation, and a test frame clock. The pure accumulator tests cannot cover these:
 * they never suspend inside Animatable or cancel a live pointer Job.
 */
class DrawerGestureInteractionTest {

    @Test
    fun `a slow deliberate swipe settles away from either anchor without springing back`() = withHarness {
        for (initial in listOf(DrawerValue.Open, DrawerValue.Closed)) {
            state.snapTo(initial)
            val wasOpen = state.isOpen
            val progress = if (wasOpen) 0.6f else 0.4f
            val session = state.beginGesture()
            state.applyGestureFrame(session, progress)
            assertTrue(state.finishGesture(session))

            val target = drawerDragTarget(wasOpen, progress, 0f)
            val settle = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                state.settleGesture(session, target, DRAWER_SPEC, releaseProgress = progress)
            }
            pumpFrames(90)

            assertTrue(settle.isCompleted)
            assertFalse(settle.isCancelled)
            assertEquals(!wasOpen, state.isOpen)
            assertEquals(if (wasOpen) 0f else 1f, state.progress.value, 0.0001f)
            assertEquals(!wasOpen, state.homeShieldActive)
        }
    }

    @Test
    fun `a short fast swipe on the moving shield settles closed`() = withHarness {
        state.snapTo(DrawerValue.Open)
        val session = state.beginGesture()
        val accumulator = DrawerDragAccumulator()
        val generation = accumulator.begin(progress = 1f, widthPx = 1_000f)
        val tracker = VelocityTracker()
        for (sample in 0..5) {
            val position = Offset(50f, 0f)
            tracker.trackDrawerPointer(PointerInputChange(
                id = PointerId(1L),
                uptimeMillis = sample * 16L,
                position = position,
                pressed = true,
                previousUptimeMillis = ((sample - 1) * 16L).coerceAtLeast(0),
                previousPosition = if (sample == 0) position else position + Offset(20f, 0f),
                previousPressed = sample != 0,
                isInitiallyConsumed = true,
            ).withDrawerOriginalPosition(Offset(1_050f - 20f * sample, 0f)))
            if (sample > 0) accumulator.dragBy(generation, -20f)
        }
        val release = accumulator.finish(generation)!!
        state.applyGestureFrame(session, release.progress)
        assertTrue(state.finishGesture(session))
        // Just 10% travel: distance alone would stay open, so this must use the actual fling.
        assertEquals(0.9f, release.progress, 0.0001f)
        val velocity = tracker.calculateVelocity().x
        assertTrue("the shield must preserve leftward fling velocity", velocity < -800f)
        val target = drawerDragTarget(state.isOpen, release.progress, velocity)
        val settle = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.settleGesture(session, target, DRAWER_SPEC,
                initialVelocity = velocity / 1_000f, releaseProgress = release.progress)
        }
        pumpFrames(90)

        assertTrue(settle.isCompleted)
        assertFalse(settle.isCancelled)
        assertFalse(state.isOpen)
        assertFalse(state.homeShieldActive)
        assertEquals(0f, state.progress.value, 0.0001f)
    }

    @Test
    fun `the shield spans the whole opening and closing animation`() = withHarness {
        state.snapTo(DrawerValue.Closed)
        assertFalse(state.homeShieldActive)

        val opening = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.animateTo(DrawerValue.Open, DRAWER_OPEN_SPEC)
        }
        assertTrue("shield must mount before the first opening frame", state.homeShieldActive)
        assertFalse("settled is still closed during opening", state.isOpen)
        pumpFrames(90)
        assertTrue(opening.isCompleted)
        assertTrue(state.isOpen)
        assertTrue(state.homeShieldActive)

        // System Back and route actions both close through animateTo.
        val closing = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.animateTo(DrawerValue.Closed, DRAWER_CLOSE_SPEC)
        }
        assertTrue("shield must stay mounted for the closing transition", state.homeShieldActive)
        pumpFrames(90)
        assertTrue(closing.isCompleted)
        assertFalse(state.isOpen)
        assertFalse(state.homeShieldActive)
    }

    @Test
    fun `a half drag released open keeps the shield through the rebound`() = withHarness {
        state.snapTo(DrawerValue.Closed)
        val session = state.beginGesture()
        state.applyGestureFrame(session, 0.5f)

        assertTrue(state.finishGesture(session))
        val settle = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.settleGesture(
                session,
                DrawerValue.Open,
                DRAWER_OPEN_SPEC,
                releaseProgress = 0.5f,
            )
        }
        assertTrue("the rebound is part of the transition", state.homeShieldActive)
        pumpFrames(90)
        assertTrue(settle.isCompleted)
        assertTrue(state.isOpen)
        assertTrue(state.homeShieldActive)
    }

    @Test
    fun `a cancelled half drag keeps the shield until the panel closes`() = withHarness {
        state.snapTo(DrawerValue.Closed)
        val session = state.beginGesture()
        state.applyGestureFrame(session, 0.4f)
        assertTrue(state.homeShieldActive)

        assertTrue(state.cancelGesture(session))
        assertTrue("a cancelled half-open panel is still exposed", state.homeShieldActive)
        assertFalse(state.isOpen)

        val closing = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.animateTo(DrawerValue.Closed, DRAWER_CLOSE_SPEC)
        }
        assertTrue(state.homeShieldActive)
        pumpFrames(90)
        assertTrue(closing.isCompleted)
        assertFalse(state.homeShieldActive)

        val atZero = state.beginGesture()
        assertTrue("a fresh owned drag mounts the shield", state.homeShieldActive)
        state.applyGestureFrame(atZero, 0f)
        assertTrue(state.cancelGesture(atZero))
        assertFalse("zero progress after cancel exposes nothing", state.homeShieldActive)
    }

    @Test
    fun `a pointer-input restart releases only its own drag and never starts a stale settle`() =
        withHarness {
            state.progress.snapTo(0.5f)
            val accumulator = DrawerDragAccumulator()
            val first = state.beginGesture()
            val firstGeneration = accumulator.begin(progress = 0.5f, widthPx = 200f)
            accumulator.dragBy(firstGeneration, 40f)
            state.applyGestureFrame(first, 0.7f)
            assertEquals(0.7f, state.progress.value, 0.0001f)

            // The host pointerInput key changes (route/width) and its Job is cancelled without
            // onDragCancel. Only the finally block in the production loop runs here.
            val hostJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    cancelOwnedDrawerDrag(state, accumulator, first, firstGeneration)
                }
            }
            hostJob.cancelAndJoin()

            assertFalse(state.isGestureActive(first))
            assertNull(state.gestureSession)
            assertEquals("pointer cancellation must not settle", 0.7f, state.progress.value, 0.0001f)

            // A newer drag owns the drawer. A late finalizer from the old pointer Job must not
            // clear it, and the old release must not move the panel.
            val second = state.beginGesture()
            assertTrue(state.isGestureActive(second))
            state.cancelGesture(first)
            assertSame(second, state.gestureSession)

            val staleSettle = scope.launch {
                state.settleGesture(
                    first,
                    DrawerValue.Closed,
                    DRAWER_SPEC,
                    releaseProgress = 0.7f,
                )
            }
            staleSettle.join()
            pumpFrames(30)
            assertEquals(0.7f, state.progress.value, 0.0001f)
            assertFalse(state.isOpen)
        }

    @Test
    fun `external close cancels a frame already queued inside Animatable's mutator`() =
        withHarness {
            state.snapTo(DrawerValue.Open)
            assertTrue(state.isOpen)
            val accumulator = DrawerDragAccumulator()
            val session = state.beginGesture()
            val generation = accumulator.begin(progress = 1f, widthPx = 300f)
            accumulator.dragBy(generation, -90f)
            state.applyGestureFrame(session, 0.7f)

            // Hold Animatable's mutator with an animation that only advances on test frames.
            val blocker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                state.progress.animateTo(0.9f, tween(durationMillis = 10_000))
            }
            // This frame has already passed the ownership check and is waiting for the mutator.
            val staleFrame = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                state.applyGestureFrame(session, 0.2f)
            }
            yield()

            // System Back / route close revokes the gesture epoch before its first animation
            // frame. It must cancel that queued snap, not wait behind it and get reversed.
            val close = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                state.animateTo(DrawerValue.Closed, DRAWER_SPEC)
            }
            assertFalse(state.isGestureActive(session))
            assertNull(state.gestureSession)
            assertTrue("system Back keeps the shield through its close", state.homeShieldActive)

            val staleSettle = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                state.settleGesture(
                    session,
                    DrawerValue.Open,
                    DRAWER_SPEC,
                    releaseProgress = 0.2f,
                )
            }
            staleSettle.join()
            blocker.cancelAndJoin()
            staleFrame.join()
            pumpFrames(150)

            assertTrue("the external close must finish", close.isCompleted)
            assertFalse("an old release must not reopen the drawer", state.isOpen)
            assertFalse("the shield drops after the close settles", state.homeShieldActive)
            assertEquals(0f, state.progress.value, 0.0001f)
        }

    @Test
    fun `a new reverse drag cancels a release queued inside Animatable's mutator`() = withHarness {
        state.progress.snapTo(0.2f)
        val first = state.beginGesture()
        assertTrue("the first owner shields home", state.homeShieldActive)
        val firstAccumulator = DrawerDragAccumulator()
        val firstGeneration = firstAccumulator.begin(progress = 0.2f, widthPx = 200f)
        firstAccumulator.dragBy(firstGeneration, 100f)
        state.applyGestureFrame(first, 0.7f)
        val release = firstAccumulator.finish(firstGeneration)!!
        assertTrue(state.finishGesture(first))

        // Hold Animatable's mutator with an animation that only advances when we send a frame.
        // The release below therefore checks ownership and then suspends inside snapTo.
        val blocker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.progress.animateTo(0.9f, tween(durationMillis = 10_000))
        }
        val queuedRelease = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.settleGesture(
                first,
                DrawerValue.Closed,
                DRAWER_SPEC,
                releaseProgress = release.progress,
            )
        }
        yield()

        val second = state.beginGesture()
        assertTrue("the reverse owner keeps the shield mounted", state.homeShieldActive)
        val secondAccumulator = DrawerDragAccumulator()
        val secondGeneration = secondAccumulator.begin(progress = 0.7f, widthPx = 200f)
        val reverseProgress = secondAccumulator.dragBy(secondGeneration, -100f)!!
        val newFrame = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.applyGestureFrame(second, reverseProgress)
        }
        yield()

        blocker.cancelAndJoin()
        queuedRelease.join()
        newFrame.join()
        pumpFrames(30)

        assertTrue("the old release must not survive a newer gesture", queuedRelease.isCancelled)
        assertTrue(state.isGestureActive(second))
        assertEquals("the new drag owns the panel", reverseProgress, state.progress.value, 0.0001f)
        assertFalse(state.isOpen)
        assertTrue("an owned reverse drag leaves home shielded", state.homeShieldActive)
    }

    @Test
    fun `a queued old frame cannot write after a newer drag begins`() = withHarness {
        state.progress.snapTo(0.5f)
        val first = state.beginGesture()
        val accumulator = DrawerDragAccumulator()
        val generation = accumulator.begin(progress = 0.5f, widthPx = 200f)
        accumulator.dragBy(generation, 20f)

        // Same narrow window as the release case, but for a frame: it has already passed the
        // active-session check and is waiting in Animatable's mutator when a new drag starts.
        val blocker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.progress.animateTo(0.9f, tween(durationMillis = 10_000))
        }
        val staleFrame = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.applyGestureFrame(first, 0.3f)
        }
        yield()

        val second = state.beginGesture()
        yield()
        blocker.cancelAndJoin()
        staleFrame.join()

        assertEquals("a stale snap must not write after new ownership", 0.5f, state.progress.value, 0.0001f)

        state.applyGestureFrame(second, 0.8f)
        assertEquals(0.8f, state.progress.value, 0.0001f)
        assertTrue(state.isGestureActive(second))
    }

    @Test
    fun `the production frame loop follows the newest session and stops on finish`() =
        withHarness {
            state.progress.snapTo(0.1f)
            val accumulator = DrawerDragAccumulator()
            val first = state.beginGesture()
            val firstGeneration = accumulator.begin(progress = 0.1f, widthPx = 200f)
            accumulator.dragBy(firstGeneration, 20f)
            val pump = scope.launch {
                runDrawerDragFrameLoop(
                    sessions = snapshotFlow { state.gestureSession },
                    dragAccumulator = accumulator,
                    isActive = state::isGestureActive,
                    applyFrame = state::applyGestureFrame,
                    awaitFrame = {
                        Snapshot.sendApplyNotifications()
                        yield()
                    },
                )
            }
            repeat(12) { yield() }
            assertEquals(0.2f, state.progress.value, 0.0001f)

            val second = state.beginGesture()
            val secondGeneration = accumulator.begin(progress = 0.2f, widthPx = 200f)
            val secondProgress = accumulator.dragBy(secondGeneration, 40f)!!
            Snapshot.sendApplyNotifications()
            repeat(12) { yield() }
            assertEquals(secondProgress, state.progress.value, 0.0001f)

            assertTrue(state.finishGesture(second))
            Snapshot.sendApplyNotifications()
            repeat(8) { yield() }
            assertFalse(state.isGestureActive(second))
            pump.cancelAndJoin()
        }

    private class Harness(
        val state: SuixingDrawerState,
        val scope: CoroutineScope,
        private val clock: BroadcastFrameClock,
    ) {
        private var frameNanos = 0L

        suspend fun pumpFrame(stepNanos: Long = FRAME_NANOS) {
            Snapshot.sendApplyNotifications()
            frameNanos += stepNanos
            clock.sendFrame(frameNanos)
            Snapshot.sendApplyNotifications()
            repeat(6) { yield() }
        }

        suspend fun pumpFrames(count: Int) {
            repeat(count) { pumpFrame() }
        }
    }

    private fun withHarness(block: suspend Harness.() -> Unit) = runBlocking {
        val clock = BroadcastFrameClock()
        val jobs = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + clock + jobs)
        val harness = Harness(SuixingDrawerState(DrawerValue.Closed), scope, clock)
        try {
            harness.block()
        } finally {
            jobs.cancelAndJoin()
            Snapshot.sendApplyNotifications()
        }
    }
}
