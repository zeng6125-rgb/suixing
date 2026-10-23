package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 打字机每拍放几个字。
 *
 * **每拍放积压的 1/4、无上限**（0.8.202 原始公式）。这是实测换来的教训
 * （0.8.381~0.8.403，用户连续六轮反馈"瞬移"）：
 *  - 任何稳态上限都会让"显示速度 < 到达速度"（实测思考 ~470 字/秒），
 *    欠账在 run 结束、落盘行替换 live 行的那一刻**全部一次性吐出** ——
 *    打字机对已定型行不生效，这就是瞬移的机制；
 *  - 比例追上是指数收敛（余量每拍 ×0.75），5000 字 ≈ 16 拍（640ms）追平，
 *    不需要任何"加速档"。
 */
class TypewriterStepTest {

    @Test
    fun `each tick releases a quarter of the backlog`() {
        assertEquals(2, typewriterStep(1))   // 下限：慢端点也看得见在动
        assertEquals(2, typewriterStep(8))
        assertEquals(25, typewriterStep(100))
        assertEquals(250, typewriterStep(1_000))
    }

    @Test
    fun `there is no cap so reveal always outpaces arrival`() {
        // 470 字/秒到达 = 每 40ms 约 19 字；revealed 每拍放积压 1/4，
        // 稳态欠账只有 4 拍的到达量（~75 字）—— run 结束时的跳变肉眼不可见
        assertEquals(500, typewriterStep(2_000))
        assertEquals(1_250, typewriterStep(5_000))
    }
}
