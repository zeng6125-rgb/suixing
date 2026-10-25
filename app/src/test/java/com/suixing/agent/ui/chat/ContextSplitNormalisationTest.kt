package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 圆环三条（系统提示词 / 工具定义 / 对话消息）与顶部总数对不上：
 * 顶部是端点实测的 prompt tokens，三条是本地字符估算（÷4）。归一后三条必须**精确相加**
 * 等于顶部总数。
 */
class ContextSplitNormalisationTest {

    @Test
    fun threePartsSumExactlyToTotal() {
        val (system, tools, history) = normaliseContextSplit(
            system = 12_345,
            tools = 6_789,
            history = 98_765,
            total = 500_000,
        )
        assertEquals(500_000, system + tools + history)
    }

    @Test
    fun proportionsArePreserved() {
        val (system, tools, history) = normaliseContextSplit(
            system = 1_000,
            tools = 1_000,
            history = 2_000,
            total = 40_000,
        )
        assertEquals(10_000, system)
        assertEquals(10_000, tools)
        assertEquals(20_000, history)
    }

    @Test
    fun zeroTotalLeavesTheSplitAlone() {
        val (system, tools, history) = normaliseContextSplit(
            system = 100,
            tools = 200,
            history = 300,
            total = 0,
        )
        assertEquals(100, system)
        assertEquals(200, tools)
        assertEquals(300, history)
    }

    @Test
    fun zeroSplitIsNotInvented() {
        val (system, tools, history) = normaliseContextSplit(
            system = 0,
            tools = 0,
            history = 0,
            total = 500_000,
        )
        assertEquals(0, system)
        assertEquals(0, tools)
        assertEquals(0, history)
    }

    @Test
    fun remainderNeverGoesNegative() {
        // 三个都很小的估算值被放大到实测总数：最后一条吸收误差后仍不能为负。
        val (system, tools, history) = normaliseContextSplit(
            system = 1,
            tools = 1,
            history = 1,
            total = 10,
        )
        assertTrue(system >= 0 && tools >= 0 && history >= 0)
        assertEquals(10, system + tools + history)
    }
}
