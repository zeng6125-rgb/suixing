package com.suixing.agent.ui.drawer

import androidx.compose.material3.DrawerValue
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Applier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputEventHandler
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.suixing.agent.ui.components.LocalDecorationMotion
import com.suixing.agent.ui.chat.ChatScroll
import com.suixing.agent.ui.chat.ChatReadingGesture
import com.suixing.agent.ui.chat.SelectionFocusTracker
import com.suixing.agent.ui.chat.TranscriptPosition
import com.suixing.agent.ui.chat.TRANSCRIPT_END_OFFSET
import com.suixing.agent.ui.chat.chatTranscriptGesture
import com.suixing.agent.ui.chat.rememberChatScroll
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

/** Run the drawer's actual pointerInput handlers with finite events, without Android windows. */
class DrawerPointerLoopTest {
    @Test fun `a transcript already following skips the bottom resume timer`() {
        val traces = mutableListOf<String>()
        val chat = DrawerChatFixture(initialFollowing = true, trace = traces::add)
            .apply { tailBottom.value = viewportHeight.value }
        withPointerLoop(DrawerValue.Closed, shield = false, events = emptyList(), homeContent = { chat.bind() }) {
            frames(3)
            delay(460)
            assertTrue(chat.scroll.stickToBottom)
            assertTrue("there is no follow state to restore", traces.isEmpty())
        }
    }

    @Test fun `a closed drawer left swipe cannot drag an already following transcript vertically`() {
        val chat = DrawerChatFixture(initialFollowing = true).apply { tailBottom.value = viewportHeight.value }
        val leftMove = change(16L, 120f, 220f, y = 30f)
        withPointerLoop(DrawerValue.Closed, shield = false, homeContent = { chat.bind() },
            homePointerInput = true, includeHost = true, events = listOf(
                event(change(0L, 220f, 220f, previousPressed = false)),
                event(leftMove),
                event(change(32L, 120f, 120f, y = 30f, previousY = 30f, pressed = false)),
            )) {
            assertTrue("plain transcript must consume the horizontal move before the list sees it", leftMove.isConsumed)
            assertFalse(state.homeShieldActive)
            assertTrue(chat.scroll.stickToBottom)
            chat.position.viewportPlaced(chat.coordinates)
            verify(exactly = 0) { chat.list.requestScrollToItem(any(), any()) }
        }
    }

    @Test fun `a closed drawer left swipe keeps a horizontal child eligible to move`() {
        val chat = DrawerChatFixture(initialFollowing = true)
        val leftMove = change(16L, 120f, 220f, y = 30f)
        withPointerLoop(DrawerValue.Closed, shield = false, homeContent = { chat.bind() },
            homePointerInput = true, includeHost = true, events = listOf(
                event(change(0L, 220f, 220f, previousPressed = false)),
                event(leftMove),
            ), beforeInput = {
                state.priorityPointerId = PointerId(1L)
                state.priorityCanScroll = { true }
            }) {
            assertFalse("the horizontal child needs the original pointer delta", leftMove.isConsumed)
            assertTrue(chat.scroll.stickToBottom)
        }
    }

    @Test fun `a vertical drag at the bottom passes to the list and releases following`() {
        val chat = DrawerChatFixture(initialFollowing = true).apply { tailBottom.value = viewportHeight.value }
        val verticalMove = change(16L, 220f, 220f, y = 30f)
        withPointerLoop(DrawerValue.Closed, shield = false, homeContent = { chat.bind() },
            homePointerInput = true, includeHost = true, events = listOf(
                event(change(0L, 220f, 220f, previousPressed = false)),
                event(verticalMove),
            )) {
            assertFalse(verticalMove.isConsumed)
            assertFalse(chat.scroll.stickToBottom)
        }
    }

    @Test fun `near-bottom reading survives rapid drawer reversals and later tail growth`() {
        val chat = DrawerChatFixture()
        try {
            withPointerLoop(DrawerValue.Closed, shield = false, homeContent = { chat.bind() },
                homePointerInput = true, includeHost = true,
                events = listOf(
                    event(change(0L, 100f, 100f, previousPressed = false)),
                    event(change(16L, 160f, 100f)),
                    event(change(32L, 200f, 160f, pressed = false)),
                ), beforeInput = {
                    frames(3)
                    assertFalse(chat.scroll.stickToBottom)
                    assertTrue(chat.list.canScrollForward)
                }) {
                // Hiding the keyboard can expose the end without a vertical chat scroll.
                chat.viewportHeight.value = 800
                repeat(64) { index ->
                    scope.launch(clock, CoroutineStart.UNDISPATCHED) {
                        state.animateTo(if (index % 2 == 0) DrawerValue.Closed else DrawerValue.Open,
                            DRAWER_SPEC)
                    }
                    frames(1 + index % 3)
                    assertTrue(state.visualProgress.value in 0f..1f)
                }
                scope.launch(clock, CoroutineStart.UNDISPATCHED) {
                    state.animateTo(DrawerValue.Closed, DRAWER_CLOSE_SPEC)
                }
                frames(90)
                assertFalse(state.isOpen)
                assertFalse(state.isMoving)
                assertFalse(state.homeShieldActive)
                assertSame(initialShieldNode, currentShieldNode())

                delay(460)
                frames(3)
                chat.tailBottom.value += 200
                frames(3)
                chat.position.viewportPlaced(chat.coordinates)

                assertEquals("drawer motion must not create a request to the transcript end",
                    8, chat.list.firstVisibleItemIndex)
                assertEquals(30, chat.list.firstVisibleItemScrollOffset)
                assertFalse("reading must remain reading after the drawer closes", chat.scroll.stickToBottom)
                verify(exactly = 0) { chat.list.requestScrollToItem(any(), any()) }
            }
        } finally { unmockkAll() }
    }

    @Test fun `a pending bottom resume cannot survive a quick drawer open and close`() {
        val chat = DrawerChatFixture()
        try {
            withPointerLoop(DrawerValue.Closed, shield = false, events = emptyList(),
                homeContent = { chat.bind() }, beforeInput = {
                    frames(3)
                    chat.viewportHeight.value = 800
                    frames(3)
                    delay(250)
                    assertFalse(chat.scroll.stickToBottom)
                }) {
                scope.launch(clock, CoroutineStart.UNDISPATCHED) {
                    state.animateTo(DrawerValue.Open, DRAWER_OPEN_SPEC)
                }
                frames(2)
                scope.launch(clock, CoroutineStart.UNDISPATCHED) {
                    state.animateTo(DrawerValue.Closed, DRAWER_CLOSE_SPEC)
                }
                frames(90)
                delay(460)
                frames(3)
                assertFalse(chat.scroll.stickToBottom)
                chat.tailBottom.value += 200
                chat.position.viewportPlaced(chat.coordinates)
                assertEquals(8, chat.list.firstVisibleItemIndex)
                verify(exactly = 0) { chat.list.requestScrollToItem(any(), any()) }
            }
        } finally { unmockkAll() }
    }

    @Test fun `drawer reversals before the first frame still preserve near-bottom reading`() {
        val chat = DrawerChatFixture()
        try {
            withPointerLoop(DrawerValue.Closed, shield = false, events = emptyList(),
                homeContent = { chat.bind() }, beforeInput = { frames(3) }) {
                repeat(20) {
                    scope.launch(clock, CoroutineStart.UNDISPATCHED) {
                        state.animateTo(DrawerValue.Open, DRAWER_OPEN_SPEC)
                    }
                    scope.launch(clock, CoroutineStart.UNDISPATCHED) {
                        state.animateTo(DrawerValue.Closed, DRAWER_CLOSE_SPEC)
                    }
                }
                assertFalse(state.homeShieldActive)
                chat.viewportHeight.value = 800
                frames(3)
                delay(460)
                frames(3)
                chat.tailBottom.value += 200
                chat.position.viewportPlaced(chat.coordinates)
                assertEquals("coalesced drawer states must not lose the reading interruption",
                    8, chat.list.firstVisibleItemIndex)
                assertFalse(chat.scroll.stickToBottom)
                verify(exactly = 0) { chat.list.requestScrollToItem(any(), any()) }
            }
        } finally { unmockkAll() }
    }

    @Test fun `vertical transcript input can resume following after the drawer closes`() {
        val chat = DrawerChatFixture()
        try {
            withPointerLoop(DrawerValue.Closed, shield = false, homeContent = { chat.bind() },
                homePointerInput = true, includeHost = true, events = listOf(
                    event(change(0L, 100f, 100f, previousPressed = false)),
                    event(change(16L, 100f, 100f, y = -40f)),
                    event(change(32L, 100f, 100f, y = -80f, previousY = -40f, pressed = false)),
                ), beforeInput = {
                    frames(3)
                    state.snapTo(DrawerValue.Open)
                    frames(3)
                    chat.viewportHeight.value = 800
                    state.snapTo(DrawerValue.Closed)
                    frames(3)
                    delay(460)
                    frames(3)
                    assertFalse(chat.scroll.stickToBottom)
                }) {
                frames(3)
                assertTrue(chat.scroll.allowBottomResume)
                delay(460)
                frames(3)
                assertTrue("a real vertical gesture can return to normal bottom following", chat.scroll.stickToBottom)
                chat.tailBottom.value += 200
                chat.position.viewportPlaced(chat.coordinates)
                assertEquals(9, chat.list.firstVisibleItemIndex)
                verify(exactly = 1) { chat.list.requestScrollToItem(9, TRANSCRIPT_END_OFFSET) }
            }
        } finally { unmockkAll() }
    }

    @Test fun `horizontal drawer input keeps an already following transcript following`() {
        val chat = DrawerChatFixture(initialFollowing = true)
        try {
            withPointerLoop(DrawerValue.Closed, shield = false, homeContent = { chat.bind() },
                homePointerInput = true, includeHost = true, events = listOf(
                    event(change(0L, 100f, 100f, previousPressed = false)),
                    event(change(16L, 160f, 100f, y = 4f)),
                    event(change(32L, 200f, 160f, y = 6f, previousY = 4f, pressed = false)),
                )) {
                frames(90)
                assertTrue(state.isOpen)
                assertTrue(chat.scroll.stickToBottom)
                chat.tailBottom.value += 200
                chat.position.viewportPlaced(chat.coordinates)
                assertEquals(9, chat.list.firstVisibleItemIndex)
                verify(exactly = 1) { chat.list.requestScrollToItem(9, TRANSCRIPT_END_OFFSET) }
            }
        } finally { unmockkAll() }
    }

    @Test
    fun `starting an opening drag reuses the shield already in the layout`() =
        withPointerLoop(DrawerValue.Closed, shield = false, events = listOf(
            event(change(0L, 100f, 100f, previousPressed = false)),
            event(change(16L, 140f, 100f)),
        )) {
            frames(1)
            assertTrue(state.homeShieldActive)
            assertNotNull(initialShieldNode)
            assertSame(initialShieldNode, currentShieldNode())
        }

    @Test
    fun `changing system back exclusion does not recompose the drawer host`() =
        withPointerLoop(DrawerValue.Closed, shield = false, events = listOf(
            event(change(0L, 100f, 100f, previousPressed = false)),
            event(change(16L, 140f, 100f)),
        )) {
            frames(3)
            assertTrue(state.homeShieldActive)
            assertEquals(1, panelDensityReads)
            state.snapTo(DrawerValue.Closed)
            frames(3)
            assertFalse(state.homeShieldActive)
            assertEquals(1, panelDensityReads)
        }

    @Test
    fun `home decorations pause until the drawer is fully closed`() =
        withPointerLoop(DrawerValue.Closed, shield = false, events = listOf(
            event(change(0L, 100f, 100f, previousPressed = false)),
            event(change(16L, 140f, 100f)),
            event(change(32L, 180f, 140f, pressed = false)),
        ), beforeInput = {
            assertTrue(homeDecorationMotion())
        }) {
            assertTrue(state.isMoving)
            assertFalse(homeDecorationMotion())
            frames(90)
            assertFalse(state.isMoving)
            assertTrue(state.isOpen)
            assertFalse(homeDecorationMotion())
            state.snapTo(DrawerValue.Closed)
            assertTrue(homeDecorationMotion())
        }

    @Test
    fun `the first horizontal segment follows the finger when the drawer takes ownership`() =
        withPointerLoop(DrawerValue.Closed, shield = false, events = listOf(
            event(change(0L, 100f, 100f, previousPressed = false)),
            event(change(16L, 140f, 100f)),
        )) {
            assertEquals(
                "taking ownership must not leave the touch-slop distance behind the finger",
                40f / 300f,
                state.visualProgress.value,
                0.0001f,
            )
        }

    @Test fun `a fast clear horizontal move begins following below ordinary slop`() {
        for (opening in listOf(true, false)) {
            val start = if (opening) 100f else 350f
            val delta = if (opening) 6f else -6f
            withPointerLoop(if (opening) DrawerValue.Closed else DrawerValue.Open,
                shield = !opening, events = listOf(
                    event(change(0L, start, start, previousPressed = false)),
                    event(change(8L, start + delta, start, y = 1f)),
                )) {
                assertEquals(if (opening) 6f / 300f else 1f - 6f / 300f,
                    state.visualProgress.value, 0.0001f)
            }
        }
    }

    @Test fun `a small diagonal slow or wrong direction move does not start the drawer early`() {
        val undecidedMoves = listOf(
            change(8L, 103f, 100f),
            change(8L, 106f, 100f, y = 3f),
            change(40L, 106f, 100f),
            change(8L, 94f, 100f),
        )
        for (move in undecidedMoves) {
            withPointerLoop(DrawerValue.Closed, shield = false, events = listOf(
                event(change(0L, 100f, 100f, previousPressed = false)),
                event(move),
            )) {
                assertEquals(0f, state.visualProgress.value, 0.0001f)
                assertFalse(move.isConsumed)
            }
        }
    }

    @Test fun `a horizontal child keeps priority over an early drawer move`() {
        val move = change(8L, 106f, 100f)
        withPointerLoop(DrawerValue.Closed, shield = false, includeHost = true,
            childOrientation = Orientation.Horizontal, events = listOf(
                event(change(0L, 100f, 100f, previousPressed = false)),
                event(move),
            )) {
            assertEquals(0f, state.visualProgress.value, 0.0001f)
            assertFalse(move.isConsumed)
        }
    }

    @Test fun `a small wrong way move at the open anchor can turn into a closing drag`() =
        withPointerLoop(DrawerValue.Open, shield = false, events = listOf(
            event(change(0L, 100f, 100f, previousPressed = false)),
            event(change(8L, 106f, 100f)),
            event(change(16L, 76f, 106f)),
        )) {
            assertEquals(1f - 24f / 300f, state.visualProgress.value, 0.0001f)
        }

    @Test
    fun `a deliberate drag moves the panel by the same distance as the finger`() =
        withPointerLoop(DrawerValue.Closed, shield = false, events = listOf(
            event(change(0L, 100f, 100f, previousPressed = false)),
            event(change(16L, 300f, 100f)),
        )) {
            assertEquals(
                200f / 300f,
                state.visualProgress.value,
                0.0001f,
            )
        }

    @Test fun `a slow continuing drag still opens after horizontal content reaches its start`() {
        var childOffset = 32f
        withPointerLoop(DrawerValue.Closed, shield = false, includeHost = true,
            childOrientation = Orientation.Horizontal,
            childCanScroll = { delta -> delta < 0f || childOffset > 0f },
            childDrag = { delta -> childOffset = (childOffset - delta).coerceAtLeast(0f) }, events = listOf(
                event(change(0L, 100f, 100f, previousPressed = false)),
                event(change(16L, 140f, 100f)),
                event(change(640L, 240f, 140f)),
                event(change(704L, 340f, 240f)),
            )) {
            assertEquals(0f, childOffset, 0.0001f)
            assertEquals("scrolling must not turn into a long-press owner mid-drag",
                200f / 300f, state.visualProgress.value, 0.0001f)
        }
    }

    @Test fun `a continuing drag opens the drawer after horizontal content reaches its start`() {
        var childOffset = 32f
        withPointerLoop(DrawerValue.Closed, shield = false, includeHost = true,
            childOrientation = Orientation.Horizontal,
            childCanScroll = { delta -> delta < 0f || childOffset > 0f },
            childDrag = { delta -> childOffset = (childOffset - delta).coerceAtLeast(0f) }, events = listOf(
                event(change(0L, 100f, 100f, previousPressed = false)),
                event(change(16L, 140f, 100f)),
                event(change(32L, 240f, 140f)),
                event(change(48L, 340f, 240f)),
            )) {
            assertEquals(0f, childOffset, 0.0001f)
            assertEquals("the remaining drag at the scroll boundary must reach the drawer",
                200f / 300f, state.visualProgress.value, 0.0001f)
        }
    }

    @Test fun `a slight vertical start does not discard the later dominant horizontal drag`() {
        var childScroll = 0f
        withPointerLoop(DrawerValue.Closed, shield = false, includeHost = true,
            childOrientation = Orientation.Vertical, childDrag = { childScroll += it }, events = listOf(
                event(change(0L, 100f, 100f, y = 100f, previousY = 100f, previousPressed = false)),
                event(change(16L, 104f, 100f, y = 112f, previousY = 100f)),
                event(change(32L, 204f, 104f, y = 113f, previousY = 112f)),
                event(change(48L, 304f, 204f, y = 114f, previousY = 113f)),
            )) {
            assertTrue("initial vertical scrolling must still be delivered", childScroll > 0f)
            assertEquals("later horizontal movement must be able to open the drawer",
                200f / 300f, state.visualProgress.value, 0.0001f)
        }
    }

    @Test fun `a cancelled close at zero lets the next deliberate swipe open`() =
        withPointerLoop(DrawerValue.Open, shield = false, events = listOf(
            event(change(0L, 100f, 100f, previousPressed = false)),
            event(change(64L, 140f, 100f)),
            event(change(128L, 180f, 140f)),
            event(change(192L, 280f, 180f)),
            event(change(288L, 280f, 280f, pressed = false)),
        ), beforeInput = {
            val gesture = state.beginGesture()
            state.applyGestureFrame(gesture, 0f)
            state.cancelGesture(gesture)
        }) {
            frames(90)
            assertTrue("the visible closed endpoint must allow the next swipe to open", state.isOpen)
            assertEquals(1f, state.visualProgress.value, 0.0001f)
        }

    @Test fun `a cancelled opening at one lets the next deliberate swipe close`() =
        withPointerLoop(DrawerValue.Closed, shield = true, includeHost = true, events = listOf(
            event(change(0L, 350f, 350f, previousPressed = false)),
            event(change(64L, 310f, 350f)),
            event(change(128L, 270f, 310f)),
            event(change(192L, 170f, 270f)),
            event(change(288L, 170f, 170f, pressed = false)),
        ), beforeInput = {
            val gesture = state.beginGesture()
            state.applyGestureFrame(gesture, 1f)
            state.cancelGesture(gesture)
        }) {
            frames(90)
            assertEquals("the visible open endpoint must allow the next swipe to close",
                0f, state.visualProgress.value, 0.0001f)
        }

    @Test fun `a horizontal scroller at its start keeps an inward drag`() {
        var childScroll = 0f
        withPointerLoop(DrawerValue.Closed, shield = false, includeHost = true,
            childOrientation = Orientation.Horizontal, childCanScroll = { it < 0f },
            childDrag = { childScroll += it }, events = listOf(
                event(change(0L, 180f, 180f, previousPressed = false)),
                event(change(16L, 140f, 180f)),
                event(change(32L, 100f, 140f)),
                event(change(48L, 100f, 100f, pressed = false)),
            )) {
            assertEquals(0f, state.visualProgress.value, 0.0001f)
            assertEquals(-72f, childScroll, 0.0001f)
        }
    }

    @Test fun `a code block without horizontal overflow lets the drawer move`() =
        withPointerLoop(DrawerValue.Closed, shield = false, includeHost = true,
            childOrientation = Orientation.Horizontal, childCanScroll = { false }, events = listOf(
                event(change(0L, 100f, 100f, previousPressed = false)),
                event(change(16L, 140f, 100f)),
                event(change(32L, 180f, 140f)),
            )) {
            assertEquals("a non-scrolling code block must not trap the horizontal drag",
                80f / 300f, state.visualProgress.value, 0.0001f)
        }

    @Test fun `a horizontal scroller at its start yields an outward drag to the drawer`() =
        withPointerLoop(DrawerValue.Closed, shield = false, includeHost = true,
            childOrientation = Orientation.Horizontal, childCanScroll = { it < 0f }, events = listOf(
                event(change(0L, 100f, 100f, previousPressed = false)),
                event(change(16L, 140f, 100f)),
                event(change(32L, 180f, 140f)),
            )) {
            assertEquals("pulling past the content's start must reach the drawer",
                80f / 300f, state.visualProgress.value, 0.0001f)
        }

    @Test fun `a horizontal decision wins a diagonal move before the vertical child`() {
        var childScroll = 0f
        withPointerLoop(DrawerValue.Closed, shield = false, includeHost = true,
            childOrientation = Orientation.Vertical, childDrag = { childScroll += it }, events = listOf(
                event(change(0L, 100f, 100f, previousPressed = false, y = 100f, previousY = 100f)),
                event(change(16L, 140f, 100f, y = 120f, previousY = 100f)),
                event(change(32L, 180f, 140f, pressed = false, y = 130f, previousY = 120f)),
            )) {
            assertEquals("a mainly horizontal move must reach the drawer",
                80f / 300f,
                state.visualProgress.value, 0.0001f)
            assertEquals("the vertical child must not steal this drag", 0f, childScroll, 0.0001f)
        }
    }

    @Test fun `a horizontal decision includes movement carried only by up`() =
        withPointerLoop(DrawerValue.Closed, shield = false, events = listOf(
            event(change(0L, 100f, 100f, previousPressed = false)),
            event(change(16L, 105f, 100f)),
            event(change(32L, 140f, 105f, pressed = false)),
        )) {
            assertEquals("UP may be the first event to cross horizontal touch slop",
                40f / 300f,
                state.visualProgress.value, 0.0001f)
        }

    @Test fun `a closing decision follows the finger while opening is interrupted`() =
        withPointerLoop(DrawerValue.Closed, shield = true, includeHost = true, events = listOf(
            event(change(0L, 350f, 350f, previousPressed = false)),
            event(change(64L, 160f, 350f)),
            event(change(80L, 160f, 160f, pressed = false)),
        ), beforeInput = {
            scope.launch(clock, CoroutineStart.UNDISPATCHED) {
                state.animateTo(DrawerValue.Open, tween(1_000))
            }
            frames(30)
        }) {
            frames(90)
            assertEquals("a closing drag must not reopen from the old closed anchor", 0f,
                state.visualProgress.value, 0.0001f)
        }

    @Test fun `an opening decision follows the finger while closing is interrupted`() =
        withPointerLoop(DrawerValue.Open, shield = true, includeHost = true, events = listOf(
            event(change(0L, 350f, 350f, previousPressed = false)),
            event(change(64L, 540f, 350f)),
            event(change(80L, 540f, 540f, pressed = false)),
        ), beforeInput = {
            scope.launch(clock, CoroutineStart.UNDISPATCHED) {
                state.animateTo(DrawerValue.Closed, tween(1_000))
            }
            frames(30)
        }) {
            frames(90)
            assertTrue("an opening drag must not close from the old open anchor", state.isOpen)
        }

    @Test fun `a vertical shield decision does not become a tap`() =
        withPointerLoop(DrawerValue.Open, shield = true, includeHost = true, events = listOf(
            event(change(0L, 350f, 350f, previousPressed = false, y = 100f, previousY = 100f)),
            event(change(16L, 350f, 350f, y = 160f, previousY = 100f)),
            event(change(32L, 350f, 350f, pressed = false, y = 180f, previousY = 160f)),
        )) {
            frames(90)
            assertTrue("a vertical swipe on the shield must leave the drawer open", state.isOpen)
        }

    @Test fun `a diagonal start can turn into a slow drawer opening`() =
        withPointerLoop(DrawerValue.Closed, shield = false, events = listOf(
            event(change(0L, 100f, 100f, previousPressed = false)),
            event(change(60L, 116f, 100f, y = 18f)),
            event(change(220L, 280f, 116f, y = 20f, previousY = 18f)),
            event(change(320L, 280f, 280f, pressed = false, y = 20f, previousY = 20f)),
        )) {
            frames(90)
            assertTrue(state.isOpen)
        }

    @Test fun `a diagonal start can turn into a slow drawer closing`() =
        withPointerLoop(DrawerValue.Open, shield = true, includeHost = true, events = listOf(
            event(change(0L, 350f, 350f, previousPressed = false)),
            event(change(60L, 334f, 350f, y = 18f)),
            event(change(220L, 170f, 334f, y = 20f, previousY = 18f)),
            event(change(320L, 170f, 170f, pressed = false, y = 20f, previousY = 20f)),
        )) {
            frames(90)
            assertEquals(0f, state.visualProgress.value, 0.0001f)
        }

    @Test fun `a left swipe on the closed drawer leaves the events and does not open on a recoil`() {
        val left = change(16L, 140f, 180f)
        val recoil = change(32L, 156f, 140f)
        withPointerLoop(DrawerValue.Closed, shield = false, events = listOf(
            event(change(0L, 180f, 180f, previousPressed = false)),
            event(left),
            event(recoil),
            event(change(48L, 156f, 156f, pressed = false)),
        )) {
            assertFalse(left.isConsumed)
            assertFalse(recoil.isConsumed)
            assertEquals(0f, state.visualProgress.value, 0f)
            frames(90)
            assertEquals(0f, state.visualProgress.value, 0f)
        }
    }

    @Test fun `a right swipe on the open shield stays open through a recoil and release`() =
        withPointerLoop(DrawerValue.Open, shield = true, includeHost = true, events = listOf(
            event(change(0L, 350f, 350f, previousPressed = false)),
            event(change(16L, 390f, 350f)),
            event(change(32L, 374f, 390f)),
            event(change(48L, 374f, 374f, pressed = false)),
        )) {
            assertEquals(1f, state.visualProgress.value, 0f)
            frames(90)
            assertEquals(1f, state.visualProgress.value, 0f)
        }

    @Test fun `an opening drag follows a reversal before release`() =
        withPointerLoop(DrawerValue.Closed, shield = false, events = listOf(
            event(change(0L, 100f, 100f, previousPressed = false)),
            event(change(64L, 180f, 100f)),
            event(change(128L, 130f, 180f)),
            event(change(228L, 130f, 130f, pressed = false)),
        )) {
            assertEquals(30f / 300f, state.visualProgress.value, 0.0001f)
            frames(90)
            assertEquals(0f, state.visualProgress.value, 0f)
        }

    @Test fun `a slow deliberate reversal settles in the return direction on both surfaces`() {
        for (open in listOf(false, true)) {
            val start = if (open) 350f else 100f
            val direction = if (open) -1f else 1f
            val turn = start + direction * 180f
            val end = turn - direction * 80f
            withPointerLoop(if (open) DrawerValue.Open else DrawerValue.Closed, shield = open,
                includeHost = open, events = listOf(
                    event(change(0L, start, start, previousPressed = false)),
                    event(change(250L, turn, start)),
                    event(change(450L, end, turn)),
                    event(change(550L, end, end, pressed = false)),
                )) {
                assertEquals(if (open) 2f / 3f else 1f / 3f, state.visualProgress.value, 0.0001f)
                frames(90)
                assertEquals(if (open) 1f else 0f, state.visualProgress.value, 0f)
            }
        }
    }

    @Test fun `a slow quarter-width swipe from either endpoint can finish without crossing halfway`() {
        for (open in listOf(false, true)) {
            val start = if (open) 350f else 100f
            val direction = if (open) -1f else 1f
            for (travel in listOf(30f, 75f, 76f, 90f)) {
                val end = start + direction * travel
                val peak = end + direction * 3f
                withPointerLoop(if (open) DrawerValue.Open else DrawerValue.Closed,
                    shield = open, includeHost = open, events = listOf(
                        event(change(0L, start, start, previousPressed = false)),
                        event(change(250L, peak, start)),
                        event(change(270L, end, peak)),
                        event(change(400L, end, end, pressed = false)),
                    )) {
                    val position = if (open) 1f - travel / 300f else travel / 300f
                    assertEquals(position, state.visualProgress.value, 0.0001f)
                    frames(90)
                    assertEquals("open=$open travel=$travel", if (travel > 75f) !open else open, state.isOpen)
                }
            }
        }
    }

    @Test fun `a deliberate pullback uses release position while a direct drag accepts shorter travel`() {
        for (open in listOf(false, true)) {
            val start = if (open) 350f else 100f
            val direction = if (open) -1f else 1f
            for (travel in listOf(120f, 180f)) {
                val end = start + direction * travel
                for (peak in listOf(end, start + direction * 280f)) {
                    withPointerLoop(if (open) DrawerValue.Open else DrawerValue.Closed,
                        shield = open, includeHost = open, events = listOf(
                            event(change(0L, start, start, previousPressed = false)),
                            event(change(250L, peak, start)),
                            event(change(450L, end, peak)),
                            event(change(600L, end, end, pressed = false)),
                        )) {
                        val position = if (open) 1f - travel / 300f else travel / 300f
                        assertEquals(position, state.visualProgress.value, 0.0001f)
                        frames(90)
                        assertEquals(if (peak == end) !open else position > 0.5f, state.isOpen)
                    }
                }
            }
        }
    }

    @Test fun `a tiny recoil on lift does not cancel a short fling or reverse a long drag`() {
        for (open in listOf(false, true)) {
            val start = if (open) 350f else 100f
            val direction = if (open) -1f else 1f
            for (travel in listOf(60f, 180f)) {
                val half = start + direction * travel / 2f
                val end = start + direction * travel
                withPointerLoop(if (open) DrawerValue.Open else DrawerValue.Closed, shield = open,
                    includeHost = open, events = listOf(
                        event(change(0L, start, start, previousPressed = false)),
                        event(change(16L, half, start)),
                        event(change(32L, end, half)),
                        event(change(36L, end - direction * 3f, end, previousTime = 32L, pressed = false)),
                    )) {
                    frames(90)
                    assertEquals("initially open=$open, travel=$travel", !open, state.isOpen)
                }
            }
        }
    }

    @Test fun `a quick reversal before release flings in the new direction`() =
        withPointerLoop(DrawerValue.Closed, shield = false, events = listOf(
            event(change(0L, 100f, 100f, previousPressed = false)),
            event(change(16L, 164f, 100f)),
            event(change(32L, 228f, 164f)),
            event(change(48L, 292f, 228f)),
            event(change(64L, 276f, 292f)),
            event(change(80L, 276f, 276f, pressed = false)),
        )) {
            frames(90)
            assertEquals(0f, state.visualProgress.value, 0f)
        }

    @Test fun `holding a partial drag across display frames leaves the panel under the finger`() {
        for (open in listOf(false, true)) {
            val start = if (open) 350f else 100f
            val end = start + if (open) -90f else 90f
            withPointerLoop(if (open) DrawerValue.Open else DrawerValue.Closed,
                shield = open, includeHost = open, events = listOf(
                    event(change(0L, start, start, previousPressed = false)),
                    event(change(32L, end, start)),
                )) {
                repeat(12) {
                    frames(1)
                    assertNotNull(state.gestureSession)
                    assertEquals(if (open) 0.7f else 0.3f, state.visualProgress.value, 0.0001f)
                }
            }
        }
    }

    @Test fun `a vertical decision stays with the child when later movement turns horizontal`() {
        var childScroll = 0f
        withPointerLoop(DrawerValue.Closed, shield = false, includeHost = true,
            childOrientation = Orientation.Vertical, childDrag = { childScroll += it }, events = listOf(
                event(change(0L, 100f, 100f, previousPressed = false, y = 100f, previousY = 100f)),
                event(change(16L, 120f, 100f, y = 140f, previousY = 100f)),
                event(change(32L, 180f, 120f, y = 160f, previousY = 140f)),
                event(change(48L, 200f, 180f, pressed = false, y = 160f, previousY = 160f)),
            )) {
            assertEquals(0f, state.visualProgress.value, 0.0001f)
            assertEquals(52f, childScroll, 0.0001f)
        }
    }

    @Test fun `a vertical child that started scrolling keeps a swipe that turns horizontal`() {
        var childScroll = 0f
        withPointerLoop(DrawerValue.Closed, shield = false, includeHost = true,
            childOrientation = Orientation.Vertical, childDrag = { childScroll += it }, events = listOf(
                event(change(0L, 100f, 100f, previousPressed = false, y = 100f, previousY = 100f)),
                event(change(16L, 100f, 100f, y = 111f, previousY = 100f)),
                event(change(32L, 144f, 100f, y = 114f, previousY = 111f)),
                event(change(48L, 150f, 144f, pressed = false, y = 115f, previousY = 114f)),
            )) {
            assertEquals(6f, childScroll, 0.0001f)
            assertEquals(0f, state.visualProgress.value, 0.0001f)
        }
    }

    @Test fun `a horizontal child keeps priority over the drawer`() {
        var childScroll = 0f
        withPointerLoop(DrawerValue.Closed, shield = false, includeHost = true,
            childOrientation = Orientation.Horizontal, childDrag = { childScroll += it }, events = listOf(
                event(change(0L, 100f, 100f, previousPressed = false)),
                event(change(16L, 140f, 100f)),
                event(change(32L, 180f, 140f)),
                event(change(48L, 180f, 180f, pressed = false)),
            )) {
            assertEquals(0f, state.visualProgress.value, 0.0001f)
            assertEquals(72f, childScroll, 0.0001f)
        }
    }

    @Test fun `a long press lets the child claim movement before the drawer`() {
        var childScroll = 0f
        withPointerLoop(DrawerValue.Closed, shield = false, includeHost = true,
            childOrientation = Orientation.Vertical, childDrag = { childScroll += it }, events = listOf(
                event(change(0L, 100f, 100f, previousPressed = false, y = 100f, previousY = 100f)),
                event(change(600L, 140f, 100f, y = 120f, previousY = 100f)),
                event(change(616L, 140f, 140f, pressed = false, y = 120f, previousY = 120f)),
            )) {
            assertEquals("long-press movement belongs to the child that consumes it", 0f,
                state.visualProgress.value, 0.0001f)
            assertEquals(12f, childScroll, 0.0001f)
        }
    }

    @Test fun `a host down catches the spring and follows movement below slop`() {
        var caught = 0f
        withPointerLoop(DrawerValue.Open, shield = false, events = listOf(
            event(change(0L, 100f, 100f, previousPressed = false)),
            event(change(16L, 104f, 100f)),
        ), beforeInput = {
            startClosing()
            caught = state.visualProgress.value
        }) {
            assertNotNull(state.gestureSession)
            frames(12)
            assertEquals(caught + 4f / 300f, state.visualProgress.value, 0.0001f)
        }
    }

    @Test fun `a vertical decision resumes the interrupted motion on host and shield`() {
        val spec = tween<Float>(1_000)
        for (shield in listOf(false, true)) {
            withPointerLoop(DrawerValue.Closed, shield = shield, includeHost = true, events = listOf(
                event(change(0L, 350f, 350f, previousPressed = false, y = 100f, previousY = 100f)),
                event(change(16L, 350f, 350f, y = 160f, previousY = 100f)),
                event(change(32L, 350f, 350f, pressed = false, y = 180f, previousY = 160f)),
            ), beforeInput = {
                scope.launch(clock, CoroutineStart.UNDISPATCHED) {
                    state.animateTo(DrawerValue.Open, spec)
                }
                frames(30)
            }) {
                assertTrue("vertical input must preserve the interrupted animation spec", state.motion?.spec === spec)
                frames(90)
                assertTrue("vertical input must not stop or reverse opening", state.isOpen)
            }
        }
    }

    @Test fun `a visible drawer row remains clickable while the drawer is closing`() {
        var clicks = 0
        withPointerLoop(DrawerValue.Open, shield = false, includeHost = true,
            rowClick = { clicks++ }, events = listOf(
                event(change(0L, 100f, 100f, previousPressed = false)),
                event(change(16L, 100f, 100f, pressed = false)),
            ), beforeInput = { startClosing() }) {
            assertEquals("catching the spring must not consume the drawer row's tap", 1, clicks)
            frames(90)
        }
    }

    @Test fun `a stationary drawer row receives its tap through the host`() {
        var clicks = 0
        withPointerLoop(DrawerValue.Open, shield = false, includeHost = true,
            rowClick = { clicks++ }, events = listOf(
                event(change(0L, 100f, 100f, previousPressed = false)),
                event(change(16L, 100f, 100f, pressed = false)),
            )) {
            assertEquals(1, clicks)
        }
    }

    @Test fun `a shield down catches the spring and a small move follows the finger`() {
        var caught = 0f
        withPointerLoop(DrawerValue.Open, shield = true, includeHost = true, events = listOf(
            event(change(0L, 350f, 350f, previousPressed = false)),
            event(change(16L, 354f, 350f)),
        ), beforeInput = {
            startClosing()
            caught = state.visualProgress.value
        }) {
            assertNotNull(state.gestureSession)
            frames(12)
            assertEquals(caught + 4f / 300f, state.visualProgress.value, 0.0001f)
        }
    }

    @Test fun `tapping a moving shield still closes the drawer`() =
        withPointerLoop(DrawerValue.Open, shield = true, includeHost = true, events = listOf(
            event(change(0L, 350f, 350f, previousPressed = false)),
            event(change(16L, 350f, 350f, pressed = false)),
        ), beforeInput = { startClosing() }) {
            frames(90)
            assertEquals(0f, state.visualProgress.value, 0.0001f)
        }

    @Test fun `host moves update the visible drawer before the first display frame`() =
        withPointerLoop(DrawerValue.Closed, shield = false, events = listOf(
            event(change(0L, 100f, 100f, previousPressed = false)),
            event(change(16L, 120f, 100f)),
            event(change(32L, 140f, 120f)),
        )) {
            assertEquals("both moves must be visible without advancing the frame clock",
                40f / 300f, state.visualProgress.value, 0.0001f)
        }

    @Test fun `a short host fling includes the down sample`() =
        withPointerLoop(DrawerValue.Closed, shield = false, events = listOf(
            event(change(0L, 100f, 100f, previousPressed = false)),
            event(change(16L, 120f, 100f)),
            event(change(32L, 120f, 120f, pressed = false)),
        )) {
            frames(90)
            assertEquals(DrawerValue.Open, if (state.isOpen) DrawerValue.Open else DrawerValue.Closed)
        }

    @Test fun `the same swipe in dp has the same fling decision across densities`() {
        for (density in listOf(1f, 3f)) {
            for (stepDp in listOf(5f, 9f)) {
                val step = stepDp * density
                withPointerLoop(DrawerValue.Closed, shield = false, density = density, events = listOf(
                    event(change(0L, 0f, 0f, previousPressed = false)),
                    event(change(16L, step, 0f)),
                    event(change(32L, 2f * step, step)),
                    event(change(48L, 3f * step, 2f * step)),
                    event(change(64L, 3f * step, 3f * step, pressed = false)),
                )) {
                    frames(90)
                    assertEquals("step=$stepDp dp, density=$density", stepDp == 9f, state.isOpen)
                }
            }
        }
    }

    @Test fun `a host release carries its final movement and velocity into the spring`() =
        withPointerLoop(DrawerValue.Closed, shield = false, events = listOf(
            event(change(0L, 100f, 100f, previousPressed = false)),
            event(change(16L, 110f, 100f)),
            event(change(32L, 140f, 110f, pressed = false)),
        )) {
            assertEquals("the release position must be applied before the first spring frame",
                40f / 300f, state.visualProgress.value, 0.0001f)
            frames(90)
            assertTrue("the final accelerating movement must produce an opening fling", state.isOpen)
        }

    @Test fun `a shield release carries its final movement and velocity into the spring`() =
        withPointerLoop(DrawerValue.Open, shield = true, events = listOf(
            event(change(0L, 350f, 350f, previousPressed = false)),
            event(change(16L, 340f, 350f)),
            event(change(32L, 310f, 340f, pressed = false)),
        )) {
            assertEquals("the shield must retain the physical release position",
                1f - 40f / 300f, state.visualProgress.value, 0.0001f)
            frames(90)
            assertEquals("the final accelerating movement must produce a closing fling",
                0f, state.visualProgress.value, 0.0001f)
        }

    @Test fun `a very fast flick limits the spring step after leaving the finger`() {
        for (opening in listOf(true, false)) {
            val start = if (opening) 100f else 350f
            val step = if (opening) 36f else -36f
            val changes = (0..5).map { i ->
                event(change(i * 8L, start + i * step, start + (i - 1).coerceAtLeast(0) * step,
                    pressed = i != 5, previousPressed = i != 0))
            }
            withPointerLoop(if (opening) DrawerValue.Closed else DrawerValue.Open,
                shield = !opening, events = changes) {
                var previous = state.visualProgress.value
                assertEquals(if (opening) 0.6f else 0.4f, previous, 0.0001f)
                repeat(8) {
                    frames(1)
                    val current = state.visualProgress.value
                    val distance = kotlin.math.abs(current - previous)
                    assertTrue("opening=$opening spring step=$distance", distance <= 0.12f)
                    previous = current
                }
                frames(90)
                assertEquals(opening, state.isOpen)
            }
        }
    }

    @Test fun `a host drag held still before up has no fling velocity`() =
        withPointerLoop(DrawerValue.Closed, shield = false, events = listOf(
            event(change(0L, 100f, 100f, previousPressed = false)),
            event(change(16L, 120f, 100f)),
            event(change(32L, 140f, 120f)),
            event(change(96L, 140f, 140f, pressed = false)),
        )) {
            frames(90)
            assertEquals("holding before release must keep this short drag closed",
                0f, state.visualProgress.value, 0.0001f)
        }

    @Test fun `a short drag springs back directly from the release position`() =
        withPointerLoop(DrawerValue.Closed, shield = false, events = listOf(
            event(change(0L, 100f, 100f, previousPressed = false)),
            event(change(16L, 105f, 100f)),
            event(change(32L, 110f, 105f)),
            event(change(48L, 115f, 110f, pressed = false)),
        )) {
            var previous = state.visualProgress.value
            repeat(4) {
                frames(1)
                val current = state.visualProgress.value
                assertTrue("returning to closed must not move farther open: $previous → $current", current <= previous)
                previous = current
            }
            frames(90)
            assertEquals(0f, state.visualProgress.value, 0f)
        }

    @Test fun `a shield drag held still before up has no fling velocity`() =
        withPointerLoop(DrawerValue.Open, shield = true, events = listOf(
            event(change(0L, 350f, 350f, previousPressed = false)),
            event(change(16L, 330f, 350f)),
            event(change(32L, 310f, 330f)),
            event(change(96L, 310f, 310f, pressed = false)),
        )) {
            frames(90)
            assertEquals("holding before release must keep this short drag open",
                1f, state.visualProgress.value, 0.0001f)
        }

    @Test fun `lifting the first shield pointer leaves the surviving pointer dragging`() =
        withPointerLoop(DrawerValue.Open, shield = true, events = listOf(
            event(change(0L, 350f, 350f, previousPressed = false)),
            event(change(16L, 330f, 350f)),
            event(
                change(24L, 330f, 330f),
                change(24L, 600f, 600f, id = 2L, previousPressed = false),
            ),
            event(
                change(32L, 330f, 330f, pressed = false),
                change(32L, 600f, 600f, id = 2L),
            ),
            event(change(48L, 580f, 600f, id = 2L)),
        )) {
            assertNotNull("the remaining finger still owns the drag", state.gestureSession)
            assertEquals("only finger motion, not the distance between fingers, moves the drawer",
                1f - 40f / 300f, state.visualProgress.value, 0.0001f)
        }

    private class DrawerChatFixture(
        private val initialFollowing: Boolean = false,
        private val trace: (String) -> Unit = {},
    ) {
        val list = spyk(LazyListState(8, 30))
        val viewportHeight = mutableStateOf(600)
        val tailBottom = mutableStateOf(680)
        val position = TranscriptPosition(list)
        val coordinates = mockk<LayoutCoordinates>()
        private val gesture = ChatReadingGesture()
        private val selection = SelectionFocusTracker()
        private val navigation = arrayOfNulls<kotlinx.coroutines.Job>(1)
        lateinit var scroll: ChatScroll

        init {
            val layout = mockk<LazyListLayoutInfo>()
            every { layout.totalItemsCount } returns 10
            every { layout.beforeContentPadding } returns 0
            every { layout.afterContentPadding } returns 0
            every { layout.viewportSize } answers { IntSize(400, viewportHeight.value) }
            every { list.layoutInfo } returns layout
            every { list.canScrollForward } answers { tailBottom.value > viewportHeight.value }
        }

        @Composable fun bind() {
            scroll = rememberChatScroll(list, true, { gesture.pressed }, { gesture.pressGuardOn },
                conversationId = "chat", trace = trace, initialFollowing = initialFollowing)
            scroll.restoring = false
            position.following = { scroll.stickToBottom && !gesture.pressed && !gesture.pressGuardOn }
            position.latestStillWanted = { scroll.stickToBottom }
            Box(Modifier.chatTranscriptGesture("chat", gesture, navigation, scroll, selection,
                LocalViewConfiguration.current, LocalFocusManager.current, null, LocalDrawerGestureState.current))
        }
    }

    private class Harness(
        val state: SuixingDrawerState,
        val scope: CoroutineScope,
        val clock: BroadcastFrameClock,
    ) {
        lateinit var homeDecorationMotion: () -> Boolean
        var initialShieldNode: Any? = null
        lateinit var currentShieldNode: () -> Any?
        var panelDensityReads = 0
        private var nanos = 0L

        suspend fun startClosing() {
            scope.launch(clock, CoroutineStart.UNDISPATCHED) {
                state.animateTo(DrawerValue.Closed, DRAWER_CLOSE_SPEC)
            }
            frames(4)
            assertTrue("the fixture must catch an active spring", state.progress.isRunning)
        }

        suspend fun frames(count: Int) {
            repeat(count) {
                Snapshot.sendApplyNotifications()
                repeat(6) { yield() }
                nanos += 16_000_000L
                clock.sendFrame(nanos)
                Snapshot.sendApplyNotifications()
                repeat(6) { yield() }
            }
        }
    }

    private fun withPointerLoop(
        initialValue: DrawerValue,
        shield: Boolean,
        events: List<PointerEvent>,
        beforeInput: suspend Harness.() -> Unit = {},
        includeHost: Boolean = false,
        rowClick: (() -> Unit)? = null,
        childOrientation: Orientation? = null,
        childCanScroll: (Float) -> Boolean = { true },
        childDrag: (Float) -> Unit = {},
        homeContent: @Composable () -> Unit = {},
        homePointerInput: Boolean = false,
        density: Float = 1f,
        block: suspend Harness.() -> Unit,
    ) = runBlocking {
        withTimeout(5_000L) {
            val nodeClass = Class.forName("androidx.compose.ui.node.LayoutNode")
            val root = nodeClass.getDeclaredConstructor().newInstance()
            @Suppress("UNCHECKED_CAST")
            val applier = Class.forName("androidx.compose.ui.node.UiApplier")
                .getDeclaredConstructor(nodeClass).newInstance(root) as Applier<Any>
            val clock = BroadcastFrameClock()
            val state = SuixingDrawerState(initialValue)
            val harness = Harness(state, this, clock)
            val drawerDensity = object : Density by Density(density) {
                override fun Dp.toPx(): Float {
                    if (value == DRAWER_SHEET_WIDTH.toFloat()) harness.panelDensityReads++
                    return value * density
                }
            }
            val recomposer = Recomposer(coroutineContext + clock)
            val composition = Composition(applier, recomposer)
            val runner = launch(clock, CoroutineStart.UNDISPATCHED) {
                recomposer.runRecomposeAndApplyChanges()
            }
            val pointerJobs = mutableListOf<kotlinx.coroutines.Job>()
            try {
                composition.setContent {
                    CompositionLocalProvider(
                        LocalDensity provides drawerDensity,
                        LocalLayoutDirection provides LayoutDirection.Ltr,
                        LocalViewConfiguration provides DrawerViewConfiguration,
                        LocalSoftwareKeyboardController provides null,
                        LocalFocusManager provides object : FocusManager {
                            override fun clearFocus(force: Boolean) = Unit
                            override fun moveFocus(focusDirection: FocusDirection): Boolean = false
                        },
                    ) {
                        SuixingDrawer(state, gesturesEnabled = true, drawerContent = {
                            if (rowClick != null) Box(drawerRowTapForTest(rowClick))
                        }, content = {
                            harness.homeDecorationMotion = LocalDecorationMotion.current
                            homeContent()
                            if (childOrientation != null) {
                                val modifier = if (childOrientation == Orientation.Horizontal)
                                    Modifier.drawerScrollPriority(childCanScroll) else Modifier
                                Box(modifier.pointerInput(childOrientation) {
                                    when (childOrientation) {
                                        Orientation.Horizontal -> detectHorizontalDragGestures { _, amount ->
                                            childDrag(amount)
                                        }
                                        Orientation.Vertical -> detectVerticalDragGestures { _, amount ->
                                            childDrag(amount)
                                        }
                                    }
                                })
                            }
                        })
                    }
                }
                harness.beforeInput()
                val children = nodeClass.getMethod("getChildrenInfo")
                val host = (children.invoke(root) as List<*>).single()!!
                harness.currentShieldNode = { (children.invoke(host) as List<*>).drop(1).lastOrNull() }
                harness.initialShieldNode = harness.currentShieldNode()
                val target = when {
                    rowClick != null -> {
                        val panel = (children.invoke(host) as List<*>).first()!!
                        (children.invoke(panel) as List<*>).single()!!
                    }
                    shield -> (children.invoke(host) as List<*>).last()!!
                    homePointerInput -> (children.invoke(host) as List<*>)[1]!!
                    childOrientation != null -> (children.invoke(host) as List<*>)[1]!!
                    else -> host
                }
                val targets = if ((shield || rowClick != null || childOrientation != null || homePointerInput) && includeHost)
                    listOf(host, target)
                    else listOf(target)
                val inputs = targets.flatMap { node ->
                    // Detached test nodes retain their real modifier until an Android owner attaches.
                    val modifier = nodeClass.getDeclaredField("pendingModifier")
                        .apply { isAccessible = true }.get(node) as Modifier
                    val handlers = modifier.foldIn(mutableListOf<PointerInputEventHandler>()) { current, element ->
                        if (element.javaClass.simpleName == "SuspendPointerInputElement") {
                            current.add(element.javaClass.getDeclaredMethod("getPointerInputEventHandler")
                                .invoke(element) as PointerInputEventHandler)
                        }
                        current
                    }
                    handlers.map { handler ->
                        DrawerPointerEvents().also { input ->
                            pointerJobs += harness.scope.launch(clock, CoroutineStart.UNDISPATCHED) {
                                with(handler) { input.invoke() }
                            }
                        }
                    }
                }
                events.forEach { event ->
                    PointerEventPass.entries.forEach { pass ->
                        val ordered = if (pass == PointerEventPass.Main) inputs.asReversed() else inputs
                        ordered.forEach { it.dispatch(event, pass) }
                    }
                }
                harness.block()
            } finally {
                pointerJobs.forEach { it.cancelAndJoin() }
                composition.dispose()
                recomposer.close()
                runner.cancelAndJoin()
            }
        }
    }

    @OptIn(InternalComposeApi::class)
    @Composable
    private fun drawerRowTapForTest(onClick: () -> Unit): Modifier {
        val method = Class.forName("com.suixing.agent.ui.drawer.DrawerRowsKt")
            .declaredMethods.single { it.name == "drawerRowTap" }
            .apply { isAccessible = true }
        return method.invoke(null, Modifier, true, onClick, null, currentComposer, 0, 0) as Modifier
    }

    private class DrawerPointerEvents : PointerInputScope, Density by Density(1f) {
        override val size = IntSize(385, 800)
        override val viewConfiguration: ViewConfiguration = DrawerViewConfiguration
        private val eventScope = DrawerAwaitEvents()

        fun dispatch(event: PointerEvent, pass: PointerEventPass) = eventScope.dispatch(event, pass)

        override suspend fun <R> awaitPointerEventScope(block: suspend AwaitPointerEventScope.() -> R): R =
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation {
                    eventScope.cancel(it ?: CancellationException("Pointer input cancelled"))
                }
                block.startCoroutine(eventScope, object : Continuation<R> {
                    override val context = EmptyCoroutineContext
                    override fun resumeWith(result: Result<R>) = continuation.resumeWith(result)
                })
            }
    }

    private class DrawerAwaitEvents : AwaitPointerEventScope, Density by Density(1f) {
        override val size = IntSize(385, 800)
        override val extendedTouchPadding = Size.Zero
        override val viewConfiguration: ViewConfiguration = DrawerViewConfiguration
        override var currentEvent = PointerEvent(emptyList())
            private set
        private var awaitingPass: PointerEventPass? = null
        private var awaitingEvent: Continuation<PointerEvent>? = null

        override suspend fun awaitPointerEvent(pass: PointerEventPass): PointerEvent =
            suspendCoroutine {
                awaitingPass = pass
                awaitingEvent = it
            }

        fun dispatch(event: PointerEvent, pass: PointerEventPass) {
            currentEvent = event
            if (awaitingPass != pass) return
            val continuation = awaitingEvent
            awaitingPass = null
            awaitingEvent = null
            continuation?.resume(event)
        }

        fun cancel(cause: Throwable) {
            val continuation = awaitingEvent
            awaitingEvent = null
            continuation?.resumeWithException(cause)
        }
    }

    private companion object {
        val DrawerViewConfiguration = object : ViewConfiguration {
            override val longPressTimeoutMillis = 500L
            override val doubleTapTimeoutMillis = 300L
            override val doubleTapMinTimeMillis = 40L
            override val touchSlop = 8f
        }

        fun event(vararg changes: PointerInputChange): PointerEvent = PointerEvent(changes.toList())

        fun change(
            time: Long,
            x: Float,
            previousX: Float,
            id: Long = 1L,
            pressed: Boolean = true,
            previousPressed: Boolean = true,
            y: Float = 0f,
            previousY: Float = 0f,
            previousTime: Long = (time - 16L).coerceAtLeast(0L),
        ): PointerInputChange = PointerInputChange(
            id = PointerId(id),
            uptimeMillis = time,
            position = Offset(x, y),
            pressed = pressed,
            previousUptimeMillis = previousTime,
            previousPosition = Offset(previousX, previousY),
            previousPressed = previousPressed,
            isInitiallyConsumed = false,
        )
    }
}
