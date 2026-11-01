package com.suixing.agent.ui.files

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 目录列表投影的可复现成本对照（issue #68 P2-D）。
 *
 * 只量**纯 CPU 那部分**：文件名 `lowercase()` + 「文件夹优先、忽略大小写」的稳定排序。
 * 逐文件的 `isDirectory` / `length` / 时间格式化是 IO 与系统调用，JVM 上量不出真实值，
 * 这里不假装能测，也不据此声称 FPS。
 */
class FileListProjectionBenchmarkTest {

    private fun names(n: Int): List<Pair<Boolean, String>> = (0 until n).map { i ->
        (i % 7 == 0) to ("第${i}个文件_Report-${i}-数据说明.txt")
    }

    private fun project(entries: List<Pair<Boolean, String>>): List<Pair<Boolean, String>> =
        entries.map { (isDir, name) -> isDir to name.lowercase() }
            .sortedWith(compareBy({ !it.first }, { it.second }))

    @Test
    fun `listing projection cost at 100 and 1000 files`() {
        val out = StringBuilder("P2-D file projection:")
        for (n in intArrayOf(100, 1000)) {
            val input = names(n)
            repeat(50) { project(input) }
            val runs = 200
            val start = System.nanoTime()
            var last: List<Pair<Boolean, String>> = emptyList()
            repeat(runs) { last = project(input) }
            val perCallUs = (System.nanoTime() - start) / 1_000.0 / runs
            assertEquals("同输入必须同输出", project(input), last)
            assertEquals(n, last.size)
            out.append(" | n=").append(n).append(" ").append("%.1f".format(perCallUs)).append("us")
        }
        println(out.toString())
    }
}
