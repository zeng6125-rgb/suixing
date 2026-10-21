package com.suixing.agent.data

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The repository on the append-only store: the write path, the old-format migration, and the
 * promise that a user's existing chats survive being moved.
 */
class ChatRepositoryStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val chatsDir: File get() = File(folder.root, "chats")

    private val store: ChatStore get() = ChatStore(chatsDir)

    private fun repository(): ChatRepository {
        val context = mockk<Context>()
        every { context.filesDir } returns folder.root
        return ChatRepository(context)
    }

    // --- fixtures ----------------------------------------------------------------------------

    private fun conversation(
        id: String,
        title: String = "会话 $id",
        updatedAt: Long = 1_700_000_000_000L,
        messages: List<StoredMessage> = twoMessages("$id-1"),
        pinned: Boolean = false,
        archived: Boolean = false,
    ) = Conversation(
        id = id,
        title = title,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        messages = messages,
        pinned = pinned,
        archived = archived,
    )

    private fun twoMessages(tag: String) = listOf(
        StoredMessage(id = "$tag-u", role = Roles.USER, text = "问题 $tag"),
        StoredMessage(id = "$tag-a", role = Roles.ASSISTANT, text = "答案 $tag", runId = "$tag-r"),
    )

    /** An old-format chat: one JSON object in `<id>.json`, exactly as the shipped app wrote it. */
    private fun writeLegacy(conversation: Conversation): File {
        chatsDir.mkdirs()
        return File(chatsDir, "${conversation.id}.json").apply {
            writeText(json.encodeToString(Conversation.serializer(), conversation))
        }
    }

    private fun legacyFile(id: String) = File(chatsDir, "$id.json")

    private fun migratedFile(id: String) = File(chatsDir, "$id.json.migrated")

    private fun failedMarker(id: String) = File(chatsDir, "$id.json.migrate-failed")

    private fun logBytes(id: String): Long =
        File(chatsDir, "$STORE_DIR").listFiles { f -> f.name.startsWith("$id.") && f.name.endsWith(LOG_SUFFIX) }
            ?.sumOf { it.length() }
            ?: -1

    // --- the new write path ------------------------------------------------------------------

    @Test
    fun `a saved chat reads back from the store after a restart`() = runBlocking {
        val saved = conversation("c1")
        repository().save(saved)

        val reloaded = repository().apply { load() }.conversations.value

        assertEquals("the chat has to come back", 1, reloaded.size)
        assertEquals(saved.id, reloaded.single().id)
        assertEquals("标题 has to survive", saved.title, reloaded.single().title)
        assertEquals("消息 has to survive, in order", saved.messages, reloaded.single().messages)
        assertFalse("a new-format chat leaves no .json behind", legacyFile("c1").exists())
    }

    @Test
    fun `a new turn appends instead of rewriting the transcript`() = runBlocking {
        val repository = repository()
        val first = conversation("c1")
        repository.save(first)
        val before = logBytes("c1")

        repository.save(first.copy(messages = first.messages + twoMessages("c1-2")))

        assertTrue(
            "the log has to grow by the new messages, not be written again ($before → ${logBytes("c1")})",
            logBytes("c1") > before,
        )
        val reloaded = repository().apply { load() }.conversations.value.single()
        assertEquals(4, reloaded.messages.size)
    }

    @Test
    fun `pinning a chat does not touch the transcript`() = runBlocking {
        val repository = repository()
        repository.save(conversation("c1"))
        val before = logBytes("c1")

        repository.setPinned("c1", true)

        assertEquals("置顶 must not re-encode a single message", before, logBytes("c1"))
        val reloaded = repository().apply { load() }.conversations.value.single()
        assertTrue("置顶 has to survive a restart", reloaded.pinned)
        assertEquals("for the same reason, its time must not move", 1_700_000_000_000L, reloaded.updatedAt)
    }

    @Test
    fun `archiving a chat survives a restart`() = runBlocking {
        val repository = repository()
        repository.save(conversation("c1"))
        repository.setArchived("c1", true)

        val reloaded = repository().apply { load() }.conversations.value.single()
        assertTrue("归档 has to survive a restart", reloaded.archived)
        assertFalse("and it is not pinned by accident", reloaded.pinned)
    }

    @Test
    fun `a shorter list is written as a rewrite, not refused`() = runBlocking {
        val repository = repository()
        val full = conversation("c1", messages = twoMessages("a") + twoMessages("b") + twoMessages("c"))
        repository.save(full)

        // What editAndResend does: everything the model said after the corrected question goes.
        repository.save(full.copy(messages = full.messages.take(2) + full.messages[0].copy(text = "改过的问题")))

        val reloaded = repository().apply { load() }.conversations.value.single()
        assertEquals("the drop has to reach the disk", 3, reloaded.messages.size)
        assertEquals("改过的问题", reloaded.messages.last().text)
    }

    @Test
    fun `deleting a chat takes its store files with it`() = runBlocking {
        val repository = repository()
        repository.save(conversation("c1"))
        assertTrue("precondition: the store has the chat", store.has("c1"))

        repository.delete("c1")

        assertFalse("the store entry goes", store.has("c1"))
        assertTrue("and the repository no longer lists it", repository.conversations.value.isEmpty())
    }

    // --- migrating the old format ------------------------------------------------------------

    @Test
    fun `an old chat is listed before it is migrated and unchanged after`() = runBlocking {
        val legacy = conversation("c1", title = "老会话")
        writeLegacy(legacy)
        val repository = repository()

        repository.load()
        val beforeMigration = repository.conversations.value.single()
        assertEquals("an unmigrated chat is listed like any other", legacy.messages, beforeMigration.messages)

        repository.migratePending()

        assertFalse("the old file is retired", legacyFile("c1").exists())
        assertTrue("into a rollback copy", migratedFile("c1").exists())
        assertTrue("the store owns it now", store.has("c1"))

        repository.load()
        val afterMigration = repository.conversations.value.single()
        assertEquals("the chat has to be identical across the migration", beforeMigration, afterMigration)
    }

    @Test
    fun `migrating twice changes nothing`() = runBlocking {
        writeLegacy(conversation("c1"))
        val repository = repository()

        repository.migratePending()
        val bytes = logBytes("c1")
        repository.migratePending()

        assertEquals("a second pass must not rewrite the log", bytes, logBytes("c1"))
        assertEquals(1, repository().apply { load() }.conversations.value.size)
    }

    @Test
    fun `a migration killed before the rename does not double the chat`() = runBlocking {
        val legacy = conversation("c1")
        writeLegacy(legacy)
        // The state a kill between the store write and the rename leaves: both copies on disk.
        store.append(store.newMeta(legacy), legacy.messages)

        val repository = repository()
        repository.load()

        assertEquals(
            "one chat, not two, when both copies exist",
            1,
            repository.conversations.value.size,
        )
        repository.migratePending()
        assertFalse("the leftover .json is retired on the next pass", legacyFile("c1").exists())
    }

    @Test
    fun `an unparseable old chat is left alone and reported`() = runBlocking {
        chatsDir.mkdirs()
        val broken = File(chatsDir, "c1.json").apply { writeText("{\"id\":\"c1\", broken") }
        val onDisk = broken.readBytes()
        val repository = repository()

        repository.migratePending()

        assertTrue("the old file must still be there", broken.exists())
        assertEquals("and not one byte of it may change", onDisk.toList(), broken.readBytes().toList())
        assertTrue("the failure has to leave a marker", failedMarker("c1").exists())
        assertFalse("nothing may reach the store", store.has("c1"))
    }

    @Test
    fun `saving an old chat moves it to the store and retires the old file`() = runBlocking {
        val legacy = conversation("c1")
        writeLegacy(legacy)
        val repository = repository()
        repository.load()

        repository.save(legacy.copy(messages = legacy.messages + twoMessages("c1-2")))

        assertFalse("the old file is retired by the write itself", legacyFile("c1").exists())
        assertEquals("and the chat is not listed twice", 1, repository().apply { load() }.conversations.value.size)
    }

    @Test
    fun `migration keeps the order the user saw`() = runBlocking {
        writeLegacy(conversation("old", updatedAt = 1_000))
        writeLegacy(conversation("new", updatedAt = 2_000))
        val repository = repository()
        repository.load()
        val before = repository.conversations.value.map { it.id }

        repository.migratePending()
        repository.load()

        assertEquals("migrating must not reshuffle the list", before, repository.conversations.value.map { it.id })
    }

    @Test
    fun `an old chat that already has a store copy keeps the store copy`() = runBlocking {
        val legacy = conversation("c1", title = "老标题")
        writeLegacy(legacy)
        store.append(store.newMeta(legacy.copy(title = "新标题")), legacy.messages)

        val listed = repository().apply { load() }.conversations.value.single()

        assertEquals("the store is newer than a .json it could not retire", "新标题", listed.title)
    }

    @Test
    fun `checkpoints and the model choice survive a round trip`() = runBlocking {
        val chosen = conversation("c1").copy(
            providerId = "p1",
            model = "m1",
            reasoningEffort = "high",
            activeRuns = mapOf("c1-1-u" to "run-2"),
            assistantId = "persona-1",
            checkpoints = listOf(
                ContextCheckpoint(
                    id = "k1",
                    at = 5L,
                    fromMessageId = "c1-1-u",
                    toMessageId = "c1-1-a",
                    summary = "摘要",
                    messages = 2,
                    promptTokens = 10,
                    anchorId = "c1-1-a",
                ),
            ),
        )
        repository().save(chosen)

        val reloaded = repository().apply { load() }.conversations.value.single()

        assertEquals("p1", reloaded.providerId)
        assertEquals("m1", reloaded.model)
        assertEquals("high", reloaded.reasoningEffort)
        assertEquals("persona-1", reloaded.assistantId)
        assertEquals(mapOf("c1-1-u" to "run-2"), reloaded.activeRuns)
        assertEquals("a checkpoint is part of the chat, not of its messages", chosen.checkpoints, reloaded.checkpoints)
    }

    private companion object {
        const val STORE_DIR = "store"
    }
}
