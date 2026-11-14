package com.suixing.agent.ui.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownBoundaryTest {
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

    @Test fun `equal length prefix replacements invalidate both parsers`() {
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
