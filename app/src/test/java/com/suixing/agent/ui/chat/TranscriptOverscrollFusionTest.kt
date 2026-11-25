package com.suixing.agent.ui.chat

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptOverscrollFusionTest {
    private class FakeDelegateEffect : OverscrollEffect {
        var progress = false
        val scrollAvailable = mutableListOf<Offset>()
        val flingVelocity = mutableListOf<Velocity>()

        override val isInProgress: Boolean
            get() = progress

        override val node: DelegatableNode = object : Modifier.Node() {}

        override fun applyToScroll(
            delta: Offset,
            source: NestedScrollSource,
            performScroll: (Offset) -> Offset,
        ): Offset {
            scrollAvailable += delta
            val innerConsumed = performScroll(delta)
            val unconsumed = delta - innerConsumed
            if (unconsumed.y != 0f || unconsumed.x != 0f) {
                progress = true
            }
            return delta
        }

        override suspend fun applyToFling(
            velocity: Velocity,
            performFling: suspend (Velocity) -> Velocity,
        ) {
            flingVelocity += velocity
            val consumed = performFling(velocity)
            if ((velocity - consumed).y != 0f) {
                progress = true
            }
        }
    }

    @Test
    fun `vertical overscroll wrapper strips horizontal deltas and tracks settle cleanly`() = runBlocking {
        val delegate = FakeDelegateEffect()
        val host = VerticalOverscrollHost(object : OverscrollFactory {
            override fun createOverscrollEffect(): OverscrollEffect = delegate
            override fun equals(other: Any?): Boolean = other === this
            override fun hashCode(): Int = System.identityHashCode(this)
        })
        val effect = host.factory.createOverscrollEffect()
        val forwardedToScroller = mutableListOf<Offset>()

        assertFalse(host.active)
        val consumed = effect.applyToScroll(
            delta = Offset(x = 42f, y = -90f),
            source = NestedScrollSource.UserInput,
        ) { remaining ->
            forwardedToScroller += remaining
            Offset(x = 0f, y = -30f)
        }

        // Delegate never sees horizontal X so sideways offsetX never accumulates,
        // while inner scroller still gets full X and consumed.x stays 0f for parent drawer.
        assertEquals(listOf(Offset(0f, -90f)), delegate.scrollAvailable)
        assertEquals(listOf(Offset(42f, -90f)), forwardedToScroller)
        assertEquals(Offset(0f, -90f), consumed)
        assertTrue(host.active)

        effect.applyToFling(Velocity(x = 640f, y = -1200f)) { vel ->
            assertEquals(Velocity(640f, -1200f), vel)
            Velocity(0f, -400f)
        }
        assertEquals(listOf(Velocity(0f, -1200f)), delegate.flingVelocity)
        assertTrue(host.active)

        // Once the spring settles back inside threshold, refreshActive clears host.active on that frame.
        delegate.progress = false
        assertFalse(host.refreshActive())
        assertFalse(host.active)
    }
}
