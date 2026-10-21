package com.suixing.agent.data

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The repository while the append-only store is **read but not written**: everything still lands
 * in `<id>.json`, and any chat the build that wrote to the store left behind is healed back to a
 * plain file. The store keeps its own unit tests in [ChatStoreTest]; the migration path is
 * exercised here because it is the repository that drives it.
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
    ) = Conversation(
        id = id,
        title = title,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        messages = messages,
    )

    private fun twoMessages(tag: String) = listOf(
        StoredMessage(id = "$tag-u", role = Roles.USER, text = "问题 $tag"),
        StoredMessage(id = "$tag-a", role = Roles.ASSISTANT, text = "答案 $tag", runId = "$tag-r"),
    )

    /** An old-format chat: one JSON object in `<id>.json`, exactly as the shipped app wrote it. */
    private fun writeLegacy(conversation: Conversation): File {
        chatsDir.mkdirs()
        return legacyFile(conversation.id).apply {
            writeText(json.encodeToString(Conversation.serializer(), conversation))
        }
    }

    private fun legacyFile(id: String) = File(chatsDir, "$id.json")

    private fun migratedFile(id: String) = File(chatsDir, "$id.json.migrated")

    private fun failedMarker(id: String) = File(chatsDir, "$id.json.migrate-failed")

    private fun readLegacy(id: String): Conversation =
        json.decodeFromString(legacyFile(id).readText())

    /** The chat as the half-connected build left it: only in the store, no `.json` at all. */
    private fun putInStoreOnly(conversation: Conversation) {
        store.append(store.newMeta(conversation), conversation.messages)
    }

    // --- the write path: a plain file, as before ----------------------------------------------

    @Test
    fun `saving a chat writes the single json file it always did`() = runBlocking {
        val saved = conversation("c1")
        repository().save(saved)

        assertTrue("the transcript is a plain .json again", legacyFile("c1").exists())
        assertFalse("nothing may be written to the store", store.has("c1"))
        val reloaded = repository().apply { load() }.conversations.value.single()
        assertEquals(saved.messages, reloaded.messages)
        assertEquals(saved.title, reloaded.title)
    }

    @Test
    fun `a new chat is a plain json file`() = runBlocking {
        repository().create(title = "新会话")

        val files = chatsDir.listFiles { f -> f.extension == "json" && !f.name.endsWith(LIVE_SUFFIX) }
        assertEquals("exactly one transcript file", 1, files?.size)
        assertTrue("and nothing in the store", storeMetaIds().isEmpty())
    }

    @Test
    fun `pinning and archiving survive a restart`() = runBlocking {
        val repository = repository()
        repository.save(conversation("c1"))
        repository.setPinned("c1", true)
        repository.setArchived("c1", true)

        val reloaded = repository().apply { load() }.conversations.value.single()
        assertTrue("置顶 has to survive a restart", reloaded.pinned)
        assertTrue("归档 has to survive a restart", reloaded.archived)
    }

    @Test
    fun `a shortened transcript is written as it was given`() = runBlocking {
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
    fun `deleting a chat removes its files in both formats`() = runBlocking {
        val repository = repository()
        val chat = conversation("c1")
        repository.save(chat)
        putInStoreOnly(chat)
        assertTrue("precondition: both formats are on disk", legacyFile("c1").exists() && store.has("c1"))

        repository.delete("c1")

        assertFalse("the plain file goes", legacyFile("c1").exists())
        assertFalse("the store entry goes", store.has("c1"))
        assertTrue("and it is no longer listed", repository.conversations.value.isEmpty())
    }

    // --- healing: a chat the half-connected build moved into the store -------------------------

    @Test
    fun `saving a store-only chat puts it back in a plain file and drops the store copy`() = runBlocking {
        val onlyInStore = conversation("c1", title = "只在 store 里")
        putInStoreOnly(onlyInStore)
        assertFalse("precondition: no plain file", legacyFile("c1").exists())

        val repository = repository()
        repository.load()
        val listed = repository.conversations.value.single()
        assertEquals("listed from the store, so saving it has the whole transcript", 2, listed.messages.size)

        repository.save(listed.copy(title = "救回来了"))

        assertTrue("the plain file has to exist again", legacyFile("c1").exists())
        assertEquals("with the whole transcript", listed.messages, readLegacy("c1").messages)
        assertEquals("救回来了", readLegacy("c1").title)
        assertFalse("and the store copy is gone", store.has("c1"))
        assertEquals(
            "so a build that only reads .json sees it again",
            1,
            repository().apply { load() }.conversations.value.size,
        )
    }

    @Test
    fun `a failed write leaves the store copy alone and the chat readable`() = runBlocking {
        val onlyInStore = conversation("c1")
        putInStoreOnly(onlyInStore)
        // A directory where the temp file has to go: writeText onto a directory throws on every
        // platform, so the write fails at its first step, before anything is discarded.
        // (Blocking `<id>.json` itself is not enough — see the note in the class docs.)
        File(chatsDir, "c1.json.tmp").mkdirs()

        val repository = repository()
        repository.load()
        val listed = repository.conversations.value.single()
        val result = runCatching { repository.save(listed.copy(title = "写不进去")) }

        assertTrue("the save has to fail loudly", result.isFailure)
        assertTrue("the store copy must survive a failed write", store.has("c1"))
        val meta = store.readMeta("c1")
        assertNotNull(meta)
        assertEquals("and it still holds the whole conversation", onlyInStore.messages, store.all(meta!!))

        // Readable again once the obstacle is gone, from the copy that was never dropped.
        File(chatsDir, "c1.json.tmp").delete()
        val reread = repository().apply { load() }.conversations.value.single()
        assertEquals(onlyInStore.messages, reread.messages)
    }

    // --- repair-on-launch: the chats that build already scattered ------------------------------

    @Test
    fun `repair writes a store-only chat back to a plain file and drops the store copy`() = runBlocking {
        val onlyInStore = conversation("c1", title = "只在 store 里")
        putInStoreOnly(onlyInStore)

        repository().repairPending()

        assertTrue("the chat is a plain file again", legacyFile("c1").exists())
        assertEquals("with the whole transcript", onlyInStore.messages, readLegacy("c1").messages)
        assertEquals("只在 store 里", readLegacy("c1").title)
        assertFalse("only after that file reads back may the store copy go", store.has("c1"))
    }

    @Test
    fun `a repair that cannot write keeps the store copy and leaves the chat readable`() = runBlocking {
        putInStoreOnly(conversation("c1"))
        File(chatsDir, "c1.json.tmp").mkdirs()
        val repository = repository()

        repository.repairPending()

        assertFalse("nothing was written", legacyFile("c1").exists())
        assertTrue("so the only copy must still be here", store.has("c1"))
        assertEquals(2, store.all(store.readMeta("c1")!!).size)
        repository.load()
        assertEquals("and the chat is still listed", 1, repository.conversations.value.size)
    }

    @Test
    fun `a chat retired to json migrated is renamed back`() = runBlocking {
        val chat = conversation("c1")
        chatsDir.mkdirs()
        migratedFile("c1").writeText(json.encodeToString(Conversation.serializer(), chat))

        repository().repairPending()

        assertTrue("the transcript is where the app looks for it", legacyFile("c1").exists())
        assertFalse("the marker file is gone", migratedFile("c1").exists())
        assertEquals(chat.messages, readLegacy("c1").messages)
    }

    @Test
    fun `repair keeps whichever copy is newer`() = runBlocking {
        writeLegacy(conversation("c1", title = "文件里的", updatedAt = 5_000))
        putInStoreOnly(conversation("c1", title = "store 里的", updatedAt = 1_000))

        repository().repairPending()

        assertEquals("the older store copy must not overwrite the file", "文件里的", readLegacy("c1").title)
        assertFalse("and it is dropped", store.has("c1"))
    }

    @Test
    fun `repair takes the store copy when it is the newer one`() = runBlocking {
        writeLegacy(conversation("c1", title = "文件里的", updatedAt = 1_000))
        putInStoreOnly(conversation("c1", title = "store 里的", updatedAt = 5_000))

        repository().repairPending()

        assertEquals("a chat saved by that build keeps its newer text", "store 里的", readLegacy("c1").title)
        assertFalse(store.has("c1"))
    }

    @Test
    fun `repair is idempotent`() = runBlocking {
        putInStoreOnly(conversation("c1"))
        val repository = repository()
        repository.repairPending()
        val repaired = readLegacy("c1")

        repository.repairPending()

        assertEquals("a second pass must change nothing", repaired, readLegacy("c1"))
        assertFalse(store.has("c1"))
    }

    @Test
    fun `a plain file wins over a leftover store copy`() = runBlocking {
        val chat = conversation("c1", title = "旧标题")
        putInStoreOnly(chat.copy(title = "store 里的标题"))
        writeLegacy(chat.copy(title = "文件里的标题"))

        val listed = repository().apply { load() }.conversations.value.single()

        assertEquals("while the store is not the write path, the file is the newer copy", "文件里的标题", listed.title)
    }

    // --- migrating the old format --------------------------------------------------------------

    @Test
    fun `an old chat is listed before it is migrated and identical after`() = runBlocking {
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
        assertEquals("the chat has to be identical across the migration", beforeMigration, repository.conversations.value.single())
    }

    @Test
    fun `migrating twice changes nothing`() = runBlocking {
        writeLegacy(conversation("c1"))
        val repository = repository()

        repository.migratePending()
        val meta = store.readMeta("c1")
        repository.migratePending()

        assertEquals("a second pass must not touch the log", meta, store.readMeta("c1"))
        assertEquals(1, repository().apply { load() }.conversations.value.size)
    }

    @Test
    fun `a migration killed before the rename does not double the chat`() = runBlocking {
        val legacy = conversation("c1")
        writeLegacy(legacy)
        putInStoreOnly(legacy)

        val repository = repository()
        repository.load()

        assertEquals("one chat, not two, when both copies exist", 1, repository.conversations.value.size)
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

    /** Ids the store holds a meta for — the "which chats did the bad build touch" set. */
    private fun storeMetaIds(): List<String> =
        File(chatsDir, STORE_DIR).listFiles { f -> f.name.endsWith(META_SUFFIX) }
            ?.map { it.name.removeSuffix(META_SUFFIX) }
            ?: emptyList()

    private companion object {
        const val STORE_DIR = "store"
    }
}
