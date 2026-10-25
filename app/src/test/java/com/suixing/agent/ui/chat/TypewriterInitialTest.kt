package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打字机初值（issue #46）。
 *
 * 两种互斥场景必须分开：块**刚诞生**（哪怕首片就到了几百字）从 0 开始打；块**早就存在**、
 * 这次才第一次组合（上翻回来 / 中途打开开关 / 折叠展开）直接给全文。
 * 原来的实现一律用 `text.length`，于是前者等于一个字都不打 —— 用户看到的
 * 「正文有时一次性全部显示」。
 */
class TypewriterInitialTest {

    @Test
    fun `a block born a moment ago types from zero even when a big chunk arrived at once`() {
        assertEquals(0, initialRevealed(textLength = 320, ageMs = 0))
        assertEquals(0, initialRevealed(textLength = 320, ageMs = 120))
    }

    @Test
    fun `the freshness window is exactly where a re-composition stops being a birth`() {
        assertEquals(0, initialRevealed(textLength = 320, ageMs = 500))
        assertEquals("超过窗口 ⇒ 不必重播", 320, initialRevealed(textLength = 320, ageMs = 501))
    }

    @Test
    fun `a block that was on screen before is handed over whole`() {
        assertEquals(900, initialRevealed(textLength = 900, ageMs = 30_000))
    }

    @Test
    fun `history rows carry no birth time and never type`() {
        val stored = Part.Text("一段已经定稿的回答")
        assertEquals("历史行默认 0 = 很久以前", 0L, stored.bornAtMs)
        val age = nowMs() - stored.bornAtMs
        assertEquals(stored.text.length, initialRevealed(stored.text.length, age))
    }

    @Test
    fun `a live block keeps its birth stamp while it grows`() {
        val before = nowMs()
        val block = LivePart.Text("a")
        val after = nowMs()
        assertTrue("诞生时刻必须落在这次构造附近", block.bornAtMs in before..after)

        val grown = listOf<LivePart>(block).appendText("bc", segmentStart = 0).single() as LivePart.Text
        assertEquals("续写不能刷新诞生时刻，否则打字机会把老块当成新块重播", block.bornAtMs, grown.bornAtMs)
        assertEquals(0, initialRevealed(grown.buffer.length(), nowMs() - grown.bornAtMs))
    }

    @Test
    fun `the birth stamp stays outside equals and hashCode`() {
        // 回归锁：`equals` 按缓冲长度比，是「内容没变长度时不重算映射」这条契约（issue #38）的基础。
        // 把 bornAtMs 塞进去会让每次重建都判定为"不等"，那条契约当场失效。
        val ancient = LivePart.Text(GrowableText("abc"), bornAtMs = 1L)
        val fresh = LivePart.Text("abc")
        assertEquals("长度相同必须相等", ancient, fresh)
        assertEquals("哈希也只能由长度决定", ancient.hashCode(), fresh.hashCode())
        assertTrue(ancient.bornAtMs < fresh.bornAtMs)
    }
}
