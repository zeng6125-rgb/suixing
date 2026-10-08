package com.suixing.agent.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.suixing.agent.ui.components.MdBlock
import com.suixing.agent.ui.components.inlineMarkdown
import com.suixing.agent.ui.components.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Test

// Plain JVM tests: both parser entry points are free of Android/Compose runtime
// types, so they run without Robolectric.
class MarkdownTest {

    private val codeBackground = Color(0xFF102030)
    private val codeColor = Color(0xFF203040)
    private val linkColor = Color(0xFF304050)

    private fun inline(text: String) = inlineMarkdown(
        text = text,
        codeBackground = codeBackground,
        codeColor = codeColor,
        linkColor = linkColor,
    )

    @Test
    fun `atx headings keep their level and text`() {
        val blocks = parseMarkdown("# Title\n\n## Sub\n\n###### Deep")
        assertEquals(
            listOf<MdBlock>(
                MdBlock.Heading(1, "Title"),
                MdBlock.Heading(2, "Sub"),
                MdBlock.Heading(6, "Deep"),
            ),
            blocks,
        )
    }

    @Test
    fun `a hash without a space is not a heading`() {
        assertEquals(listOf<MdBlock>(MdBlock.Paragraph("#hashtag")), parseMarkdown("#hashtag"))
    }

    @Test
    fun `consecutive plain lines merge into one paragraph`() {
        val blocks = parseMarkdown("第一行文字\n第二行文字\n第三行文字")
        assertEquals(1, blocks.size)
        assertEquals(MdBlock.Paragraph("第一行文字\n第二行文字\n第三行文字"), blocks[0])
    }

    @Test
    fun `a blank line separates paragraphs`() {
        assertEquals(
            listOf<MdBlock>(MdBlock.Paragraph("first"), MdBlock.Paragraph("second")),
            parseMarkdown("first\n\nsecond"),
        )
    }

    @Test
    fun `bullet lists merge consecutive items`() {
        assertEquals(
            listOf<MdBlock>(MdBlock.Bullet(listOf("one", "two", "three"), ordered = false)),
            parseMarkdown("- one\n* two\n+ three"),
        )
    }

    @Test
    fun `ordered lists accept both dots and parens`() {
        assertEquals(
            listOf<MdBlock>(MdBlock.Bullet(listOf("one", "two", "ten"), ordered = true)),
            parseMarkdown("1. one\n2) two\n10. ten"),
        )
    }

    @Test
    fun `a list marker needs a space to start a list`() {
        assertEquals(
            listOf<MdBlock>(MdBlock.Paragraph("-nope\n1.nope")),
            parseMarkdown("-nope\n1.nope"),
        )
    }

    @Test
    fun `a list ends at a different kind of line`() {
        assertEquals(
            listOf<MdBlock>(
                MdBlock.Bullet(listOf("one"), ordered = false),
                MdBlock.Paragraph("tail"),
            ),
            parseMarkdown("- one\ntail"),
        )
    }

    @Test
    fun `a nested list becomes its own indented block`() {
        assertEquals(
            listOf<MdBlock>(
                MdBlock.Bullet(listOf("a"), ordered = false, depth = 0),
                MdBlock.Bullet(listOf("b"), ordered = false, depth = 1),
            ),
            parseMarkdown("- a\n  - b"),
        )
    }

    @Test
    fun `a tab indents one level like two spaces`() {
        assertEquals(
            listOf<MdBlock>(
                MdBlock.Bullet(listOf("a"), ordered = false, depth = 0),
                MdBlock.Bullet(listOf("b"), ordered = false, depth = 1),
            ),
            parseMarkdown("- a\n\t- b"),
        )
    }

    @Test
    fun `indentation deeper than four levels is capped`() {
        assertEquals(
            listOf<MdBlock>(
                MdBlock.Bullet(listOf("a"), ordered = false, depth = 0),
                MdBlock.Bullet(listOf("b"), ordered = false, depth = 4),
            ),
            parseMarkdown("- a\n" + " ".repeat(20) + "- b"),
        )
    }

    @Test
    fun `fenced code keeps its language and body`() {
        assertEquals(
            listOf<MdBlock>(MdBlock.Code("kotlin", "val x = 1\nfun main() {}")),
            parseMarkdown("```kotlin\nval x = 1\nfun main() {}\n```"),
        )
    }

    @Test
    fun `an unterminated fence swallows the rest of the document`() {
        assertEquals(
            listOf<MdBlock>(
                MdBlock.Paragraph("text"),
                MdBlock.Code("", "val x = 1\nval y = 2"),
            ),
            parseMarkdown("text\n\n```\nval x = 1\nval y = 2"),
        )
    }

    @Test
    fun `blockquote lines merge and drop their marker`() {
        assertEquals(
            listOf<MdBlock>(MdBlock.Quote("first\nsecond")),
            parseMarkdown("> first\n> second"),
        )
    }

    @Test
    fun `horizontal rules accept three marker styles`() {
        assertEquals(
            listOf<MdBlock>(MdBlock.Rule, MdBlock.Rule, MdBlock.Rule),
            parseMarkdown("---\n***\n___"),
        )
    }

    @Test
    fun `a table keeps its header and body rows`() {
        assertEquals(
            listOf<MdBlock>(
                MdBlock.Table(
                    header = listOf("a", "b"),
                    rows = listOf(listOf("1", "2"), listOf("3", "4")),
                ),
            ),
            parseMarkdown("| a | b |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |"),
        )
    }

    @Test
    fun `a pipe row without a separator stays a paragraph`() {
        assertEquals(
            listOf<MdBlock>(MdBlock.Paragraph("| a | b |\nnot a table")),
            parseMarkdown("| a | b |\nnot a table"),
        )
    }

    @Test
    fun `a table ends at a row without pipes`() {
        assertEquals(
            listOf<MdBlock>(
                MdBlock.Table(header = listOf("a"), rows = listOf(listOf("1"))),
                MdBlock.Paragraph("after"),
            ),
            parseMarkdown("| a |\n| --- |\n| 1 |\nafter"),
        )
    }

    @Test
    fun `inline markers become spans over stripped text`() {
        val result = inline("plain **bold** and *italic* and `code` and ~~gone~~")
        // The code span is padded with thin spaces, which are real characters in the output.
        assertEquals("plain bold and italic and \u2009code\u2009 and gone", result.text)
        assertEquals(4, result.spanStyles.size)
    }

    @Test
    fun `inline code carries the code colours and monospace`() {
        val result = inline("see `val x` here")
        // Thin spaces around the code: a SpanStyle background cannot carry padding, so
        // the breathing room is characters.
        assertEquals("see \u2009val x\u2009 here", result.text)
        // Spans address the output text, where the backticks are already gone.
        val span = result.spanStyles.single()
        assertEquals(4, span.start)
        assertEquals(11, span.end)
        assertEquals(codeBackground, span.item.background)
        assertEquals(codeColor, span.item.color)
        assertEquals(FontFamily.Monospace, span.item.fontFamily)
    }

    @Test
    fun `a link keeps its label and gets an underline`() {
        val result = inline("[docs](https://x.dev)")
        assertEquals("docs", result.text)
        val span = result.spanStyles.single()
        assertEquals(0, span.start)
        assertEquals(4, span.end)
        assertEquals(linkColor, span.item.color)
        assertEquals(TextDecoration.Underline, span.item.textDecoration)
    }

    @Test
    fun `escaped markers stay literal`() {
        val result = inline("\\*not italic\\* and \\_plain\\_")
        assertEquals("*not italic* and _plain_", result.text)
        assertEquals(0, result.spanStyles.size)
    }

    @Test
    fun `an unclosed marker stays literal`() {
        val bold = inline("**oops")
        assertEquals("**oops", bold.text)
        assertEquals(0, bold.spanStyles.size)

        val code = inline("`oops")
        assertEquals("`oops", code.text)
        assertEquals(0, code.spanStyles.size)
    }

    @Test
    fun `a bare url is linked without its trailing dot`() {
        val result = inline("see https://x.dev/a. next")
        assertEquals("see https://x.dev/a. next", result.text)
        val span = result.spanStyles.single()
        assertEquals(4, span.start)
        assertEquals(19, span.end)
        assertEquals(linkColor, span.item.color)
    }

    @Test
    fun `bold nests inside italic`() {
        val result = inline("*a **b** c*")
        assertEquals("a b c", result.text)
        assertEquals(3, result.spanStyles.size)
        val middle = result.spanStyles[1]
        assertEquals(2, middle.start)
        assertEquals(3, middle.end)
        // SemiBold, not Bold: the rendering spec rules bold text to 600 so it sits
        // between the body weight and a heading without shouting inside a paragraph.
        assertEquals(FontWeight.SemiBold, middle.item.fontWeight)
        assertEquals(FontStyle.Italic, middle.item.fontStyle)
    }
}
