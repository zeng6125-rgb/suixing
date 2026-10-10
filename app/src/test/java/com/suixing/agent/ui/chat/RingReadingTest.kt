package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which number the ring draws, and whether it says 「约」.
 *
 * The order is the whole point of the function: the endpoint's own count for the round that just
 * reported, then the newest round in the transcript, then the run's own character-based estimate
 * of the wire, and last the transcript's own size. The last two are what took 「暂无数据」 off the
 * card — for the gateways that ignore `stream_options.include_usage` there is no count at all, and
 * the card used to be empty on a conversation plainly full of text. Both are flagged, because a
 * proportion presented as a count is the one thing this card must not do.
 */
class RingReadingTest {

    @Test
    fun `the endpoint's own count wins over everything else`() {
        val reading = ringReading(liveReported = 12_000, newestStored = 9_000, liveEstimate = 11_000)
        assertEquals(12_000, reading.tokens)
        assertFalse(reading.estimated)
    }

    @Test
    fun `the newest stored round is used when nothing is reported yet`() {
        val reading = ringReading(liveReported = 0, newestStored = 9_000, liveEstimate = 11_000)
        assertEquals(9_000, reading.tokens)
        assertFalse("a stored count is the endpoint's own, not an estimate", reading.estimated)
    }

    @Test
    fun `the estimate is the last resort among the live sources, and it says so`() {
        val reading = ringReading(liveReported = 0, newestStored = 0, liveEstimate = 11_000)
        assertEquals(11_000, reading.tokens)
        assertTrue(reading.estimated)
    }

    /**
     * The case the user reported: nothing was ever priced — a gateway that ignores
     * `stream_options.include_usage`, or a conversation stored before the counts existed — and no
     * run is going, so there is no wire to estimate either. The transcript's own size is the
     * floor that keeps the card from being empty.
     */
    @Test
    fun `the transcript's own size is the floor when nothing was ever priced`() {
        val reading = ringReading(
            liveReported = 0,
            newestStored = 0,
            liveEstimate = 0,
            transcriptEstimate = 4_200,
        )
        assertEquals(4_200, reading.tokens)
        assertTrue(reading.estimated)
    }

    /** A live estimate off the wire beats the transcript floor: it is the same thing, measured. */
    @Test
    fun `the live estimate wins over the transcript floor`() {
        val reading = ringReading(0, 0, liveEstimate = 11_000, transcriptEstimate = 4_200)
        assertEquals(11_000, reading.tokens)
        assertTrue(reading.estimated)
    }

    @Test
    fun `nothing to read at all draws nothing`() {
        val reading = ringReading(liveReported = 0, newestStored = 0, liveEstimate = 0)
        assertEquals(0, reading.tokens)
        assertFalse(reading.estimated)
    }

    /** A negative or nonsense source is "no data", never a number the arc would draw backwards. */
    @Test
    fun `negative sources are treated as missing`() {
        val reading = ringReading(liveReported = -5, newestStored = -1, liveEstimate = -9)
        assertEquals(0, reading.tokens)
        assertFalse(reading.estimated)
    }

    /** A reported count arriving mid-run replaces the estimate on the very next reading. */
    @Test
    fun `a reported count takes over from the estimate`() {
        val estimated = ringReading(0, 0, 11_000)
        assertTrue(estimated.estimated)
        val reported = ringReading(11_400, 0, 11_000)
        assertEquals(11_400, reported.tokens)
        assertFalse(reported.estimated)
    }
}
