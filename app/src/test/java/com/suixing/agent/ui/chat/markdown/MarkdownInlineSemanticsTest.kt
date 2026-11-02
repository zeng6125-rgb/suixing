package com.suixing.agent.ui.chat.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownInlineSemanticsTest {
    private val linkColor = Color(0xFF304050)
    private val codeBackground = Color(0xFF102030)
    private val codeColor = Color(0xFF203040)

    private fun inline(text: String) = inlineMarkdown(
        text = text,
        codeBackground = codeBackground,
        codeColor = codeColor,
        linkColor = linkColor,
    )

    @Test
    fun `literal and escaped source keep their rendered text and bold range`() {
        val literal = inline("plain source")
        assertEquals("plain source", literal.text)
        assertEquals(0, literal.spanStyles.size)

        val escaped = inline("raw \\*literal\\*")
        assertEquals("raw *literal*", escaped.text)
        assertEquals(0, escaped.spanStyles.size)

        val escapedOnly = inline("\\*\\_")
        assertEquals("*_", escapedOnly.text)
        assertEquals(0, escapedOnly.spanStyles.size)

        val trailingBackslash = inline("tail\\")
        assertEquals("tail\\", trailingBackslash.text)
        assertEquals(0, trailingBackslash.spanStyles.size)

        val unmatchedMarkers = inline("**unfinished `code")
        assertEquals("**unfinished `code", unmatchedMarkers.text)
        assertEquals(0, unmatchedMarkers.spanStyles.size)

        val escapedInsideBold = inline("**be\\*ta**")
        assertEquals("be*ta", escapedInsideBold.text)
        assertEquals(1, escapedInsideBold.spanStyles.size)
        val bold = escapedInsideBold.spanStyles.single()
        assertEquals(0, bold.start)
        assertEquals(5, bold.end)
        assertEquals(FontWeight.SemiBold, bold.item.fontWeight)
    }

    @Test
    fun `nested italic bold strike and escaped link label retain span and link ranges`() {
        val nested = inline("~~*a **b** c*~~")
        assertEquals("a b c", nested.text)
        assertEquals(3, nested.spanStyles.size)
        assertEquals(listOf(0 to 2, 2 to 3, 3 to 5), nested.spanStyles.map { it.start to it.end })
        assertEquals(FontStyle.Italic, nested.spanStyles[0].item.fontStyle)
        assertEquals(TextDecoration.LineThrough, nested.spanStyles[0].item.textDecoration)
        assertEquals(FontWeight.SemiBold, nested.spanStyles[1].item.fontWeight)
        assertEquals(FontStyle.Italic, nested.spanStyles[1].item.fontStyle)
        assertEquals(TextDecoration.LineThrough, nested.spanStyles[1].item.textDecoration)
        assertEquals(FontStyle.Italic, nested.spanStyles[2].item.fontStyle)
        assertEquals(TextDecoration.LineThrough, nested.spanStyles[2].item.textDecoration)

        val link = inline("**[a\\*b](https://x.dev)**")
        assertEquals("a*b", link.text)
        assertEquals(1, link.spanStyles.size)
        val labelStyle = link.spanStyles.single()
        assertEquals(0, labelStyle.start)
        assertEquals(3, labelStyle.end)
        assertEquals(FontWeight.SemiBold, labelStyle.item.fontWeight)
        assertEquals(linkColor, labelStyle.item.color)
        assertEquals(TextDecoration.Underline, labelStyle.item.textDecoration)
        val annotation = link.getLinkAnnotations(0, link.length).single()
        assertEquals(0, annotation.start)
        assertEquals(3, annotation.end)
        assertTrue(annotation.item is LinkAnnotation.Url)
        assertEquals("https://x.dev", (annotation.item as LinkAnnotation.Url).url)
    }

    @Test
    fun `inline code preserves escaped backtick and bare url annotations`() {
        val code = inline("`a\\`b`")
        assertEquals("\u2009a\\`b\u2009", code.text)
        assertEquals(1, code.spanStyles.size)
        val codeSpan = code.spanStyles.single()
        assertEquals(0, codeSpan.start)
        assertEquals(6, codeSpan.end)
        assertEquals(codeBackground, codeSpan.item.background)
        assertEquals(codeColor, codeSpan.item.color)

        val url = inline("see https://x.dev/a. next")
        assertEquals("see https://x.dev/a. next", url.text)
        assertEquals(1, url.spanStyles.size)
        assertEquals(4 to 19, url.spanStyles.single().let { it.start to it.end })
        val annotation = url.getLinkAnnotations(0, url.length).single()
        assertEquals(4, annotation.start)
        assertEquals(19, annotation.end)
        assertTrue(annotation.item is LinkAnnotation.Url)
        assertEquals("https://x.dev/a", (annotation.item as LinkAnnotation.Url).url)
    }

    @Test
    fun `path nested in bold remains one colored unlinked range`() {
        val path = inline("**/sdcard/a.txt**")
        assertEquals("/sdcard/a.txt", path.text)
        assertEquals(1, path.spanStyles.size)
        val span = path.spanStyles.single()
        assertEquals(0, span.start)
        assertEquals(13, span.end)
        assertEquals(FontWeight.SemiBold, span.item.fontWeight)
        assertEquals(linkColor, span.item.color)
        assertEquals(null, span.item.textDecoration)
        assertTrue(path.getLinkAnnotations(0, path.length).isEmpty())
    }

    @Test
    fun `scanner and plain code append exactly one caret after all annotations`() {
        val source = "**bold** [docs](https://x.dev)"
        val plain = inline(source)
        val withCaret = inlineMarkdownWithCaret(
            text = source,
            codeBackground = codeBackground,
            codeColor = codeColor,
            linkColor = linkColor,
        )
        assertEquals(plain.text + "\u200B", withCaret.text)
        assertEquals(1, withCaret.text.count { it == '\u200B' })
        assertEquals(plain.spanStyles, withCaret.spanStyles)
        assertEquals(plain.getLinkAnnotations(0, plain.length), withCaret.getLinkAnnotations(0, withCaret.length))

        val plainCode = codeBlockAnnotatedText("val x = 1", appendCaret = false)
        assertEquals("val x = 1", plainCode.text)
        val code = codeBlockAnnotatedText("val x = 1", appendCaret = true)
        assertEquals("val x = 1\u200B", code.text)
        assertEquals(1, code.text.count { it == '\u200B' })
        assertTrue(code.spanStyles.isEmpty())
        assertTrue(code.getLinkAnnotations(0, code.length).isEmpty())
    }
}
