package com.suixing.agent.ui.chat

import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.runtime.Applier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Controlled mid-transition poses verify snapshot identity and actions, not device pixels/timing. */
class SlashSuggestionTransitionTest {
    private val five = (1..5).map { "tool_$it" to "description $it" }
    private val one = listOf("goal" to "set goal")
    private val other = listOf("goal_read" to "read goal")

    @Test fun `filtering retains the outgoing snapshot but only the target is interactive`() = runBlocking {
        withTimeout(5000) {
            val harness = Harness(this, five)
            try {
                harness.start()
                assertEquals(mapOf(five to true), harness.mounted)
                harness.seek(one)
                assertTrue("outgoing snapshot missing at an intermediate pose", five in harness.mounted)
                assertFalse("outgoing commands remained selectable", harness.mounted.getValue(five))
                assertEquals(true, harness.mounted[one])
                harness.settle()
                assertEquals(mapOf(one to true), harness.mounted)
                harness.seek(five)
                assertEquals(false, harness.mounted[one])
                assertEquals(true, harness.mounted[five])
                harness.settle()
                assertEquals(mapOf(five to true), harness.mounted)
            } finally { harness.close() }
        }
    }

    @Test fun `same count different commands preserves identity but equal results do not remount`() = runBlocking {
        withTimeout(5000) {
            val harness = Harness(this, one)
            try {
                harness.start()
                harness.seek(other)
                assertTrue("count-only identity swallowed the old payload", one in harness.mounted)
                assertEquals(false, harness.mounted[one])
                assertEquals(true, harness.mounted[other])
                harness.settle()
                val before = harness.mounts
                harness.seek(other.toList())
                harness.settle()
                assertEquals("unchanged results remounted", before, harness.mounts)
                assertEquals(mapOf(other to true), harness.mounted)
            } finally { harness.close() }
        }
    }

    @Test fun `rapid retargets and hiding never leave any stale command interactive`() = runBlocking {
        withTimeout(5000) {
            val harness = Harness(this, five)
            try {
                harness.start()
                for (next in listOf(one, five.take(3), other, five)) {
                    harness.seek(next)
                    assertTrue(harness.mounted.all { (shown, enabled) -> !enabled || shown == next })
                }
                harness.interactive = false
                harness.seek(emptyList())
                assertTrue(harness.mounted.values.none { it })
                harness.interactive = true
                harness.seek(five)
                harness.settle()
                assertEquals(mapOf(five to true), harness.mounted)
            } finally { harness.close() }
            assertTrue("disposed popup retained a payload", harness.mounted.isEmpty())
        }
    }

    private class Harness(private val scope: CoroutineScope, initial: List<Pair<String, String>>) {
        private val state = SeekableTransitionState(initial)
        var interactive by mutableStateOf(true)
        val mounted = linkedMapOf<List<Pair<String, String>>, Boolean>()
        var mounts = 0
        private val viewConfiguration = mockk<ViewConfiguration>(relaxed = true)
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext)
        private val composition = Composition(uiApplier(), recomposer)
        private val runner = scope.launch(clock, CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        private var nanos = 0L

        suspend fun start() {
            composition.setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f),
                    LocalLayoutDirection provides LayoutDirection.Ltr,
                    LocalViewConfiguration provides viewConfiguration,
                ) {
                    val transition = rememberTransition(state, label = "slash-suggestions-test")
                    AnimatedSlashSuggestions(transition, interactive) { shown, enabled ->
                        SideEffect { mounted[shown] = enabled }
                        DisposableEffect(shown) {
                            mounts++
                            onDispose { mounted.remove(shown) }
                        }
                    }
                }
            }
            frames(3)
        }

        suspend fun seek(target: List<Pair<String, String>>) {
            val task = scope.async(clock, CoroutineStart.UNDISPATCHED) { state.seekTo(0.25f, targetState = target) }
            frames(6)
            task.await()
            frames(2)
        }

        suspend fun settle() {
            val task = scope.async(clock, CoroutineStart.UNDISPATCHED) { state.snapTo(state.targetState) }
            frames(6)
            task.await()
            frames(2)
        }

        private suspend fun frames(count: Int) {
            repeat(count) {
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

    companion object {
        // UiApplier/LayoutNode are Kotlin-internal; use the shipped UI tree, not a copied
        // content-identity model. It remains unattached, so no pixel geometry is asserted.
        @Suppress("UNCHECKED_CAST")
        private fun uiApplier(): Applier<Any> {
            val nodeClass = Class.forName("androidx.compose.ui.node.LayoutNode")
            val root = nodeClass.getDeclaredConstructor().newInstance()
            return Class.forName("androidx.compose.ui.node.UiApplier")
                .getDeclaredConstructor(nodeClass).newInstance(root) as Applier<Any>
        }
    }
}
