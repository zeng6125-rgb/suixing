package com.suixing.agent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GoalStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `completed goal is visible until the next user turn while remaining readable`() {
        val goal = Goal(text = "verify", chatId = "owner", status = GoalStatus.COMPLETED, updatedAt = 10)
        assertTrue(goal.showInComposer("owner", latestUserAt = 9))
        assertFalse(goal.showInComposer("owner", latestUserAt = 11))
        assertTrue(goal.visibleIn("owner"))
        assertFalse(goal.showInComposer("other", latestUserAt = 0))
    }

    @Test
    fun `restoring a running goal pauses without losing progress or making it visible in another chat`() {
        val file = File(folder.root, "goal.json")
        val store = GoalStore(file)
        val active = requireNotNull(store.start("owner", "ship it"))
        store.report(active.id, GoalStatus.ACTIVE, "tests passed", wakeAt = 1_000L)
        val restored = GoalStore(file)
        assertEquals(GoalStatus.PAUSED, restored.read().status)
        assertEquals("tests passed", restored.changes.value?.progress)
        assertEquals(0L, restored.read().wakeAt)
        assertFalse(restored.read().visibleIn("other"))
        assertNull(restored.start("other", "unrelated"))
        assertNull(restored.resume("other"))
        assertNull(restored.clearFor("other"))
        val resumed = requireNotNull(restored.resume("owner"))
        assertNotEquals(active.id, resumed.id)
        assertNull(restored.report(active.id, GoalStatus.COMPLETED, "late report", wakeAt = 0L))
        assertEquals(GoalStatus.ACTIVE, restored.read().status)
    }

    @Test
    fun `a goal survives a new store instance`() {
        val file = File(folder.root, "goal.json")
        GoalStore(file).write("  finish the migration  ")

        val restored = GoalStore(file).read()

        assertEquals("finish the migration", restored.text)
        assertTrue(restored.isSet)
    }

    @Test
    fun `empty text clears the goal and keeps the timestamp write`() {
        val store = GoalStore(File(folder.root, "goal.json"))
        store.write("ship it")
        val cleared = store.clear()

        assertFalse(cleared.isSet)
        assertFalse(store.read().isSet)
        assertTrue(cleared.updatedAt > 0L)
    }

    @Test
    fun `a corrupt file is preserved and reads as no goal`() {
        val file = File(folder.root, "goal.json")
        file.writeText("{not json")

        val goal = GoalStore(file).read()

        assertFalse(goal.isSet)
        assertFalse(file.exists())
        assertTrue(File(folder.root, "goal.corrupt.json").exists())
    }
}
