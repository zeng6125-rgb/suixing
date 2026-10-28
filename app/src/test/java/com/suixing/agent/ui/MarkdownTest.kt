package com.suixing.agent.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.suixing.agent.ui.components.MdBlock
import com.suixing.agent.ui.components.inlineMarkdown
import com.suixing.agent.ui.components.listStart
import com.suixing.agent.ui.components.orderedMarker
import com.suixing.agent.ui.components.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `a nested list belongs to its parent item`() {
        assertEquals(
            listOf<MdBlock>(
                MdBlock.Bullet(
                    listOf("a"), ordered = false,
                    children = mapOf(0 to listOf(MdBlock.Bullet(listOf("b"), ordered = false, depth = 1))),
                ),
            ),
            parseMarkdown("- a\n  - b"),
        )
    }

    @Test
    fun `returning from a nested list retains following parent items`() {
        assertEquals(
            listOf<MdBlock>(
                MdBlock.Bullet(
                    listOf("parent", "next", "last"), ordered = false,
                    children = mapOf(0 to listOf(MdBlock.Bullet(listOf("child"), ordered = false, depth = 1))),
                ),
            ),
            parseMarkdown("- parent\n  - child\n- next\n- last"),
        )
    }

    @Test
    fun `a tab indents one level like two spaces`() {
        assertEquals(
            listOf<MdBlock>(
                MdBlock.Bullet(
                    listOf("a"), ordered = false,
                    children = mapOf(0 to listOf(MdBlock.Bullet(listOf("b"), ordered = false, depth = 1))),
                ),
            ),
            parseMarkdown("- a\n\t- b"),
        )
    }

    @Test
    fun `indentation deeper than four levels is capped`() {
        assertEquals(
            listOf<MdBlock>(
                MdBlock.Bullet(
                    listOf("a"), ordered = false,
                    children = mapOf(0 to listOf(MdBlock.Bullet(listOf("b"), ordered = false, depth = 4))),
                ),
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
    fun `a fence written on the same line as its content stays prose`() {
        // The opening test is matchEntire-strict: ```echo x is not a fence, so the
        // line cannot open a box that a later stray fence then closes empty.
        val blocks = parseMarkdown("```echo x\nmore prose")
        assertEquals(true, blocks.none { it is MdBlock.Code })
        assertEquals("```echo x\nmore prose", (blocks.single() as MdBlock.Paragraph).text)
    }

    @Test
    fun `an inline fence at a line end never opens a code block`() {
        val blocks = parseMarkdown("run it ``` then explain\nsecond line")
        assertEquals(true, blocks.none { it is MdBlock.Code })
    }

    @Test
    fun `two stray fence lines produce no empty code box`() {
        // The invariant the user's blank grey slab broke: no MdBlock.Code with a
        // blank body, ever. The pair still opens one block around "after", and the
        // blank-body guard is what keeps a fence-only pair from painting a slab.
        val blocks = parseMarkdown("before\n```\nafter\n```")
        assertEquals(
            listOf<MdBlock>(MdBlock.Paragraph("before"), MdBlock.Code("", "after")),
            blocks,
        )
        assertEquals(true, blocks.filterIsInstance<MdBlock.Code>().none { it.code.isBlank() })
        assertEquals(true, parseMarkdown("```\n```").none { it is MdBlock.Code })
    }

    @Test
    fun `an unterminated stream fence still keeps its body`() {
        val blocks = parseMarkdown("```\npartial thought")
        assertEquals(listOf<MdBlock>(MdBlock.Code("", "partial thought")), blocks)
    }

    @Test
    fun `a normal code block still parses unchanged`() {
        assertEquals(
            listOf<MdBlock>(MdBlock.Code("sh", "echo hi")),
            parseMarkdown("```sh\necho hi\n```"),
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
    fun `a bare url carries a Url annotation even with no callback`() {
        // 回归：旧 addRun 只在 onUrlClick != null 时挂 LinkAnnotation，而 inline() 传的正是 null，
        // 于是 URL 有下划线样式却**没有可点击 annotation** —— 点了没反应。URL 恒挂 Url annotation
        // 后，listener 为 null 时 Compose 用 LocalUriHandler 默认开浏览器。
        val result = inline("see https://x.dev/a. next")
        val links = result.getLinkAnnotations(0, result.length)
        assertEquals(1, links.size)
        val url = links.single().item
        assertTrue("必须是 Url annotation", url is LinkAnnotation.Url)
        assertEquals("https://x.dev/a", (url as LinkAnnotation.Url).url)
        assertNull("没有回调时 listener 为 null，走默认浏览器", url.linkInteractionListener)
    }

    @Test
    fun `a file path is colored but not underlined`() {
        // §1.3 裸文件路径识别：主色但无下划线（区别于 URL）。onUrlClick=null 时不挂 Clickable，
        // 所以它就是一段带色的普通文本 —— 有样式 span、无 link annotation。
        val result = inline("见 /sdcard/x.md")
        assertEquals("见 /sdcard/x.md", result.text)
        val span = result.spanStyles.single()
        assertEquals(2, span.start)
        assertEquals(14, span.end)
        assertEquals(linkColor, span.item.color)
        assertNull("路径无下划线（区别于 URL）", span.item.textDecoration)
        assertEquals("null 回调时路径不可点，无 link annotation", 0, result.getLinkAnnotations(0, result.length).size)
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

    // ------------------------------------------------------------------ 有序列表的序号

    /** The markers an ordered list would draw, in order — the same call the renderer makes. */
    private fun markers(blocks: List<MdBlock>): String = blocks
        .filterIsInstance<MdBlock.Bullet>()
        .flatMap { block -> block.items.indices.map { orderedMarker(block, it) } }
        .joinToString(" ")

    private fun userChain(count: Int): String = (1..count).joinToString("\n") { "$it. tool$it" }

    @Test
    fun `a tight ordered list is one block, not one block per line`() {
        // 分水岭：单 \n 分隔的连续 N. 行必须并进同一个块。若这里变成 3 个各含 1 项的块，
        // 下面每条断言都会退回「所有序号都是 1」。
        val blocks = parseMarkdown("1. a\n2. b\n3. c")
        assertEquals(1, blocks.size)
        val list = blocks.single() as MdBlock.Bullet
        assertEquals(listOf("a", "b", "c"), list.items)
        assertEquals(1, list.start)
    }

    @Test
    fun `a fifty-one item chain of thought is numbered one to fifty-one`() {
        // 用户真机那条「数工具」的思维链：1→51，单 \n 分隔。
        val blocks = parseMarkdown(userChain(51))
        assertEquals(1, blocks.size)
        assertEquals((1..51).joinToString(" ") { "$it." }, markers(blocks))
    }

    @Test
    fun `a list rendered one line at a time still keeps its own numbers`() {
        // 思维链是按行渲染的（ChatScreen 把 text 拆成行后逐行交给 MarkdownText），
        // 所以每个块只有一项：序号只能来自源码里的字面数字，数不出 1、2、3。
        val lines = userChain(51).split('\n')
        val drawn = lines.map { line -> markers(parseMarkdown(line)) }
        assertEquals((1..51).map { "$it." }, drawn)
    }

    @Test
    fun `a list that starts at three counts from three`() {
        val blocks = parseMarkdown("3. a\n4. b\n5. c")
        assertEquals(3, (blocks.single() as MdBlock.Bullet).start)
        assertEquals("3. 4. 5.", markers(blocks))
    }

    @Test
    fun `a loose list is two blocks and each keeps its own number`() {
        // 空行分隔（loose）＝ 两个列表：第二段从它自己写的 2 开始，而不是被重新从 1 数。
        val tight = parseMarkdown("1. a\n2. b")
        val loose = parseMarkdown("1. a\n\n2. b")
        assertEquals(1, tight.size)
        assertEquals(2, loose.size)
        assertEquals("1. 2.", markers(tight))
        assertEquals("1. 2.", markers(loose))
    }

    @Test
    fun `only the first number of a run decides the counting`() {
        // CommonMark 同样忽略后续项的字面数字：1. / 7. 是一个列表，序号 1、2。
        val blocks = parseMarkdown("1. a\n7. b")
        assertEquals(1, blocks.size)
        assertEquals("1. 2.", markers(blocks))
    }

    @Test
    fun `a paren marker is the same list and keeps its start`() {
        val blocks = parseMarkdown("1) a\n2) b")
        assertEquals(1, blocks.size)
        assertEquals("1. 2.", markers(blocks))
        assertEquals("7. 8.", markers(parseMarkdown("7) a\n8) b")))
    }

    @Test
    fun `a nested ordered list keeps its own start`() {
        val blocks = parseMarkdown("1. a\n   3. b\n   4. c")
        val outer = blocks.single() as MdBlock.Bullet
        val inner = outer.children.getValue(0).single()
        assertEquals(0, outer.depth)
        assertEquals(1, inner.depth)
        assertEquals(3, inner.start)
        assertEquals("1.", markers(listOf(outer)))
        assertEquals("3. 4.", markers(listOf(inner)))
    }

    @Test
    fun `outer numbering continues across nested ordered and unordered lists`() {
        val outer = parseMarkdown("3. a\n  7. b\n  8. c\n  - note\n4. d\n5. e").single() as MdBlock.Bullet
        assertEquals(listOf("a", "d", "e"), outer.items)
        assertEquals("3. 4. 5.", markers(listOf(outer)))
        val nested = outer.children.getValue(0)
        assertEquals(2, nested.size)
        assertEquals("7. 8.", markers(listOf(nested.first())))
        assertEquals(listOf("note"), nested.last().items)
        assertEquals(false, nested.last().ordered)
    }

    @Test
    fun `children at several levels stay with the item before them`() {
        val outer = parseMarkdown("- a\n  - a1\n    - a11\n  - a2\n- b\n  - b1\n- c").single() as MdBlock.Bullet
        assertEquals(listOf("a", "b", "c"), outer.items)
        assertEquals(setOf(0, 1), outer.children.keys)
        val aChildren = outer.children.getValue(0).single()
        assertEquals(listOf("a1", "a2"), aChildren.items)
        assertEquals(listOf("a11"), aChildren.children.getValue(0).single().items)
        assertEquals(2, aChildren.children.getValue(0).single().depth)
        assertEquals(listOf("b1"), outer.children.getValue(1).single().items)
    }

    @Test
    fun `different list kinds at the same level remain separate lists`() {
        val blocks = parseMarkdown("- bullet\n  - child\n7. ordered\n8. next")
        assertEquals(2, blocks.size)
        assertEquals(false, (blocks.first() as MdBlock.Bullet).ordered)
        assertEquals("7. 8.", markers(listOf(blocks.last())))
    }

    @Test
    fun `a start too wide for the marker column is clamped`() {
        // 22dp 的序号列放不下更宽的数字，而 Int 也存不下任意长的数字串：两种都退回 1。
        assertEquals(9999, listStart("12345"))
        assertEquals(1, listStart("99999999999999999999"))
        assertEquals(9999, (parseMarkdown("12345. a").single() as MdBlock.Bullet).start)
        assertEquals(1, (parseMarkdown("99999999999999999999. a").single() as MdBlock.Bullet).start)
        // 0. 是 CommonMark 允许的起点，序号就是 0、1、2。
        assertEquals("0. 1.", markers(parseMarkdown("0. a\n0. b")))
    }
}
