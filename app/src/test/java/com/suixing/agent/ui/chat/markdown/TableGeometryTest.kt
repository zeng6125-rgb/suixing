package com.suixing.agent.ui.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TableGeometryTest {
    @Test fun `equal geometry across a thousand rows reuses arrays and layout keys invalidate it`() {
        val cache = TableColumnCache()
        val columns = cache.get(1_000, 4, 3)
        repeat(1_000) { assertSame(columns, cache.get(1_000, 4, 3)) }
        assertNotSame(columns, cache.get(1_001, 4, 3))
        assertNotSame(columns, cache.get(1_000, 5, 3))
        assertNotSame(columns, cache.get(1_000, 4, 2))
        for (width in 1_100..1_110) cache.get(width, 4, 3)
        assertNotSame(columns, cache.get(1_000, 4, 3))
    }

    @Test fun `weighted widths preserve total width and foundation remainder order`() {
        assertEquals(listOf(32, 33, 33), TableColumns(100, 3, 1).widths.toList())
        assertEquals(listOf(34, 33, 33), TableColumns(100, 3, 0).widths.toList())
        for (width in 0..1_200) for (count in 1..12) for (divider in 0..4) {
            val columns = TableColumns(width, count, divider)
            assertEquals(width, columns.widths.sum() + columns.dividerWidths.sum())
            assertEquals(0, columns.starts.first())
            assertEquals(width, columns.starts.last() + columns.widths.last())
            assertTrue(columns.widths.all { it >= 0 })
            for (index in columns.dividers.indices) {
                assertEquals(columns.starts[index] + columns.widths[index], columns.dividers[index])
                assertEquals(columns.dividers[index] + columns.dividerWidths[index], columns.starts[index + 1])
            }
        }
    }

    @Test fun `rtl separators mirror their full pixel extent rather than just their origin`() {
        val columns = TableColumns(101, 3, 3)
        for (index in columns.dividers.indices) {
            assertEquals(101, columns.dividerX(index, 101, true) + columns.dividerWidths[index] +
                columns.dividerX(index, 101, false))
        }
    }

    @Test fun `empty rows and more separators than available pixels remain bounded`() {
        assertTrue(TableColumns(100, 0, 1).widths.isEmpty())
        val columns = TableColumns(3, 5, 2)
        assertEquals(listOf(0, 0, 0, 0, 0), columns.widths.toList())
        assertEquals(listOf(2, 1, 0, 0), columns.dividerWidths.toList())
        assertEquals(3, columns.starts.last())
    }
}
