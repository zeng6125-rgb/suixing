package com.suixing.agent.ui.chat

import android.os.SystemClock
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntSize
import com.suixing.agent.data.AppSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class NestedFileFlingTest {
    @After fun tearDown() = unmockkAll()

    @Test fun nestedFlingKeepsMovingTheTranscriptAfterTheFileListReachesItsEdge() = withFixture { fixture ->
        val fling = fixture.startFling()
        try {
            fixture.frames()
            assertTrue(fixture.child.isScrollInProgress)
            assertFalse("nested raw deltas do not acquire the parent's scroll lock", fixture.parent.isScrollInProgress)
            assertTrue("anchoring must not rewind every real nested fling frame", fixture.parent.firstVisibleItemScrollOffset > 0)

            fling.cancelAndJoin()
            fixture.frames()
            val stopped = fixture.parent.firstVisibleItemScrollOffset
            fixture.owner.contentPlaced()
            assertEquals("ending the child must not restore its pre-fling parent offset", stopped, fixture.parent.firstVisibleItemScrollOffset)
            fixture.textBaseY += 24f
            fixture.owner.contentPlaced()
            assertEquals("ordinary text reflow still repairs the current reading point", stopped + 24, fixture.parent.firstVisibleItemScrollOffset)
        } finally { fling.cancelAndJoin() }
    }

    @Test fun latestFollowingCannotPinTheParentWhileAnInnerScrollOwnsMotion() = withFixture { fixture ->
        fixture.owner.following = { true }
        val moving = fixture.holdChildScroll()
        try {
            fixture.owner.viewportPlaced(fixture.root)
            assertEquals("following must not request an end position during child motion", 0, fixture.parent.firstVisibleItemScrollOffset)
            moving.cancelAndJoin()
            fixture.frames()
            fixture.owner.viewportPlaced(fixture.root)
            assertEquals(TRANSCRIPT_END_OFFSET, fixture.parent.firstVisibleItemScrollOffset)
        } finally { moving.cancelAndJoin() }
    }

    @Test fun cancellingAChildScrollReleasesTheHoldAndRepairsDeferredTextGrowth() = withFixture { fixture ->
        val moving = fixture.holdChildScroll()
        try {
            fixture.textBaseY += 35f
            fixture.owner.contentPlaced()
            assertEquals("in-range child scrolling must defer reflow repair", 0, fixture.parent.firstVisibleItemScrollOffset)
            moving.cancelAndJoin()
            fixture.frames()
            assertFalse(fixture.child.isScrollInProgress)
            fixture.owner.contentPlaced()
            assertEquals(35, fixture.parent.firstVisibleItemScrollOffset)
        } finally { moving.cancelAndJoin() }
    }

    @Test fun disposingTheFileListUnregistersItsStillActiveScrollState() = withFixture { fixture ->
        val moving = fixture.holdChildScroll()
        try {
            fixture.unregister()
            fixture.owner.interactionChanged()
            fixture.textBaseY += 18f
            fixture.owner.contentPlaced()
            assertEquals("a detached child cannot leave the transcript held", 18, fixture.parent.firstVisibleItemScrollOffset)
        } finally { moving.cancelAndJoin() }
    }

    @Test fun reachingBottomCannotResumeFollowingDuringChildMomentum() = withFixture { fixture ->
        every { fixture.parent.canScrollForward } returns false
        val moving = fixture.holdChildScroll()
        try {
            fixture.withScroll { scroll ->
                delay(460)
                fixture.frames()
                assertFalse("the 420ms bottom delay must not outlive a child's fling", scroll.stickToBottom)

                scroll.allowBottomResume = false
                moving.cancelAndJoin()
                fixture.frames()
                delay(460)
                fixture.frames()
                assertFalse("pagination's bottom-resume policy still applies after child motion", scroll.stickToBottom)

                scroll.allowBottomResume = true
                fixture.frames()
                delay(460)
                fixture.frames()
                assertTrue("idle bottom following resumes through the existing delay", scroll.stickToBottom)
            }
        } finally { moving.cancelAndJoin() }
    }

    @Test fun completionWaitsForChildFlingAndSettledRunsStaySettled() = withFixture { fixture ->
        val state = ChatUiState(conversationId = "chat", loading = false)
        val settings = AppSettings()
        val gesture = ChatReadingGesture().apply { pressed = true }
        val expansion = ReasoningExpansion("chat", mutableMapOf()).apply { protectRun("run") }
        val testViewport = ConversationViewport("chat", null)
        val transcript = mockk<ChatTranscriptProjection> {
            every { listState } returns fixture.parent
            every { viewport } returns testViewport
        }
        val row = fixture.parent.layoutInfo.visibleItemsInfo.single()
        every { row.key } returns liveRowKey("chat", "run")
        fixture.withScroll(content = { scroll ->
            val position = rememberChatReadingPosition(state, settings, transcript, gesture, scroll, expansion)
            DisposableEffect(position) {
                position.registerNestedScroll(fixture.child)
                onDispose { position.unregisterNestedScroll(fixture.child) }
            }
        }) {
            assertTrue("completion first waits for the reader's press", expansion.completionDeferred("run"))
            val fling = fixture.startFling()
            try {
                gesture.pressed = false
                fixture.frames()
                assertTrue(fixture.child.isScrollInProgress)
                assertFalse(fixture.parent.isScrollInProgress)
                assertTrue("child momentum must keep the visible run's geometry live", expansion.completionDeferred("run"))

                while (fling.isActive) fixture.frames()
                fixture.frames()
                assertFalse(fixture.child.isScrollInProgress)
                assertFalse("completion releases after native child momentum ends", expansion.completionDeferred("run"))
                expansion.settleSummary("run", expanded = false, deferred = expansion.completionDeferred("run"))
            } finally { fling.cancelAndJoin() }

            val laterFling = fixture.startFling()
            try {
                fixture.frames()
                assertTrue(fixture.child.isScrollInProgress)
                assertFalse("a settled run must not reopen its completion hold", expansion.completionDeferred("run"))
            } finally { laterFling.cancelAndJoin() }
        }
    }

    private fun withFixture(block: suspend (Fixture) -> Unit) = runBlocking {
        withTimeout(15_000) {
            val fixture = Fixture(this)
            try { block(fixture) } finally { fixture.close() }
        }
    }

    private class Fixture(private val scope: CoroutineScope) {
        val parent = spyk(LazyListState())
        val child = LazyListState()
        val owner = TranscriptPosition(parent)
        val root = mockk<LayoutCoordinates>()
        var textBaseY = 120f
        private val clock = BroadcastFrameClock()
        private var frameNanos = 0L
        private val observer: Job
        private val childState = scrollState(child)
        private val dispatcher = spyk(NestedScrollDispatcher())

        init {
            mockkStatic(SystemClock::class)
            every { SystemClock.uptimeMillis() } returns 1000L
            val layout = mockk<LazyListLayoutInfo>()
            val row = mockk<LazyListItemInfo>()
            every { row.key } returns "reply"
            every { row.index } returns 0
            every { layout.visibleItemsInfo } returns listOf(row)
            every { layout.beforeContentPadding } returns 0
            every { layout.afterContentPadding } returns 0
            every { layout.viewportSize } returns IntSize(400, 800)
            every { layout.totalItemsCount } returns 1
            every { parent.layoutInfo } returns layout
            every { parent.canScrollForward } returns true
            // Supply the layout engine's offset update; requestScrollToItem remains real.
            val raw = ScrollableState { delta ->
                updateOffset(parent, parent.firstVisibleItemScrollOffset + delta.roundToInt())
                delta
            }
            LazyListState::class.java.getDeclaredField("scrollableState").apply {
                isAccessible = true
                set(parent, raw)
            }
            val parentConnection = object : NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                    Offset(0f, parent.dispatchRawDelta(available.y))
            }
            // Only the absent Android modifier attachment is supplied here. Dispatch is real.
            val parentGetter = NestedScrollDispatcher::class.java.getMethod("getParent\$ui")
            every { parentGetter.invoke(dispatcher) } returns parentConnection
            val coordinates = mockk<LayoutCoordinates>()
            every { root.isAttached } returns true
            every { coordinates.isAttached } returns true
            every { coordinates.size } returns IntSize(200, 500)
            every { root.localPositionOf(coordinates, any()) } answers {
                val point = Offset(secondArg<Long>())
                Offset(point.x, textY() + point.y)
            }
            every { coordinates.localPositionOf(root, any()) } answers {
                val point = Offset(secondArg<Long>())
                Offset(point.x, point.y - textY())
            }
            every { root.localBoundingBoxOf(coordinates, any()) } answers { Rect(0f, textY(), 200f, textY() + 500f) }
            val text = mockk<TextLayoutResult>()
            val input = mockk<TextLayoutInput>()
            every { input.text } returns AnnotatedString("the reading point above the file card")
            every { text.layoutInput } returns input
            every { text.lineCount } returns 1
            every { text.getLineForVerticalPosition(any()) } returns 0
            every { text.getLineStart(0) } returns 0
            every { text.getLineTop(0) } returns 0f
            every { text.getLineForOffset(any()) } returns 0
            owner.reading = { !parent.isScrollInProgress }
            owner.viewportPlaced(root)
            owner.textPlaced("text", "reply", coordinates) { text }
            owner.interactionChanged()
            register()
            observer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                snapshotFlow { child.isScrollInProgress }.distinctUntilChanged().collect { owner.interactionChanged() }
            }
        }

        private fun textY() = textBaseY - parent.firstVisibleItemScrollOffset

        suspend fun holdChildScroll(): Job {
            val started = CompletableDeferred<Unit>()
            val moving = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                childState.scroll(MutatePriority.Default) { started.complete(Unit); CompletableDeferred<Unit>().await() }
            }
            started.await()
            frames()
            return moving
        }

        fun startFling(): Job = scope.launch(clock, CoroutineStart.UNDISPATCHED) {
            val behavior = Class.forName("androidx.compose.foundation.gestures.DefaultFlingBehavior")
                .getDeclaredConstructor(DecayAnimationSpec::class.java, MotionDurationScale::class.java)
                .newInstance(exponentialDecay<Float>(), object : MotionDurationScale { override val scaleFactor = 1f }) as FlingBehavior
            childState.scroll(MutatePriority.Default) {
                val flingScope = object : ScrollScope {
                    override fun scrollBy(pixels: Float): Float {
                        // The file list is at its end; the native nested chain hands off the delta.
                        val consumed = dispatcher.dispatchPostScroll(Offset.Zero, Offset(0f, pixels), NestedScrollSource.SideEffect).y
                        owner.contentPlaced()
                        return consumed
                    }
                }
                with(behavior) { flingScope.performFling(2000f) }
            }
        }

        suspend fun frames() {
            repeat(3) {
                Snapshot.sendApplyNotifications()
                repeat(8) { yield() }
                frameNanos += 16_000_000L
                clock.sendFrame(frameNanos)
                Snapshot.sendApplyNotifications()
                repeat(8) { yield() }
            }
        }

        suspend fun withScroll(content: @Composable (ChatScroll) -> Unit = {}, block: suspend (ChatScroll) -> Unit) {
            val recomposer = Recomposer(scope.coroutineContext + clock)
            val composition = Composition(object : AbstractApplier<Unit>(Unit) {
                override fun insertTopDown(index: Int, instance: Unit) = Unit
                override fun insertBottomUp(index: Int, instance: Unit) = Unit
                override fun move(from: Int, to: Int, count: Int) = Unit
                override fun remove(index: Int, count: Int) = Unit
                override fun onClear() = Unit
            }, recomposer)
            val runner = scope.launch(clock, CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
            val nestedNow = { owner.nestedScrolling }
            lateinit var scroll: ChatScroll
            try {
                composition.setContent {
                    scroll = rememberChatScroll(parent, true, { false }, { false }, initialFollowing = false)
                    scroll.restoring = false
                    scroll.nestedScrolling = nestedNow
                    content(scroll)
                }
                frames()
                block(scroll)
            } finally {
                composition.dispose()
                recomposer.close()
                runner.cancelAndJoin()
            }
        }

        private fun register() { owner.registerNestedScroll(child) }
        fun unregister() { owner.unregisterNestedScroll(child) }

        suspend fun close() { unregister(); observer.cancelAndJoin() }

        companion object {
            private fun scrollState(list: LazyListState): ScrollableState = LazyListState::class.java
                .getDeclaredField("scrollableState").apply { isAccessible = true }.get(list) as ScrollableState

            private fun updateOffset(list: LazyListState, offset: Int) {
                val position = LazyListState::class.java.getDeclaredField("scrollPosition").apply { isAccessible = true }.get(list)
                position.javaClass.getMethod("updateScrollOffset", Int::class.javaPrimitiveType).invoke(position, offset)
            }
        }
    }
}
