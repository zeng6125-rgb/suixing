package com.suixing.agent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * **孤儿日志**（有 `<id>.*.jsonl`、没有可读 meta）不许被清空（评审 M-3，不可逆）。
 *
 * 真实路径：`.meta` 被隔离（改名 `.meta.corrupt`）之后，下次启动的迁移会以为"store 里没有这个会话"，
 * 用 `newMeta`（`logBytes = 0`）去 append —— 原来会把还在那儿的日志 `setLength(0)` 后再写。
 */
class OrphanLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val chatsDir: File get() = File(folder.root, "chats")
    private val storeDir: File get() = File(chatsDir, "store")
    private val store: ChatStore get() = ChatStore(chatsDir)

    @Test
    fun `appending to a meta that claims empty never truncates an existing log`() {
        val seed = (1..6).map {
            StoredMessage(id = "m$it", role = if (it % 2 == 1) Roles.USER else Roles.ASSISTANT, text = "第 $it 条")
        }
        val meta = store.append(store.newMeta(Conversation(
            id = "c1", title = "t", createdAt = 1L, updatedAt = 1L, messages = seed,
        )), seed)
        val logFile = storeDir.listFiles { f -> f.name.startsWith("c1.") && f.name.endsWith(".jsonl") }!!.single()
        val bytesBefore = logFile.length()
        assertTrue("前提：日志非空", bytesBefore > 0)

        // 模拟"meta 丢了/读不出来"：用一份全新的空 meta 去 append。
        val emptyMeta = store.newMeta(Conversation(
            id = "c1", title = "t", createdAt = 1L, updatedAt = 1L, messages = emptyList(),
        ))
        val threw = runCatching { store.append(emptyMeta, seed.take(1)) }.isFailure
        assertTrue("必须拒绝，而不是把孤儿日志清空再写", threw)
        assertEquals("日志一个字节都不能变", bytesBefore, logFile.length())
        // 原 meta 仍然完整可读。
        assertEquals(6, store.readMeta("c1")!!.messageCount)
        assertEquals(meta.logBytes, bytesBefore)
    }
}
