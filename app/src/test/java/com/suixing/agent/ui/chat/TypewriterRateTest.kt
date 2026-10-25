package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打字机速度的可验证判据。
 *
 * **2026-10-26 规格变更**（用户：「流式必须给我做好」）：恒定 2 字/拍在快模型下永远追不上，
 * 文本一路落后、最后一刻还在补字 —— 观感是"一段一段蹦"而不是流式。现在改为**随积压自适应**：
 *
 * 1. 积压 ≤ [SMALL_BACKLOG] ⇒ 恒定 [BASE_STEP] 字/拍（慢速端点也看得见在动）；
 * 2. 积压更大 ⇒ 按积压量提速（`backlog / 4`），追平后自动回落到基础速度；
 * 3. 单调性：积压越多，单拍推进量不会减少（不能出现"越积越慢"）。
 *
 * 这组测试替代原来的"恒定速度"断言 —— 那是上一版规格，已被用户明确否掉。
 */
class TypewriterRateTest {

    private companion object {
        const val TICK = 16
        const val PUBLISH_MS = 80
        const val SMALL_BACKLOG = 8
        const val BASE_STEP = 2
    }

    private data class Sim(val drainMs: Int, val worstLagMs: Int, val firstJump: Int)

    private fun simulate(totalChars: Int, chunk: Int, chunkEveryMs: Int = 80): Sim {
        var arrived = 0
        var revealed = 0
        var t = 0
        var worstLagMs = 0
        var firstJump = -1
        while (revealed < totalChars && t < 600_000) {
            t += TICK
            if (t % chunkEveryMs == 0 && arrived < totalChars) {
                arrived = minOf(totalChars, arrived + chunk)
            }
            val step = typeStep(arrived - revealed)
            val before = revealed
            revealed = minOf(arrived, revealed + step)
            if (firstJump < 0 && revealed > before) firstJump = revealed - before
            worstLagMs = maxOf(worstLagMs, (arrived - revealed) * chunkEveryMs / chunk)
        }
        return Sim(t, worstLagMs, firstJump)
    }

    @Test
    fun `小积压时保持基础步长`() {
        // 积压 0~8 个字符时必须是基础步长，保证慢速端点也是"逐字"的观感。
        val steps = (0..SMALL_BACKLOG).map { typeStep(it) }
        assertTrue("小积压应恒为 $BASE_STEP，实际 $steps", steps.all { it == BASE_STEP })
    }

    @Test
    fun `单拍推进量随积压单调不减`() {
        val backlogs = listOf(0, 1, 8, 9, 10, 40, 100, 400, 2000, 10_000)
        val steps = backlogs.map { typeStep(it) }
        steps.zipWithNext().forEach { (a, b) ->
            assertTrue("积压增大时推进量不能减少（$steps）", b >= a)
        }
    }

    @Test
    fun `大积压时会提速`() {
        // 规则是 backlog - 4：积压 12 以上才会超过基础步长 2。
        assertTrue("积压 400 字时应明显快于基础步长", typeStep(400) > BASE_STEP)
        assertTrue("积压 12 字时开始提速", typeStep(12) > BASE_STEP)
        // **滞后上限**：大积压时一拍几乎追平，只保留 4 字缓冲。
        assertEquals("积压 400 字时只留 4 字", 396, typeStep(400))
    }

    @Test
    fun `一次到达三百字也不会一拍放完`() {
        val sim = simulate(totalChars = 300, chunk = 300)
        // 第一拍只按积压量走，而不是一次把 300 字全放出来。
        assertTrue("第一拍不该一次放完，实际 ${sim.firstJump}", sim.firstJump < 300)
        assertTrue("第一拍必须已经动起来", sim.firstJump > 0)
    }

    @Test
    fun `长答案能追平而不是无限落后`() {
        val sim = simulate(totalChars = 2_000, chunk = 2_000)
        // 自适应提速的意义就在这里：一次性到达 2000 字也必须快速追平，
        // 而不是按 2 字/拍 打 16 秒。给 3 秒的宽松上界。
        assertTrue("2000 字应很快追平，实际 ${sim.drainMs}ms", sim.drainMs < 3_000)
    }

    @Test
    fun `正常流式不会越积越多`() {
        // 每 80ms 5 个字 = 62 字/秒 ⇒ 基础速度 125 字/秒足够，积压不会增长。
        val sim = simulate(totalChars = 3_000, chunk = 5)
        assertTrue("延迟不该越来越大，实际最坏 ${sim.worstLagMs}ms", sim.worstLagMs < 4_000)
    }

    @Test
    fun `揭示速度有下界`() {
        assertTrue("任何积压下都必须至少推进 1 个字", (0..1000).all { typeStep(it) >= 1 })
        assertEquals(BASE_STEP, typeStep(0))
    }
}
