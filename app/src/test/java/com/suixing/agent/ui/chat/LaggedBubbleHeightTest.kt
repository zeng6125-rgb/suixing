package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `laggedBubbleHeight` —— 钉住期间气泡该报多高。
 *
 * 这条算式决定 #17 的核心承诺：「钉住时 item 总高不变 ⇒ 上面的内容不动」。
 * 它只有两个分支，但两个都踩过事故（`minOf` 造成的单向棘轮、`own<=0` 导致的静默放弃钉住），
 * 所以把边界钉死，将来改"帧时序"那一块时至少有网。
 */
class LaggedBubbleHeightTest {

    @Test
    fun `when nothing is pinned the natural height is reported`() {
        assertEquals("reported<=0 ⇒ 与改动前一致", 500, laggedBubbleHeight(reported = 0, outside = 80, natural = 500))
        assertEquals(500, laggedBubbleHeight(reported = -1, outside = 80, natural = 500))
    }

    @Test
    fun `the bubble shrinks by exactly the status line so the total stays put`() {
        // 总高 1200：气泡 1120 + 状态行 80。
        assertEquals(1_120, laggedBubbleHeight(reported = 1_200, outside = 80, natural = 5_000))
        // 状态行长到 200 ⇒ 气泡缩到 1000 ⇒ **总高仍是 1200**（这就是"不漂移"的全部依据）。
        assertEquals(1_000, laggedBubbleHeight(reported = 1_200, outside = 200, natural = 5_000))
        assertEquals(1_200, laggedBubbleHeight(1_200, 200, 5_000) + 200)
    }

    @Test
    fun `a status line taller than the pinned total clamps to one pixel`() {
        // 小气泡 + 三行 429 提示：总高会变大（已知取舍），但**不能返回 0 或负数**。
        assertEquals(1, laggedBubbleHeight(reported = 100, outside = 300, natural = 5_000))
        assertEquals(1, laggedBubbleHeight(reported = 300, outside = 300, natural = 5_000))
    }

    @Test
    fun `the pinned value never tracks the natural height`() {
        // 单向棘轮事故的回归锁：自然高度变长时，报出的高度**不许跟着长**。
        val pinned = laggedBubbleHeight(reported = 1_200, outside = 0, natural = 2_000)
        val grown = laggedBubbleHeight(reported = 1_200, outside = 0, natural = 9_000)
        assertEquals("自然高度长了，报出的高度不能变", pinned, grown)
    }
}
