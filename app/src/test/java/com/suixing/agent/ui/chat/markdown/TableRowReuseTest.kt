package com.suixing.agent.ui.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class TableRowReuseTest {
    @Test
    fun `a new parsed snapshot with identical content reuses header and row lists`() {
        val oldHeader = cells("name", "value")
        val oldRows = listOf(cells("alpha", "1"), cells("beta", "2"))
        val before = reuseTableRows(previous = null, header = oldHeader, rows = oldRows)

        val newHeader = cells("name", "value")
        val newRows = listOf(cells("alpha", "1"), cells("beta", "2"))
        val after = reuseTableRows(before, newHeader, newRows)

        assertSame(oldHeader, after.header)
        assertSame(oldRows[0], after.rows[0])
        assertSame(oldRows[1], after.rows[1])
    }

    @Test
    fun `an appended tail row leaves earlier cell list references intact`() {
        val header = cells("name", "value")
        val first = cells("alpha", "1")
        val second = cells("beta", "2")
        val before = reuseTableRows(null, header, listOf(first, second))
        val changedTail = cells("beta", "updated")

        val after = reuseTableRows(before, header, listOf(first, changedTail))

        assertSame(first, after.rows[0])
        assertSame(changedTail, after.rows[1])
        assertEquals(listOf("beta", "updated"), after.rows[1])
    }

    @Test
    fun `inserted rows do not take a moved row reference from a different ordinal`() {
        val header = cells("name")
        val first = cells("first")
        val second = cells("second")
        val before = reuseTableRows(null, header, listOf(first, second))
        val inserted = cells("inserted")
        val movedFirst = cells("first")
        val movedSecond = cells("second")

        val after = reuseTableRows(before, header, listOf(inserted, movedFirst, movedSecond))

        assertEquals(listOf(listOf("inserted"), listOf("first"), listOf("second")), after.rows)
        assertSame(inserted, after.rows[0])
        assertNotSame(first, after.rows[1])
        assertNotSame(second, after.rows[2])
    }

    @Test
    fun `deleted rows do not shift a later row reference into an earlier ordinal`() {
        val header = cells("name")
        val first = cells("first")
        val removed = cells("removed")
        val last = cells("last")
        val before = reuseTableRows(null, header, listOf(first, removed, last))
        val freshLast = cells("last")

        val after = reuseTableRows(before, header, listOf(first, freshLast))

        assertEquals(listOf(listOf("first"), listOf("last")), after.rows)
        assertSame(first, after.rows[0])
        assertNotSame(last, after.rows[1])
    }

    @Test
    fun `reordered rows do not reuse references from their old ordinals`() {
        val header = cells("name")
        val first = cells("first")
        val second = cells("second")
        val unchanged = cells("unchanged")
        val before = reuseTableRows(null, header, listOf(first, second, unchanged))
        val movedSecond = cells("second")
        val movedFirst = cells("first")

        val after = reuseTableRows(before, header, listOf(movedSecond, movedFirst, unchanged))

        assertEquals(listOf(listOf("second"), listOf("first"), listOf("unchanged")), after.rows)
        assertSame(movedSecond, after.rows[0])
        assertSame(movedFirst, after.rows[1])
        assertSame(unchanged, after.rows[2])
    }

    @Test
    fun `repeated equal rows retain separate ordinal references`() {
        val header = cells("name")
        val firstDuplicate = cells("same")
        val secondDuplicate = cells("same")
        val before = reuseTableRows(null, header, listOf(firstDuplicate, secondDuplicate))

        val after = reuseTableRows(before, cells("name"), listOf(cells("same"), cells("same")))

        assertSame(firstDuplicate, after.rows[0])
        assertSame(secondDuplicate, after.rows[1])
        assertNotSame(after.rows[0], after.rows[1])
    }

    @Test
    fun `removed row references are not retained for a later row at that ordinal`() {
        val header = cells("name")
        val retained = cells("retained")
        val removed = cells("removed")
        val before = reuseTableRows(null, header, listOf(retained, removed))
        val afterDelete = reuseTableRows(before, header, listOf(retained))
        val reintroduced = cells("removed")

        val afterAppend = reuseTableRows(afterDelete, header, listOf(retained, reintroduced))

        assertSame(reintroduced, afterAppend.rows[1])
        assertNotSame(removed, afterAppend.rows[1])
    }

    @Test
    fun `hash collisions do not reuse different row contents`() {
        val header = cells("value")
        val oldRow = cells("FB")
        val before = reuseTableRows(null, header, listOf(oldRow))
        val newRow = cells("Ea")
        assertEquals(oldRow[0].hashCode(), newRow[0].hashCode())

        val after = reuseTableRows(before, header, listOf(newRow))

        assertEquals(listOf("Ea"), after.rows.single())
        assertSame(newRow, after.rows.single())
    }

    @Test
    fun `the identical source snapshot returns its existing row references`() {
        val header = cells("name")
        val row = cells("alpha")
        val rows = listOf(row)
        val before = reuseTableRows(null, header, rows)

        val after = reuseTableRows(before, header, rows)

        assertSame(before, after)
        assertSame(row, after.rows.single())
    }

    private fun cells(vararg values: String): List<String> = ArrayList(values.asList())
}
