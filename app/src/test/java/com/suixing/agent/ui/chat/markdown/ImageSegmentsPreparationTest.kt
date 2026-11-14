package com.suixing.agent.ui.chat.markdown

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
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

class ImageSegmentsPreparationTest {
    @Test fun coldImageSegmentsPrepareAfterCompositionAndPublishTheCompleteCurrentSource() = runBlocking {
        withTimeout(5_000) {
            val before = "before the first image\n"
            val after = "\nafter the first image".repeat(100)
            val firstImage = "![first](https://example.test/first.png)"
            val initial = before + firstImage + after
            val harness = Harness(this, initial).apply { streaming.value = true }
            try {
                harness.start()
                assertTrue("a cold image source must not split inside composition", harness.output.isEmpty())
                harness.await(listOf(
                    MessageSegment.Text(before),
                    MessageSegment.Image("first", "https://example.test/first.png"),
                    MessageSegment.Text(after),
                ))

                val completed = initial + "\n![second](https://example.test/second.png)\nfinished"
                harness.text.value = completed
                harness.version.value = 2
                harness.await(listOf(
                    MessageSegment.Text(before),
                    MessageSegment.Image("first", "https://example.test/first.png"),
                    MessageSegment.Text(after + "\n"),
                    MessageSegment.Image("second", "https://example.test/second.png"),
                    MessageSegment.Text("\nfinished"),
                ))

                harness.text.value = completed + " pending".repeat(1_000)
                harness.version.value = 3
                harness.frames()
                harness.text.value = "replacement\n![new](https://example.test/new.png)\ncomplete"
                harness.sourceId.value = 2
                harness.version.value = 1
                val replacement = listOf(
                    MessageSegment.Text("replacement\n"),
                    MessageSegment.Image("new", "https://example.test/new.png"),
                    MessageSegment.Text("\ncomplete"),
                )
                harness.await(replacement)
                harness.frames()
                assertEquals("an older source must not replace the completed current segments", replacement, harness.output)
            } finally { harness.close() }
        }
    }

    @Test fun preparedSettledSegmentsAreImmediateAndSurviveRemovingTheirProvider() = runBlocking {
        withTimeout(5_000) {
            val source = "prepared\n![image](https://example.test/prepared.png)\ncomplete"
            val prepared = listOf(
                MessageSegment.Text("prepared\n"),
                MessageSegment.Image("image", "https://example.test/prepared.png"),
                MessageSegment.Text("\ncomplete"),
            )
            val harness = Harness(this, source).apply { documents.value = mapOf(source to prepared) }
            try {
                harness.start()
                assertSame("a prepared settled source must be available during its first composition", prepared, harness.output)
                harness.documents.value = emptyMap()
                harness.frames()
                assertSame("removing the provider must retain the same completed segmentation", prepared, harness.output)
            } finally { harness.close() }
        }
    }

    private class Harness(scope: CoroutineScope, initial: String) {
        val text = mutableStateOf(initial)
        val version = mutableStateOf(1)
        val sourceId = mutableStateOf(1)
        val streaming = mutableStateOf(false)
        val documents = mutableStateOf<Map<String, List<MessageSegment>>>(emptyMap())
        var output: List<MessageSegment> = emptyList()
            private set
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext)
        private val composition = Composition(UnitApplier(), recomposer)
        private val runner = scope.launch(clock, CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }
        private var nanos = 0L

        fun start() {
            composition.setContent {
                CompositionLocalProvider(LocalPreparedImageSegments provides documents.value) {
                    val segments = rememberImageSegments(text.value, streaming.value, version.value, sourceId.value)
                    SideEffect { output = segments }
                }
            }
        }

        suspend fun await(expected: List<MessageSegment>) {
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
