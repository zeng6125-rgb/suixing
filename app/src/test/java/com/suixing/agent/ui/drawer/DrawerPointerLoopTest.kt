package com.suixing.agent.ui.drawer

import androidx.compose.material3.DrawerValue
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Applier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.currentComposer
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
            event(change(192L, 220f, 180f)),
            event(change(288L, 220f, 220f, pressed = false)),
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
            event(change(192L, 230f, 270f)),
            event(change(288L, 230f, 230f, pressed = false)),
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
                72f / 300f, state.visualProgress.value, 0.0001f)
        }

    @Test fun `a horizontal scroller at its start yields an outward drag to the drawer`() =
        withPointerLoop(DrawerValue.Closed, shield = false, includeHost = true,
            childOrientation = Orientation.Horizontal, childCanScroll = { it < 0f }, events = listOf(
                event(change(0L, 100f, 100f, previousPressed = false)),
                event(change(16L, 140f, 100f)),
                event(change(32L, 180f, 140f)),
            )) {
            assertEquals("pulling past the content's start must reach the drawer",
                72f / 300f, state.visualProgress.value, 0.0001f)
        }

    @Test fun `a horizontal decision wins a diagonal move before the vertical child`() {
        var childScroll = 0f
        withPointerLoop(DrawerValue.Closed, shield = false, includeHost = true,
            childOrientation = Orientation.Vertical, childDrag = { childScroll += it }, events = listOf(
                event(change(0L, 100f, 100f, previousPressed = false, y = 100f, previousY = 100f)),
                event(change(16L, 140f, 100f, y = 120f, previousY = 100f)),
                event(change(32L, 180f, 140f, pressed = false, y = 130f, previousY = 120f)),
            )) {
            assertEquals("a mainly horizontal move must reach the drawer", 72f / 300f,
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
            assertEquals("UP may be the first event to cross horizontal touch slop", 32f / 300f,
                state.visualProgress.value, 0.0001f)
        }

    @Test fun `a closing decision follows the finger while opening is interrupted`() =
        withPointerLoop(DrawerValue.Closed, shield = true, includeHost = true, events = listOf(
            event(change(0L, 350f, 350f, previousPressed = false)),
            event(change(64L, 338f, 350f)),
            event(change(80L, 338f, 338f, pressed = false)),
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
            event(change(64L, 362f, 350f)),
            event(change(80L, 362f, 362f, pressed = false)),
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
                0.1066667f, state.visualProgress.value, 0.0001f)
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

    @Test fun `a host release carries its final movement and velocity into the spring`() =
        withPointerLoop(DrawerValue.Closed, shield = false, events = listOf(
            event(change(0L, 100f, 100f, previousPressed = false)),
            event(change(16L, 110f, 100f)),
            event(change(32L, 140f, 110f, pressed = false)),
        )) {
            assertEquals("the release position must be applied before the first spring frame",
                32f / 300f, state.visualProgress.value, 0.0001f)
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
                1f - 32f / 300f, state.visualProgress.value, 0.0001f)
            frames(90)
            assertEquals("the final accelerating movement must produce a closing fling",
                0f, state.visualProgress.value, 0.0001f)
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
                0.8933333f, state.visualProgress.value, 0.0001f)
        }

    private class Harness(
        val state: SuixingDrawerState,
        val scope: CoroutineScope,
        val clock: BroadcastFrameClock,
    ) {
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
            val recomposer = Recomposer(coroutineContext + clock)
            val composition = Composition(applier, recomposer)
            val runner = launch(clock, CoroutineStart.UNDISPATCHED) {
                recomposer.runRecomposeAndApplyChanges()
            }
            val pointerJobs = mutableListOf<kotlinx.coroutines.Job>()
            try {
                composition.setContent {
                    CompositionLocalProvider(
                        LocalDensity provides Density(1f),
                        LocalLayoutDirection provides LayoutDirection.Ltr,
                        LocalViewConfiguration provides DrawerViewConfiguration,
                        LocalFocusManager provides object : FocusManager {
                            override fun clearFocus(force: Boolean) = Unit
                            override fun moveFocus(focusDirection: FocusDirection): Boolean = false
                        },
                    ) {
                        SuixingDrawer(state, gesturesEnabled = true, drawerContent = {
                            if (rowClick != null) Box(drawerRowTapForTest(rowClick))
                        }, content = {
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
                val children = nodeClass.getDeclaredMethod("getChildren\$ui")
                val host = (children.invoke(root) as List<*>).single()!!
                val target = when {
                    rowClick != null -> {
                        val panel = (children.invoke(host) as List<*>).first()!!
                        (children.invoke(panel) as List<*>).single()!!
                    }
                    shield -> (children.invoke(host) as List<*>).last()!!
                    childOrientation != null -> (children.invoke(host) as List<*>).last()!!
                    else -> host
                }
                val targets = if ((shield || rowClick != null || childOrientation != null) && includeHost)
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
        ): PointerInputChange = PointerInputChange(
            id = PointerId(id),
            uptimeMillis = time,
            position = Offset(x, y),
            pressed = pressed,
            previousUptimeMillis = (time - 16L).coerceAtLeast(0L),
            previousPosition = Offset(previousX, previousY),
            previousPressed = previousPressed,
            isInitiallyConsumed = false,
        )
    }
}
