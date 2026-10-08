package com.suixing.agent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TodoStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store() = TodoStore(folder.newFile("todos.json"))

    @Test
    fun `writes and reads the board back`() {
        val store = store()
        store.write(
            listOf(
                TodoItem(id = "", subject = "读契约", status = TODO_COMPLETED),
                TodoItem(id = "", subject = "写工具", status = TODO_IN_PROGRESS),
            ),
        )

        val read = store.read()
        assertEquals(2, read.size)
        assertEquals("读契约", read[0].subject)
        assertEquals(TODO_COMPLETED, read[0].status)
        assertEquals(TODO_IN_PROGRESS, read[1].status)
    }

    @Test
    fun `missing file reads as an empty board`() {
        assertTrue(TodoStore(java.io.File(folder.root, "absent.json")).read().isEmpty())
    }

    /** Every write is the whole list: the second call is what the board says, not a merge. */
    @Test
    fun `a second write replaces the first`() {
        val store = store()
        store.write(listOf(TodoItem("t1", "旧的一步", TODO_IN_PROGRESS)))
        store.write(listOf(TodoItem("t1", "新的一步", TODO_COMPLETED)))

        val read = store.read()
        assertEquals(1, read.size)
        assertEquals("新的一步", read[0].subject)
        assertEquals(TODO_COMPLETED, read[0].status)
    }

    @Test
    fun `an empty list clears the board`() {
        val store = store()
        store.write(listOf(TodoItem("t1", "会被清掉", TODO_PENDING)))
        store.write(emptyList())

        assertTrue(store.read().isEmpty())
    }

    /** Ids are what the model refers to when it reports progress, so they must be stable. */
    @Test
    fun `blank ids are numbered in list order`() {
        val store = store()
        val stored = store.write(
            listOf(
                TodoItem("", "第一步", TODO_PENDING),
                TodoItem("keep-me", "第二步", TODO_PENDING),
                TodoItem("", "第三步", TODO_PENDING),
            ),
        )

        assertEquals(listOf("t1", "keep-me", "t3"), stored.map { it.id })
        assertEquals(listOf("t1", "keep-me", "t3"), store.read().map { it.id })
    }
}
