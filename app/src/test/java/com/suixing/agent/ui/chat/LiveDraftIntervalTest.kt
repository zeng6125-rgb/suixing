package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 活跃输出的恢复副本落盘间隔（R4）。
 *
 * 语义：增量越大、间隔越长（每秒重复组装/编码整段正文的收益越低）；增量小则保持 1s，
 * 让“被杀最多丢 1 秒”这条既有承诺不被破坏。
 */
class LiveDraftIntervalTest {

    @Test
    fun `没有增量时保持 1 秒`() {
        assertEquals(1_000L, liveDraftIntervalMs(0))
        assertEquals(1_000L, liveDraftIntervalMs(1_999))
    }

    @Test
    fun `中等增量翻倍到 2 秒`() {
        assertEquals(2_000L, liveDraftIntervalMs(2_000))
        assertEquals(2_000L, liveDraftIntervalMs(7_999))
    }

    @Test
    fun `大增量到 3 秒，且不会无界增长`() {
        assertEquals(3_000L, liveDraftIntervalMs(8_000))
        assertEquals(3_000L, liveDraftIntervalMs(10_000_000))
    }
}
