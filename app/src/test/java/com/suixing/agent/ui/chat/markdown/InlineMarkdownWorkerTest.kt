package com.suixing.agent.ui.chat.markdown

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.AnnotatedString
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
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
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class InlineMarkdownWorkerTest {
    @Test fun continuousAppendsPublishCompletedPrefixesWithoutWaitingForTheSourceToStop() = runBlocking {
        withTimeout(10_000) {
            val started = Channel<String>(Channel.UNLIMITED)
            val release = Semaphore(0)
            mockkObject(NativeMarkdown)
            every { NativeMarkdown.inlineRuns(any()) } answers {
                started.trySend(firstArg()).getOrThrow()
                release.acquire()
                null
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
            val started = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            mockkObject(NativeMarkdown)
            every { NativeMarkdown.inlineRuns(any()) } answers {
                if (firstArg<String>() == replacement) {
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
            val started = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            mockkObject(NativeMarkdown)
            every { NativeMarkdown.inlineRuns(any()) } answers {
                if (firstArg<String>() == "**old blocked**") {
                    started.complete(Unit)
                    release.await()
                }
                null
            }
            val harness = Harness(this, InlineMarkdownSource("**old blocked**"))
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

    private class Harness(scope: CoroutineScope, initial: InlineMarkdownSource) {
        val source = mutableStateOf(initial)
        val owner = mutableStateOf(0)
        var output = AnnotatedString("")
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
                key(owner.value) {
                    val prepared = rememberInlineMarkdown(source.value)
                    SideEffect { output = prepared }
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
