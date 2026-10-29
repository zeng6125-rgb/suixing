package com.suixing.agent.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunElapsedTest {
    @Test
    fun `clock is available before the first token`() = runBlocking {
        val result = samples(1_000L, 1_000L)
        assertEquals(listOf(0L), result.first)
        assertEquals(listOf(1_000L), result.second)
        assertEquals("0 秒", formatRunElapsed(result.first.single()))
    }

    @Test
    fun `late wakeups sample the real duration and resynchronise at the next second`() = runBlocking {
        val (elapsed, waits) = samples(1_000L, 4_500L, 5_000L, 12_550L)
        assertEquals(listOf(3_500L, 4_000L, 11_550L), elapsed)
        assertEquals(listOf(500L, 1_000L, 450L), waits)
    }

    @Test
    fun `recreated caption resumes from the run origin instead of restarting`() = runBlocking {
        assertEquals(19_000L, samples(1_000L, 20_000L).first.single())
        assertEquals("1 分 03 秒", formatRunElapsed(samples(1_000L, 64_000L).first.single()))
    }

    @Test
    fun `an unknown origin never starts an unbounded ticker`() = runBlocking {
        val (elapsed, waits) = samples(0L, 20_000L)
        assertTrue(elapsed.isEmpty())
        assertTrue(waits.isEmpty())
    }

    @Test
    fun `clock corrections cannot display a negative duration`() = runBlocking {
        val (elapsed, waits) = samples(1_000L, 500L, 2_250L)
        assertEquals(listOf(0L, 1_250L), elapsed)
        assertEquals(listOf(1_000L, 750L), waits)
    }

    private suspend fun samples(start: Long, initialNow: Long, vararg following: Long): Pair<List<Long>, List<Long>> {
        var now = initialNow
        var index = 0
        val elapsed = mutableListOf<Long>()
        val waits = mutableListOf<Long>()
        try {
            runElapsedTicker(start, { now }, { delay ->
                waits += delay
                if (index == following.size) throw EndSamples()
                now = following[index++]
            }, { elapsed += it })
        } catch (_: EndSamples) {
            // Stop this deterministic replay without sleeping or leaving a job alive.
        }
        return elapsed to waits
    }

    private class EndSamples : CancellationException()
}
