package com.suixing.agent.ui.components

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercise the card's production composition lifetime and real Animatables without Android windows. */
class PopupCompositionLifecycleTest {
    @Test fun `closed cards allocate no motion and a completed exit releases it`() = withHarness {
        start()
        assertNull(motion)
        visible = true
        settleOpen()
        val first = motion
        assertNotNull(first)

        visible = false
        frames(2)
        assertSame("the outgoing card must retain its animation state", first, motion)
        settleClosed()
        assertNull(motion)

        visible = true
        settleOpen()
        assertNotSame("a fully closed card must have released its motion", first, motion)
    }

    @Test fun `reopening during exit retains and reverses the same motion`() = withHarness {
        start()
        visible = true
        settleOpen()
        val first = requireNotNull(motion)

        visible = false
        frames(3)
        assertSame(first, motion)
        assertTrue("exit must have advanced before reversal", first.opacity.value in 0f..0.9999f)
        assertTrue("exit must not have completed before reversal", first.opacity.value > 0f)

        visible = true
        frames(1)
        assertSame("reversal must keep the mounted card", first, motion)
        settleOpen()
        assertSame(first, motion)
        assertTrue(first.opacity.value == 1f && first.progress.value == 1f)

        visible = false
        settleClosed()
    }

    private class Harness(scope: CoroutineScope) {
        var visible by mutableStateOf(false)
        var motion: PopupMotionState? = null
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
                val mounted = rememberMountedPopupMotion(visible, compactMotion = true)
                SideEffect { motion = mounted }
            }
            frames(3)
        }

        suspend fun settleOpen() {
            frames(2)
            repeat(120) {
                val current = motion
                if (current != null && current.opacity.value == 1f && current.progress.value == 1f) return
                frames(1)
            }
            error("popup did not finish entering")
        }

        suspend fun settleClosed() {
            frames(2)
            repeat(120) {
                if (motion == null) return
                frames(1)
            }
            error("popup did not leave composition after its exit")
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

    private fun withHarness(block: suspend Harness.() -> Unit) = runBlocking {
        withTimeout(5000) {
            val harness = Harness(this)
            try { harness.block() } finally { harness.close() }
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
