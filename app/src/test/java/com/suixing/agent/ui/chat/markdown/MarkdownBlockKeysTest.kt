package com.suixing.agent.ui.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MarkdownBlockKeysTest {
    @Test fun `inserting an earlier block preserves later settled state identities`() {
        val cache = MarkdownBlockKeyCache()
        val first = MdBlock.Paragraph("first")
        val second = MdBlock.Paragraph("second")
        val tail = MdBlock.Paragraph("growing")
        val before = cache.keysFor(listOf(first, second, tail))
        val after = cache.keysFor(listOf(MdBlock.Heading(1, "intro"), first.copy(), second.copy(), tail))
        assertEquals(before[0], after[1])
        assertEquals(before[1], after[2])
        assertEquals(before[2], after[3])
    }

    @Test fun `repeated blocks never share a key including the tail`() {
        val cache = MarkdownBlockKeyCache()
        val block = MdBlock.Paragraph("same")
        val before = cache.keysFor(List(3) { block })
        val keys = cache.keysFor(List(4) { block })
        assertEquals(3, before.size)
        assertEquals(before[0], keys[0])
        assertEquals(before[1], keys[1])
        assertEquals(4, keys.toSet().size)
    }

    @Test fun `appending text preserves the active block state`() {
        val cache = MarkdownBlockKeyCache()
        val prefix = MdBlock.Paragraph("settled")
        val before = cache.keysFor(listOf(prefix, MdBlock.Paragraph("one")))
        val after = cache.keysFor(listOf(prefix, MdBlock.Paragraph("one two")))
        assertEquals(before, after)
    }

    @Test fun `changing tail kind gives it independent state`() {
        val cache = MarkdownBlockKeyCache()
        val before = cache.keysFor(listOf(MdBlock.Paragraph("text")))
        val after = cache.keysFor(listOf(MdBlock.Heading(1, "text")))
        assertNotEquals(before.single(), after.single())
    }

    @Test fun `a growing table preserves its viewport when later prose settles it`() {
        val cache = MarkdownBlockKeyCache()
        val first = MdBlock.Table(listOf("a", "b"), listOf(listOf("1", "2")))
        val grown = first.copy(rows = first.rows + listOf(listOf("3", "4")))
        val tailKey = cache.keysFor(listOf(first)).single()
        assertEquals(tailKey, cache.keysFor(listOf(grown)).single())
        assertEquals(tailKey, cache.keysFor(listOf(grown, MdBlock.Paragraph("after"))).first())
        val duplicates = cache.keysFor(listOf(first, grown, first))
        assertEquals(3, duplicates.toSet().size)
    }

    @Test fun `table key calculation never reads body cells`() {
        val cache = MarkdownBlockKeyCache()
        val rows = object : AbstractList<List<String>>() {
            override val size = 10_000
            override fun get(index: Int): List<String> = error("table body must not be hashed for keys")
        }
        val table = MdBlock.Table(listOf("a", "b"), rows)
        val first = cache.keysFor(listOf(table, MdBlock.Paragraph("tail")))
        val second = cache.keysFor(listOf(table, MdBlock.Paragraph("tail changes")))
        assertEquals(first, second)
    }

    @Test fun `growing prose does not reread a settled bullet prefix for keys`() {
        val cache = MarkdownBlockKeyCache()
        val items = CountingItems(10_000)
        val prefix = MdBlock.Bullet(items, ordered = false)
        val first = cache.keysFor(listOf(prefix, MdBlock.Paragraph("one")))
        items.reads = 0

        val second = cache.keysFor(listOf(prefix, MdBlock.Paragraph("one two")))

        assertEquals("tail growth must reuse the settled prefix", 0, items.reads)
        assertEquals(first, second)
    }

    @Test fun `hashing a settled key does not reread nested list contents`() {
        val cache = MarkdownBlockKeyCache()
        val items = CountingItems(10_000)
        val child = MdBlock.Bullet(items, ordered = false, depth = 1)
        val prefix = MdBlock.Bullet(
            listOf("parent"),
            ordered = false,
            children = mapOf(0 to listOf(child)),
        )
        val key = cache.keysFor(listOf(prefix, MdBlock.Paragraph("tail"))).first()
        items.reads = 0

        repeat(3) { key.hashCode() }

        assertEquals("Compose key hashing must reuse the settled hash", 0, items.reads)
    }

    @Test fun `shrinking and regrowing a duplicate prefix resets occurrence counts`() {
        val cache = MarkdownBlockKeyCache()
        val block = MdBlock.Paragraph("same")
        val before = cache.keysFor(List(4) { block })

        cache.keysFor(List(2) { block })
        val after = cache.keysFor(List(3) { block })

        assertEquals(before[0], after[0])
        assertEquals(before[1], after[1])
        assertEquals(before.last(), after.last())
        assertEquals(3, after.toSet().size)
    }

    private class CountingItems(override val size: Int) : AbstractList<String>() {
        var reads = 0
        override fun get(index: Int): String {
            reads++
            return "item $index"
        }
    }
}
