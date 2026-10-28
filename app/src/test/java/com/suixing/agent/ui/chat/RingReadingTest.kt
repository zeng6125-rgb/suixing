package com.suixing.agent.ui.chat

import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.StoredToolCall
import com.suixing.agent.data.foldTotals
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
     * 可见气泡不是完整存储：纯工具调用的无正文 assistant 轮可以有 usage，却不进气泡。
     * 会话级值必须排在可见气泡的旧值前面；失败本身没有新 usage 时不会制造这类差异。
     */
    @Test
    fun `a full-session report outranks an older visible report`() {
        val reading = ringReading(
            liveReported = 0,
            newestStored = 40_000,      // 可见气泡的旧实测
            liveEstimate = 0,
            persistedContext = 730_000, // 会话级权威值：最近一次真正上报的 prompt
        )
        assertEquals("失败轮不该把读数拉到旧轮次", 730_000, reading.tokens)
        assertFalse(reading.estimated)
    }

    @Test
    fun `a textless tool round remains authoritative after a later failure`() {
        val messages = listOf(
            StoredMessage(id = "u1", role = Roles.USER, text = "第一问"),
            StoredMessage(
                id = "a1", role = Roles.ASSISTANT, text = "上一轮回答",
                runId = "r1", promptTokens = 40_000,
            ),
            StoredMessage(id = "u2", role = Roles.USER, text = "执行工具"),
            StoredMessage(
                id = "a2", role = Roles.ASSISTANT, text = "",
                runId = "r2", promptTokens = 730_000,
                toolCalls = listOf(StoredToolCall("call-2", "run-shell", "{}")),
            ),
            StoredMessage(
                id = "t2", role = Roles.TOOL, text = "工具结果",
                runId = "r2", toolName = "run-shell", toolCallId = "call-2",
            ),
            StoredMessage(id = "error", role = Roles.SYSTEM, text = "这一轮最终失败：网络错误", runId = "r2"),
        )
        val visible = groupRows(messages, emptyMap(), running = false)
        val visiblePrompt = visible.lastOrNull { it.usage != null }?.usage?.prompt ?: 0
        val persistedPrompt = foldTotals(messages).contextTokens
        assertEquals("the textless tool-call round has no visible usage chip", 40_000, visiblePrompt)
        assertEquals(730_000, persistedPrompt)
        val reading = ringReading(0, visiblePrompt, 0, persistedContext = persistedPrompt)
        assertEquals(730_000, reading.tokens)
        assertFalse(reading.estimated)
    }

    @Test
    fun `a plain failure without usage preserves the same latest report in both sources`() {
        val messages = listOf(
            StoredMessage(
                id = "a1", role = Roles.ASSISTANT, text = "回答", promptTokens = 40_000,
            ),
            StoredMessage(id = "error", role = Roles.SYSTEM, text = "这一轮最终失败：网络错误"),
        )
        val visible = groupRows(messages, emptyMap(), running = false)
        assertEquals(
            foldTotals(messages).contextTokens,
            visible.lastOrNull { it.usage != null }?.usage?.prompt,
        )
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
