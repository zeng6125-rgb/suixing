package com.suixing.agent.ui.chat

import android.content.res.Configuration
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.suixing.agent.ui.components.LocalDrawerBusy
import com.suixing.agent.ui.drawer.LocalDrawerGestureState
import com.suixing.agent.ui.drawer.SuixingDrawerState
import com.suixing.agent.core.Core
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.ViewportBookmark
import com.suixing.agent.ui.chat.markdown.InlineMarkdownSource
import com.suixing.agent.ui.chat.markdown.NativeMarkdown
import com.suixing.agent.ui.chat.markdown.rememberInlineMarkdown
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationViewportEntranceTest {
    @Test fun nearbyPreparationYieldsToScrollAndDrawerMotion() = runBlocking {
        val batches = mutableListOf<List<String>>()
        var cancelled = 0
        val harness = OffsetHarness(this, cached = true, prepare = { rows ->
            batches += rows.map { it.message.id }
            try {
                awaitCancellation()
            } finally {
                cancelled++
            }
        })
        try {
            withTimeout(5_000) {
                harness.release.countDown()
                harness.start()
                harness.await { batches.isNotEmpty() }
                harness.scrolling = true
                harness.frames(3)
                assertEquals("an active scroll must cancel the nearby batch", 1, cancelled)

                harness.drawer.snapTo(DrawerValue.Open)
                harness.scrolling = false
                harness.frames(3)
                assertEquals("the drawer still covers the transcript", 1, batches.size)

                harness.drawer.snapTo(DrawerValue.Closed)
                harness.await { batches.size == 2 }
                assertEquals(listOf("target", "next"), batches.last())
                harness.drawer.snapTo(DrawerValue.Open)
                harness.frames(3)
                assertEquals("opening the drawer also cancels an active batch", 2, cancelled)
            }
        } finally { harness.close() }
    }

    @Test fun openingBrowserKeepsTheReaderFromFollowingBackgroundLayoutChanges() = runBlocking {
        val harness = OffsetHarness(this, cached = true)
        try {
            withTimeout(5_000L) {
                harness.release.countDown()
                harness.start()
                harness.await { harness.acknowledgements.isNotEmpty() }
                val position = TranscriptPosition(harness.list).apply {
                    following = { harness.scroll.stickToBottom && !harness.scroll.restoring }
                }
                harness.hideForBrowser()
                // A WebView keyboard transition can expose the hidden transcript's end.
                harness.canScrollForward = false
                harness.frames(3)
                delay(460)
                harness.frames(3)
                harness.canScrollForward = true
                harness.frames(3)
                position.viewportPlaced(mockk<LayoutCoordinates>(relaxed = true))

                assertEquals("returning from the browser must keep the historical row", 0, harness.list.firstVisibleItemIndex)
                assertEquals(600, harness.list.firstVisibleItemScrollOffset)
                assertFalse(harness.scroll.stickToBottom)
            }
        } finally { harness.close() }
    }

    @Test fun aHistoricalOffsetWaitsForItsTargetBeforeRestoringTheOriginalPosition() = runBlocking {
        val harness = OffsetHarness(this, cached = false)
        try {
            withTimeout(5_000L) {
                harness.start()
                harness.await { harness.started.count == 0L }
                assertEquals("the first layout must compose the historical target", 0 to 0, harness.requests.first())
                assertEquals(listOf(0 to 0), harness.requests)
                assertTrue(harness.acknowledgements.isEmpty())
                assertFalse(harness.viewport.presented)
                harness.release.countDown()
                harness.await { harness.acknowledgements.isNotEmpty() }
                assertEquals("historical target message", harness.output.text)
                assertEquals(0 to 600, harness.requests.last())
                assertEquals(600, harness.list.firstVisibleItemScrollOffset)
                assertEquals("target", harness.list.layoutInfo.visibleItemsInfo.single().key)
            }
        } finally { harness.close() }
    }

    @Test fun aCachedHistoricalOffsetWaitsForTheRemountedTargetAndItsFinalLayout() = runBlocking {
        val harness = OffsetHarness(this, cached = true)
        try {
            withTimeout(5_000L) {
                harness.start()
                harness.await { harness.started.count == 0L }
                assertEquals("a remount must compose its target before restoring the cached offset", 0 to 0, harness.requests.first())
                assertEquals(listOf(0 to 0), harness.requests)
                assertTrue(harness.acknowledgements.isEmpty())
                harness.release.countDown()
                harness.await { harness.acknowledgements.isNotEmpty() }
                assertEquals("historical target message", harness.output.text)
                assertEquals(0 to 600, harness.requests.last())
                assertEquals(600, harness.list.firstVisibleItemScrollOffset)
                assertFalse(harness.scroll.restoring)
            }
        } finally { harness.close() }
    }

    @Test fun removingAllRowsReleasesPreparationWithoutWaitingForADetachedListsLayout() = runBlocking {
        val harness = OffsetHarness(this, cached = false)
        try {
            withTimeout(5_000L) {
                harness.start()
                harness.await { harness.started.count == 0L }
                val detachedLayout = harness.list.layoutInfo
                assertTrue(harness.acknowledgements.isEmpty())
                harness.clearRows()
                harness.await { harness.acknowledgements.isNotEmpty() }
                assertSame("a detached list does not supply another measurement", detachedLayout, harness.list.layoutInfo)
                assertFalse("disposing the row must release readiness without finishing its worker", harness.release.count == 0L)
                assertFalse(harness.scroll.restoring)
            }
        } finally { harness.close() }
    }

    @Test fun returningToACachedViewportCompletesItsInterruptedEntrance() = runBlocking {
        withTimeout(5_000L) {
            val cache = ConversationViewportCache()
            val geometry = Triple(400, 1f, 14f)
            val a = ChatUiState(conversationId = "a", loading = false)
            val b = ChatUiState(conversationId = "b", loading = false)
            val interrupted = cache.get(a, null, 12, geometry, true).apply {
                prepared = true
                presented = true
            }
            interrupted.entrance.snapTo(0.4f)
            cache.get(b, null, 12, geometry, true).apply {
                prepared = true
                presented = true
                entrance.snapTo(1f)
            }
            var current by mutableStateOf(b)
            lateinit var mounted: ConversationViewport
            lateinit var scroll: ChatScroll
            val acknowledgements = mutableListOf<String>()
            fun bindViewport(chatId: String, capture: () -> Unit) = Unit
            fun unbindViewport(capture: () -> Unit) = Unit
            fun presentViewport(chatId: String) { acknowledgements += chatId }
            val actions = mockk<ChatScreenActions>()
            val states = MutableStateFlow(b)
            every { actions.state } returns states
            every { actions.bindViewport } returns ::bindViewport
            every { actions.unbindViewport } returns ::unbindViewport
            every { actions.presentViewport } returns ::presentViewport
            val lifecycle = mockk<Lifecycle>(relaxed = true)
            val owner = mockk<LifecycleOwner>()
            every { owner.lifecycle } returns lifecycle
            val configuration = Configuration().apply { screenWidthDp = 400; screenHeightDp = 800 }
            val clock = BroadcastFrameClock()
            val recomposer = Recomposer(coroutineContext)
            val composition = Composition(UnitApplier(), recomposer)
            val runner = launch(clock, CoroutineStart.UNDISPATCHED) {
                recomposer.runRecomposeAndApplyChanges()
            }
            var nanos = 0L
            suspend fun frames() {
                repeat(3) {
                    Snapshot.sendApplyNotifications()
                    repeat(6) { yield() }
                    nanos += 16_000_000L
                    clock.sendFrame(nanos)
                    Snapshot.sendApplyNotifications()
                    repeat(6) { yield() }
                }
            }
            try {
                composition.setContent {
                    CompositionLocalProvider(
                        LocalConfiguration provides configuration,
                        LocalDensity provides Density(1f),
                        LocalLifecycleOwner provides owner,
                        LocalDrawerBusy provides remember { mutableStateOf(false) },
                    ) {
                        val viewport = cache.get(current, null, 12, geometry, true)
                        key(current.conversationId) {
                            val policy = remember(viewport) {
                                ChatScroll(viewport.listState, viewport.conversationId, initialFollowing = false)
                            }
                            val readiness = remember(viewport) { ViewportContentReadiness() }
                            BindConversationViewport(
                                viewport = viewport, actions = actions, scroll = policy,
                                rows = emptyList(), keys = emptyList(), keyIndices = emptyMap(),
                                feedPrefix = 0, itemCount = 0, shownTurns = 12, totalTurns = 0,
                                fontScale = 14f, storageReady = true, messagesRevision = 0,
                                prepare = {}, contentReadiness = readiness, hasTranscript = false,
                            )
                            SideEffect { mounted = viewport; scroll = policy }
                        }
                    }
                }
                frames()
                current = a
                states.value = a
                frames()

                assertSame("returning must exercise the retained viewport", interrupted, mounted)
                assertEquals("a cached conversation must finish an interrupted fade", 1f, mounted.entrance.value, 0f)
                assertFalse(scroll.restoring)
                assertEquals(listOf("b", "a"), acknowledgements)
            } finally {
                composition.dispose()
                recomposer.close()
                runner.cancelAndJoin()
            }
        }
    }

    private class OffsetHarness(
        scope: CoroutineScope,
        cached: Boolean,
        private val prepare: suspend (List<ChatRow>) -> Unit = {},
    ) {
        private val ownerThread = Thread.currentThread()
        private val text = "historical **target** message"
        private val rows = listOf(
            ChatRow(StoredMessage("target", "assistant", text), false, listOf(Part.Text(text))),
            ChatRow(StoredMessage("next", "assistant", "next message"), false),
        )
        private val state = ChatUiState(conversationId = "chat", loading = false, messages = rows.map { it.message })
        private val states = MutableStateFlow(state)
        private val screens = MutableStateFlow(state.screenSnapshot())
        private var active by mutableStateOf(true)
        private val bookmark = ViewportBookmark(
            conversationId = "chat", rowKey = "target", messageId = "target", offset = 600,
            followLatest = false, width = 400, fontScale = 14f, topAnchored = true,
        )
        val viewport = spyk(ConversationViewport("chat", bookmark)).apply {
            prepared = cached
            presented = cached
        }
        val list = mockk<LazyListState>(relaxed = true)
        lateinit var scroll: ChatScroll
            private set
        var canScrollForward by mutableStateOf(true)
        var scrolling by mutableStateOf(false)
        val drawer = SuixingDrawerState(DrawerValue.Closed)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = mutableListOf<Pair<Int, Int>>()
        val acknowledgements = mutableListOf<String>()
        var output = AnnotatedString("")
            private set
        private var position = 0 to if (cached) 600 else 0
        private val layout = mutableStateOf(layout(if (cached) "target" else null, 1_000))
        private val pendingLayout = mutableStateOf<LazyListLayoutInfo?>(null)
        private val actions = mockk<ChatScreenActions>()
        private val owner = ChatUiLifecycleOwner(true, { LifecycleRegistry.createUnsafe(it) }).apply {
            parentChanged(Lifecycle.State.RESUMED)
        }
        private val readiness = ViewportContentReadiness()
        private val configuration = Configuration().apply { screenWidthDp = 400; screenHeightDp = 800 }
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext)
        private val composition = Composition(UnitApplier(), recomposer)
        private val runner = scope.launch(clock, CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }
        private var nanos = 0L

        init {
            every { viewport.listState } returns list
            every { list.layoutInfo } answers { layout.value }
            every { list.canScrollForward } answers { canScrollForward }
            every { list.isScrollInProgress } answers { scrolling }
            every { list.firstVisibleItemIndex } answers { position.first }
            every { list.firstVisibleItemScrollOffset } answers { position.second }
            every { list.requestScrollToItem(any(), any()) } answers {
                val requested = firstArg<Int>() to secondArg<Int>()
                requests += requested
                val targetHeight = if (output.text.isEmpty()) 20 else 1_000
                val skipped = requested.first == 0 && requested.second >= targetHeight
                position = if (skipped) 1 to requested.second - targetHeight else requested
                if (active) {
                    val key = if (position.first == 0) "target" else "next"
                    val measured = layout(key, targetHeight)
                    // A newly visible row composes before the list publishes its measurement.
                    if (viewport.prepared && layout.value.visibleItemsInfo.singleOrNull()?.key == key) layout.value = measured
                    else pendingLayout.value = measured
                }
                Unit
            }
            every { actions.state } returns states
            every { actions.screenState } returns screens
            every { actions.bindViewport } returns ::bindViewport
            every { actions.unbindViewport } returns ::unbindViewport
            every { actions.presentViewport } returns ::presentViewport
            mockkObject(Core, NativeMarkdown)
            every { Core.conversationViewports } returns mockk(relaxed = true)
            every { Core.startup } returns mockk(relaxed = true)
            every { NativeMarkdown.inlineRuns(any()) } answers {
                if (Thread.currentThread() === ownerThread) return@answers null
                started.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                null
            }
        }

        private fun bindViewport(chatId: String, capture: () -> Unit) = Unit
        private fun unbindViewport(capture: () -> Unit) = Unit
        private fun presentViewport(chatId: String) { acknowledgements += chatId }

        fun hideForBrowser() { owner.visibilityChanged(false) }

        fun clearRows() {
            active = false
            states.value = state.copy(messages = emptyList(), messagesRevision = state.messagesRevision + 1)
            screens.value = states.value.screenSnapshot()
        }

        private fun layout(key: String?, targetHeight: Int): LazyListLayoutInfo {
            val items = if (key == null) emptyList() else listOf(mockk<LazyListItemInfo>().also {
                every { it.key } returns key
                every { it.index } returns position.first
                every { it.offset } returns -position.second
                every { it.size } returns if (key == "target") targetHeight else 1_000
            })
            return mockk<LazyListLayoutInfo>(relaxed = true).also {
                every { it.visibleItemsInfo } returns items
                every { it.totalItemsCount } returns rows.size
                every { it.viewportSize } returns IntSize(400, 800)
            }
        }

        fun start() {
            composition.setContent {
                CompositionLocalProvider(
                    LocalConfiguration provides configuration,
                    LocalDensity provides Density(1f),
                    LocalLifecycleOwner provides owner,
                    LocalDrawerBusy provides remember { mutableStateOf(true) },
                    LocalDrawerGestureState provides drawer,
                    LocalViewportContentReadiness provides readiness,
                ) {
                    val screen by states.collectAsState()
                    scroll = rememberChatScroll(list, true, { false }, { false },
                        conversationId = "chat", initialFollowing = false)
                    val displayedRows = if (active) rows else emptyList()
                    val keys = displayedRows.map { it.message.id }
                    BindConversationViewport(
                        viewport, actions, scroll, displayedRows, keys, keys.withIndex().associate { it.value to it.index },
                        0, displayedRows.size, 2, displayedRows.size, 14f, true, screen.messagesRevision, prepare, readiness,
                        hasTranscript = active,
                    )
                    if (active && viewport.prepared) {
                        val measured = pendingLayout.value ?: layout.value
                        if (measured.visibleItemsInfo.any { it.key == "target" }) {
                            val prepared = rememberInlineMarkdown(InlineMarkdownSource(text))
                            SideEffect { output = prepared }
                        }
                        SideEffect {
                            pendingLayout.value?.let { layout.value = it; pendingLayout.value = null }
                        }
                    }
                }
            }
        }

        suspend fun await(condition: () -> Boolean) {
            while (!condition()) frames(1)
        }

        suspend fun frames(count: Int) {
            repeat(count) {
                Snapshot.sendApplyNotifications()
                repeat(8) { yield() }
                nanos += 16_000_000L
                clock.sendFrame(nanos)
                Snapshot.sendApplyNotifications()
                repeat(8) { yield() }
            }
        }

        suspend fun close() = withContext(NonCancellable) {
            release.countDown()
            composition.dispose()
            recomposer.close()
            runner.cancelAndJoin()
            unmockkObject(Core, NativeMarkdown)
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
