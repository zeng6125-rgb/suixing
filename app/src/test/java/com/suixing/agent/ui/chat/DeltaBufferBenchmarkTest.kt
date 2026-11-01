package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 流式 delta 缓冲的可复现成本对照（issue #67 P1-4「只针对有证据的 CPU 开销」）。
 *
 * `flushDeltas` 每次发布都会 `take` 一次；判据是"这条路径到底值不值得优化"，
 * 而不是先改再找理由。这里量 `append` + `take` 的稳态成本。
 */
class DeltaBufferBenchmarkTest {

    @Test
    fun `append and take stay cheap at streaming cadence`() {
        val buffer = DeltaBuffer()
        val text = "文".repeat(40)          // 一个 delta 片段约 40 字
        // 预热
        repeat(2_000) { buffer.append("c", text, null); buffer.take("c") }
        val rounds = 20_000
        val start = System.nanoTime()
        var total = 0
        repeat(rounds) {
            buffer.append("c", text, "想")
            val (t, r) = buffer.take("c")
            total += t.length + r.length
        }
        val perOpUs = (System.nanoTime() - start) / 1_000.0 / rounds
        assertEquals("每个 delta 的拼接结果必须完整", rounds * (text.length + 1), total)
        println("P1-4 delta buffer: $rounds 轮 append+take = " + "%.2f".format(perOpUs) + "us/轮")
    }
}
