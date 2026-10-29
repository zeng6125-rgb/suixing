package com.suixing.agent.ui.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MarkdownBlockKeysTest {
    @Test fun `inserting an earlier block preserves later settled state identities`() {
        val first = MdBlock.Paragraph("first")
        val second = MdBlock.Paragraph("second")
        val tail = MdBlock.Paragraph("growing")
        val before = markdownBlockKeys(listOf(first, second, tail))
        val after = markdownBlockKeys(listOf(MdBlock.Heading(1, "intro"), first, second, tail))
        assertEquals(before[0], after[1])
        assertEquals(before[1], after[2])
        assertEquals(before[2], after[3])
    }

    @Test fun `repeated blocks never share a key including the tail`() {
        val block = MdBlock.Paragraph("same")
        val keys = markdownBlockKeys(List(4) { block })
        assertEquals(4, keys.toSet().size)
    }

    @Test fun `appending text preserves the active block state`() {
        val prefix = MdBlock.Paragraph("settled")
        val before = markdownBlockKeys(listOf(prefix, MdBlock.Paragraph("one")))
        val after = markdownBlockKeys(listOf(prefix, MdBlock.Paragraph("one two")))
        assertEquals(before, after)
    }

    @Test fun `changing tail kind gives it independent state`() {
        val before = markdownBlockKeys(listOf(MdBlock.Paragraph("text")))
        val after = markdownBlockKeys(listOf(MdBlock.Heading(1, "text")))
        assertNotEquals(before.single(), after.single())
    }

    @Test fun `a growing table preserves its viewport when later prose settles it`() {
        val first = MdBlock.Table(listOf("a", "b"), listOf(listOf("1", "2")))
        val grown = first.copy(rows = first.rows + listOf(listOf("3", "4")))
        val tailKey = markdownBlockKeys(listOf(first)).single()
        assertEquals(tailKey, markdownBlockKeys(listOf(grown)).single())
        assertEquals(tailKey, markdownBlockKeys(listOf(grown, MdBlock.Paragraph("after"))).first())
        val duplicates = markdownBlockKeys(listOf(first, grown, first))
        assertEquals(3, duplicates.toSet().size)
    }

    @Test fun `table key calculation never reads body cells`() {
        val rows = object : AbstractList<List<String>>() {
            override val size = 10_000
            override fun get(index: Int): List<String> = error("table body must not be hashed for keys")
        }
        val table = MdBlock.Table(listOf("a", "b"), rows)
        val first = markdownBlockKeys(listOf(table, MdBlock.Paragraph("tail")))
        val second = markdownBlockKeys(listOf(table, MdBlock.Paragraph("tail changes")))
        assertEquals(first, second)
    }
}
