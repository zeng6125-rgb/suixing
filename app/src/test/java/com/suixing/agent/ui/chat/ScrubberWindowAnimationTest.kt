package com.suixing.agent.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrubberWindowAnimationTest {
    @Test fun `a newer reading window enters from the previous tick then centers`() = runBlocking {
        assertIncomingShift(20, 21, -10f)
    }

    @Test fun `an older reading window enters in the opposite direction then centers`() = runBlocking {
        assertIncomingShift(20, 19, 10f)
    }

    @Test fun `another forward window continues from the running position`() = runBlocking {
        assertContinuousShift(-10f)
    }

    @Test fun `a reversed window continues from the running position`() = runBlocking {
        assertContinuousShift(10f)
    }

    private suspend fun assertContinuousShift(nextShift: Float) = kotlinx.coroutines.coroutineScope {
        val clock = BroadcastFrameClock()
        val nudge = Animatable(0f)
        val velocity = floatArrayOf(0f)
        var frame = 0L
        val first = launch(clock, start = CoroutineStart.UNDISPATCHED) {
            animateScrubberNudge(nudge, -10f, velocity)
        }
        repeat(5) {
            frame += 16_000_000L
            clock.sendFrame(frame)
            yield()
        }
        assertTrue("the next window arrives during the previous entry", first.isActive)
        val position = nudge.value
        val speed = velocity[0]
        assertTrue("the first entry has left its starting position", position > -10f)
        assertTrue("the first entry is still moving", speed != 0f)
        first.cancelAndJoin()
        val next = launch(clock, start = CoroutineStart.UNDISPATCHED) {
            animateScrubberNudge(nudge, nextShift, velocity)
        }
        try {
            assertEquals("changing windows must not reset the running position",
                position, nudge.value, 0.001f)
            assertEquals("changing windows must retain the sampled speed", speed, velocity[0], 0.001f)
            repeat(120) {
                if (next.isActive) {
                    frame += 16_000_000L
                    clock.sendFrame(frame)
                    yield()
                }
            }
            assertFalse("continuous window entries must settle", next.isActive)
            assertEquals(0f, nudge.value, 0.001f)
            assertEquals(0f, velocity[0], 0.001f)
        } finally {
            next.cancelAndJoin()
        }
    }

    private suspend fun assertIncomingShift(previousTurn: Int, nextTurn: Int, expectedOffset: Float) =
        kotlinx.coroutines.coroutineScope {
            val previous = scrubberWindow(40, previousTurn, 40).first
            val current = scrubberWindow(40, nextTurn, 40).first
            assertTrue(scrubberNudgeNeeded(previous, current, -1, false))
            val shift = if (current > previous) -10f else 10f
            val clock = BroadcastFrameClock()
            val nudge = Animatable(0f)
            val velocity = floatArrayOf(0f)
            val animation = launch(clock, start = CoroutineStart.UNDISPATCHED) {
                animateScrubberNudge(nudge, shift, velocity)
            }
            try {
                assertEquals("the new window must begin at the previous tick's position",
                    expectedOffset, nudge.value, 0.001f)
                var frame = 0L
                repeat(120) {
                    if (animation.isActive) {
                        frame += 16_000_000L
                        clock.sendFrame(frame)
                        yield()
                    }
                }
                assertFalse("the reading-window entry must settle", animation.isActive)
                assertEquals(0f, nudge.value, 0.001f)
                assertEquals(0f, velocity[0], 0.001f)
            } finally {
                animation.cancelAndJoin()
            }
        }
}
