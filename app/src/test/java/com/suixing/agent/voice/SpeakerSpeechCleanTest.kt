package com.suixing.agent.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerSpeechCleanTest {
    @Test
    fun `cleanMarkdownForSpeech strips code, images, links, headings, lists, emphasis, tables, quotes, urls`() {
        val input = """
            ### 标题
            
            - 列表 **粗体** 和 *斜体* 以及 ~~删除线~~
            1. 有序 `code`
            > 引用 ![图](a.png) [链接文本](https://x.com)
            ```kotlin
            val x = 1
            ```
            | a | b |
            | - | - |
            | 1 | 2 |
            访问 https://example.com/foo  结束
        """.trimIndent()
        val cleaned = cleanMarkdownForSpeech(input)
        assertTrue(cleaned.contains("标题"))
        assertTrue(cleaned.contains("粗体"))
        assertTrue(cleaned.contains("斜体"))
        assertTrue(cleaned.contains("删除线"))
        assertTrue(cleaned.contains("链接文本"))
        assertTrue(cleaned.contains("代码块省略"))
        assertTrue(cleaned.contains("表格省略"))
        assertFalse(cleaned.contains("val x = 1"))
        assertFalse(cleaned.contains("https://"))
        assertFalse(cleaned.contains("**"))
        assertFalse(cleaned.contains("~~"))
    }

    @Test
    fun `cleanMarkdownForSpeech truncates to 4000 characters`() {
        val long = "word ".repeat(1200)
        val cleaned = cleanMarkdownForSpeech(long)
        assertEquals(4000, cleaned.length)
    }
}
