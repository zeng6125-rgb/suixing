package com.suixing.agent.ui

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives the production opacity owner, not a duplicate model or a device renderer. */
class ConversationDepartureTest {
    @Test fun `only the outgoing conversation fades and navigation restores its visibility`() = runBlocking {
        withTimeout(5_000) {
            val intents = ConversationSwitchIntents()
            val token = intents.begin()
            val frames = Frames()
            val departure = launch(frames.clock, CoroutineStart.UNDISPATCHED) { intents.depart(token, "A") }
            frames.advance(4)
            assertTrue(intents.departureAlpha("A") in 0.001f..0.999f)
            assertEquals(1f, intents.departureAlpha("B"), 0f)
            frames.advance(10)
            departure.join()
            assertEquals(0f, intents.departureAlpha("A"), 0f)
            intents.finish(token)
            assertEquals("a late source frame must not flash back", 0f, intents.departureAlpha("A"), 0f)
            intents.invalidate()
            assertEquals(1f, intents.departureAlpha("A"), 0f)
        }
    }

    @Test fun `a newer selection continues the source opacity and stale cleanup cannot restore it`() = runBlocking {
        withTimeout(5_000) {
            val intents = ConversationSwitchIntents()
            val first = intents.begin()
            val frames = Frames()
            val old = launch(frames.clock, CoroutineStart.UNDISPATCHED) { intents.depart(first, "A") }
            frames.advance(3)
            val interruptedAlpha = intents.departureAlpha("A")
            val newest = intents.begin()
            val current = launch(frames.clock, CoroutineStart.UNDISPATCHED) { intents.depart(newest, "A") }
            assertEquals(interruptedAlpha, intents.departureAlpha("A"), 0.0001f)
            frames.advance(12)
            current.join()
            assertTrue(old.isCancelled)
            intents.cancelDeparture(first)
            assertEquals(0f, intents.departureAlpha("A"), 0f)
            intents.cancelDeparture(newest)
            assertEquals(1f, intents.departureAlpha("A"), 0f)
        }
    }

    @Test fun `returning to a prior source never inherits its finished departure`() = runBlocking {
        withTimeout(5_000) {
            val intents = ConversationSwitchIntents()
            val frames = Frames()
            val first = intents.begin()
            val leaveA = launch(frames.clock, CoroutineStart.UNDISPATCHED) { intents.depart(first, "A") }
            frames.advance(12)
            leaveA.join()
            val next = intents.begin()
            val leaveB = launch(frames.clock, CoroutineStart.UNDISPATCHED) { intents.depart(next, "B") }
            assertEquals(1f, intents.departureAlpha("B"), 0f)
            assertEquals(1f, intents.departureAlpha("A"), 0f)
            frames.advance(12)
            leaveB.join()
            assertEquals(0f, intents.departureAlpha("B"), 0f)
            assertEquals(1f, intents.departureAlpha("A"), 0f)
        }
    }

    private class Frames {
        val clock = BroadcastFrameClock()
        private var nanos = 0L

        suspend fun advance(count: Int) {
            repeat(count) {
                Snapshot.sendApplyNotifications()
                repeat(4) { yield() }
                nanos += 16_000_000L
                clock.sendFrame(nanos)
                repeat(4) { yield() }
            }
        }
    }
}
