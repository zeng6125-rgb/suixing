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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GrowingMarkdownPreparationTest {
    @Test fun settlingWithANewTailKeepsTheDisplayedPrefixDuringRemount() = runBlocking {
        withTimeout(5_000) {
            val prefix = "intro\n\n**ongoing** response"
            val complete = "$prefix final tail"
            val harness = Harness(this, prefix)
            try {
                harness.start()
                harness.await(parseMarkdown(prefix))
                val displayed = harness.output
                harness.publications.clear()
                harness.text.value = complete
                harness.settled.value = true
                harness.version.value = -1
                harness.sourceId.value = 0
                harness.owner.value++
                harness.await(parseMarkdown(complete))
                assertSame("the stored row must initially retain the displayed prefix", displayed, harness.publications.first())
                assertTrue("completion must not publish an empty document", harness.publications.none { it.isEmpty() })
            } finally { harness.close() }
        }
    }

    @Test fun shortColdSettledTextParsesSynchronouslyOnFirstComposition() = runBlocking {
        withTimeout(5_000) {
            val text = "7. Short **reasoning** line `ok`"
            val harness = Harness(this, text).apply { settled.value = true; retainParser.value = false }
            try {
                harness.start()
                assertEquals(parseMarkdown(text), harness.output)
            } finally { harness.close() }
        }
    }

    @Test fun coldSettledTextParsesAfterCompositionAndReusesTheCompletedResultOnRemount() = runBlocking {
        withTimeout(5_000) {
            val text = "cold settled heading\n\n```kt\n" + "val cold = 7\n".repeat(100) + "```\n\ncomplete"
            val harness = Harness(this, text).apply { settled.value = true; retainParser.value = false }
            lateinit var parsed: List<MdBlock>
            try {
                harness.start()
                assertTrue("an unprepared settled row must not parse inside composition", harness.output.isEmpty())
                harness.await(parseMarkdown(text))
                parsed = harness.output
            } finally { harness.close() }
            val reopened = Harness(this, text).apply { settled.value = true; retainParser.value = false }
            try {
                reopened.start()
                assertSame("the completed cold parse must be available immediately on remount", parsed, reopened.output)
            } finally { reopened.close() }
        }
    }

    @Test fun aCompletedLiveParseSurvivesRemovingItsProviderAtSettlement() = runBlocking {
        withTimeout(5_000) {
            val text = "retained final text\n\n" + "complete paragraph\n\n".repeat(80)
            val harness = Harness(this, text)
            try {
                harness.start()
                harness.await(parseMarkdown(text))
                val parsed = harness.output
                harness.retainParser.value = false
                harness.settled.value = true
                harness.frames()
                assertSame("settlement must reuse the live document even after its provider releases it", parsed, harness.output)
            } finally { harness.close() }
        }
    }

    @Test fun growingTextDoesNotParseInsideCompositionAndSettlingRetainsTheResult() = runBlocking {
        withTimeout(5_000) {
            val text = "intro\n\n```kt\n" + "val value = 1\n".repeat(80) + "```\n\nend"
            val harness = Harness(this, text)
            try {
                harness.start()
                assertTrue("the initial composition must not synchronously parse the live document", harness.output.isEmpty())
                harness.await(parseMarkdown(text))
                val parsed = harness.output
                harness.settled.value = true
                harness.frames()
                assertSame("live-to-stored handover must reuse the complete live parse", parsed, harness.output)
            } finally {
                harness.close()
            }
        }
    }

    @Test fun appendsAndAReplacementSourcePublishTheCompleteCurrentDocument() = runBlocking {
        withTimeout(5_000) {
            val initial = "intro\n\n```kt\n" + "val value = 1\n".repeat(60)
            val harness = Harness(this, initial)
            try {
                harness.start()
                harness.await(parseMarkdown(initial))
                val firstBlock = harness.output.first()
                val completed = initial + "```\n\nafter"
                harness.text.value = completed
                harness.version.value = 2
                harness.await(parseMarkdown(completed))
                assertSame("append parsing must retain the settled prefix", firstBlock, harness.output.first())

                harness.text.value = completed + " more text"
                harness.version.value = 3
                harness.frames()
                val replaced = "| left | right |\n| --- | --- |\n" + "| one | two |\n".repeat(50)
                harness.text.value = replaced
                harness.sourceId.value = 2
                harness.version.value = 1
                harness.await(parseMarkdown(replaced))
                assertEquals(parseMarkdown(replaced), harness.output)
            } finally {
                harness.close()
            }
        }
    }

    private class Harness(scope: CoroutineScope, initial: String) {
        val text = mutableStateOf(initial)
        val version = mutableStateOf(1)
        val sourceId = mutableStateOf(1)
        val settled = mutableStateOf(false)
        val retainParser = mutableStateOf(true)
        val owner = mutableStateOf(0)
        val publications = mutableListOf<List<MdBlock>>()
        var output: List<MdBlock> = emptyList()
            private set
        private val parser = LiveBlocks()
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext)
        private val composition = Composition(UnitApplier(), recomposer)
        private val runner = scope.launch(clock, CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }
        private var nanos = 0L

        fun start() {
            composition.setContent {
                CompositionLocalProvider(LocalRetainedLiveMarkdown provides if (retainParser.value) parser else null) {
                    key(owner.value) {
                        val parsed = rememberGrowingMarkdown(text.value, settled.value, version.value, sourceId.value)
                        SideEffect {
                            output = parsed
                            publications += parsed
                        }
                    }
                }
            }
        }

        suspend fun await(expected: List<MdBlock>) {
            while (output != expected) frames()
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

        suspend fun close() {
            composition.dispose()
            recomposer.close()
            runner.cancelAndJoin()
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
