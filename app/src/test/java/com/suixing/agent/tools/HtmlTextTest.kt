package com.suixing.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HTML-to-text step is what decides how many tokens a fetched page costs, so
 * it is worth pinning down without a device.
 */
class HtmlTextTest {

    @Test
    fun `drops scripts and styles`() {
        val html = """
            <html><head>
            <style>body { color: red; }</style>
            <script>var secret = "do not read me";</script>
            </head><body><p>正文在这里</p></body></html>
        """.trimIndent()
        val text = htmlToText(html)
        assertTrue(text.contains("正文在这里"))
        assertFalse(text.contains("do not read me"))
        assertFalse(text.contains("color: red"))
    }

    @Test
    fun `keeps block structure as line breaks`() {
        val html = "<div>第一行</div><div>第二行</div><ul><li>甲</li><li>乙</li></ul>"
        val text = htmlToText(html)
        val lines = text.lines().filter { it.isNotBlank() }
        assertEquals(listOf("第一行", "第二行", "- 甲", "- 乙"), lines)
    }

    @Test
    fun `decodes named and numeric entities`() {
        assertEquals("A & B < C", decodeEntities("A &amp; B &lt; C"))
        assertEquals("中文", decodeEntities("&#20013;&#25991;"))
        assertEquals("中文", decodeEntities("&#x4E2D;&#x6587;"))
        assertEquals("“引号”", decodeEntities("&ldquo;引号&rdquo;"))
    }

    @Test
    fun `leaves unknown entities alone`() {
        assertEquals("&notarealentity;", decodeEntities("&notarealentity;"))
    }

    @Test
    fun `collapses whitespace but keeps paragraphs`() {
        val html = "<p>一</p>\n\n\n\n<p>二</p>"
        val text = htmlToText(html)
        assertFalse(text.contains("\n\n\n"))
        assertEquals("一\n\n二", text)
    }

    @Test
    fun `handles an empty document`() {
        assertEquals("", htmlToText(""))
        assertEquals("", decodeEntities(""))
    }
}
