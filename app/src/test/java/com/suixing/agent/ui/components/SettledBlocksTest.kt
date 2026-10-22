package com.suixing.agent.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 定稿文本解析缓存的容量与淘汰行为。
 *
 * 动机：它存在的价值是「滚出去再滚回来不必重新解析」，而代价是**内存**。
 * 淘汰策略错了的表现是两种：要么永远命中不了（缓存没用），要么无界增长（OOM 风险）。
 * 所以这里断言的是**上界**与**访问序**，不是"能存进去"。
 */
class SettledBlocksTest {
    private fun text(tag: String) = "$tag " + "内容".repeat(300)   // 超过 MIN_CHARS

    @Test
    fun `round trips a long text`() {
        val t = text("a")
        val blocks = parseMarkdown(t)
        SettledBlocks.put(t, blocks)
        assertSame("必须拿到同一份对象（引用相等才说明真的没重解析）", blocks, SettledBlocks.get(t))
    }

    @Test
    fun `short texts are not cached`() {
        val t = "短"
        assertTrue("这条的前提是它低于门槛", t.length < SettledBlocks.MIN_CHARS)
        SettledBlocks.put(t, parseMarkdown(t))
        assertNull("短文本解析本来就快，缓存它只占内存", SettledBlocks.get(t))
    }

    @Test
    fun `capacity is bounded`() {
        // 塞超过容量，最早的必须被淘汰 —— 否则长时间浏览会把整段会话的解析结果都留在内存里。
        val texts = (0 until SettledBlocks.CAPACITY + 4).map { text("cap$it") }
        texts.forEach { SettledBlocks.put(it, parseMarkdown(it)) }
        assertNull("最老的那条应被淘汰", SettledBlocks.get(texts.first()))
        assertTrue("最新的必须还在", SettledBlocks.get(texts.last()) != null)
        // **精确上界**：满了之后插入不会让容量继续涨（先前这里写的是"简化到 ≤ 容量"，
        // 掩盖了"到底是不是刚好 16"这个问题）。
        assertEquals("容量必须恰好停在上限", SettledBlocks.CAPACITY, SettledBlocks.sizeForTest())
        val alive = texts.count { SettledBlocks.get(it) != null }
        assertEquals("命中数必须恰好等于容量（不多不少）", SettledBlocks.CAPACITY, alive)
    }

    @Test
    fun `eviction follows access order, not insertion order`() {
        val texts = (0 until SettledBlocks.CAPACITY).map { text("lru$it") }
        texts.forEach { SettledBlocks.put(it, parseMarkdown(it)) }
        assertEquals(SettledBlocks.CAPACITY, SettledBlocks.sizeForTest())

        // 读一下最老的那条：它变成最近使用 ⇒ 下一条被淘汰的应是**第二条**（插入序则仍是第一条）。
        SettledBlocks.get(texts.first())
        val extra = text("lru-extra")
        SettledBlocks.put(extra, parseMarkdown(extra))

        assertEquals("插入一条后仍在上限", SettledBlocks.CAPACITY, SettledBlocks.sizeForTest())
        assertTrue("刚读过的不该被淘汰", SettledBlocks.get(texts.first()) != null)
        assertNull("该淘汰的是第二条（访问序）", SettledBlocks.get(texts[1]))
        assertTrue("其它都还在", SettledBlocks.get(texts[2]) != null)
        assertTrue("新插入的也在", SettledBlocks.get(extra) != null)
        assertEquals("恰好淘汰一条", SettledBlocks.CAPACITY, SettledBlocks.sizeForTest())
    }
}
