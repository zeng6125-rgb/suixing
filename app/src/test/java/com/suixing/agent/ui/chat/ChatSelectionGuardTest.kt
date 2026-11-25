package com.suixing.agent.ui.chat

import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ViewConfiguration
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalFoundationApi::class)
class ChatSelectionGuardTest {
    @Test fun switchingConversationsBindsTheGuardAndDriftCleanupToTheNewList() = withGuard { fixture ->
        val first = fixture.current.value
        val second = Binding("b")
        fixture.current.value = second
        fixture.frames()

        fixture.gesture.pressed = true
        fixture.gesture.pressGuardOn = true
        fixture.frames()
        assertEquals(emptyList<Boolean>(), first.events)
        assertEquals(listOf(true), second.events)

        second.offset.intValue = 8
        fixture.gesture.pressGuardOn = false
        fixture.frames()
        assertFalse("B's displaced selection must turn off B's following", second.scroll.stickToBottom)
        assertEquals(listOf(true, false), second.events)
    }

    @Test fun replacingTheShellCallbackReleasesTheOldOwnerAndBindsTheNewOne() = withGuard { fixture ->
        val first = fixture.current.value
        fixture.gesture.pressed = true
        fixture.gesture.pressGuardOn = true
        fixture.frames()
        assertEquals(listOf(true), first.events)

        val events = mutableListOf<Boolean>()
        fixture.current.value = first.copy(onSelection = { events += it })
        fixture.frames()
        assertEquals(listOf(true, false), first.events)
        assertEquals(listOf(true), events)
        fixture.gesture.pressGuardOn = false
        fixture.frames()
        assertEquals(listOf(true, false), events)
    }

    @Test fun releaseSettlesTheSelectionWithoutAnotherDisplayFrame() {
        mockkStatic(SystemClock::class)
        try {
            every { SystemClock.uptimeMillis() } returns 2_000L
            withGuard { fixture ->
                val binding = fixture.current.value
                fixture.gesture.pressed = true
                fixture.gesture.pressGuardOn = true
                fixture.frames()
                assertEquals(listOf(true), binding.events)

                fixture.gesture.pressUpAt = 2_000L - LONG_PRESS_SETTLE_MS
                fixture.gesture.pressed = false
                repeat(3) {
                    Snapshot.sendApplyNotifications()
                    repeat(8) { yield() }
                }
                assertFalse(fixture.gesture.pressGuardOn)
                assertEquals(listOf(true, false), binding.events)
            }
        } finally {
            unmockkStatic(SystemClock::class)
        }
    }

    private data class Binding(
        val id: String,
        val offset: androidx.compose.runtime.MutableIntState = mutableIntStateOf(0),
        val events: MutableList<Boolean> = mutableListOf(),
        val list: LazyListState = testList(id, offset),
        val scroll: ChatScroll = ChatScroll(list, id, true),
        val onSelection: (Boolean) -> Unit = { events += it },
    )

    private class Fixture(private val clock: BroadcastFrameClock) {
        val gesture = ChatReadingGesture()
        val current = mutableStateOf(Binding("a"))
        private var nanos = 0L

        suspend fun frames() {
            repeat(3) {
                Snapshot.sendApplyNotifications()
                repeat(8) { yield() }
                nanos += 16_000_000L
                clock.sendFrame(nanos)
                Snapshot.sendApplyNotifications()
                repeat(8) { yield() }
            }
        }
    }

    private fun withGuard(block: suspend (Fixture) -> Unit) = runBlocking {
        val clock = BroadcastFrameClock()
        val fixture = Fixture(clock)
        val viewConfig = mockk<ViewConfiguration> {
            every { longPressTimeoutMillis } returns 500L
        }
        withTimeout(5_000) {
            val bringIntoViewSpec = object : BringIntoViewSpec {
                override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
            }
            val recomposer = Recomposer(coroutineContext + clock)
            val composition = Composition(object : AbstractApplier<Unit>(Unit) {
                override fun insertTopDown(index: Int, instance: Unit) = Unit
                override fun insertBottomUp(index: Int, instance: Unit) = Unit
                override fun move(from: Int, to: Int, count: Int) = Unit
                override fun remove(index: Int, count: Int) = Unit
                override fun onClear() = Unit
            }, recomposer)
            val runner = launch(clock, CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
            try {
                composition.setContent {
                    CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoViewSpec) {
                        val current = fixture.current.value
                        rememberChatSelectionGuard(fixture.gesture, current.list, current.scroll, viewConfig, current.onSelection)
                    }
                }
                fixture.frames()
                block(fixture)
            } finally {
                composition.dispose()
                recomposer.close()
                runner.cancelAndJoin()
            }
        }
    }

    private companion object {
        fun testList(id: String, offset: androidx.compose.runtime.MutableIntState): LazyListState {
            val item = mockk<LazyListItemInfo> {
                every { key } returns id
                every { index } returns 0
            }
            val info = mockk<LazyListLayoutInfo> {
                every { visibleItemsInfo } returns listOf(item)
            }
            return mockk {
                every { layoutInfo } returns info
                every { firstVisibleItemIndex } returns 0
                every { firstVisibleItemScrollOffset } answers { offset.intValue }
            }
        }
    }
}
