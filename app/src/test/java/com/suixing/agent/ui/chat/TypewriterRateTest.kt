package com.suixing.agent.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打字机速度规则的可验证判据。
 *
 * 上一轮（0.8.430）用 `backlog / 4`：正文一个 delta 带几百字时，**第一拍就推上去 75 个字**
 * —— 视觉上整屏刷出来，这就是「正文没能逐字」；思考块的 delta 小，所以一直看着正常。
 *
 * 这组测试不测"手感"（那要真机看），只把手感背后的三条**可量化**性质钉死：
 *
 * 1. **不许一次刷一屏**：单拍推进量有上限，几百字的 delta 不会在头几拍就全部出现。
 * 2. **不许吊着**：一次几百字的 delta 必须在几秒内放完（不是慢慢爬）。
 * 3. **稳态不落后**：正常到达速率下（每 80ms 5 个字 ≈ 62 字/秒），显示落后不超过 ~1.5 秒的量。
 *
 * 真机验收仍然需要：录一次「思考刚结束、正文首片较大」的流，看是否逐字、结束时是否瞬移。
 */
class TypewriterRateTest {

    private data class Sim(val drainMs: Int, val worstLagMs: Int, val firstJump: Int)

    /**
     * 模拟一次流式：每 [chunkEveryMs] 到达 [chunk] 个字，打字机每 40ms 一拍。
     * 返回放完全部内容用了多久、过程中最大落后（换算成毫秒）、以及第一次推进的字数。
     */
    private fun simulate(totalChars: Int, chunk: Int, chunkEveryMs: Int = 80): Sim {
        var arrived = 0
        var revealed = 0
        var t = 0
        var worstLagMs = 0
        var firstJump = -1
        while (revealed < totalChars && t < 200_000) {
            t += TICK
            if (t % chunkEveryMs == 0 && arrived < totalChars) {
                arrived = minOf(totalChars, arrived + chunk)
            }
            val before = revealed
            revealed = minOf(arrived, revealed + typeStep(arrived - revealed))
            if (firstJump < 0 && revealed > before) firstJump = revealed - before
            // 落后量换算成"按当前到达速率还要等多久"
            worstLagMs = maxOf(worstLagMs, (arrived - revealed) * chunkEveryMs / chunk)
        }
        return Sim(drainMs = t, worstLagMs = worstLagMs, firstJump = firstJump)
    }

    @Test
    fun `a few hundred characters never appear in one wall of text`() {
        // 正文最常见的坏场景：思考刚结束，首片正文一次到 300 字。
        val sim = simulate(totalChars = 300, chunk = 300)
        assertTrue(
            "第一拍只该推几个字（旧规则是 75 个），实际 ${sim.firstJump}",
            sim.firstJump in 1..TYPE_MAX_STEP_FOR_TEST,
        )
        assertTrue(
            "300 字必须放得完且放得住（1.5~5 秒），实际 ${sim.drainMs}ms",
            sim.drainMs in 1_500..5_000,
        )
    }

    @Test
    fun `a big chunk still finishes in seconds rather than crawling`() {
        // 2000 字一次性到达：加速档必须介入，不能按 125 字/秒慢慢爬 16 秒。
        val sim = simulate(totalChars = 2_000, chunk = 2_000)
        assertTrue("2000 字应在 6 秒内放完，实际 ${sim.drainMs}ms", sim.drainMs <= 6_000)
    }

    @Test
    fun `a normal stream does not fall behind`() {
        // 正常流式：每 80ms 5 个字（62 字/秒）。显示最多落后 1.5 秒的量。
        val sim = simulate(totalChars = 3_000, chunk = 5)
        assertTrue("稳态落后应 ≤1500ms，实际 ${sim.worstLagMs}ms", sim.worstLagMs <= 1_500)
    }

    @Test
    fun `a slow endpoint still shows visible motion`() {
        // 慢端点：每 80ms 1 个字。每一拍至少推进，不能停住。
        val sim = simulate(totalChars = 40, chunk = 1)
        // 40 个字按 1 个/80ms 到达本身就要 3200ms；显示最多再拖半秒。
        assertTrue("慢端点最多比到达晚 0.5 秒，实际 ${sim.drainMs}ms", sim.drainMs <= 40 * 80 + 500)
    }

    private companion object {
        /** 与 Typewriter.kt 的 TYPE_TICK_MS 一致（改动时两边一起改）。 */
        const val TICK = 40

        /** 单拍上限的测试侧副本：Typewriter.kt 里是 private，这里按同样口径断言。 */
        const val TYPE_MAX_STEP_FOR_TEST = 6
    }
}
