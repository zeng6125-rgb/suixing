package com.suixing.agent.ui.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Every complete or interrupted document must match the full parser. */
class MarkdownCacheTest {

    /** 逐字符喂进去，每一步都要与一次性解析等价。 */
    private fun assertEquivalent(text: String) {
        val cache = MarkdownCache()
        for (end in 1..text.length) {
            val prefix = text.substring(0, end)
            assertEquals(
                "前缀长度 $end 的缓存结果必须等于一次性解析",
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
        // A run may stop at any prefix, including before its closing fence.
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
    fun `settling a multiline template literal preserves its single code block`() {
        val text = "```js\nconst message = `first\n\nsecond`;\n```"
        val live = LiveBlocks().blocksFor(text)

        assertEquals(listOf(MdBlock.Code("js", "const message = `first\n\nsecond`;")), live)
        assertEquals("settling must preserve the live code block", live, MarkdownCache().blocksFor(text))
    }

    @Test
    fun `settling a four backtick fence preserves internal blank lines`() {
        val text = "````kotlin\nval first = 1\n\nval second = 2\n````"
        val expected = listOf(MdBlock.Code("kotlin", "val first = 1\n\nval second = 2"))

        assertEquals(expected, parseMarkdown(text))
        assertEquals(expected, LiveBlocks().blocksFor(text))
        assertEquals("a fence's length must not turn its body into separate blocks",
            expected, MarkdownCache().blocksFor(text))
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
        assertEquivalent("一二三四五六七八九十")
    }

    @Test
    fun `a leading blank line is handled like any other`() {
        val text = "\n\n第一段。\n\n第二段。"
        val cache = MarkdownCache()
        val parsed = cache.blocksFor(text)
        assertEquals(parseMarkdown(text), parsed)
        assertSame(parsed, cache.blocksFor(text))
    }
}
