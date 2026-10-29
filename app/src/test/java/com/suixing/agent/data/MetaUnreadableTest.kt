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
 * **meta 读不出来但日志还在**时的保存行为（评审 M4，不可逆）。
 *
 * 原来的路径：`readMeta` 返回 null ⇒ `save` 用 `newMeta`（logBytes = 0）⇒ `ChatStore` 看到
 * "元数据说 0 字节、文件却非空" ⇒ **`setLength(0)` 把整份日志清空**，再写进手里的窗口。
 * 迁移出来的会话 gen 本来就是 0，被清空的往往就是那份完整日志本身 —— 不可逆。
 *
 * 现在的契约：返回 **SaveResult.Refused**，日志一个字节不动，保存协程不崩溃。
 */
class MetaUnreadableTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val chatsDir: File get() = File(folder.root, "chats")
    private val storeDir: File get() = File(chatsDir, "store")

    private fun repository(): ChatRepository {
        val context = mockk<Context>()
        every { context.filesDir } returns folder.root
        return ChatRepository(context)
    }

    private fun conversation(id: String, count: Int): Conversation = Conversation(
        id = id,
        title = "t",
        createdAt = 1L,
        updatedAt = 1L,
        messages = (1..count).map {
            StoredMessage(id = "$id-$it", role = if (it % 2 == 0) Roles.ASSISTANT else Roles.USER, text = "第 $it 条")
        },
    )

    @Test
    fun `an unreadable meta never lets a window replace the log`() = runBlocking {
        val repository = repository()
        repository.save(conversation("c1", 20))

        // 让 meta 读不出来（模拟解析失败 / 被外部改坏），日志文件保持原样。
        val metas = storeDir.listFiles { f -> f.name.endsWith(".meta") }.orEmpty()
        assertTrue("前提：应该有 meta 文件", metas.isNotEmpty())
        val logsBefore = storeDir.listFiles { f -> f.name.endsWith(".jsonl") }.orEmpty()
            .map { it.name to it.length() }.toSet()
        assertTrue("前提：应该有日志文件", logsBefore.isNotEmpty())
        metas.forEach { it.writeText("{ 这不是合法 JSON") }

        // 手里只有窗口（3 条）—— 正是 #33 的形状。
        val window = conversation("c1", 20).copy(messages = conversation("c1", 20).messages.take(3))
        val result = repository.save(window)
        assertTrue("必须返回未写入，而且不能让异常逃出保存协程", result is ChatRepository.SaveResult.Refused)

        val logsAfter = storeDir.listFiles { f -> f.name.endsWith(".jsonl") }.orEmpty()
            .map { it.name to it.length() }.toSet()
        assertEquals("日志文件与字节数一个都不能变", logsBefore, logsAfter)
    }
}
