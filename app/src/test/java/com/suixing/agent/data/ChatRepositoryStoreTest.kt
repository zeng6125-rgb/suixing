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
    fun `a failed write leaves the store copy alone and the chat readable`() = runBlocking {
        // **新契约**（#31）：写入目标是 store 的 JSONL，所以要让它失败得堵**store 的日志文件**，
        // 而不是旧的 `<id>.json.tmp`（那个路径已经不参与写入了）。
        val onlyInStore = conversation("c1")
        putInStoreOnly(onlyInStore)

        val repository = repository()
        repository.load()
        val listed = repository.conversations.value.single()
        val full = repository.readAll("c1")

        val meta = store.readMeta("c1")
        assertNotNull(meta)
        // 把日志文件换成目录：下一次 append 打开它会失败。
        val logFile = File(chatsDir, "store/" + meta!!.id + "." + meta.gen + ".jsonl")
        assertTrue("precondition: the log is on disk", logFile.exists())
        logFile.delete()
        logFile.mkdirs()
        assertTrue(logFile.isDirectory)

        val result = runCatching {
            repository.save(listed.copy(title = "写不进去", messages = full + full[0].copy(id = "extra")))
        }

        assertTrue("the save has to fail loudly", result.isFailure)
        // **正确断言**：`has()` 要求「meta + 它指名的日志」都在（见 ChatStore:128），
        // 我把日志换成了目录，所以 has() 必然 false —— 那验证不了任何东西。
        // 这条测试真正要守的是：**失败不能动 meta 里记录的提交边界与条数**，
        // 于是下次还能从边界重来，而不是把半截日志当成历史。
        val survived = store.readMeta("c1")
        assertNotNull("the meta itself must still be there", survived)
        assertEquals(
            "and it still describes the whole conversation",
            onlyInStore.messages.size,
            survived!!.messageCount,
        )
        assertEquals(
            "the commit boundary is unchanged, so the next save starts from it",
            meta.logBytes,
            survived.logBytes,
        )

        // Remove the obstacle: the chat is readable again from what was never dropped.
        logFile.delete()
        val rereader = repository().apply { load() }
        assertEquals("the chat is listed again", 1, rereader.conversations.value.size)
    }

    @Test
    fun `a new chat is a store log, not a plain json file`() = runBlocking {
        repository().create(title = "新会话")

        val files = chatsDir.listFiles { f -> f.extension == "json" && !f.name.endsWith(LIVE_SUFFIX) }
        assertEquals("no plain transcript any more", 0, files?.size)
        assertEquals("exactly one chat in the store", 1, storeMetaIds().size)
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
    fun `a paged window can never be passed off as the whole history`() = runBlocking {
        // **这就是 #33 的形状**（评审 M1）：调用方手里只有窗口（或任何比盘上短的片段），
        // 却带着 `allowShrink = true`。没有 `fullHistory` 声明时，护栏必须拒绝，
        // 并且**盘上一个字节都不能动**。
        val repository = repository()
        val full = conversation("c1", messages = twoMessages("a") + twoMessages("b") + twoMessages("c"))
        repository.save(full)
        val before = repository.readAll("c1").map { it.id }

        // 典型现场：编辑重答拿了屏幕上的窗口（这里取前 2 条模拟），却声称"我要缩短"。
        val refused = repository.save(full.copy(messages = full.messages.take(2)), allowShrink = true)
        assertTrue("必须被拒绝而不是静默写入", refused is ChatRepository.SaveResult.Refused)

        val after = repository().apply { load() }.readAll("c1")
        assertEquals("日志一个字都不能少", before, after.map { it.id })
    }

    @Test
    fun `claiming the whole history is checked, not trusted`() = runBlocking {
        // 评审 S-3：`fullHistory = true` 只是调用方的一句话。声明了却比盘上少、首条也对不上
        // ⇒ 仍然是"窗口冒充全量"，必须拒绝，且盘上一个字节不动。
        val repository = repository()
        val full = conversation("c1", messages = twoMessages("a") + twoMessages("b") + twoMessages("c"))
        repository.save(full)
        val before = repository.readAll("c1").map { it.id }

        val window = full.copy(messages = full.messages.takeLast(2))
        val refused = repository.save(window, allowShrink = true, fullHistory = true)
        assertTrue("声明与内容不符时必须拒绝", refused is ChatRepository.SaveResult.Refused)

        val after = repository().apply { load() }.readAll("c1")
        assertEquals("日志一条都不能少", before, after.map { it.id })
    }

    @Test
    fun `a shortened transcript is written as it was given`() = runBlocking {
        val repository = repository()
        val full = conversation("c1", messages = twoMessages("a") + twoMessages("b") + twoMessages("c"))
        repository.save(full)

        // What editAndResend does: everything the model said after the corrected question goes.
        // **新契约**（#31 写侧）：缩短必须显式声明（`allowShrink = true`）—— 护栏默认拒绝
        // "手里只有半页"的回写，正是它防止了"打开长会话却把历史截断"。
        // **契约在 0.8.367 收紧了**（评审 M1）：光是 `allowShrink = true` 不够 ——
        // 它还要求调用方**显式声明手里是整份历史**（`fullHistory = true`）。
        // 原因：`allowShrink` 曾是绕过「窗口不许当全量」护栏的唯一下口子，
        // 而编辑重答当时交给它的是 12 轮窗口 ⇒ 整份日志被重写、旧代被删（#33 的形状）。
        // 这里调用方手里**确实是**完整历史（从 `full` 派生），所以声明是诚实的。
        repository.save(
            full.copy(messages = full.messages.take(2) + full.messages[0].copy(text = "改过的问题")),
            allowShrink = true,
            fullHistory = true,
        )

        // **新契约**（#30）：load() 只给 meta，正文用 readAll 读回来。
        // 这条测试要验的是"缩短后的历史真的落到盘上了"，所以必须读正文，而不是看列表。
        val rereader = repository().apply { load() }
        val onDisk = rereader.readAll("c1")
        assertEquals("the drop has to reach the disk", 3, onDisk.size)
        assertEquals("改过的问题", onDisk.last().text)
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
