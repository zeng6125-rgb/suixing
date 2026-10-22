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
 * 置顶 / 归档 / 改名：**只改 meta，不碰日志**。
 *
 * 真机崩溃（0.8.327，`2026-10-23 01:18:58`）：
 *
 * ```
 * ConversationLogShrunkException: chat 460f1de3…: log has 83 messages, caller holds 0
 *     at ChatStore.save(ChatStore.kt:279)
 *     at ChatRepository.write(ChatRepository.kt:789)
 *     at ChatRepository$setArchived$2.invokeSuspend(ChatRepository.kt:604)
 * ```
 *
 * 原因：#30 之后 `load()` 只读 meta，列表里的 `messages` 是空列表；而这三个方法原来走
 * `write()`，等于拿「0 条」当全量回写 ⇒ 抛异常 ⇒ 崩进程（用户点「取消归档」时闪退）。
 */
class ChatArchiveFlagTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun repository(root: File): ChatRepository {
        val context = mockk<Context>()
        every { context.filesDir } returns root
        return ChatRepository(context)
    }

    private fun convo(id: String, n: Int) = Conversation(
        id = id,
        title = "t",
        messages = (1..n).map { StoredMessage(id = "m$it", role = Roles.USER, text = "m$it") },
    )

    @Test
    fun `archiving a chat with a log does not throw and keeps the log`() = runBlocking {
        val dir = tmp.newFolder("files")
        val repo = repository(dir)
        repo.save(convo("c1", 12))

        // 这一句在修之前会抛 ConversationLogShrunkException。
        repo.setArchived("c1", true)

        val listed = repo.conversations.value.first { it.id == "c1" }
        assertTrue("归档位要落盘", listed.archived)
        assertEquals("历史一条都不能少", 12, repo.readAll("c1").size)
    }

    @Test
    fun `unarchiving works the same way`() = runBlocking {
        val dir = tmp.newFolder("files")
        val repo = repository(dir)
        repo.save(convo("c1", 5))
        repo.setArchived("c1", true)
        repo.setArchived("c1", false)

        assertFalse(repo.conversations.value.first { it.id == "c1" }.archived)
        assertEquals(5, repo.readAll("c1").size)
    }

    @Test
    fun `pinning and renaming also leave the log alone`() = runBlocking {
        val dir = tmp.newFolder("files")
        val repo = repository(dir)
        repo.save(convo("c1", 7))

        repo.setPinned("c1", true)
        repo.rename("c1", "新标题")

        val listed = repo.conversations.value.first { it.id == "c1" }
        assertTrue(listed.pinned)
        assertEquals("新标题", listed.title)
        assertEquals("日志没被回写", 7, repo.readAll("c1").size)
    }
}
