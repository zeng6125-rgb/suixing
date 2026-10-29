package com.suixing.agent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #44 第四项：GUI / 会话式 shell / 特权写操作串行，纯读类仍并行。
 *
 * 这组测试守的是**分组**，不是执行：分组错了，两个手势就会被同时提交（一个按在另一个刚滑走
 * 的位置上），或者本来就安全的两个读被白白串起来（丢掉 #36 的并行收益）。
 */
class ToolBatchingTest {

    @Test
    fun `纯读类照旧并行打包`() {
        val groups = batchGroups(listOf("read_file", "grep", "device_info", "web_search"), parallel = 3)
        assertEquals(listOf(listOf(0, 1, 2), listOf(3)), groups)
    }

    @Test
    fun `GUI 手势自成一组且不与前后交叠`() {
        val groups = batchGroups(listOf("read_file", "ui_tap", "grep"), parallel = 4)
        // read_file 与 grep 不能因为夹着一个 ui_tap 就被并到一起。
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), groups)
    }

    @Test
    fun `连续两个手势各走各的`() {
        val groups = batchGroups(listOf("ui_tap", "ui_swipe"), parallel = 8)
        assertEquals(listOf(listOf(0), listOf(1)), groups)
    }

    @Test
    fun `shell 与特权写操作串行`() {
        val groups = batchGroups(listOf("run_shell", "app_action", "read_file"), parallel = 4)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), groups)
    }

    @Test
    fun `屏幕快照与元素读取仍可并行`() {
        assertTrue(isParallelizable("ui_dump"))
        assertTrue(isParallelizable("ui_screenshot"))
        assertTrue(isParallelizable("read_file"))
        assertFalse(isParallelizable("ui_tap"))
        assertFalse(isParallelizable("ui_long_press"))
        assertFalse(isParallelizable("run_shell"))
        assertFalse(isParallelizable("app_action"))
    }

    @Test
    fun `组的下标覆盖每个调用且保持原顺序`() {
        val names = listOf("read_file", "ui_tap", "grep", "run_shell", "device_info", "web_search")
        val groups = batchGroups(names, parallel = 2)
        assertEquals("每个调用必须恰好出现一次", names.indices.toList(), groups.flatten().sorted())
        // 顺序单调：回写顺序必须与模型给出的顺序一致。
        assertEquals(names.indices.toList(), groups.flatten())
    }

    @Test
    fun `两次读被两个手势隔开时切成四组`() {
        // issue #48 第四项点名的原样场景：中间那两段读任何一段都不许与手势交叠。
        val groups = batchGroups(listOf("read_file", "ui_tap", "read_file", "ui_swipe"), parallel = 8)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3)), groups)
    }

    @Test
    fun `空批与单调用`() {
        assertEquals(emptyList<List<Int>>(), batchGroups(emptyList(), parallel = 4))
        assertEquals(listOf(listOf(0)), batchGroups(listOf("read_file"), parallel = 4))
        assertEquals(listOf(listOf(0)), batchGroups(listOf("run_shell"), parallel = 4))
    }
}
