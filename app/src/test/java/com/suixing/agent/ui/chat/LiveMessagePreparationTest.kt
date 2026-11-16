package com.suixing.agent.ui.chat

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import com.suixing.agent.ui.ConversationSwitchHandoff
import com.suixing.agent.ui.chat.markdown.InlineMarkdownSource
import com.suixing.agent.ui.chat.markdown.NativeMarkdown
import com.suixing.agent.ui.chat.markdown.rememberInlineMarkdown
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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

class LiveMessagePreparationTest {
    @Test fun aNewTextSnapshotIsMaterializedOutsideTheCompositionThread() = runBlocking {
        val ownerThread = Thread.currentThread()
        val materializedOn = CopyOnWriteArrayList<Thread>()
        val actualBuffer = GrowableText("paragraph\n".repeat(200))
        val buffer = spyk(actualBuffer)
        actualBuffer.append("final tail")
        every { buffer.toString() } answers {
            materializedOn += Thread.currentThread()
            // MockK replaces the original toString call with its mock label.
            actualBuffer.toString()
        }
        val harness = Harness(this, listOf(LivePart.Text(buffer, 7L, 1)))
        try {
            withTimeout(5_000) {
                harness.start()
                assertFalse("the first composition must not materialize the full live text",
                    materializedOn.any { it === ownerThread })
                harness.await { (it.singleOrNull() as? Part.Text)?.text?.endsWith("final tail") == true }
                assertTrue(materializedOn.isNotEmpty())
                assertFalse(materializedOn.any { it === ownerThread })
            }
        } finally { harness.close() }
    }

    @Test fun appendingAndChangingReasoningVisibilityPreservesUnchangedParts() = runBlocking {
        val tools = LivePart.Tools(listOf(LiveTool("run_shell", "id", 1L)))
        val first = listOf<LivePart>(tools, LivePart.Thinking("thinking"), LivePart.Text("answer"))
        val harness = Harness(this, first)
        try {
            withTimeout(5_000) {
                harness.start()
                harness.await { it.size == 3 && (it.last() as? Part.Text)?.text == "answer" }
                val initialTools = harness.output.parts.first()
                val appended = first.appendText(" tail", 0).appendThinking(" tail", 0)
                harness.source.value = LiveContent("chat", "run", appended)
                harness.await {
                    (it.getOrNull(1) as? Part.Thinking)?.text == "thinking tail" &&
                        (it.lastOrNull() as? Part.Text)?.text == "answer tail"
                }
                assertSame(initialTools, harness.output.parts.first())
                val text = harness.output.parts.last()
                harness.showReasoning.value = false
                harness.await { it.size == 2 }
                assertSame(initialTools, harness.output.parts.first())
                assertSame(text, harness.output.parts.last())
                harness.showReasoning.value = true
                harness.await { it.size == 3 }
                assertSame(initialTools, harness.output.parts.first())
                assertSame(text, harness.output.parts.last())
                assertEquals("run", (harness.output.parts[1] as Part.Thinking).runId)
            }
        } finally { harness.close() }
    }

    @Test fun anOlderPreparationCannotPublishIntoTheReplacementRun() = runBlocking {
        val ownerThread = Thread.currentThread()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val actualBuffer = GrowableText("old run")
        val buffer = spyk(actualBuffer)
        actualBuffer.append(" tail")
        every { buffer.toString() } answers {
            entered.countDown()
            if (Thread.currentThread() !== ownerThread) {
                assertTrue("old preparation was not released", release.await(5, TimeUnit.SECONDS))
            }
            actualBuffer.toString()
        }
        val harness = Harness(this, listOf(LivePart.Text(buffer, 7L, 1)))
        try {
            withTimeout(5_000) {
                harness.start()
                while (entered.count != 0L) harness.frames()
                val afterSwitch = harness.publications.size
                harness.runId.value = "replacement"
                harness.source.value = LiveContent("chat", "replacement", listOf(LivePart.Text("new run")))
                harness.frames()
                release.countDown()
                harness.await { (it.singleOrNull() as? Part.Text)?.text == "new run" }
                harness.frames()
                assertEquals("replacement", harness.output.runId)
                assertFalse(harness.publications.drop(afterSwitch).any { rendered ->
                    rendered.runId == "replacement" && rendered.parts.any {
                        it is Part.Text && it.text.contains("old run")
                    }
                })
            }
        } finally { release.countDown(); harness.close() }
    }

    @Test fun initialLiveAndInlineContentMustBeAppliedBeforeTheSwitchCanClose() = runBlocking {
        val ownerThread = Thread.currentThread()
        val liveEntered = CountDownLatch(1)
        val liveRelease = CountDownLatch(1)
        val inlineEntered = CountDownLatch(1)
        val inlineRelease = CountDownLatch(1)
        val appendEntered = CountDownLatch(1)
        val appendRelease = CountDownLatch(1)
        val actualBuffer = GrowableText("live body")
        val buffer = spyk(actualBuffer)
        actualBuffer.append(" tail")
        every { buffer.toString() } answers {
            liveEntered.countDown()
            assertTrue(liveRelease.await(5, TimeUnit.SECONDS))
            actualBuffer.toString()
        }
        val harness = Harness(this, listOf(LivePart.Text(buffer, 7L, 1)))
        harness.inlineSource.value = InlineMarkdownSource("**stored** body")
        mockkObject(NativeMarkdown)
        every { NativeMarkdown.inlineRuns(any()) } answers {
            if (Thread.currentThread() === ownerThread) return@answers null
            if (firstArg<String>() == "**stored** body") {
                inlineEntered.countDown()
                assertTrue(inlineRelease.await(5, TimeUnit.SECONDS))
            } else {
                appendEntered.countDown()
                assertTrue(appendRelease.await(5, TimeUnit.SECONDS))
            }
            null
        }
        try {
            withTimeout(5_000) {
                harness.start()
                while (liveEntered.count != 0L || inlineEntered.count != 0L) harness.frames()
                val handoff = ConversationSwitchHandoff()
                val posted = ArrayList<() -> Unit>()
                var closed = false
                var drawn = "" to ""
                fun draw() {
                    handoff.draw(
                        content = {
                            drawn = harness.output.parts.filterIsInstance<Part.Text>().joinToString { it.text } to
                                harness.inlineOutput.text
                        },
                        record = { error("this target draw does not capture the previous page") },
                        post = { posted += it },
                    )
                }
                val switch = launch(start = CoroutineStart.UNDISPATCHED) {
                    handoff.close(prepare = {
                        harness.readiness.awaitReady()
                        handoff.awaitDraw()
                        true
                    }) { closed = true }
                }
                draw()
                posted.forEach { it() }
                posted.clear()
                harness.frames()
                assertEquals("" to "", drawn)
                assertFalse("an empty first draw cannot start closing", closed)

                liveRelease.countDown()
                harness.await { (it.singleOrNull() as? Part.Text)?.text == "live body tail" }
                draw()
                posted.forEach { it() }
                posted.clear()
                harness.frames()
                assertFalse("inline content is still preparing", closed)

                inlineRelease.countDown()
                while (harness.inlineOutput.text != "stored body" || handoff.pendingDraw == null) harness.frames()
                draw()
                assertEquals("live body tail" to "stored body", drawn)
                assertFalse("the completed content still needs its final posted draw", closed)
                posted.single().invoke()
                switch.join()
                assertTrue(closed)

                harness.inlineSource.value = InlineMarkdownSource("**stored** body tail")
                while (appendEntered.count != 0L) harness.frames()
                harness.readiness.awaitReady()
                assertEquals("ordinary append keeps the completed text and does not reopen the gate",
                    "stored body", harness.inlineOutput.text)
                appendRelease.countDown()
                while (harness.inlineOutput.text != "stored body tail") harness.frames()
            }
        } finally {
            liveRelease.countDown()
            inlineRelease.countDown()
            appendRelease.countDown()
            harness.close()
        }
    }

    @Test fun disposingThePendingLeafReleasesANonOwnedOrEmptyLiveViewport() = runBlocking {
        val ownerThread = Thread.currentThread()
        val inlineEntered = CountDownLatch(1)
        val inlineRelease = CountDownLatch(1)
        val foreign = spyk(GrowableText("foreign run"))
        every { foreign.toString() } answers { error("a different run must not be projected") }
        val harness = Harness(this, emptyList())
        harness.source.value = LiveContent("other", "foreign", listOf(LivePart.Text(foreign)))
        harness.inlineSource.value = InlineMarkdownSource("**pending** stored body")
        mockkObject(NativeMarkdown)
        every { NativeMarkdown.inlineRuns(any()) } answers {
            if (Thread.currentThread() === ownerThread) return@answers null
            inlineEntered.countDown()
            assertTrue(inlineRelease.await(5, TimeUnit.SECONDS))
            null
        }
        try {
            withTimeout(5_000) {
                harness.start()
                while (inlineEntered.count != 0L) harness.frames()
                val ready = async(start = CoroutineStart.UNDISPATCHED) { harness.readiness.awaitReady() }
                assertFalse(ready.isCompleted)
                harness.inlineSource.value = null
                while (!ready.isCompleted) harness.frames()
                ready.await()
                assertTrue(harness.output.parts.isEmpty())

                harness.runId.value = "empty"
                harness.source.value = LiveContent("chat", "empty", emptyList())
                harness.await { it.isEmpty() }
                val emptyReady = async { harness.readiness.awaitReady() }
                while (!emptyReady.isCompleted) harness.frames()
                emptyReady.await()
                assertEquals("empty", harness.output.runId)
            }
        } finally { inlineRelease.countDown(); harness.close() }
    }

    @Test fun replacingAReadyRunWaitsForTheNewRunsOwnInitialContent() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val actualReplacement = GrowableText("replacement body")
        val replacement = spyk(actualReplacement)
        actualReplacement.append(" tail")
        every { replacement.toString() } answers {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            actualReplacement.toString()
        }
        val harness = Harness(this, listOf(LivePart.Text("ready original")))
        try {
            withTimeout(5_000) {
                harness.start()
                harness.await { (it.singleOrNull() as? Part.Text)?.text == "ready original" }
                harness.readiness.awaitReady()
                harness.runId.value = "replacement"
                harness.source.value = LiveContent("chat", "replacement", listOf(LivePart.Text(replacement, 7L, 1)))
                while (entered.count != 0L) harness.frames()
                val ready = async(start = CoroutineStart.UNDISPATCHED) { harness.readiness.awaitReady() }
                assertFalse("the previous run's collected value cannot release the replacement", ready.isCompleted)
                assertTrue(harness.output.parts.isEmpty())
                release.countDown()
                harness.await { (it.singleOrNull() as? Part.Text)?.text == "replacement body tail" }
                ready.await()
            }
        } finally { release.countDown(); harness.close() }
    }

    private data class Rendered(val runId: String, val parts: List<Part>)

    private class Harness(scope: CoroutineScope, parts: List<LivePart>) {
        val source = MutableStateFlow(LiveContent("chat", "run", parts))
        val runId = mutableStateOf("run")
        val showReasoning = mutableStateOf(true)
        val readiness = ViewportContentReadiness()
        val inlineSource = mutableStateOf<InlineMarkdownSource?>(null)
        var inlineOutput = AnnotatedString("")
            private set
        val publications = mutableListOf<Rendered>()
        var output = Rendered("", emptyList())
            private set
        private val actions = mockk<ChatScreenActions>()
        private val lifecycle = ChatUiLifecycleOwner(true, LifecycleRegistry::createUnsafe)
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext)
        private val composition = Composition(UnitApplier(), recomposer)
        private val runner = scope.launch(clock, CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }
        private var nanos = 0L

        init {
            val dispatcher = scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
            val background = Dispatchers.Default
            val io = Dispatchers.IO
            val unconfined = Dispatchers.Unconfined
            val main = object : MainCoroutineDispatcher() {
                override val immediate: MainCoroutineDispatcher get() = this
                override fun dispatch(context: CoroutineContext, block: Runnable) = dispatcher.dispatch(context, block)
            }
            mockkStatic(Dispatchers::class)
            every { Dispatchers.Main } returns main
            every { Dispatchers.Default } returns background
            every { Dispatchers.IO } returns io
            every { Dispatchers.Unconfined } returns unconfined
            every { actions.liveContent } returns source
            every { actions.currentLiveContent } returns this::currentLiveContent
            lifecycle.parentChanged(Lifecycle.State.RESUMED)
        }

        private fun currentLiveContent(): LiveContent = source.value

        fun start() {
            composition.setContent {
                val current = runId.value
                CompositionLocalProvider(
                    LocalLifecycleOwner provides lifecycle,
                    LocalViewportContentReadiness provides readiness,
                ) {
                    LiveMessageParts(actions, "chat", current, showReasoning.value) { parts ->
                        SideEffect {
                            output = Rendered(current, parts)
                            publications += output
                        }
                    }
                    inlineSource.value?.let { source ->
                        val annotated = rememberInlineMarkdown(source)
                        SideEffect { inlineOutput = annotated }
                    }
                }
            }
        }

        suspend fun await(matches: (List<Part>) -> Boolean) {
            while (output.runId != runId.value || !matches(output.parts)) frames()
        }

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

        suspend fun close() = withContext(NonCancellable) {
            lifecycle.destroy()
            composition.dispose()
            recomposer.close()
            runner.cancelAndJoin()
            unmockkAll()
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
