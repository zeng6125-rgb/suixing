package com.suixing.agent.data

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 「加载更早」的两条路径必须给出**同一批内容**：
 *  - 按消息 id（`readEarlierBefore`，从日志开头扫，O(文件大小)）；
 *  - 按字节偏移（`readEarlier`，O(窗口)）—— 界面现在优先走这条。
 *
 * 这条断言守的是"偏移翻页出现空洞/重复"这类错误：它不会崩、不会报错，
 * 只表现为"往上翻的时候少了几句 / 同一句出现两次"，而用户多半会以为是模型的问题。
 */
class PagedReadTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val chatsDir: File get() = File(folder.root, "chats")

    private val store: ChatStore get() = ChatStore(chatsDir)

    /** 直接把一份会话种进 store（`ChatRepository.write` 是 private）。 */
    private fun seed(conversation: Conversation) {
        store.append(store.newMeta(conversation), conversation.messages)
    }

    private fun repository(): ChatRepository {
        val context = mockk<Context>()
        every { context.filesDir } returns folder.root
        return ChatRepository(context)
    }

    /** 12 轮，每轮一问一答，正文里带足字符让窗口切在中间。 */
    private fun twelveTurns(id: String): Conversation {
        val messages = (1..12).flatMap { turn ->
            listOf(
                StoredMessage(
                    id = "$id-u$turn", role = Roles.USER, text = "问题 $turn " + "内容".repeat(20),
                    timestamp = turn.toLong(),
                ),
                StoredMessage(
                    id = "$id-a$turn", role = Roles.ASSISTANT, text = "回答 $turn " + "内容".repeat(40),
                    timestamp = turn.toLong() * 1000,
                ),
            )
        }
        return Conversation(id = id, title = "分页", createdAt = 1L, updatedAt = 1L, messages = messages)
    }

    @Test
    fun `paging by byte offset yields the same pages as paging by id`() = runBlocking {
        val repository = repository()
        val chat = twelveTurns("p1")
        seed(chat)

        val tail = repository.readTailWindow(chat.id, turns = 3)
        assertNotNull("尾部窗口必须能读出来", tail)
        val (opened, startOffset, gen) = tail!!

        // 按字节偏移一路往回翻，收集所有页。
        val byOffset = ArrayList<StoredMessage>()
        byOffset.addAll(opened.messages)
        var offset = startOffset
        var guard = 0
        while (offset > 0 && guard++ < 20) {
            val window = repository.readEarlier(chat.id, offset, turns = 3, expectGen = gen) ?: break
            if (window.messages.isEmpty()) break
            byOffset.addAll(0, window.messages)
            offset = window.startOffset
        }

        // 按消息 id 一路往回翻（旧路径），同样收集。
        val byId = ArrayList<StoredMessage>()
        byId.addAll(opened.messages)
        guard = 0
        while (guard++ < 20) {
            val first = byId.first()
            if (byId.size >= chat.messages.size) break
            // 回退路径现在返回 `EarlierPage`（消息 + startOffset + gen，供调用方补种）。
            val page = repository.readEarlierBefore(chat.id, first.id, turns = 3) ?: break
            if (page.messages.isEmpty()) break
            byId.addAll(0, page.messages)
        }

        assertEquals("两条路径必须给出同样多的内容", byId.map { it.id }, byOffset.map { it.id })
        assertEquals("最终应覆盖全部消息（不缺不重）", chat.messages.map { it.id }, byOffset.map { it.id })
    }

    @Test
    fun `a stale generation falls back instead of returning a wrong slice`() = runBlocking {
        val repository = repository()
        val chat = twelveTurns("p2")
        seed(chat)
        val (_, startOffset, gen) = repository.readTailWindow(chat.id, turns = 3)!!

        // 换代重写：编辑后重答会走这条路（gen 递增、日志重建），旧的字节偏移随之失效。
        val rewritten = chat.copy(messages = chat.messages.drop(2), updatedAt = 2L)
        val nextGen = gen + 1
        store.append(
            store.newMeta(rewritten).copy(gen = nextGen),
            rewritten.messages,
        )

        val stale = repository.readEarlier(chat.id, startOffset, turns = 3, expectGen = gen)
        assertEquals("代次不匹配时必须返回 null（让调用方回退到按 id 查）", null, stale)
    }
}
