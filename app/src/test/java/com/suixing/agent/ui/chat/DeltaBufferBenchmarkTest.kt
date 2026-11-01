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
        val text = "文".repeat(40)
        fun run(): Pair<Double, Int> {
            val rounds = 5_000
            var total = 0
            val start = System.nanoTime()
            repeat(rounds) {
                buffer.append("c", text, "想")
                val (t, r) = buffer.take("c")
                total += t.length + r.length
            }
            return (System.nanoTime() - start) / 1_000.0 / rounds to total
        }
        run()                                   // 预热
        // 多轮取中位数（与 docs/benchmarks.md 的方法论一致）：单次会受 JIT/GC 影响
        val samples = DoubleArray(7)
        var lastTotal = 0
        repeat(7) { i -> val (perOp, total) = run(); samples[i] = perOp; lastTotal = total }
        val median = samples.sorted()[samples.size / 2]
        val check = run().second
        assertEquals("每个 delta 的拼接结果必须完整", 5_000 * (text.length + 1), check)
        assertEquals("多轮之间总量一致", lastTotal, check)
        println(
            "P1-4 delta buffer: 7 轮中位数 " + "%.2f".format(median) + "us/轮" +
                "（min/max " + "%.2f".format(samples.min()) + "/" + "%.2f".format(samples.max()) + "）",
        )
    }
}
