package com.suixing.agent.ui.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTailAppendTest {
    @Test fun `closing a table row and adding another never reparses old cells`() {
        val live = LiveBlocks()
        val source = "| left | right |\n| --- | --- |\n" + "| a | b |\n".repeat(1_000) + "| last | value |"
        val before = live.blocksFor(source, 1, 5).single() as MdBlock.Table
        var checks = 0
        val nextSource = source + "\n| next | row |\n"
        val after = live.blocksFor(nextSource, 2, 5) { checks++ }.single() as MdBlock.Table
        assertEquals(parseMarkdown(nextSource).single(), after)
        assertTrue("rescanned old rows at a row boundary: $checks checkpoints", checks <= 4)
        for (index in before.rows.indices) assertSame(before.rows[index], after.rows[index])
        val next = nextSource + "| more | values"
        val final = live.blocksFor(next, 3, 5).single() as MdBlock.Table
        assertEquals(parseMarkdown(next).single(), final)
        for (index in after.rows.indices) assertSame(after.rows[index], final.rows[index])
        val completedSource = next + "\n| finished | row |\n"
        val completed = live.blocksFor(completedSource, 4, 5).single() as MdBlock.Table
        assertEquals(parseMarkdown(completedSource).single(), completed)
        // Both results already reference the append-only prefix; a later completion
        // must not grow their published row counts or replace their partial final row.
        assertEquals(parseMarkdown(nextSource).single(), after)
        assertEquals(parseMarkdown(next).single(), final)
        assertEquals(parseMarkdown(source).single(), before)
    }

    @Test fun `only the growing table row is reparsed and previous snapshots remain unchanged`() {
        val live = LiveBlocks()
        val source = "| left | right |\n| --- | --- |\n" + "| a | b |\n".repeat(1_000) + "| last | value"
        val before = live.blocksFor(source, 1, 5).single() as MdBlock.Table
        var checks = 0
        val after = live.blocksFor(source + " updated |", 1, 5) { checks++ }.single() as MdBlock.Table
        assertEquals(parseMarkdown(source + " updated |").single(), after)
        assertTrue("rescanned the old table: $checks checkpoints", checks <= 1)
        assertSame(before.header, after.header)
        for (index in 0 until before.rows.lastIndex) assertSame(before.rows[index], after.rows[index])
        assertEquals(parseMarkdown(source).single(), before)
    }

    @Test fun `an ordinary long paragraph continues without entering the block parser again`() {
        val live = LiveBlocks()
        val source = "intro\n\n" + "word ".repeat(2_000)
        val first = live.blocksFor(source, 1, 5)
        var checks = 0
        val next = live.blocksFor(source + "more", 1, 5) { checks++ }
        assertEquals(parseMarkdown(source + "more"), next)
        assertSame(first.first(), next.first())
        assertTrue("re-entered the parser: $checks checkpoints", checks <= 1)
        assertEquals(parseMarkdown(source), first)
    }

    @Test fun `a growing fenced code body checks new lines rather than the existing body`() {
        val live = LiveBlocks()
        val source = "intro\n\n```kt\n" + "val x = 1\n".repeat(1_000) + "last"
        live.blocksFor(source, 1, 5)
        var checks = 0
        val complete = source + " line\nnext line"
        assertEquals(parseMarkdown(complete), live.blocksFor(complete, 2, 5) { checks++ })
        assertTrue("rescanned the old code body: $checks checkpoints", checks <= 4)
    }

    @Test fun `every prefix preserves block transitions whitespace and closing fences`() {
        for (source in listOf(
            "plain  text  with spaces\ncontinued\n\n# heading",
            "```kt\nfirst\n  ``\nsecond\n```\nend",
            "```sh\nfirst\n\n  ```  \nend",
            "| a | b |\n| --- | --- |\n| c | d |",
            "| a | b |\n| --- | --- |\nc | d\nlast | value\n\nafter",
            "| a | b |\n| --- | --- |\n  \nplain\n\nnext",
            "---\n1. list\n  - nested\n\n: text",
            "a\r\nb\r\n\r\n```\r\nc\r\n```",
        )) {
            val live = LiveBlocks()
            for (end in 1..source.length) {
                val prefix = source.take(end)
                assertEquals("prefix=$prefix", parseMarkdown(prefix), live.blocksFor(prefix, end, 5))
            }
        }
    }

    @Test fun `rewrites and source changes cannot reuse an append-only tail`() {
        val live = LiveBlocks()
        for ((version, source) in listOf(
            "plain tail", "# rewritten", "new plain tail", "new plain\rtail", "short", "```kt\nx", "```kt\n```",
        ).withIndex()) {
            assertEquals(parseMarkdown(source), live.blocksFor(source, version, 0))
        }
        live.blocksFor("old paragraph", 1, 8)
        assertEquals(parseMarkdown("new paragraph"), live.blocksFor("new paragraph", 2, 9))
        live.blocksFor("alpha\n\nbeta\n\n" + "long tail ".repeat(100), 5, 8)
        val shorter = "rewritten\n\nnew paragraph longer than the old stable prefix"
        assertEquals(parseMarkdown(shorter), live.blocksFor(shorter, 6, 8))
    }
}
