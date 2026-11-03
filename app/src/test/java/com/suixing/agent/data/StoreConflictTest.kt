package com.suixing.agent.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StoreConflictTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val a = TodoItem(id = "a", subject = "A", status = TODO_PENDING)
    private val b = TodoItem(id = "b", subject = "B", status = TODO_PENDING)

    @Test
    fun `a stale task-screen toggle preserves a task the agent added`() {
        val store = TodoStore(File(folder.root, "todos.json"))
        store.write(listOf(a))
        // The agent writes while the screen still holds [A] from its first read.
        store.write(listOf(a, b))
        // The screen's tap is a targeted mutation; it must read the latest board.
        val stored = store.mutate { board ->
            board.map { if (it.id == a.id) it.copy(status = TODO_COMPLETED) else it }
        }

        assertEquals(listOf("a", "b"), stored.map { it.id })
        assertEquals(TODO_COMPLETED, stored.first { it.id == a.id }.status)
        assertEquals(TODO_PENDING, stored.first { it.id == b.id }.status)
    }

    @Test
    fun `reminder mutation and arm see the same latest board`() = runBlocking {
        val store = ScheduleStore(File(folder.root, "schedules.json"))
        val armed = mutableListOf<List<String>>()
        val writer = ScheduleWriter(store) { armed += store.read().map { it.id } }
        val first = schedule("a", "A")
        val second = schedule("b", "B")
        store.upsert(first)

        writer.mutate { it + second } // agent creates B
        assertTrue(armed.single().containsAll(listOf("a", "b")))

        // A stale UI toggle of A must not rewrite the board it loaded before B existed.
        val stored = writer.mutate { board ->
            board.map { if (it.id == "a") it.copy(enabled = false) else it }
        }

        assertEquals(listOf("a", "b"), stored.map { it.id })
        assertEquals(false, stored.first { it.id == "a" }.enabled)
        assertEquals(true, stored.first { it.id == "b" }.enabled)
        assertEquals(listOf("a", "b"), armed.last())
    }

    @Test
    fun `an arm failure leaves the committed board and the next write re-arms it`() = runBlocking {
        val store = ScheduleStore(File(folder.root, "schedules.json"))
        var failArm = true
        val arms = mutableListOf<List<String>>()
        val writer = ScheduleWriter(store) {
            if (failArm) throw IllegalStateException("no alarm service")
            arms += store.read().map { it.id }
        }
        store.upsert(schedule("a", "A"))

        runCatching { writer.mutate { it + schedule("b", "B") } }
        assertEquals(listOf("a", "b"), store.read().map { it.id })

        failArm = false
        writer.mutate { it } // a later commit re-arms the already-stored board
        assertEquals(listOf("a", "b"), arms.last())
    }

    private fun schedule(id: String, title: String) = Schedule(
        id = id,
        title = title,
        prompt = "prompt $id",
        kind = SCHEDULE_AT,
        atMillis = System.currentTimeMillis() + 60_000,
        nextAt = System.currentTimeMillis() + 60_000,
    )
}