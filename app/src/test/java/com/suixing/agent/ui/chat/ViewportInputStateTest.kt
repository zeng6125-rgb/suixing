package com.suixing.agent.ui.chat

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ViewportInputStateTest {
    @Test fun equalRebuiltInputsUpdateTheMountedStateAndNotifyItsReader() = withInput(listOf("a", "b")) {
        val mounted = latest
        var changes = 0
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            observer.observeReads(Unit, { _: Unit -> changes++ }) { mounted.value }
            val rebuilt = listOf("a", "b")
            input = rebuilt
            frames(3)

            assertSame("long-lived viewport effects must keep their State object", mounted, latest)
            assertSame("viewport preparation must see the latest row projection", rebuilt, mounted.value)
            assertEquals("equal-new projections must notify snapshot readers", 1, changes)
        } finally {
            observer.stop()
            observer.clear()
        }
    }

    @Test fun rebuildingAnEqualProjectionDoesNotWalkItsElements() = withInput(
        CountedList(listOf("a", "b")),
    ) {
        val before = input
        val after = CountedList(listOf("a", "b"))
        before.comparisons = 0
        input = after
        frames(3)

        assertEquals("viewport input updates must not compare transcript collections", 0,
            before.comparisons + after.comparisons)
        assertSame(after, latest.value)
    }

    private class CountedList(private val items: List<String>) : AbstractList<String>() {
        var comparisons = 0
        override val size get() = items.size
        override fun get(index: Int) = items[index]
        override fun equals(other: Any?): Boolean {
            comparisons++
            return super.equals(other)
        }
    }

    private class Harness<T>(scope: CoroutineScope, initial: T) {
        var input by mutableStateOf(initial, referentialEqualityPolicy())
        lateinit var latest: State<T>
            private set
        private val clock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext)
        private val composition = Composition(UnitApplier(), recomposer)
        private val runner = scope.launch(clock, CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }
        private var nanos = 0L

        suspend fun start() {
            composition.setContent {
                val current = rememberViewportInput(input)
                SideEffect { latest = current }
            }
            frames(3)
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

        suspend fun close() {
            composition.dispose()
            recomposer.close()
            runner.cancelAndJoin()
        }
    }

    private fun <T> withInput(initial: T, block: suspend Harness<T>.() -> Unit) = runBlocking {
        withTimeout(5000) {
            val harness = Harness(this, initial)
            try {
                harness.start()
                harness.block()
            } finally {
                harness.close()
            }
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
