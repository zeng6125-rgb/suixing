package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ListSlots] 的槽位数。
 *
 * 这些断言守的是一个**真实发生过的不一致**：`feedPrefix` 数了「压缩中」那一行、`itemCount`
 * 没数，两个数各写一遍。今天少一项只是无害的判据偏差，但列表一旦再加一行，
 * 另一处必然漏 —— 而这条链路已经出过三次同类事故。
 */
class ListSlotsTest {

    @Test
    fun `an idle conversation has only its rows`() {
        val slots = ListSlots.of(
            hasLiveCard = false, runningHere = false, compressing = false,
            hasErrorLine = false, visibleRows = 7, hasLoadEarlier = false,
        )
        assertEquals(0, slots.feedPrefix)
        assertEquals(7, slots.total)
    }

    @Test
    fun `the live card sits in front of the rows`() {
        val slots = ListSlots.of(
            hasLiveCard = true, runningHere = true, compressing = false,
            hasErrorLine = false, visibleRows = 5, hasLoadEarlier = false,
        )
        assertEquals("流式卡是喂序第 0 项", 1, slots.feedPrefix)
        assertEquals(6, slots.total)
    }

    @Test
    fun `the compression row is counted in both numbers`() {
        // **这条就是当初漏掉的那一项**：旧代码里 `feedPrefix` 数了它、`itemCount` 没数。
        val slots = ListSlots.of(
            hasLiveCard = false, runningHere = false, compressing = true,
            hasErrorLine = false, visibleRows = 3, hasLoadEarlier = false,
        )
        assertEquals("压缩状态行也要占喂序前缀", 1, slots.feedPrefix)
        assertEquals("而且必须计入总数", 4, slots.total)
    }

    @Test
    fun `the compression row is hidden while the run is on screen`() {
        // 一边跑一边压缩不会同时画两行（压缩状态行只在没在跑时出现）。
        val slots = ListSlots.of(
            hasLiveCard = true, runningHere = true, compressing = true,
            hasErrorLine = false, visibleRows = 2, hasLoadEarlier = false,
        )
        assertEquals(1, slots.feedPrefix)
        assertEquals(3, slots.total)
    }

    @Test
    fun `error line and load-earlier both count`() {
        val slots = ListSlots.of(
            hasLiveCard = true, runningHere = true, compressing = false,
            hasErrorLine = true, visibleRows = 4, hasLoadEarlier = true,
        )
        assertEquals("前缀 = 流式 + 错误", 2, slots.feedPrefix)
        assertEquals("总数 = 前缀 + 行 + 加载更早", 7, slots.total)
    }

    @Test
    fun `everything at once`() {
        val slots = ListSlots.of(
            hasLiveCard = false, runningHere = false, compressing = true,
            hasErrorLine = true, visibleRows = 0, hasLoadEarlier = true,
        )
        assertEquals(2, slots.feedPrefix)
        assertEquals(3, slots.total)
    }

    @Test
    fun `a negative row count is clamped`() {
        val slots = ListSlots.of(
            hasLiveCard = false, runningHere = false, compressing = false,
            hasErrorLine = false, visibleRows = -5, hasLoadEarlier = false,
        )
        assertEquals(0, slots.total)
    }
}
