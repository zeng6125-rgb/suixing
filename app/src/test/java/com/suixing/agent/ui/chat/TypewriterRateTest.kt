package com.suixing.agent.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打字机速度的可验证判据。
 *
 * 用户的要求（2026-10-25 第四次）：**任何长度都逐字，不要分档**。
 *
 * 这一组测试守着三条性质：
 *
 * 1. **恒定速度**：单拍推进量永远是 [TYPE_STEP_FOR_TEST]，不随积压变化（分档会让积压多时
 *    又快成整块，等于没有打字机）。
 * 2. **慢于到达速度**：设备实测生成 51~175 tokens/s（中文约 100~200 字/秒）；揭示速度必须
 *    明显低于它，否则每一拍正好把新到的字放完，看着就是整块出现。
 * 3. **代价被记录**：恒定 3 字/拍意味着长答案会比模型慢 —— 2000 字约 27 秒。这是用户明确
 *    接受的取舍，写成断言是为了防止以后有人"顺手"把速度提上去又毁掉逐字。
 */
class TypewriterRateTest {

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
    fun `单拍推进量与积压无关`() {
        // 从 0 到几千字的积压，推进量必须完全一样。
        val steps = listOf(0, 1, 10, 100, 300, 1000, 5000, 100_000).map { typeStep(it) }
        assertTrue("推进量必须恒定，实际 $steps", steps.all { it == TYPE_STEP_FOR_TEST })
    }

    @Test
    fun `三百字的整块仍然逐字`() {
        val sim = simulate(totalChars = 300, chunk = 300)
        assertTrue(
            "第一拍只该推 ${TYPE_STEP_FOR_TEST} 个字，实际 ${sim.firstJump}",
            sim.firstJump == TYPE_STEP_FOR_TEST,
        )
        // **时长不是需求，是常量的推论**：300 字 ÷ 每拍字数 × 每拍毫秒。
        // 断言只允许一个节拍的误差（收尾那一拍），所以改 TYPE_STEP 不需要改这里。
        assertTrue(
            "300 字应约 ${expectedMs(300)}ms，实际 ${sim.drainMs}ms",
            sim.drainMs in (expectedMs(300) - TICK)..(expectedMs(300) + TICK),
        )
    }

    @Test
    fun `长答案按同一速度打完（代价已接受）`() {
        val sim = simulate(totalChars = 2_000, chunk = 2_000)
        // 恒定速度的必然结果（2000 字 ≈ 16 秒），不再有加速档。
        assertTrue(
            "2000 字应约 ${expectedMs(2_000)}ms，实际 ${sim.drainMs}ms",
            sim.drainMs in (expectedMs(2_000) - TICK)..(expectedMs(2_000) + TICK),
        )
    }

    @Test
    fun `正常流式不会越积越多`() {
        // 每 80ms 5 个字 = 62 字/秒 < 75 字/秒的揭示速度 ⇒ 不会掉队。
        val sim = simulate(totalChars = 3_000, chunk = 5)
        assertTrue("稳态落后应 ≤1500ms，实际 ${sim.worstLagMs}ms", sim.worstLagMs <= 1_500)
    }

    @Test
    fun `慢端点也看得见在动`() {
        // 每 80ms 1 个字：揭示速度远高于到达速度，随到随打。
        val sim = simulate(totalChars = 40, chunk = 1)
        assertTrue("慢端点最多比到达晚 0.5 秒，实际 ${sim.drainMs}ms", sim.drainMs <= 40 * 80 + 500)
    }

    @Test
    fun `速度落在可读又不拖沓的区间`() {
        // 这是**需求本身**（用户：75 字/秒太慢；再快就看不出逐字了），与具体实现常量解耦：
        // 换算成每秒字数必须在 100~200 之间。
        val charsPerSecond = TYPE_STEP_FOR_TEST * 1000.0 / TICK
        assertTrue(
            "揭示速度应在 100~200 字/秒，实际 ${charsPerSecond}",
            charsPerSecond in 100.0..200.0,
        )
    }

    private companion object {
        /** 按常量推导「打完全部要多久」，不写死秒数。 */
        fun expectedMs(totalChars: Int): Int = (totalChars + TYPE_STEP_FOR_TEST - 1) / TYPE_STEP_FOR_TEST * TICK

        /** 与 Typewriter.kt 的 TYPE_TICK_MS 一致（改动时两边一起改）。 */
        const val TICK = 40

        /** 与 Typewriter.kt 的 TYPE_STEP 一致。 */
        const val TYPE_STEP_FOR_TEST = 5
    }
}
