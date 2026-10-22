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
 * issue #33：**迁移把正在使用的会话截断了 96%**（5.2MB / 400+ 条 → 212KB / 85 条）。
 *
 * 事故的三个根因，这里逐个锁住：
 *  1. 护栏在 `messageCount == 0` 时完全失效 —— `newMeta()` 的 count 是 0（表示"刚建"，
 *     不是"空会话"），于是 `known == 0` 时什么都拦不住，窗口被当全量写进去。
 *  2. 迁移把 `last-opened`（正在被写的那个）排**最前**。
 *  3. `repairPending` 与 `migratePending` 同时跑：`repairOne` 把 store 拆回 `.json`，
 *     `migrateOne` 又搬回 store，中间的窗口里 meta 被重建、count 归零。
 */
class ChatRepositoryIssue33Test {
    @get:Rule
    val tmp = TemporaryFolder()

    /** `filesDir` 是 root，聊天目录是 `root/chats`（与 ChatRepository 的约定一致）。 */
    private fun chatsDir(root: File): File = File(root, "chats").apply { mkdirs() }

    private fun repository(root: File): ChatRepository {
        val context = mockk<Context>()
        every { context.filesDir } returns root
        return ChatRepository(context)
    }

    private fun msg(id: String) = StoredMessage(id = id, role = Roles.USER, text = id)

    private fun convo(id: String, n: Int) = Conversation(
        id = id,
        title = "t",
        messages = (1..n).map { msg("m$it") },
    )

    /**
     * 根因 1：**窗口写进一个"空"的 store 会顶替整份历史**。
     *
     * 这就是事故：store 里还没有这个会话的日志（`logBytes == 0`），UI 手里只有
     * 最近 12 轮，而那一轮把 12 轮写成了全部。
     */
    @Test
    fun `a paged window never replaces a legacy transcript`() = runBlocking {
        val dir = tmp.newFolder("files")
        val chats = chatsDir(dir)
        val repository = repository(dir)
        // 旧格式文件里有 400 条（几天累积的历史）。
        val legacy = File(chats, "c1.json")
        legacy.writeText(
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .encodeToString(Conversation.serializer(), convo("c1", 400)),
        )

        // UI 手里只有最近 12 轮 + 1 条新的（窗口）。
        val window = (389..400).map { msg("m$it") } + msg("new")
        repository.save(Conversation(id = "c1", title = "t", messages = window))

        val all = repository.readAll("c1")
        assertEquals("400 条历史一条都不能少，加新的一条 = 401", 401, all.size)
        assertEquals("第一条还在", "m1", all.first().id)
        assertEquals("新的一条在最后", "new", all.last().id)
    }

    /** 迁移完成后，meta 的轮数/条数必须是**整份**算出来的，不是窗口的。 */
    @Test
    fun `the counts on disk are the whole transcript not the loaded window`() = runBlocking {
        val dir = tmp.newFolder("files")
        val chats = chatsDir(dir)
        val repository = repository(dir)
        File(chats, "c1.json").writeText(
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .encodeToString(Conversation.serializer(), convo("c1", 40)),
        )

        // 只加载最后 8 轮（窗口），然后保存。
        val window = (33..40).map { msg("m$it") }
        repository.save(Conversation(id = "c1", title = "t", messages = window))

        val meta = repository.conversations.value.first { it.id == "c1" }
        assertEquals("条数是全量", 40, meta.messageCount)
        assertEquals("轮数是全量（每条都是 user 消息 = 一轮）", 40, meta.turnCount)
    }

    /** 根因 2：正在使用的会话（`last-opened`）不参与迁移。 */
    @Test
    fun `the conversation in use is not migrated`() = runBlocking {
        val dir = tmp.newFolder("files")
        val chats = chatsDir(dir)
        val repository = repository(dir)
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        File(chats, "a.json").writeText(json.encodeToString(Conversation.serializer(), convo("a", 20)))
        File(chats, "b.json").writeText(json.encodeToString(Conversation.serializer(), convo("b", 20)))
        // 标记「正在打开」的是 b。
        File(chats, "last-opened").writeText("b")

        repository.migratePending()

        assertTrue("别的会话照常迁", File(chats, "store/a.meta").isFile)
        assertFalse("正在用的那个不迁", File(chats, "store/b.meta").isFile)
    }

    /**
     * 根因 3 的另一半：`repairOne` 曾经把 store 的内容**写回** `.json`，
     * 覆盖掉更完整的那份（store 里可能只是个窗口）。
     */
    @Test
    fun `repair never writes the store copy over a fuller plain file`() = runBlocking {
        val dir = tmp.newFolder("files")
        val chats = chatsDir(dir)
        val repository = repository(dir)
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        // `.json` 有 40 条。
        File(chats, "c1.json").writeText(json.encodeToString(Conversation.serializer(), convo("c1", 40)))
        // store 里只有 12 条（窗口）。
        val store = ChatStore(chats)
        store.save(store.newMeta(convo("c1", 12)), (1..12).map { msg("m$it") })

        repository.repairPending()

        val onDisk = json.decodeFromString<Conversation>(File(chats, "c1.json").readText())
        assertEquals("`.json` 的 40 条不能被 store 的 12 条覆盖", 40, onDisk.messages.size)
    }
}
