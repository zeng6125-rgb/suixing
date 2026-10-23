package com.suixing.agent.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「一次到达 N 字」时，显示跟上需要多久。
 *
 * 比例追上是指数收敛：余量每拍 ×0.75，n 字在 4·log₄(n/2) 拍内追平。
 * 这条测试守住两件事：过程可见（不瞬移）、尾巴不拖（不磨几十秒）。
 */
class TypewriterArrivalTest {

    private fun catchUpMs(total: Int): Int {
        var backlog = total
        var ticks = 0
        while (backlog > 0) {
            backlog -= typewriterStep(backlog)
            ticks++
            if (ticks > 100_000) error("追不上")
        }
        return ticks * TYPE_TICK_MS.toInt()
    }

    @Test
    fun `a small burst catches up in a few ticks`() {
        assertTrue(catchUpMs(100) <= 800)    // ~8 拍
    }

    @Test
    fun `a huge one-shot chunk still catches up quickly`() {
        // 指数收敛：5000 字 ≈ 16 拍（640ms），不是几十秒
        assertTrue("5000 字 ${catchUpMs(5_000)}ms", catchUpMs(5_000) <= 1_200)
        assertTrue("50000 字 ${catchUpMs(50_000)}ms", catchUpMs(50_000) <= 2_000)
    }
}
