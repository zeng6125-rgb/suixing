package com.suixing.agent.data

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [MemoryStore] is the one store the engine tests mock away, so its own file format had no coverage
 * at all: the legacy bare-string form, the quarantine of an unreadable file, the length cap. It
 * takes a Context rather than a File, so the context is a mock and only `filesDir` is real.
 */
class MemoryStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(): MemoryStore {
        val context = mockk<Context>()
        every { context.filesDir } returns folder.root
        return MemoryStore(context)
    }

    private fun file(name: String) = File(folder.root, name)

    @Test
    fun `a note survives the write and is read back by a fresh store`() = runBlocking {
        assertTrue(store().put("city", "杭州"))

        val reloaded = store()
        reloaded.load()

        assertEquals("杭州", reloaded.entries.value["city"]?.value)
    }

    @Test
    fun `put merges keys instead of replacing the file`() = runBlocking {
        val store = store()
        store.put("a", "第一个")
        store.put("b", "第二个")

        assertEquals(setOf("a", "b"), store.entries.value.keys)

        val reloaded = store()
        reloaded.load()
        assertEquals(setOf("a", "b"), reloaded.entries.value.keys)
    }

    @Test
    fun `remove takes one key and leaves the others`() = runBlocking {
        val store = store()
        store.put("keep", "留着")
        store.put("drop", "删掉")

        assertTrue(store.remove("drop"))

        assertEquals(setOf("keep"), store.entries.value.keys)
        val reloaded = store()
        reloaded.load()
        assertEquals(setOf("keep"), reloaded.entries.value.keys)
    }

    @Test
    fun `clear empties the file as well as the flow`() = runBlocking {
        val store = store()
        store.put("a", "1")

        assertTrue(store.clear())

        assertTrue(store.entries.value.isEmpty())
        val reloaded = store()
        reloaded.load()
        assertTrue(reloaded.entries.value.isEmpty())
    }

    /** The cap is a refusal, not a truncation: a note the model cannot read back in full is worse. */
    @Test
    fun `a note longer than the cap is refused and nothing is written`() = runBlocking {
        val store = store()

        assertFalse(store.put("long", "x".repeat(MemoryStore.MAX_VALUE_LENGTH + 1)))

        assertTrue(store.entries.value.isEmpty())
        assertFalse(file("memory.json").exists())
    }

    /** Written by 0.7.0 and earlier: `{"key": "value"}`, upgraded on read with no timestamp. */
    @Test
    fun `bare strings from the old format are upgraded on read`() = runBlocking {
        file("memory.json").writeText("""{"old":"老笔记"}""")

        val store = store()
        store.load()

        assertEquals("老笔记", store.entries.value["old"]?.value)
        assertEquals(0L, store.entries.value["old"]?.updatedAt ?: -1L)
    }

    /**
     * An unreadable file used to read as "no notes at all", and the next write then buried it — so
     * the bytes have to come out of the way under a name that can still be recovered by hand.
     */
    @Test
    fun `an unreadable file is quarantined instead of being overwritten`() = runBlocking {
        file("memory.json").writeText("{ 这不是 json")

        val store = store()
        store.load()

        assertTrue(store.entries.value.isEmpty())
        assertTrue(file("memory.corrupt.json").isFile)
        assertFalse(file("memory.json").exists())
    }
}
