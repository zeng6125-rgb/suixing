package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which number the ring draws, and whether it says 「约」.
 *
 * 只认**实测**一个口径：本轮网关回报 → 按会话保持的最近实测（调用方传入 liveReported）→
 * 落盘的最近一轮回报。粗估（/4）只作「从没实测过」的兜底并标「约」。
 * 两个口径混在一条回落链上就是用户报的 37/48 跃变：37 是粗估、48 是实测。
 */
class RingReadingTest {

    @Test
    fun `the measured report wins over the character estimate`() {
        val reading = ringReading(liveReported = 12_000, newestStored = 9_000, liveEstimate = 11_000)
        assertEquals(12_000, reading.tokens)
        assertFalse(reading.estimated)
    }

    @Test
    fun `the stored report is used when nothing is reported live`() {
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
    /**
     * **issue #49**：切 provider 后圆环 73% → 4% → 73%。
     *
     * 复现条件：几轮失败（网络错误）没有 usage ⇒ `newestStored` 会退到很久以前的小值。
     * 会话级权威值 `persistedContext` 不受失败轮影响，必须排在它前面。
     */
    @Test
    fun `a failed round cannot drag the ring back through newestStored`() {
        val reading = ringReading(
            liveReported = 0,
            newestStored = 40_000,      // 失败轮跳过后，退到很早的轮次
            liveEstimate = 0,
            persistedContext = 730_000, // 会话级权威值：最近一次真正上报的 prompt
        )
        assertEquals("失败轮不该把读数拉到旧轮次", 730_000, reading.tokens)
        assertFalse(reading.estimated)
    }

    @Test
    fun `the live report still wins over the session-level value`() {
        val reading = ringReading(
            liveReported = 512_000,
            newestStored = 900_000,
            liveEstimate = 0,
            persistedContext = 730_000,
        )
        assertEquals(512_000, reading.tokens)
    }

    @Test
    fun `without a session-level value it still falls back to the newest stored one`() {
        val reading = ringReading(
            liveReported = 0,
            newestStored = 90_000,
            liveEstimate = 0,
            persistedContext = 0,
        )
        assertEquals(90_000, reading.tokens)
    }

}
