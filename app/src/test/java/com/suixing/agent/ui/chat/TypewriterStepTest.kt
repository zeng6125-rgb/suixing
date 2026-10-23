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
        // 积压 3 字 → 1 字/拍被下限抬到 2；积压 6 → 2
        assertEquals(2, typewriterStep(1))
        assertEquals(2, typewriterStep(3))
        assertEquals(2, typewriterStep(6))
    }

    @Test
    fun `a normal backlog lands inside the visible band`() {
        // 30 字积压 → 10 字/拍（10 拍/秒 ⇒ 100 字/秒）
        assertEquals(10, typewriterStep(30))
        // 但绝不超过 20 字/拍：再快就成"一块一块蹦"了
        assertEquals(20, typewriterStep(300))
        assertEquals(20, typewriterStep(2_000))
    }

    @Test
    fun `a huge one-shot chunk still animates instead of snapping`() {
        // 网关一次吐 3000 字：不能一拍放完
        val step = typewriterStep(3_000)
        assertTrue("3000 字积压至少要多拍才能放完", step < 3_000)
        assertTrue("但也不能慢成磨", step >= 20)
        // 3000 字约 8 拍（320ms）
        assertTrue("3000 字应在 20 拍内追完，实际 $step 字/拍", 3_000 / step <= 20)
    }

    @Test
    fun `an enormous chunk is capped rather than unbounded`() {
        // 20 万字：每拍 400 字上限 ⇒ 500 拍（20 秒），不会一拍放完，也不会卡死
        assertEquals(400, typewriterStep(200_000))
    }

    @Test
    fun `zero backlog yields the minimum step and the caller clamps to the goal`() {
        // 积压 0 时调用方走 `revealed >= goal` 分支，这里只保证不返回 0（0 会让循环空转）
        assertEquals(2, typewriterStep(0))
    }
}
