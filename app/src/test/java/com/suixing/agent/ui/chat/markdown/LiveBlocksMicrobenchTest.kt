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

        val full = LiveBlocks()
        val t0 = System.nanoTime()
        var lastFull: List<MdBlock> = emptyList()
        for (text in frames) lastFull = full.blocksFor(text)
        val fullMs = (System.nanoTime() - t0) / 1_000_000.0

        val trusted = LiveBlocks()
        val t1 = System.nanoTime()
        var lastTrusted: List<MdBlock> = emptyList()
        for ((i, text) in frames.withIndex()) {
            lastTrusted = trusted.blocksFor(text, appendFrom = i, sourceId = sourceId)
        }
        val trustedMs = (System.nanoTime() - t1) / 1_000_000.0

        assertEquals("两条路径的块结构必须完全一致", lastFull, lastTrusted)
        assertEquals("块数必须与整段解析一致", lastFull.size, parseMarkdown(frames.last()).size)
        val ratio = if (trustedMs > 0) fullMs / trustedMs else Double.NaN
        println(
            "A1 microbench: frames=" + frames.size + " finalChars=" + frames.last().length +
                " full=" + "%.1f".format(fullMs) + "ms trusted=" + "%.1f".format(trustedMs) +
                "ms speedup=" + "%.2f".format(ratio) + "x blocks=" + lastFull.size,
        )
    }

    private val N1 = 10.toChar().toString()
    private val N2 = run { val lf = 10.toChar().toString(); lf + lf }
}
