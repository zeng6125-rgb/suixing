package com.suixing.agent.data

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `readWire` 在**有 checkpoint** 时的行为（0.8.359 给它加了字节偏移缓存）。
 *
 * 缓存不会崩、也不会报错；它错了只会表现为「发出去的历史从错误的位置开始」
 * —— 多半被当成"模型失忆/重复回答"。所以这里钉两件事：
 *  1. 返回的是**从 checkpoint 那条开始的后缀**；
 *  2. **连读两次结果一致**（缓存不该改变结果）。
 */
class WireReadTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val chatsDir: File get() = File(folder.root, "chats")
    private val store: ChatStore get() = ChatStore(chatsDir)

    private fun repository(): ChatRepository {
        val context = mockk<Context>()
        every { context.filesDir } returns folder.root
        return ChatRepository(context)
    }

    private fun chatWithCheckpoint(id: String): Pair<Conversation, ContextCheckpoint> {
        val messages = (1..6).flatMap { turn ->
            listOf(
                StoredMessage(id = "$id-u$turn", role = Roles.USER, text = "问 $turn", timestamp = turn.toLong()),
                StoredMessage(id = "$id-a$turn", role = Roles.ASSISTANT, text = "答 $turn", timestamp = turn.toLong() * 10),
            )
        }
        // checkpoint 覆盖前 4 轮（最后一条是 u/a-4 的 assistant）。
        val checkpoint = ContextCheckpoint(
            id = "cp1",
            at = 1L,
            fromMessageId = "$id-u1",
            toMessageId = "$id-a4",
            summary = "前四轮摘要",
            messages = 8,
            promptTokens = 0,
        )
        return Conversation(
            id = id, title = "wire", createdAt = 1L, updatedAt = 1L, messages = messages,
        ) to checkpoint
    }

    @Test
    fun `the wire starts at the checkpoint and runs to the end`() = runBlocking {
        val repository = repository()
        val (chat, checkpoint) = chatWithCheckpoint("w1")
        store.append(store.newMeta(chat).copy(checkpoints = listOf(checkpoint)), chat.messages)

        val wire = repository.readWire(chat.id)
        assertTrue("必须有内容", wire.isNotEmpty())
        assertEquals("最后一个 checkpoint 之后的内容必须以它开头", checkpoint.toMessageId, wire.first().id)
        assertEquals("并且一直到最后一条", chat.messages.last().id, wire.last().id)
        assertTrue("不应该把 checkpoint 之前的内容也发出去", wire.none { it.id == "w1-u1" })
    }

    @Test
    fun `reading twice returns the same list`() = runBlocking {
        val repository = repository()
        val (chat, checkpoint) = chatWithCheckpoint("w2")
        store.append(store.newMeta(chat).copy(checkpoints = listOf(checkpoint)), chat.messages)

        // 第一次会扫日志并写缓存，第二次应当直接命中 —— 两者结果必须一致。
        val first = repository.readWire(chat.id)
        val second = repository.readWire(chat.id)
        assertEquals("缓存不能改变结果", first.map { it.id }, second.map { it.id })
    }
}
