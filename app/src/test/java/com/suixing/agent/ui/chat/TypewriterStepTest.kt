package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打字机每拍放几个字。
 *
 * 这两个坑都真踩过，用户连续反馈"瞬移"：
 *  - **按积压比例放**（`积压/4`）：第一跳就是积压的 1/4，800 字积压第一跳 200 字；
 *  - **上限太大**（20 字/拍 × 25 拍 = 500 字/秒）：刚好追平快端点，也是瞬移。
 *
 * 所以稳态必须是**绝对**的逐字：每拍 ≤ 4 个字。
 */
class TypewriterStepTest {

    @Test
    fun `steady state is absolute per-tick not proportional`() {
        // 不管积压 3 字还是 900 字，每拍最多 4 个字
        assertEquals(3, typewriterStep(3))
        assertEquals(4, typewriterStep(4))
        assertEquals(4, typewriterStep(40))
        assertEquals(4, typewriterStep(400))
        assertEquals(4, typewriterStep(900))
        assertEquals(4, typewriterStep(1_999))
        assertTrue("稳态绝不该按比例跳", typewriterStep(900) <= 4)
    }

    @Test
    fun `a tiny backlog shows what arrived`() {
        // 慢端点一个 delta 一两个字：不能少于 1，也不要抬到 2（会凭空多出字）
        assertEquals(1, typewriterStep(1))
        assertEquals(2, typewriterStep(2))
    }

    @Test
    fun `a huge one-shot chunk is relaxed so the tail does not drag`() {
        // 2001 字以上才加速：2001/8=250 → 封顶 40；5000/8=625 → 40；20 万 → 40
        assertEquals(40, typewriterStep(2_001))
        assertEquals(40, typewriterStep(5_000))
        assertEquals(40, typewriterStep(200_000))
        // 加速档也要 ≥ 稳态上限，否则大积压反而更慢
        assertTrue(typewriterStep(2_001) >= 4)
    }
}
