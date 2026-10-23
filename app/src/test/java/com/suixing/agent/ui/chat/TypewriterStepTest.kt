package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打字机每拍放几个字。
 *
 * 这组断言守的是两个**都踩过的坑**：
 *  - 步长太小/固定 ⇒ 快端点上永远追不上，屏幕停在半句话上；
 *  - 一次覆盖到底 ⇒ 不管积压多少都一拍放完，"流式"变成"整块蹦"（真机反馈「思考一下子全显示完」）。
 */
class TypewriterStepTest {

    @Test
    fun `a small backlog advances at the slow visible pace`() {
        // 积压 1~7 字 ⇒ 1 字/拍被下限抬到 2
        assertEquals(2, typewriterStep(1))
        assertEquals(2, typewriterStep(7))
    }

    @Test
    fun `the steady state is a quarter of the backlog so it always lags four ticks`() {
        // 40 字 → 10 字/拍；400 字 → 100 字/拍；2000 字 → 500 字/拍
        // **没有上限**：快端点一秒几百字，压上限就等于"刚好追平"，观感是一次全出
        assertEquals(10, typewriterStep(40))
        assertEquals(100, typewriterStep(400))
        assertEquals(500, typewriterStep(2_000))

    }

    @Test
    fun `a huge one-shot chunk is accelerated so the tail does not drag`() {
        // 5000 字 → 625 被 400 封顶；20 万 → 400 封顶
        assertEquals(400, typewriterStep(5_000))
        assertEquals(400, typewriterStep(200_000))
        // 加速档也要 >= 40，否则大积压反而比稳态慢
        assertTrue(typewriterStep(2_001) >= 40)
    }
}
