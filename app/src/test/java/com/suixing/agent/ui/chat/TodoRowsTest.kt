package com.suixing.agent.ui.chat

import com.suixing.agent.data.TODO_COMPLETED
import com.suixing.agent.data.TODO_IN_PROGRESS
import com.suixing.agent.data.TODO_PENDING
import com.suixing.agent.data.TodoItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which board rows the strip above the composer draws.
 *
 * The strip is three rows tall, so a plan longer than that has to choose. It shows what is
 * *left*: finished items leave as they finish (the user asked for exactly that — 创建、完成、
 * 消失), and the tally on the first row is what says how far along the whole board is. Showing
 * the list from the top instead would park the item being worked on below the fold as soon as a
 * plan grew past three, which is the one row the strip exists for.
 */
class TodoRowsTest {

    private fun item(id: String, status: String) = TodoItem(id = id, subject = "步骤 $id", status = status)

    @Test
    fun `a short board is drawn whole`() {
        val board = listOf(item("t1", TODO_PENDING), item("t2", TODO_IN_PROGRESS))
        assertEquals(board, todoRows(board))
    }

    @Test
    fun `finished items leave the strip as they finish`() {
        val board = listOf(
            item("t1", TODO_COMPLETED),
            item("t2", TODO_COMPLETED),
            item("t3", TODO_IN_PROGRESS),
            item("t4", TODO_PENDING),
        )
        assertEquals(listOf("t3", "t4"), todoRows(board).map { it.id })
    }

    /** The item being worked on is never the one that gets cut. */
    @Test
    fun `the open items are drawn in board order, capped`() {
        val board = listOf(
            item("t1", TODO_COMPLETED),
            item("t2", TODO_PENDING),
            item("t3", TODO_IN_PROGRESS),
            item("t4", TODO_PENDING),
            item("t5", TODO_PENDING),
        )
        assertEquals(listOf("t2", "t3", "t4"), todoRows(board).map { it.id })
    }

    /** Everything done: the last few keep their place, so the tally has something to sit on. */
    @Test
    fun `a finished board shows its last few items`() {
        val board = (1..5).map { item("t$it", TODO_COMPLETED) }
        assertEquals(listOf("t3", "t4", "t5"), todoRows(board).map { it.id })
    }

    @Test
    fun `a finished board shorter than the strip is drawn whole`() {
        val board = listOf(item("t1", TODO_COMPLETED), item("t2", TODO_COMPLETED))
        assertEquals(board, todoRows(board))
    }

    @Test
    fun `an empty board draws nothing`() {
        assertEquals(emptyList<TodoItem>(), todoRows(emptyList()))
    }

    /** A strip with no room in it draws nothing rather than everything. */
    @Test
    fun `a zero limit draws nothing`() {
        assertEquals(emptyList<TodoItem>(), todoRows(listOf(item("t1", TODO_PENDING)), limit = 0))
    }
}
