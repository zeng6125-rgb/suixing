package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LivePinState] 的状态转移。
 *
 * 这些断言是给两次真事故立的桩（都发生在"钉住"这个机制里）：
 *  - `liveForPin` 被一次性捕获 ⇒ 钉住从未生效（表现：改了无数版还在动）；
 *  - 解除时只清高度、没清 latch ⇒ 之后再也钉不回去（表现：某一刻起彻底失效）。
 * 两者都不是编译错误、也不是界面能一眼看出的错，只能靠这些断言守住。
 */
class LivePinStateTest {

    @Test
    fun `arms only when the user is reading history and the run still has content`() {
        val s = LivePinState()
        // 贴底（不在读历史）⇒ 不钉。
        assertEquals(false, s.update(notAtBottom = false, hasLiveContent = true, natural = 800))
        assertFalse(s.active)
        // 不在底部，但没有活内容（这一轮没开始/已结束）⇒ 不钉。
        assertEquals(false, s.update(notAtBottom = true, hasLiveContent = false, natural = 800))
        assertFalse(s.active)
        // 两个条件都满足 ⇒ 钉住。
        assertEquals(false, s.update(notAtBottom = true, hasLiveContent = true, natural = 800))
        assertTrue(s.active)
        assertEquals("钉住高度 = 自然高度 + 裁剪之外的部分", 800, s.height)
    }

    @Test
    fun `never arms on a height that has not been measured yet`() {
        val s = LivePinState()
        // 布局还没跑过：要求调用方等一帧再来，**不能**用 0 去钉。
        assertTrue(s.update(notAtBottom = true, hasLiveContent = true, natural = 0))
        assertFalse(s.active)
        assertEquals(0, s.height)
        // 等到了再喂一次就钉上。
        assertEquals(false, s.update(notAtBottom = true, hasLiveContent = true, natural = 640))
        assertTrue(s.active)
        assertEquals(640, s.height)
    }

    @Test
    fun `one pin keeps the height it captured even if the content keeps growing`() {
        val s = LivePinState()
        s.update(notAtBottom = true, hasLiveContent = true, natural = 500)
        // 后续自然高度继续长：钉住值不变（这就是"不许长高"）。
        s.update(notAtBottom = true, hasLiveContent = true, natural = 5000)
        assertTrue(s.active)
        assertEquals(500, s.height)
    }

    @Test
    fun `can pin again after a release`() {
        val s = LivePinState()
        s.update(notAtBottom = true, hasLiveContent = true, natural = 700)
        assertTrue(s.active)
        // 回到底部 ⇒ 解除。
        s.update(notAtBottom = false, hasLiveContent = true, natural = 700)
        assertFalse(s.active)
        assertEquals(0, s.height)
        // **再上翻必须能重新钉上**（v0.8.341 的 latch bug 就死在这里）。
        assertTrue("高度已清，要求重等一帧", s.update(notAtBottom = true, hasLiveContent = true, natural = 0))
        s.update(notAtBottom = true, hasLiveContent = true, natural = 900)
        assertTrue(s.active)
        assertEquals(900, s.height)
    }

    @Test
    fun `releases when the run clears its content mid-flight`() {
        val s = LivePinState()
        s.update(notAtBottom = true, hasLiveContent = true, natural = 720)
        assertTrue(s.active)
        // 一轮失败/重试会把 liveParts 清空 ⇒ 不能再钉着（否则留一大片空白）。
        s.update(notAtBottom = true, hasLiveContent = false, natural = 720)
        assertFalse(s.active)
        assertEquals(0, s.height)
    }

    @Test
    fun `status line height is part of the pin so its wrapping cannot push the list`() {
        val s = LivePinState()
        s.update(notAtBottom = true, hasLiveContent = true, natural = 600, outside = 48)
        assertEquals("状态行在裁剪之外，必须一起钉住", 648, s.height)
    }

    @Test
    fun `reset drops both the flag and the height on a conversation switch`() {
        val s = LivePinState()
        s.update(notAtBottom = true, hasLiveContent = true, natural = 1234)
        assertTrue(s.active)
        s.reset()
        assertFalse("换会话后不能沿用上一个会话的高度", s.active)
        assertEquals(0, s.height)
    }
}
