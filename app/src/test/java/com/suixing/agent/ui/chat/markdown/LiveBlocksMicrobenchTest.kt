package com.suixing.agent.ui.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `LiveBlocks` 两条路径的**等价性 + 成本对比**（issue #59 A1）。
 *
 * 真机帧数据要设备，但"跳过前缀校验到底省了多少"可以在 JVM 上量：同一段流式文本按固定
 * 步长推进，分别按「每次全量校验前缀」与「受信任游标」跑一遍，断言块结果一致并打印耗时。
 * 数字只作趋势参考（JVM ≠ ART），但它验证的是"这一步没白做、也没改变输出"。
 */
class LiveBlocksMicrobenchTest {

    private fun stream(chunks: Int, chunkChars: Int): List<String> {
        val sb = StringBuilder()
        val out = ArrayList<String>(chunks)
        repeat(chunks) { c ->
            when (c % 4) {
                0 -> sb.append("段落 ").append(c).append(" ").append("文".repeat(chunkChars))
                1 -> sb.append(N2).append("- 项 ").append(c).append(" ").append("字".repeat(chunkChars))
                2 -> sb.append(N2).append("> 引用 ").append(c).append(" ").append("句".repeat(chunkChars))
                else -> sb.append(N2).append("```kotlin").append(N1).append("val v").append(c).append(" = ").append(c)
            }
            if (c % 4 != 3) sb.append(N2)
            out.add(sb.toString())
        }
        return out
    }

    @Test fun `trusted cursor matches full verification and reports its cost`() {
        val frames = stream(chunks = 120, chunkChars = 300)
        val sourceId = 0x51D

        // **多轮取中位数**：单次测量受 JIT 分层、GC 影响，不能当结论。
        fun runFull(): Double {
            val lb = LiveBlocks()
            val t = System.nanoTime()
            var last: List<MdBlock> = emptyList()
            for (text in frames) last = lb.blocksFor(text)
            sink = sink xor last.size
            return (System.nanoTime() - t) / 1_000_000.0
        }
        fun runTrusted(): Double {
            val lb = LiveBlocks()
            val t = System.nanoTime()
            var last: List<MdBlock> = emptyList()
            for ((i, text) in frames.withIndex()) {
                last = lb.blocksFor(text, appendFrom = i, sourceId = sourceId)
            }
            sink = sink xor last.size
            return (System.nanoTime() - t) / 1_000_000.0
        }

        // 正确性先断言一次（与耗时测量解耦）
        val fullCheck = LiveBlocks()
        var lastFull: List<MdBlock> = emptyList()
        for (text in frames) lastFull = fullCheck.blocksFor(text)
        val trustedCheck = LiveBlocks()
        var lastTrusted: List<MdBlock> = emptyList()
        for ((i, text) in frames.withIndex()) {
            lastTrusted = trustedCheck.blocksFor(text, appendFrom = i, sourceId = sourceId)
        }
        assertEquals("两条路径的块结构必须完全一致", lastFull, lastTrusted)
        assertEquals("块数必须与整段解析一致", lastFull.size, parseMarkdown(frames.last()).size)

        runFull(); runTrusted()                 // 预热
        val rounds = 5
        val fulls = DoubleArray(rounds)
        val trusteds = DoubleArray(rounds)
        repeat(rounds) { r ->
            if (r % 2 == 0) { fulls[r] = runFull(); trusteds[r] = runTrusted() }
            else { trusteds[r] = runTrusted(); fulls[r] = runFull() }
        }
        fun med(a: DoubleArray) = a.sorted()[a.size / 2]
        println(
            "A1 microbench: frames=" + frames.size + " finalChars=" + frames.last().length +
                " rounds=" + rounds + " full_med=" + "%.1f".format(med(fulls)) + "ms" +
                " trusted_med=" + "%.1f".format(med(trusteds)) + "ms" +
                " speedup_med=" + "%.2f".format(med(fulls) / maxOf(med(trusteds), 0.001)) + "x" +
                " full_minmax=" + "%.1f".format(fulls.min()) + "/" + "%.1f".format(fulls.max()) +
                " trusted_minmax=" + "%.1f".format(trusteds.min()) + "/" + "%.1f".format(trusteds.max()),
        )
    }

    @Volatile private var sink = 0

    private val N1 = 10.toChar().toString()
    private val N2 = run { val lf = 10.toChar().toString(); lf + lf }
}
