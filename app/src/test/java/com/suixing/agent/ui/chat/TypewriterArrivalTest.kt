package com.suixing.agent.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「一次到达 N 字」时，用户多久看见全文。
 *
 * 这条测试就是"瞬移"的守门人：稳态每拍 4 字 ⇒ 100 字/秒，
 * 远慢于阅读速度 ⇒ **任何规模的一次到达都有一段可见的逐字过程**。
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
    fun `a small burst is visibly typed out`() {
        // 50 字：13 拍 = 520ms
        assertTrue("50 字 ${revealMs(50)}ms", revealMs(50) >= 400)
    }

    @Test
    fun `a medium burst never snaps`() {
        // 200 字：50 拍 = 2000ms；900 字：225 拍 = 9000ms
        // 900 字要 9 秒 —— 慢，但**没有瞬移**；用户明确要逐字
        assertTrue("200 字 ${revealMs(200)}ms", revealMs(200) >= 1_500)
        assertTrue("900 字 ${revealMs(900)}ms", revealMs(900) >= 5_000)
    }

    @Test
    fun `a one-shot chunk over 2000 chars is the only relaxed case`() {
        // 5000 字：125 拍 = 5000ms（不是一帧全出）
        assertTrue("5000 字 ${revealMs(5_000)}ms", revealMs(5_000) >= 3_000)
    }
}
