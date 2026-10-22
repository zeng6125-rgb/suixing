package com.suixing.agent.data

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 会话统计必须算**整个会话**（用户实测：标题栏的统计只算了已加载的 12 轮）。
 *
 * 数字跟着追加走：`appendTail` 只累加新落盘的几条，显示时读 meta，O(1)。
 * 旧 meta 没有这个字段（`totalsAt == -1`）⇒ 第一次读时补算一次。
 */
class ChatTotalsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun repository(root: File): ChatRepository {
        val context = mockk<Context>()
        every { context.filesDir } returns root
        return ChatRepository(context)
    }

    private fun user(id: String) = StoredMessage(id = id, role = Roles.USER, text = id)
    private fun answer(id: String, prompt: Int, completion: Int) = StoredMessage(
        id = id,
        role = Roles.ASSISTANT,
        text = id,
        promptTokens = prompt,
        completionTokens = completion,
    )

    @Test
    fun `the totals cover every message, not just the tail window`() = runBlocking {
        val dir = tmp.newFolder("files")
        val repo = repository(dir)
        // 10 轮 = 20 条，每轮 prompt 100 / completion 10。
        val messages = (1..10).flatMap { listOf(user("u$it"), answer("a$it", 100, 10)) }
        repo.save(Conversation(id = "c1", title = "t", messages = messages))

        val totals = repo.sessionTotals("c1")!!
        assertEquals("10 轮", 10, totals.turns)
        assertEquals("prompt 全量", 1000, totals.promptTokens)
        assertEquals("completion 全量", 100, totals.completionTokens)
    }

    @Test
    fun `appending keeps the running total`() = runBlocking {
        val dir = tmp.newFolder("files")
        val repo = repository(dir)
        val first = listOf(user("u1"), answer("a1", 100, 10))
        repo.save(Conversation(id = "c1", title = "t", messages = first))

        // 追加一轮（走 append 路径）。
        val second = first + listOf(user("u2"), answer("a2", 200, 20))
        repo.save(Conversation(id = "c1", title = "t", messages = second))

        val totals = repo.sessionTotals("c1")!!
        assertEquals("轮数累加", 2, totals.turns)
        assertEquals("prompt 累加", 300, totals.promptTokens)
        assertEquals("completion 累加", 30, totals.completionTokens)
    }

    @Test
    fun `an old meta without totals is backfilled once`() = runBlocking {
        val dir = tmp.newFolder("files")
        val chats = File(dir, "chats").apply { mkdirs() }
        val store = ChatStore(chats)
        val messages = listOf(user("u1"), answer("a1", 500, 50))
        store.save(store.newMeta(Conversation(id = "c1", title = "t", messages = messages)), messages)

        // 模拟旧版本的 meta：把统计字段清掉。
        val meta = store.readMeta("c1")!!
        store.writeMeta(meta.copy(totals = ChatTotals.EMPTY, totalsAt = -1))

        val repo = repository(dir)
        val totals = repo.sessionTotals("c1")!!
        assertEquals("补算出来的 prompt", 500, totals.promptTokens)
        assertEquals("补算结果要写回盘上", 2, store.readMeta("c1")!!.totalsAt)
    }
}
