package com.suixing.agent.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserMessageTextTest {
    private val highlight = Color(0xFF304050)

    @Test
    fun `markdown markers escapes and whitespace remain literal`() {
        val source = "  *x* **bold** _name_ ~~strike~~ `code` \\*escaped\\*\r\n1. do this\n> quote\t"

        val result = annotatedUserMessage(source, highlight)

        assertEquals(source, result.text)
        assertTrue(result.spanStyles.isEmpty())
        assertTrue(result.getLinkAnnotations(0, result.length).isEmpty())
    }

    @Test
    fun `a bare url is underlined and uses the default browser without consuming punctuation`() {
        val source = "see https://x.dev/a. next"

        val result = annotatedUserMessage(source, highlight)

        assertEquals(source, result.text)
        val link = result.getLinkAnnotations(0, result.length).single()
        assertEquals(4 to 19, link.start to link.end)
        val target = link.item as LinkAnnotation.Url
        assertEquals("https://x.dev/a", target.url)
        assertNull(target.linkInteractionListener)
        val style = result.spanStyles.single()
        assertEquals(4 to 19, style.start to style.end)
        assertEquals(highlight, style.item.color)
        assertEquals(TextDecoration.Underline, style.item.textDecoration)
    }

    @Test
    fun `url boundaries preserve Unicode ports queries and balanced punctuation`() {
        val cases = listOf(
            Triple("see (https://example.test/a_(b)).", "https://example.test/a_(b)", 5 to 31),
            Triple("😀 https://例子.测试:8443/路径?q=猫&lang=zh#段!", "https://例子.测试:8443/路径?q=猫&lang=zh#段", 3 to 38),
            Triple("open \"http://127.0.0.1:8080/a?q=x&n=1\", next", "http://127.0.0.1:8080/a?q=x&n=1", 6 to 37),
        )

        for ((source, target, range) in cases) {
            val result = annotatedUserMessage(source, highlight)

            assertEquals(source, result.text)
            val link = result.getLinkAnnotations(0, result.length).single()
            assertEquals(source, range, link.start to link.end)
            assertEquals(target, (link.item as LinkAnnotation.Url).url)
            val style = result.spanStyles.single()
            assertEquals(range, style.start to style.end)
            assertEquals(highlight, style.item.color)
            assertEquals(TextDecoration.Underline, style.item.textDecoration)
        }
    }

    @Test
    fun `brackets angles and backticks stay literal while their HTTP targets are linked`() {
        val source = "[docs](https://x.dev) and <https://y.dev> `https://z.dev`"

        val result = annotatedUserMessage(source, highlight)

        assertEquals(source, result.text)
        val links = result.getLinkAnnotations(0, result.length)
        assertEquals(listOf(7 to 20, 27 to 40, 43 to 56), links.map { it.start to it.end })
        assertEquals(listOf("https://x.dev", "https://y.dev", "https://z.dev"),
            links.map { (it.item as LinkAnnotation.Url).url })
        assertEquals(links.map { it.start to it.end }, result.spanStyles.map { it.start to it.end })
        assertTrue(result.spanStyles.all { it.item.fontStyle == null && it.item.fontWeight == null })
    }

    @Test
    fun `all canonical Android roots use file click targets without underlining`() {
        val paths = listOf(
            "/sdcard/Download/报告.pdf",
            "/storage/emulated/0/Documents/a.txt",
            "/storage/self/primary/notes.md",
            "/data/data/com.suixing.agent/files/a.txt",
            "/data/local/tmp/out.log",
            "/data/user/0/com.suixing.agent/files/note.md",
        )

        for (path in paths) {
            val clicked = mutableListOf<String>()
            val source = "文件在${path}，继续"
            val result = annotatedUserMessage(source, highlight, onPathClick = { clicked += it })

            assertEquals(source, result.text)
            assertTrue(clicked.isEmpty())
            val link = result.getLinkAnnotations(0, result.length).single()
            assertEquals(3 to (3 + path.length), link.start to link.end)
            val target = link.item as LinkAnnotation.Clickable
            assertEquals("file:$path", target.tag)
            val style = result.spanStyles.single()
            assertEquals(link.start to link.end, style.start to style.end)
            assertEquals(highlight, style.item.color)
            assertNull(style.item.textDecoration)
            target.linkInteractionListener!!.onClick(target)
            assertEquals(listOf(path), clicked)
        }
    }

    @Test
    fun `a path without a handler is colored but has no inert clickable annotation`() {
        val source = "open /sdcard/a.txt."

        val result = annotatedUserMessage(source, highlight)

        assertEquals(source, result.text)
        assertTrue(result.getLinkAnnotations(0, result.length).isEmpty())
        val style = result.spanStyles.single()
        assertEquals(5 to 18, style.start to style.end)
        assertEquals(highlight, style.item.color)
        assertNull(style.item.textDecoration)
    }

    @Test
    fun `path marker characters quotes and trailing slashes retain original source`() {
        val cases = listOf(
            Triple("/sdcard/folder_with*marks*/a.txt", "/sdcard/folder_with*marks*/a.txt", 0),
            Triple("\"/data/local/tmp/a.log\"", "/data/local/tmp/a.log", 1),
            Triple("/sdcard/Download/", "/sdcard/Download", 0),
        )

        for ((source, path, start) in cases) {
            val result = annotatedUserMessage(source, highlight, onPathClick = {})

            assertEquals(source, result.text)
            val link = result.getLinkAnnotations(0, result.length).single()
            assertEquals("file:$path", (link.item as LinkAnnotation.Clickable).tag)
            assertEquals(start to (start + path.length), link.start to link.end)
            val style = result.spanStyles.single()
            assertEquals(link.start to link.end, style.start to style.end)
            assertNull(style.item.fontStyle)
            assertNull(style.item.fontWeight)
            assertNull(style.item.textDecoration)
        }
    }

    @Test
    fun `goal and tool prefixes highlight only the accepted leading command token`() {
        val cases = listOf(
            Triple("/goal", 0, 5),
            Triple("/goal finish *literal*", 0, 5),
            Triple("  /tool arguments", 2, 7),
            Triple("\r\n/WEB_SEARCH\tquery", 2, 13),
            Triple("/tool_2 run", 0, 7),
        )

        for ((source, start, end) in cases) {
            val result = annotatedUserMessage(source, highlight)

            assertEquals(source, result.text)
            assertTrue(result.getLinkAnnotations(0, result.length).isEmpty())
            val style = result.spanStyles.single()
            assertEquals(start to end, style.start to style.end)
            assertEquals(highlight, style.item.color)
            assertNull(style.item.fontWeight)
            assertNull(style.item.fontStyle)
            assertNull(style.item.textDecoration)
        }
    }

    @Test
    fun `slashes in prose relative paths unsupported roots and malformed commands stay plain`() {
        val sources = listOf(
            "", " \r\n\t", "/", "/ tool", "hello /goal ship", "/goal/path", "/goal.md",
            "//goal", "/tool?query=a", "/1tool", "/data/notes.md", "path/to/file",
            "in/sdcard/a.txt", "7/sdcard/a.txt", "_/sdcard/a.txt", "/other/sdcard/a.txt",
            "ftp://example.test/sdcard/a.txt",
        )

        for (source in sources) {
            val result = annotatedUserMessage(source, highlight, onPathClick = {})

            assertEquals(source, result.text)
            assertTrue(source, result.spanStyles.isEmpty())
            assertTrue(source, result.getLinkAnnotations(0, result.length).isEmpty())
        }
    }

    @Test
    fun `a URL containing an Android root never nests a path annotation or command span`() {
        val source = "https://x.dev/sdcard/a.txt"

        val result = annotatedUserMessage(source, highlight, onPathClick = {})

        assertEquals(source, result.text)
        val link = result.getLinkAnnotations(0, result.length).single()
        assertEquals(0 to source.length, link.start to link.end)
        assertEquals(source, (link.item as LinkAnnotation.Url).url)
        val style = result.spanStyles.single()
        assertEquals(0 to source.length, style.start to style.end)
        assertEquals(TextDecoration.Underline, style.item.textDecoration)
    }

    @Test
    fun `Chinese adjacent URLs and parenthesized file targets keep correct boundaries`() {
        val urlSource = "打开https://example.com"
        val url = annotatedUserMessage(urlSource, highlight).getLinkAnnotations(0, urlSource.length).single()
        assertEquals(2 to 21, url.start to url.end)
        assertEquals("https://example.com", (url.item as LinkAnnotation.Url).url)
        for (path in listOf("/sdcard/a.txt", "/sdcard/folder_(b)/a.txt")) {
            val source = "[docs]($path)"
            val result = annotatedUserMessage(source, highlight, onPathClick = {})
            assertEquals(source, result.text)
            val link = result.getLinkAnnotations(0, result.length).single()
            assertEquals(7 to (7 + path.length), link.start to link.end)
            assertEquals("file:$path", (link.item as LinkAnnotation.Clickable).tag)
        }
        assertTrue(annotatedUserMessage("abchttps://example.com", highlight).getLinkAnnotations(0, 22).isEmpty())
    }

    @Test
    fun `adding a command span preserves URL and path annotations in its argument`() {
        val source = "/goal read https://x.dev and /sdcard/a.txt."
        val clicked = mutableListOf<String>()

        val result = annotatedUserMessage(source, highlight, onPathClick = { clicked += it })

        assertEquals(source, result.text)
        val links = result.getLinkAnnotations(0, result.length)
        assertEquals(listOf(11 to 24, 29 to 42), links.map { it.start to it.end })
        assertEquals("https://x.dev", (links[0].item as LinkAnnotation.Url).url)
        val path = links[1].item as LinkAnnotation.Clickable
        assertEquals("file:/sdcard/a.txt", path.tag)
        path.linkInteractionListener!!.onClick(path)
        assertEquals(listOf("/sdcard/a.txt"), clicked)
        val styles = result.spanStyles.sortedBy { it.start }
        assertEquals(listOf(0 to 5, 11 to 24, 29 to 42), styles.map { it.start to it.end })
        assertTrue(styles.all { it.item.color == highlight })
        assertNull(styles[0].item.textDecoration)
        assertEquals(TextDecoration.Underline, styles[1].item.textDecoration)
        assertNull(styles[2].item.textDecoration)
    }
}
