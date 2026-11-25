package com.suixing.agent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class IndexedTextLinesTest {
    @Test
    fun `indexed output preserves all line break and blank line cases`() {
        for (output in listOf("", "single", "\r", "\n", "\r\n", "α\r\n\n🙂\r末尾\r\n", "\r\r\n\n\n")) {
            assertEquals(output.lines(), IndexedTextLines(output, universalNewlines = true))
        }
    }

    @Test
    fun `distant rows remain independently accessible after growing the index`() {
        val lines = List(1_000) { "行 $it" }
        val output = IndexedTextLines(lines.joinToString("\r\n"), universalNewlines = true)

        assertEquals(lines.size, output.size)
        assertEquals(lines.last(), output[999])
        assertEquals(lines.first(), output[0])
        assertEquals(lines[511], output[511])
    }

    @Test
    fun `settled reasoning keeps exactly the same rows as the streaming splitter`() {
        val source = buildString {
            append("\r\n\n")
            repeat(10_000) { append("行🙂$it\r内容\n") }
        }
        val indexed = IndexedTextLines(source, universalNewlines = false)
        val streaming = IncrementalLines().linesFor(source)
        assertEquals(streaming, indexed)
        assertEquals("\r", indexed.first())
        assertEquals("行🙂9999\r内容", indexed[indexed.lastIndex - 1])
        assertEquals("", indexed.last())
    }
}
