package com.suixing.agent.ui.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 增量解析与一次性解析**必须等价**。
 *
 * 为什么：流式回答是逐段变长的，`MarkdownCache` 只重解析"最后一个空行之后"的尾巴，
 * 前面的部分复用上次结果。这个优化一旦出错不会崩、不会报错 ——
 * 只会**渲染错**（比如把一段代码块劈成两个、或列表项粘连），而且只在特定文本形状下出现。
 *
 * 所以这里用"喂前缀"的方式模拟流式：每加一小段就比一次 `parseMarkdown` 的结果。
 */
class MarkdownCacheTest {

    /** 逐字符喂进去，每一步都要与一次性解析等价。 */
    private fun assertEquivalent(text: String) {
        val cache = MarkdownCache()
        for (end in 1..text.length) {
            val prefix = text.substring(0, end)
            assertEquals(
                "前缀长度 $end 的增量结果必须等于一次性解析",
                parseMarkdown(prefix),
                cache.blocksFor(prefix),
            )
        }
        assertEquals("完整文本也要等价", parseMarkdown(text), cache.blocksFor(text))
    }

    @Test
    fun `paragraphs and headings match the one-shot parse at every prefix`() {
        assertEquivalent(
            """
            # 标题

            第一段文字，比较长，用来跨过空行与增量边界。

            第二段文字。
            """.trimIndent(),
        )
    }

    @Test
    fun `a fenced block whose body has blank lines never splits`() {
        // 关键形状：**围栏里的空行不是块的边界**。settledPrefix 用"围栏奇偶"判断，
        // 这里逐字符喂，任何切错都会在某个前缀上暴露。
        assertEquivalent(
            """
            前面一段。

            ```kotlin
            val a = 1

            val b = 2
            ```

            后面一段。
            """.trimIndent(),
        )
    }

    @Test
    fun `lists and quotes survive the growing tail`() {
        assertEquivalent(
            """
            - 一
            - 二
            - 三

            > 引用一
            > 引用二

            收尾。
            """.trimIndent(),
        )
    }

    @Test
    fun `text with no blank line at all stays one block`() {
        // 没有任何空行 ⇒ settledPrefix 为空 ⇒ 每一步都整份重解析（也必须等价）。
        assertEquivalent("一二三四五六七八九十")
    }

    @Test
    fun `a leading blank line is handled like any other`() {
        // 注释里记过的那条：开头就是空行时不能把它当成"没有已定稿部分"，
        // 否则整段回答每次发布都重解析。
        val text = "\n\n第一段。\n\n第二段。"
        val cache = MarkdownCache()
        assertEquals(parseMarkdown(text), cache.blocksFor(text))
        // 再喂一次同样的文本：缓存命中路径也要等价。
        assertEquals(parseMarkdown(text), cache.blocksFor(text))
    }
}
