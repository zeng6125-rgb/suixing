package com.suixing.agent.ui.chat.markdown

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.suixing.agent.ui.chat.LocalReadingTextKey
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineMarkdownWorkerTest {
    @Test fun completedInlineTextSurvivesReplacingTheLiveCompositionOwner() = runBlocking {
        withTimeout(10_000) {
            mockkObject(NativeMarkdown)
            every { NativeMarkdown.inlineRuns(any()) } returns null
            val harness = Harness(this, InlineMarkdownSource("**completed** response"), retainParser = true)
            try {
                harness.start()
                harness.await { harness.output.text == "completed response" }
                val displayed = harness.output
                harness.publications.clear()
                harness.owner.value++
                harness.frames()
                assertSame("completion must reuse the displayed spans on its first composition", displayed, harness.publications.first())
                assertTrue("completion must not publish empty inline text", harness.publications.none { it.text.isEmpty() })
            } finally {
                harness.close()
                unmockkObject(NativeMarkdown)
            }
        }
    }

    @Test fun aFinalTailKeepsItsRenderedInlinePrefixWhileTheNewOwnerParses() = runBlocking {
        withTimeout(10_000) {
            mockkObject(NativeMarkdown)
            every { NativeMarkdown.inlineRuns(any()) } returns null
            val harness = Harness(this, InlineMarkdownSource("**completed** response"), retainParser = true)
            try {
                harness.start()
                harness.await { harness.output.text == "completed response" }
                val displayed = harness.output
                harness.publications.clear()
                harness.source.value = harness.source.value.copy(text = "**completed** response final tail")
                harness.owner.value++
                harness.await { harness.output.text == "completed response final tail" }
                assertSame("the last rendered prefix must survive the completion handoff", displayed, harness.publications.first())
                assertTrue("the final tail must replace its prefix without a blank publication", harness.publications.none { it.text.isEmpty() })
            } finally {
                harness.close()
                unmockkObject(NativeMarkdown)
            }
        }
    }

    @Test fun preparedInlineIsVisibleOnTheFirstComposition() = runBlocking {
        withTimeout(10_000) {
            mockkObject(NativeMarkdown)
            every { NativeMarkdown.inlineRuns("**ready**") } returns listOf(NativeInlineRun("ready", 1, null))
            val harness = Harness(this, InlineMarkdownSource("**ready**"))
            try {
                harness.start()
                val first = checkNotNull(harness.firstOutput)
                assertEquals("ready", first.text)
                val bold = first.spanStyles.single()
                assertEquals(FontWeight.SemiBold, bold.item.fontWeight)
                assertEquals(0, bold.start)
                assertEquals(5, bold.end)
            } finally {
                harness.close()
                unmockkObject(NativeMarkdown)
            }
        }
    }

    @Test fun preparedInlineDoesNotScanItsSourceUntilTextChanges() = runBlocking {
        withTimeout(10_000) {
            val initial = "**ready**"
            val examined = CompletableDeferred<Unit>()
            mockkObject(NativeMarkdown)
            mockkStatic(::literalInlineSource)
            every { NativeMarkdown.inlineRuns(any()) } answers {
                if (firstArg<String>() == initial) listOf(NativeInlineRun("ready", 1, null)) else null
            }
            every { literalInlineSource(initial, 0) } answers {
                examined.complete(Unit)
                callOriginal()
            }
            val harness = Harness(this, InlineMarkdownSource(initial))
            try {
                harness.start()
                assertEquals("ready", harness.output.text)
                assertNull(withTimeoutOrNull(350) { examined.await() })

                harness.source.value = InlineMarkdownSource("$initial tail")
                harness.await { harness.output.text == "ready tail" }
                assertTrue(examined.isCompleted)
            } finally {
                harness.close()
                unmockkStatic(::literalInlineSource)
                unmockkObject(NativeMarkdown)
            }
        }
    }

    @Test fun aRewriteClearsAnInitiallyPreparedResultBeforeItsNewParseCompletes() = runBlocking {
        withTimeout(10_000) {
            val replacement = "a longer replacement **different** paragraph"
            val ownerThread = Thread.currentThread()
            val started = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            mockkObject(NativeMarkdown)
            every { NativeMarkdown.inlineRuns(any()) } answers {
                when (firstArg<String>()) {
                    "**ready**" -> listOf(NativeInlineRun("ready", 1, null))
                    replacement -> {
                        if (Thread.currentThread() !== ownerThread) {
                            started.complete(Unit)
                            release.await()
                        }
                        null
                    }
                    else -> null
                }
            }
            val harness = Harness(this, InlineMarkdownSource("**ready**"))
            try {
                harness.start()
                assertEquals("ready", harness.output.text)
                harness.source.value = harness.source.value.copy(text = replacement)
                harness.await { started.isCompleted }
                harness.await { harness.output.text.isEmpty() }
                release.countDown()
                harness.await { harness.output.text == "a longer replacement different paragraph" }
            } finally {
                release.countDown()
                harness.close()
                unmockkObject(NativeMarkdown)
            }
        }
    }

    @Test fun continuousAppendsPublishCompletedPrefixesWithoutWaitingForTheSourceToStop() = runBlocking {
        withTimeout(10_000) {
            val ownerThread = Thread.currentThread()
            val started = Channel<String>(Channel.UNLIMITED)
            val release = Semaphore(0)
            mockkObject(NativeMarkdown)
            every { NativeMarkdown.inlineRuns(any()) } answers {
                if (Thread.currentThread() === ownerThread) null else {
                    started.trySend(firstArg()).getOrThrow()
                    release.acquire()
                    null
                }
            }
            val harness = Harness(this, InlineMarkdownSource("**one**", cursor = true))
            try {
                harness.start()
                assertEquals("**one**", harness.next(started))
                harness.source.value = harness.source.value.copy(text = "**one** alpha")
                harness.frames()
                release.release()
                assertEquals("**one** alpha", harness.next(started))
                harness.await { harness.output.text == "one\u200B" }

                harness.source.value = harness.source.value.copy(text = "**one** alpha beta")
                harness.frames()
                release.release()
                assertEquals("**one** alpha beta", harness.next(started))
                harness.await { harness.output.text == "one alpha\u200B" }
                assertEquals(1, harness.output.text.count { it == '\u200B' })
            } finally {
                release.release()
                harness.close()
                unmockkObject(NativeMarkdown)
            }
        }
    }

    @Test fun aLongerRewriteClearsThePreviousResultBeforeItsNewParseCompletes() = runBlocking {
        withTimeout(10_000) {
            val replacement = "a longer replacement **different** paragraph"
            val ownerThread = Thread.currentThread()
            val started = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            mockkObject(NativeMarkdown)
            every { NativeMarkdown.inlineRuns(any()) } answers {
                if (Thread.currentThread() !== ownerThread && firstArg<String>() == replacement) {
                    started.complete(Unit)
                    release.await()
                }
                null
            }
            val harness = Harness(this, InlineMarkdownSource("**old** tail"))
            try {
                harness.start()
                harness.await { harness.output.text == "old tail" }
                harness.source.value = harness.source.value.copy(text = replacement)
                harness.await { started.isCompleted }
                harness.await { harness.output.text.isEmpty() }
                release.countDown()
                harness.await { harness.output.text == "a longer replacement different paragraph" }
            } finally {
                release.countDown()
                harness.close()
                unmockkObject(NativeMarkdown)
            }
        }
    }

    @Test fun aCancelledNodeCannotPublishItsDelayedResultIntoTheNextNode() = runBlocking {
        withTimeout(10_000) {
            val ownerThread = Thread.currentThread()
            val started = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            mockkObject(NativeMarkdown)
            every { NativeMarkdown.inlineRuns(any()) } answers {
                if (Thread.currentThread() !== ownerThread && firstArg<String>() == "**old blocked**") {
                    started.complete(Unit)
                    release.await()
                }
                null
            }
            val harness = Harness(this, InlineMarkdownSource("**old blocked**"), retainParser = true)
            try {
                harness.start()
                harness.await { started.isCompleted }
                harness.source.value = InlineMarkdownSource("**new**")
                harness.owner.value++
                harness.await { harness.output.text == "new" }
                release.countDown()
                harness.frames()
                assertEquals("new", harness.output.text)
            } finally {
                release.countDown()
                harness.close()
                unmockkObject(NativeMarkdown)
            }
        }
    }

    private class Harness(scope: CoroutineScope, initial: InlineMarkdownSource, private val retainParser: Boolean = false) {
        val source = mutableStateOf(initial)
        val owner = mutableStateOf(0)
        val publications = mutableListOf<AnnotatedString>()
        private val parser = LiveBlocks()
        var output = AnnotatedString("")
            private set
        var firstOutput: AnnotatedString? = null
            private set
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext)
        private val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun onClear() = Unit
        }, recomposer)
        private val runner = scope.launch(clock, CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }
        private var nanos = 0L

        fun start() {
            composition.setContent {
                CompositionLocalProvider(
                    LocalRetainedLiveMarkdown provides if (retainParser) parser else null,
                    LocalReadingTextKey provides if (retainParser) "run:text:0:block:0" else null,
                ) {
                    key(owner.value) {
                        val prepared = rememberInlineMarkdown(source.value)
                        SideEffect {
                            if (firstOutput == null) firstOutput = prepared
                            output = prepared
                            publications += prepared
                        }
                    }
                }
            }
        }

        suspend fun await(condition: () -> Boolean) {
            while (!condition()) frames()
        }

        suspend fun next(channel: Channel<String>): String {
            var next: String? = null
            await {
                next = channel.tryReceive().getOrNull() ?: next
                next != null
            }
            return next!!
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
            composition.dispose()
            recomposer.close()
            runner.cancelAndJoin()
        }
    }
}
