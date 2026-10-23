package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「网关一次吐出 N 字」时，用户多久才看见全文。
 *
 * 这是用户反馈「思考还是一下子显示完」的直接量化：`typewriterStep` 在积压 > 2000 时
 * 走 `积压/8` 分支，一次 5000 字 ⇒ 每拍 625 字（被 400 封顶）⇒ **4 拍（160ms）就放完**。
 * 40ms 一拍 ⇒ 一眨眼的事，看起来就是"整块蹦出来"。
 *
 * 这个文件就是那条证据，改参数前先看它红。
 */
class TypewriterArrivalTest {

    /** 每拍 40ms；返回从 0 放到 [total] 需要多少毫秒。 */
    private fun revealMs(total: Int): Int {
        var revealed = 0
        var ticks = 0
        while (revealed < total) {
            revealed = (revealed + typewriterStep(total - revealed)).coerceAtMost(total)
            ticks++
            if (ticks > 100_000) error("追不上")
        }
        return ticks * TYPE_TICK_MS.toInt()
    }

    @Test
    fun `a steady stream keeps the text visible for a while`() {
        // 稳态每拍放「当前积压的 1/4」，积压随 reveal 递减 ⇒ 总时长是阶梯下降的
        // （200 字 ≈ 600ms、2000 字 ≈ 880ms）。**重点是不管多大都远大于一帧** ——
        // 这就是"看得见在打字"的量化形式。
        assertTrue("200 字 ${revealMs(200)}ms", revealMs(200) >= 500)
        assertTrue("1000 字 ${revealMs(1_000)}ms", revealMs(1_000) >= 500)
        assertTrue("2000 字 ${revealMs(2_000)}ms", revealMs(2_000) >= 500)
    }

    @Test
    fun `a one-shot burst still animates instead of snapping`() {
        // 5000 字：加速档每拍 400 ⇒ 13 拍 = 520ms（不是一帧全出）
        assertTrue("5000 字 ${revealMs(5_000)}ms", revealMs(5_000) >= 400)
        // 2 万字：50 拍 = 2000ms
        assertTrue("20000 字 ${revealMs(20_000)}ms", revealMs(20_000) >= 1_500)
    }
}
