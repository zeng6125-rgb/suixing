package com.suixing.agent.ui.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 助手回复里的图片语法（用户：「助手的回复可以加上图片显示」）。
 *
 * 解析抽成纯函数就是为了这里：Compose 里画成什么样测不了，但"哪一段算图、哪一段还是文字"
 * 可以逐条钉住。
 */
class ImageMarkdownTest {

    @Test
    fun `plain prose is a single text segment`() {
        assertEquals(
            listOf(MessageSegment.Text("你好，世界")),
            splitImageSegments("你好，世界"),
        )
    }

    @Test
    fun `an http image splits the text around it`() {
        val segments = splitImageSegments("看这张：\n![截图](https://example.com/a.png)\n就这样。")
        assertEquals(
            listOf(
                MessageSegment.Text("看这张：\n"),
                MessageSegment.Image("截图", "https://example.com/a.png"),
                MessageSegment.Text("\n就这样。"),
            ),
            segments,
        )
    }

    @Test
    fun `a data url is an image too`() {
        val url = "data:image/png;base64,AAAA"
        val segments = splitImageSegments("![x]($url)")
        assertEquals(listOf(MessageSegment.Image("x", url)), segments)
    }

    @Test
    fun `several images keep their order`() {
        val segments = splitImageSegments(
            "![a](https://e.com/1.png) 与 ![b](https://e.com/2.png)",
        )
        assertEquals(2, segments.count { it is MessageSegment.Image })
        assertEquals(
            listOf("https://e.com/1.png", "https://e.com/2.png"),
            segments.filterIsInstance<MessageSegment.Image>().map { it.url },
        )
        assertEquals(MessageSegment.Text(" 与 "), segments[1])
    }

    @Test
    fun `a relative path is not turned into an image`() {
        val text = "![x](./local.png)"
        assertEquals(listOf(MessageSegment.Text(text)), splitImageSegments(text))
    }

    @Test
    fun `an unclosed image stays text`() {
        val text = "![x](https://e.com/a.png"
        assertEquals(listOf(MessageSegment.Text(text)), splitImageSegments(text))
    }

    @Test
    fun `markdown parked inside a fenced block stays text`() {
        val text = "```\n![x](https://e.com/a.png)\n```"
        assertEquals(listOf(MessageSegment.Text(text)), splitImageSegments(text))
    }

    @Test
    fun `text after a fenced block is scanned again`() {
        val segments = splitImageSegments("```\n![x](https://e.com/a.png)\n```\n![y](https://e.com/b.png)")
        assertEquals(1, segments.count { it is MessageSegment.Image })
        assertEquals("https://e.com/b.png", segments.filterIsInstance<MessageSegment.Image>().single().url)
    }

    @Test
    fun `only http https and data urls are renderable`() {
        assertTrue(isRenderableImageUrl("https://e.com/a.png"))
        assertTrue(isRenderableImageUrl("http://e.com/a.png"))
        assertTrue(isRenderableImageUrl("DATA:IMAGE/PNG;base64,AAAA"))
        assertTrue(!isRenderableImageUrl("file:///sdcard/a.png"))
        assertTrue(!isRenderableImageUrl("./a.png"))
    }

    @Test
    fun `base64 payload is extracted from a data url`() {
        assertEquals("AAAA", base64PayloadOf("data:image/jpeg;base64,AAAA"))
        assertNull(base64PayloadOf("https://e.com/a.png"))
        assertNull(base64PayloadOf("data:image/png,notbase64"))
    }
}
