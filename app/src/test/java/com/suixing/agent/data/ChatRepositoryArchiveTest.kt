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

/**
 * 归档位（`Conversation.archived`）的两条规矩，都是「标签不是内容」这条线：
 *
 *  · 写它不动 `updatedAt` —— 否则归档过的会话会跳到时间序最前面，读起来像刚编辑过；
 *  · 正文保存继承盘上的值 —— 不继承的话，归档过的会话只要再落一次盘就自己回到抽屉里
 *    （`save` 里 `pinned` 那一行是同一个坑，这里是第六个字段）。
 */
class ChatRepositoryArchiveTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun repository(): ChatRepository {
        val context = mockk<Context>()
        every { context.filesDir } returns folder.root
        return ChatRepository(context)
    }

    @Test
    fun `archiving keeps updatedAt and survives a re-read`() = runBlocking {
        val chats = repository()
        val created = chats.create(title = "要收起来的会话")
        val stamp = created.updatedAt

        chats.setArchived(created.id, true)

        val inMemory = chats.conversations.value.single { it.id == created.id }
        assertTrue("内存里已经是归档态", inMemory.archived)
        assertEquals("归档不动 updatedAt", stamp, inMemory.updatedAt)

        // 重新从盘上读一遍：归档位必须落盘了（只改内存的话，重启就白归档）。
        val reopened = repository()
        reopened.load()
        val fromDisk = reopened.conversations.value.single { it.id == created.id }
        assertTrue("归档位落盘了", fromDisk.archived)

        reopened.setArchived(created.id, false)
        assertFalse(
            "取消归档也是同一个入口",
            reopened.conversations.value.single { it.id == created.id }.archived,
        )
    }

    @Test
    fun `saving a message does not unarchive the conversation`() = runBlocking {
        val chats = repository()
        val created = chats.create(title = "归档后又聊了一句")
        chats.setArchived(created.id, true)

        // 一次普通的正文落盘：调用方手里那份（created）没有归档位，仓库必须继承盘上的。
        chats.save(created.copy(title = "归档后又聊了一句"))

        assertTrue(
            "落盘不把归档位抹掉",
            chats.conversations.value.single { it.id == created.id }.archived,
        )
    }
}
