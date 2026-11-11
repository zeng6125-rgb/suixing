package com.suixing.agent.ui.chat.markdown

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MarkdownTableRegressionTest {
    @Test fun `an escaped shell pipeline stays in one table cell`() {
        val source = "| 项目 | 单次 |\n| --- | --- |\n" +
            "| 跑一条真实命令 (`ls /sdcard/Download \\| head`) | 35 ms |"
        assertEquals(
            listOf(MdBlock.Table(listOf("项目", "单次"), listOf(
                listOf("跑一条真实命令 (`ls /sdcard/Download | head`)", "35 ms"),
            ))),
            parseMarkdown(source),
        )
    }

    @Test fun `escaped pipes in headers code and emphasis retain their literal text`() {
        val source = "| f\\|oo | value |\n| --- | --- |\n" +
            "| b `\\|` az | one |\n| b **\\|** im | two |"
        val expected = MdBlock.Table(listOf("f|oo", "value"), listOf(
            listOf("b `|` az", "one"), listOf("b **|** im", "two"),
        ))
        val parsed = parseMarkdown(source)
        assertEquals(listOf(expected), parsed)
        val cell = (parsed.single() as MdBlock.Table).rows[0][0]
        assertEquals("b \u2009|\u2009 az", inlineMarkdown(cell,
            codeBackground = Color.Transparent, codeColor = Color.Black, linkColor = Color.Black).text)
    }

    @Test fun `an escaped final pipe is cell content when outer pipes are omitted`() {
        val source = "a | b\\|\n--- | ---\none | two\\|"
        assertEquals(listOf(MdBlock.Table(listOf("a", "b|"),
            listOf(listOf("one", "two|")))), parseMarkdown(source))
    }

    @Test fun `body rows use the header column count`() {
        val source = "| a | b |\n| --- | --- |\n| short |\n| first | second | excess |"
        assertEquals(listOf(MdBlock.Table(listOf("a", "b"),
            listOf(listOf("short", ""), listOf("first", "second")))), parseMarkdown(source))
    }

    @Test fun `streaming a pipeline and uneven rows keeps a fixed two column table`() {
        val live = LiveBlocks()
        val prefix = "| command | time |\n| --- | --- |\n| settled | 1 ms |\n"
        val before = live.blocksFor(prefix, 1, 5).single() as MdBlock.Table
        val source = prefix + "| `ls /sdcard/Download \\| head` | 35 ms |\n| short |\n| a | b | excess |"
        for (end in prefix.length + 1..source.length) {
            val text = source.take(end)
            assertEquals("prefix=$text", parseMarkdown(text), live.blocksFor(text, end, 5))
        }
        val after = live.blocksFor(source, source.length, 5).single() as MdBlock.Table
        assertEquals(listOf("`ls /sdcard/Download | head`", "35 ms"), after.rows[1])
        assertEquals(listOf("short", ""), after.rows[2])
        assertEquals(listOf("a", "b"), after.rows[3])
        assertSame(before.rows[0], after.rows[0])
    }
}
