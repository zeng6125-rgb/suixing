package com.suixing.agent.ui.drawer

import com.suixing.agent.data.Conversation
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 抽屉列表投影的可复现成本对照（issue #66 P1-3）。
 *
 * 目的不是"证明它慢"，而是**先量**：20 / 200 / 2000 个会话下，每次投影要多久。
 * 只有量出真实成本，才谈得上要不要为"关闭态降频/缓存"加复杂度 —— 反过来，
 * 如果 2000 条也只要几百微秒，那任何缓存都只是把旧排序显示出来，属于净负债。
 *
 * 数字只作趋势参考（JVM ≠ ART），但它能回答"值不值得加缓存"这个判断本身。
 */
class DrawerProjectionBenchmarkTest {

    private fun conversations(n: Int): List<Conversation> = (0 until n).map { i ->
        Conversation(
            id = "c$i",
            title = "会话 $i",
            updatedAt = 1_700_000_000_000L + i,
            // 每 10 个里 1 个置顶、每 17 个里 1 个归档：三种分组路径都被走到
            pinned = i % 10 == 0,
            archived = i % 17 == 0,
        )
    }

    private fun order(n: Int): DrawerOrder = DrawerOrder(
        pinned = (0 until n).filter { it % 10 == 0 }.map { "c$it" },
        normal = (0 until n).filter { it % 23 == 0 }.map { "c$it" },
    )

    @Test
    fun `projection cost at 20 200 and 2000 conversations`() {
        val out = StringBuilder("P1-3 drawer projection:")
        for (n in intArrayOf(20, 200, 2000)) {
            val list = conversations(n)
            val ord = order(n)
            repeat(50) { drawerSections(list, ord) }           // 预热
            val runs = 200
            val start = System.nanoTime()
            var last: DrawerSections? = null
            repeat(runs) { last = drawerSections(list, ord) }
            val perCallUs = (System.nanoTime() - start) / 1_000.0 / runs
            // 同输入必须给出同输出（后面若加缓存，这条就是等价性判据）
            assertEquals(last, drawerSections(list, ord))
            out.append(" | n=").append(n)
                .append(" ").append("%.1f".format(perCallUs)).append("us/次")
                .append(" 置顶=").append(last!!.pinned.size)
                .append(" 普通=").append(last!!.normal.size)
        }
        println(out.toString())
    }

    @Test
    fun `an unrelated settings change does not change the projection`() {
        val list = conversations(200)
        val ord = order(200)
        val before = drawerSections(list, ord)
        // 与排序无关的设置变化不该让投影结果不同（缓存若引入，必须保持这条）
        val after = drawerSections(list, ord.copy())
        assertEquals(before, after)
    }
}
