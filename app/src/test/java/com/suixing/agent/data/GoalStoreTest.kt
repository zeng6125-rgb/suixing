package com.suixing.agent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GoalStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

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