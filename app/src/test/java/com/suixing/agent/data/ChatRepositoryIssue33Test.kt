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
    /**
     * 根因 1 的**遗留意图**：盘上的计数必须描述**整份**转录，不是"这次手里那一页"。
     *
     * 旧格式删除（issue #40）后，这条不能再靠"`.json` 被迁移"来播种 —— 改为直接用 store 播种，
     * 断言的含义不变：meta 的 `messageCount`/`turnCount` 来自**盘上真实内容**。
     */
    @Test
    fun `the counts on disk describe the whole transcript`() = runBlocking {
        val dir = tmp.newFolder("files")
        val repository = repository(dir)
        repository.save(convo("c1", 40))

        val meta = repository.conversations.value.first { it.id == "c1" }
        assertEquals("条数是全量", 40, meta.messageCount)
        assertEquals("轮数是全量（每条都是 user 消息 = 一轮）", 40, meta.turnCount)
        // 再读回来确认：读到的就是那 40 条，而不是某一页。
        assertEquals(40, repository.readAll("c1").size)
    }

    /** 根因 2：正在使用的会话（`last-opened`）不参与迁移。 */
}
