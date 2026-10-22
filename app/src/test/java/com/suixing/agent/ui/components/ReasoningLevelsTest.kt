package com.suixing.agent.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 七档思考强度（用户 2026-10-23：「默认 / minimal / 低 / 中 / 高 / xhigh / max 先对应七档全英」）。
 *
 * 这份测试盯两件事：
 *  - **显示名全英文**，但 **wire 值一个字都不能变** —— 左列是发给接口的
 *    `reasoning_effort`，改错会让请求语义变形（而且很难看出来）。
 *  - 档位数是 7，顺序从弱到强；滑杆与设置页共用这一张表，顺序变了位置就错。
 */
class ReasoningLevelsTest {

    @Test
    fun `there are seven levels in wire order`() {
        assertEquals(7, REASONING_LEVELS.size)
        assertEquals(
            listOf("", "minimal", "low", "medium", "high", "xhigh", "max"),
            REASONING_LEVELS.map { it.first },
        )
    }

    @Test
    fun `every label is english`() {
        val labels = REASONING_LEVELS.map { it.second }
        assertEquals(
            listOf("Default", "Minimal", "Low", "Medium", "High", "XHigh", "Max"),
            labels,
        )
        // 没有中日韩字符 —— 改名时最容易漏掉一个。
        labels.forEach { label ->
            assertTrue("档位名里不该再有中文：$label", label.none { it.code in 0x4E00..0x9FFF })
        }
    }

    @Test
    fun `the empty wire value is the default level`() {
        assertEquals("Default", REASONING_LEVELS.first().second)
        assertEquals("", REASONING_LEVELS.first().first)
    }
}
