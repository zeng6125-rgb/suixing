package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LivePinState] 的状态转移。
 *
 * **边界要说清**（第三轮评审的批评）：这里证明的是**状态转移正确**，例如
 *  - 解除钉住之后能重新钉上（曾经的 latch 事故）；
 *  - 高度还没量到时不会用 0 去钉；
 *  - 状态行高度计入钉住值、切会话清零。
 *
 * 它**测不到**接线层面的那类事故：`liveForPin` / `livePin` 被 `LaunchedEffect` 一次性捕获
 * ⇒ 判定永远是首帧常量（这个机制前几版"改了却没生效"的真因）。那类问题由
 * `PinSignals` 这个类型（把全部输入收进 `snapshotFlow` 的读取集，漏一个编译不过）+ 代码评审守住，
 * **不要以为这些断言覆盖了它**。
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
        // 两个条件都满足 ⇒ 钉住。**这里必须带 outside**，否则"忽略 outside 的实现"照样通过
        // （第三轮评审指出原来这条断言是恒真的）。
        assertEquals(false, s.update(notAtBottom = true, hasLiveContent = true, natural = 800, outside = 50))
        assertTrue(s.active)
        assertEquals("钉住高度 = 自然高度 + 裁剪之外的部分", 850, s.height)
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
        // **再上翻必须能重新钉上**（曾经的 latch bug 就死在这里）：高度已清 ⇒ 要求重等一帧，
        // 拿到新高度后按**新**高度钉（不是上一个会话/上一轮那个）。
        assertTrue("高度已清，要求重等一帧", s.update(notAtBottom = true, hasLiveContent = true, natural = 0))
        assertFalse("等待期间仍未钉住", s.active)
        s.update(notAtBottom = true, hasLiveContent = true, natural = 900)
        assertTrue(s.active)
        assertEquals("必须是新高度，不是 700", 900, s.height)
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

    @Test
    fun `pins even when the outside part is taller than the bubble`() {
        // 小气泡 + 三行 429 提示：裁剪之外的高度比气泡本身还高。
        // 用「扣完剩多少」当判据会在这里放弃钉住（而 pinActive 已是 true ⇒ 自动贴底被禁、内容跳变）。
        val s = LivePinState()
        s.update(notAtBottom = true, hasLiveContent = true, natural = 40, outside = 120)
        assertTrue("必须仍然处于钉住状态", s.active)
        assertEquals(160, s.height)
    }

    @Test
    fun `a fresh instance starts inactive`() {
        val s = LivePinState()
        assertFalse(s.active)
        assertEquals(0, s.height)
    }
}
