package com.suixing.agent.ui.components

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownBoundaryTest {
    private fun previousBoundary(text: String): String {
        var from = text.length
        while (true) {
            val at = text.lastIndexOf("\n\n", from - 1)
            if (at < 0) return ""
            val candidate = text.substring(0, at + 2)
            if (candidate.count { it == '`' } % 2 == 0) return candidate
            from = at
        }
    }

    @Test fun `linear boundary preserves previous parity semantics`() {
        val random = Random(50)
        val alphabet = charArrayOf('a', 'b', '`', '\n', ' ')
        repeat(2_000) {
            val text = buildString { repeat(random.nextInt(200)) { append(alphabet.random(random)) } }
            assertEquals(previousBoundary(text), settledMarkdownPrefix(text))
        }
    }

    @Test fun `streaming fenced code keeps internal blank lines in one block`() {
        val text = "intro\n\n```kotlin\nval x = 1\n\nval y = 2\n```\n\nafter"
        val parser = LiveBlocks()
        for (end in 0..text.length) {
            assertEquals("prefix $end", parseMarkdown(text.take(end)), parser.blocksFor(text.take(end)))
        }
    }

    @Test fun `streaming CRLF agrees with full parsing`() {
        val text = "intro\r\n\r\n```\r\na\r\n\r\nb\r\n```\r\n\r\nafter"
        val parser = LiveBlocks()
        for (end in 0..text.length) {
            assertEquals("prefix $end", parseMarkdown(text.take(end)), parser.blocksFor(text.take(end)))
        }
    }

    @Test fun `replacing a settled prefix drops cached blocks`() {
        val parser = LiveBlocks()
        parser.blocksFor("old\n\nparagraph")
        assertEquals(parseMarkdown("new"), parser.blocksFor("new"))
        assertEquals(parseMarkdown("new\n\nlast"), parser.blocksFor("new\n\nlast"))
    }

    @Test fun `equal length prefix replacements invalidate both incremental parsers`() {
        val live = LiveBlocks()
        val settled = MarkdownCache()
        for (text in listOf("old\n\ntail", "new\n\ntail", "new\n\nlonger tail", "", "end")) {
            assertEquals(parseMarkdown(text), live.blocksFor(text))
            assertEquals(parseMarkdown(text), settled.blocksFor(text))
        }
    }

    @Test fun `same length hash collisions never reuse the previous document or prefix`() {
        val live = LiveBlocks()
        val settled = MarkdownCache()
        val old = "Aa\n\ntail"
        val replacement = "BB\n\ntail"
        assertEquals(old.length, replacement.length)
        assertEquals(old.hashCode(), replacement.hashCode())
        for (text in listOf(old, replacement, replacement + " grows", old + " grows")) {
            assertEquals(parseMarkdown(text), live.blocksFor(text))
            assertEquals(parseMarkdown(text), settled.blocksFor(text))
        }
    }

    @Test fun `shrinking exactly to the completed boundary retains only that prefix`() {
        val live = LiveBlocks()
        live.blocksFor("first\n\nlast")
        assertEquals(parseMarkdown("first\n\n"), live.blocksFor("first\n\n"))
        assertEquals(parseMarkdown("first\n\nnew"), live.blocksFor("first\n\nnew"))
        assertEquals(emptyList<MdBlock>(), live.blocksFor(""))
    }
}
