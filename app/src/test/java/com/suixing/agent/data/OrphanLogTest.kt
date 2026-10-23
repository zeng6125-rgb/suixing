package com.suixing.agent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * **孤儿日志**（有 `<id>.*.jsonl`、meta 文件已经不在）不许被清空。
 *
 * 与它相对的是**合法自愈残留**：`newMeta`(0/0) 刚建、日志已经写了一点、meta 还没改名就崩了 ——
 * 那按 `ChatStore` 规则 1 是允许的，下一个 writer 截回水位继续写即可（**不能**拒绝）。
 *
 * 判据必须能区分这两者：**meta 文件是否还在**。只按"0/0 + 文件非空"判断会把自愈路径永久堵死
 * （评审 8 第 2 条）。
 */
class OrphanLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val chatsDir: File get() = File(folder.root, "chats")
    private val storeDir: File get() = File(chatsDir, "store")
    private val store: ChatStore get() = ChatStore(chatsDir)

    private fun seed(id: String, n: Int): List<StoredMessage> = (1..n).map {
        StoredMessage(id = "$id-m$it", role = if (it % 2 == 1) Roles.USER else Roles.ASSISTANT, text = "第 $it 条")
    }

    private fun logOf(id: String): File =
        storeDir.listFiles { f -> f.name.startsWith("$id.") && f.name.endsWith(".jsonl") }!!.single()

    @Test
    fun `a chat whose meta is gone never has its log truncated`() {
        val messages = seed("c1", 6)
        store.append(store.newMeta(Conversation(
            id = "c1", title = "t", createdAt = 1L, updatedAt = 1L, messages = messages,
        )), messages)
        val log = logOf("c1")
        val bytesBefore = log.length()
        assertTrue(bytesBefore > 0)

        // 模拟 meta 被隔离（`storeMetas` 会把坏 meta 改名成 `.meta.corrupt`）⇒ meta 文件不在。
        storeDir.listFiles { f -> f.name == "c1.meta" }!!.single().delete()

        val threw = runCatching {
            store.append(
                store.newMeta(Conversation(
                    id = "c1", title = "t", createdAt = 1L, updatedAt = 1L, messages = messages,
                )),
                messages.take(1),
            )
        }.isFailure
        assertTrue("meta 不在 + 日志非空 ⇒ 必须拒绝，不能清空", threw)
        assertEquals("日志一个字节都不能变", bytesBefore, log.length())
    }

    @Test
    fun `a crash between the first append and the meta rename still heals`() {
        // 「首次追加快写完、meta 还没改名就崩了」：meta 是 0/0，日志已经有内容，**meta 文件在**。
        // 这是 `ChatStore` 规则 1 明确允许的自愈形态 —— 必须继续能写，
        // 否则该会话**所有**保存都会永久失败（评审 8 第 2 条）。
        val messages = seed("c2", 3)
        val meta = store.newMeta(Conversation(
            id = "c2", title = "t", createdAt = 1L, updatedAt = 1L, messages = messages,
        ))
        // 用公开 API 造出"日志已写、水位还是 0"的现场：先正常追加一次，再把 meta 改回 0/0。
        val written = store.append(meta, messages)
        store.writeMeta(written.copy(messageCount = 0, turnCount = 0, logBytes = 0, totalsAt = 0))

        // 现在：meta 文件在（0/0），日志非空 —— 必须能自愈，而不是永久拒写。
        val healed = store.append(store.readMeta("c2")!!, messages)
        assertEquals("必须能自愈：meta 跟上真实内容", 3, healed.messageCount)
        assertEquals(3, store.readMeta("c2")!!.messageCount)
    }
}
