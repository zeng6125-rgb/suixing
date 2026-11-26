package com.suixing.agent.ui.drawer

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.HistoricalChange
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
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
    fun `programmatic opening and closing keep horizontal steps bounded at 120 hz`() {
        for (opening in listOf(true, false)) {
            val animation = TargetBasedAnimation(
                animationSpec = if (opening) DRAWER_OPEN_SPEC else DRAWER_CLOSE_SPEC,
                typeConverter = Float.VectorConverter,
                initialValue = if (opening) 0f else 1f,
                targetValue = if (opening) 1f else 0f,
            )
            val lastFrame = 54
            val positions = (0..lastFrame).map { animation.getValueFromNanos(it * 1_000_000_000L / 120) }
            val largestStep = positions.zipWithNext { a, b -> kotlin.math.abs(b - a) }.max()
            assertTrue("Largest per-frame step: $largestStep", largestStep <= 0.075f)
            assertEquals(if (opening) 1f else 0f, positions.last(), 0.0001f)
        }
    }

    @Test
    fun `a missing touch tick still moves the visible drawer between two real samples`() = withHarness {
        val session = state.beginGesture()
        val displayTick = 8_333_333L
        state.presentDragSample(session, 0.10f, 0L, scope)
        assertEquals(0.10f, state.visualProgress.value, 0f)
        pumpFrame(displayTick)

        // The second pointer sample arrives 16 ms after the first, across two display ticks.
        state.presentDragSample(session, 0.30f, 16L, scope)
        pumpFrame(displayTick)
        assertEquals(0.2041667f, state.visualProgress.value, 0.0001f)
        pumpFrame(displayTick)
        assertEquals(0.30f, state.visualProgress.value, 0f)
        pumpFrame(displayTick)
        assertEquals("No extrapolation while the finger is still", 0.30f, state.visualProgress.value, 0f)

        state.presentDragSample(session, 0.45f, 34L, scope)
        assertTrue(state.finishGesture(session))
        val settle = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.settleGesture(session, DrawerValue.Open, DRAWER_OPEN_SPEC, initialVelocity = 1f)
        }
        assertEquals("The spring starts from the displayed position", 0.30f, state.visualProgress.value, 0f)
        pumpFrames(48)
        assertTrue(settle.isCompleted)
        assertEquals(1f, state.visualProgress.value, 0f)
    }

    @Test
    @OptIn(ExperimentalComposeUiApi::class)
    fun `a batched move preserves a reversal even when the net delta is zero`() = withHarness {
        val session = state.beginGesture()
        val accumulator = DrawerDragAccumulator()
        val generation = accumulator.begin(0f, 300f)
        state.presentDragSample(session, accumulator.dragBy(generation, 90f), 0L, scope)
        val displayTick = 8_333_333L
        pumpFrame(displayTick)

        val change = PointerInputChange(
            id = PointerId(1),
            uptimeMillis = 17L,
            position = Offset(90f, 0f),
            pressed = true,
            previousUptimeMillis = 0L,
            previousPosition = Offset(90f, 0f),
            previousPressed = true,
            isInitiallyConsumed = false,
        ).copy(historical = listOf(HistoricalChange(8L, Offset(150f, 0f))))
        state.presentDragChange(session, accumulator, generation, change, 0f, scope)

        pumpFrame(displayTick)
        assertEquals(0.49259f, state.visualProgress.value, 0.0001f)
        pumpFrame(displayTick)
        assertEquals(0.30741f, state.visualProgress.value, 0.0001f)
        pumpFrame(displayTick)
        assertEquals(0.3f, state.visualProgress.value, 0.0001f)
        assertEquals(0.3f, accumulator.finish(generation)!!.progress, 0.0001f)
    }

    @Test
    fun `closing snaps the chat less than one physical pixel on its last frame`() {
        val homeLeftTravelPx = DRAWER_SHEET_WIDTH * 3f + 1080f * 0.025f
        for ((start, velocity) in listOf(1f to 0f, 0.48f to -1.5f)) {
            val animation = TargetBasedAnimation(
                animationSpec = DRAWER_CLOSE_SPEC,
                typeConverter = Float.VectorConverter,
                initialValue = start,
                targetValue = 0f,
                initialVelocityVector = Float.VectorConverter.convertToVector(velocity),
            )
            for (refreshRate in listOf(60, 120)) {
                val previousFrame = animation.durationNanos - 1_000_000_000L / refreshRate
                val previousProgress = animation.getValueFromNanos(previousFrame)
                val lastStepPx = previousProgress * homeLeftTravelPx
                assertTrue("Close at $refreshRate Hz jumps $lastStepPx px from $start", lastStepPx < 1f)
                assertTrue(drawerHomeCornerFraction(previousProgress) < 0.005f)
                assertEquals(0f, animation.getValueFromNanos(animation.durationNanos), 0f)
            }
        }
    }

    @Test
    fun `opening settles the drawer less than one physical pixel on its last frame`() {
        val homeLeftTravelPx = DRAWER_SHEET_WIDTH * 3f + 1080f * 0.025f
        for ((start, velocity) in listOf(0f to 0f, 0.48f to 1.5f)) {
            val animation = TargetBasedAnimation(
                animationSpec = DRAWER_OPEN_SPEC,
                typeConverter = Float.VectorConverter,
                initialValue = start,
                targetValue = 1f,
                initialVelocityVector = Float.VectorConverter.convertToVector(velocity),
            )
            for (refreshRate in listOf(60, 120)) {
                val previousFrame = animation.durationNanos - 1_000_000_000L / refreshRate
                val previousProgress = animation.getValueFromNanos(previousFrame)
                val lastStepPx = (1f - previousProgress) * homeLeftTravelPx
                assertTrue("Open at $refreshRate Hz jumps $lastStepPx px from $start", lastStepPx < 1f)
                assertEquals(1f, animation.getValueFromNanos(animation.durationNanos), 0f)
            }
        }
    }

    @Test
    fun `chat corners follow the last part of drawer travel in either direction`() {
        val corners = listOf(0f, 0.01f, 0.05f, 0.09f, 0.10f, 1f).map(::drawerHomeCornerFraction)
        assertEquals(0f, corners.first(), 0f)
        assertEquals(0.5f, corners[2], 0.0001f)
        assertEquals(listOf(1f, 1f), corners.takeLast(2))
        assertTrue(corners.zipWithNext().all { (left, right) -> left <= right })
    }

    @Test
    fun `chat stays frozen through drag and close but lets a conversation switch prepare`() = withHarness {
        assertFalse(state.freezesChat(switchingConversation = false))
        val drag = state.beginGesture()
        assertTrue(state.freezesChat(switchingConversation = false))
        state.applyGestureFrame(drag, 0.4f)
        state.finishGesture(drag)
        assertTrue(state.freezesChat(switchingConversation = false))
        state.snapTo(DrawerValue.Open)
        assertTrue(state.freezesChat(switchingConversation = false))
        assertFalse(state.freezesChat(switchingConversation = true))

        val close = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.animateTo(DrawerValue.Closed, DRAWER_CLOSE_SPEC)
        }
        assertTrue(state.freezesChat(switchingConversation = true))
        pumpFrames(10)
        assertTrue(state.freezesChat(switchingConversation = true))
        pumpFrames(50)
        assertTrue(close.isCompleted)
        assertFalse(state.freezesChat(switchingConversation = true))
        assertFalse(state.freezesChat(switchingConversation = false))
    }

    @Test
    fun `closed drawer presents the cached chat before revealing live chat and resuming rows`() = withHarness {
        state.snapTo(DrawerValue.Open)
        var updatesAllowed = false
        scope.launch(start = CoroutineStart.UNDISPATCHED) { state.observeContentUpdates { updatesAllowed = it } }
        suspend fun flushChanges() {
            Snapshot.sendApplyNotifications()
            repeat(6) { yield() }
        }
        flushChanges()
        assertTrue(updatesAllowed)

        state.beginGesture()
        flushChanges()
        assertFalse(updatesAllowed)
        state.snapTo(DrawerValue.Closed)
        flushChanges()
        assertFalse(state.homeShieldActive)
        assertTrue(state.freezesChat(switchingConversation = false))
        assertFalse(updatesAllowed)
        pumpFrames(1)
        assertFalse(state.freezesChat(switchingConversation = false))
        assertFalse(updatesAllowed)
        pumpFrames(1)
        assertTrue(updatesAllowed)

        state.beginGesture()
        flushChanges()
        state.snapTo(DrawerValue.Closed)
        flushChanges()
        state.beginGesture()
        flushChanges()
        pumpFrames(2)
        assertFalse("A cancelled refresh cannot resume during the new drag", updatesAllowed)
        state.snapTo(DrawerValue.Open)
        flushChanges()
        assertTrue("An open drawer resumes without waiting for a frame", updatesAllowed)
    }

    @Test
    fun `a move updates visual progress before a cancelled animation releases its mutator`() = withHarness {
        state.progress.snapTo(0.5f)
        val session = state.beginGesture()
        val accumulator = DrawerDragAccumulator()
        val generation = accumulator.begin(progress = 0.5f, widthPx = 200f)
        val blocker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.progress.animateTo(0.9f, tween(durationMillis = 10_000))
        }
        val progress = accumulator.dragBy(generation, 40f)
        val move = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.applyGestureFrame(session, progress)
        }
        try {
            assertEquals("the new move must be visible without waiting for the old animation",
                0.7f, state.visualProgress.value, 0.0001f)
            assertTrue(state.isGestureActive(session))
        } finally {
            blocker.cancelAndJoin()
            move.cancelAndJoin()
        }
    }

    @Test
    fun `a slow deliberate swipe settles away from either anchor without springing back`() = withHarness {
        for (initial in listOf(DrawerValue.Open, DrawerValue.Closed)) {
            state.snapTo(initial)
            val wasOpen = state.isOpen
            val session = state.beginGesture()
            val drag = DrawerDragAccumulator()
            val generation = drag.begin(state.visualProgress.value, 300f)
            val progress = drag.dragBy(generation, if (wasOpen) -90f else 90f)
            state.applyGestureFrame(session, progress)
            assertTrue(state.finishGesture(session))

            val target = drawerDragTarget(drag.finish(generation)!!.openByDistance, 0f)
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
    fun `a fast release finishes at the first frame that reaches its anchor`() = withHarness {
        for (opening in listOf(true, false)) {
            state.snapTo(if (opening) DrawerValue.Closed else DrawerValue.Open)
            val session = state.beginGesture()
            state.applyGestureFrame(session, if (opening) 0.98f else 0.02f)
            assertTrue(state.finishGesture(session))
            val settle = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                state.settleGesture(
                    session,
                    if (opening) DrawerValue.Open else DrawerValue.Closed,
                    DRAWER_SPEC,
                    initialVelocity = if (opening) 8f else -8f,
                )
            }
            pumpFrames(3)
            assertEquals(if (opening) 1f else 0f, state.visualProgress.value, 0.0001f)
            assertTrue("the panel has hit the anchor, so its motion must be finished", settle.isCompleted)
            assertEquals(opening, state.isOpen)
            assertEquals(opening, state.homeShieldActive)
        }
    }

    @Test
    fun `reversing near the edge still reaches the requested anchor`() = withHarness {
        state.progress.snapTo(0.02f)
        val opening = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.animateTo(DrawerValue.Open, DRAWER_SPEC, initialVelocity = -30f)
        }
        pumpFrames(3)
        assertFalse(opening.isCompleted)
        assertTrue(state.homeShieldActive)
        pumpFrames(90)
        assertTrue(opening.isCompleted)
        assertTrue(state.isOpen)
        assertEquals(1f, state.visualProgress.value, 0.0001f)
    }

    @Test
    fun `a short fast swipe on the moving shield settles closed`() = withHarness {
        state.snapTo(DrawerValue.Open)
        val session = state.beginGesture()
        val accumulator = DrawerDragAccumulator()
        val generation = accumulator.begin(progress = 1f, widthPx = 1_000f)
        val tracker = DrawerPointerVelocity(touchSlop = 8f)
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
            ))
            if (sample > 0) accumulator.dragBy(generation, -20f)
        }
        val release = accumulator.finish(generation)!!
        state.applyGestureFrame(session, release.progress)
        assertTrue(state.finishGesture(session))
        // Just 10% travel: distance alone would stay open, so this must use the actual fling.
        assertEquals(0.9f, release.progress, 0.0001f)
        val velocity = tracker.calculateVelocity().x
        assertTrue("the shield must preserve leftward fling velocity", velocity < -800f)
        val target = drawerDragTarget(release.openByDistance, velocity)
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
            assertEquals(0.7f, state.visualProgress.value, 0.0001f)

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
            assertEquals("pointer cancellation must not settle", 0.7f, state.visualProgress.value, 0.0001f)

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
            assertEquals(0.7f, state.visualProgress.value, 0.0001f)
            assertFalse(state.isOpen)
        }

    @Test
    fun `external close rejects late movement and release from the previous pointer`() =
        withHarness {
            state.snapTo(DrawerValue.Open)
            assertTrue(state.isOpen)
            val session = state.beginGesture()
            state.applyGestureFrame(session, 0.7f)

            val close = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                state.animateTo(DrawerValue.Closed, DRAWER_SPEC)
            }
            state.applyGestureFrame(session, 0.2f)
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
        val reverseProgress = secondAccumulator.dragBy(secondGeneration, -100f)
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
        assertEquals("the new drag owns the panel", reverseProgress, state.visualProgress.value, 0.0001f)
        assertFalse(state.isOpen)
        assertTrue("an owned reverse drag leaves home shielded", state.homeShieldActive)
    }

    @Test
    fun `an old pointer cannot write after a newer drag begins`() = withHarness {
        state.progress.snapTo(0.5f)
        val first = state.beginGesture()
        val second = state.beginGesture()
        state.applyGestureFrame(first, 0.3f)

        assertEquals("a stale pointer must not write after new ownership",
            0.5f, state.visualProgress.value, 0.0001f)

        state.applyGestureFrame(second, 0.8f)
        assertEquals(0.8f, state.visualProgress.value, 0.0001f)
        assertTrue(state.isGestureActive(second))
    }

    @Test
    fun `pointer updates follow the newest session and stop on finish`() =
        withHarness {
            state.progress.snapTo(0.1f)
            val accumulator = DrawerDragAccumulator()
            val first = state.beginGesture()
            val firstGeneration = accumulator.begin(progress = 0.1f, widthPx = 200f)
            state.applyGestureFrame(first, accumulator.dragBy(firstGeneration, 20f))
            assertEquals(0.2f, state.visualProgress.value, 0.0001f)

            val second = state.beginGesture()
            val secondGeneration = accumulator.begin(progress = 0.2f, widthPx = 200f)
            val secondProgress = accumulator.dragBy(secondGeneration, 40f)
            state.applyGestureFrame(first, 0.9f)
            state.applyGestureFrame(second, secondProgress)
            assertEquals(secondProgress, state.visualProgress.value, 0.0001f)

            assertTrue(state.finishGesture(second))
            state.applyGestureFrame(second, 0.9f)
            assertEquals(secondProgress, state.visualProgress.value, 0.0001f)
            assertFalse(state.isGestureActive(second))
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
