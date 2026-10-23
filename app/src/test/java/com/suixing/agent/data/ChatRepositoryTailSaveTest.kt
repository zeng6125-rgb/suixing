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
 * **分页窗口回写**（真机闪退的根因）。
 *
 * 0.8.316 起，列表不再常驻全文，UI 只持有最近 N 轮（#30）。于是保存时手里的列表
 * **比盘上短** —— 这是分页的正常结果，不是 bug。
 *
 * 0.8.317~320 的实现把它当成"要截断"直接抛异常：
 *
 * ```
 * java.lang.IllegalStateException: refusing to truncate …: 92 of 2215 messages
 *   at ChatRepository$save$2.invokeSuspend(ChatRepository.kt:422)
 * ```
 *
 * 那个异常在 `viewModelScope.launch` 里没人接 ⇒ **一发送就闪退**（真机实测，
 * 崩溃记录在 dropbox 的 `data_app_crash`）。
 *
 * 正确的做法：**只把新增的那几条追加进日志**，历史一条不动。
 */
class ChatRepositoryTailSaveTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun repository(root: File): ChatRepository {
        val context = mockk<Context>()
        every { context.filesDir } returns root
        return ChatRepository(context)
    }

    private fun msg(id: String, text: String = id) = StoredMessage(
        id = id,
        role = Roles.USER,
        text = text,
    )

    @Test
    fun `a paged window is appended, not refused`() = runBlocking {
        val dir = tmp.newFolder("chats")
        val repository = repository(dir)

        // 盘上 6 条。
        val onDisk = (1..6).map { msg("m$it") }
        repository.save(Conversation(id = "c1", title = "t", messages = onDisk))

        // UI 只持有最后 3 条（窗口）+ 1 条新的 ⇒ 比盘上短。
        val window = onDisk.takeLast(3) + msg("m7")
        repository.save(Conversation(id = "c1", title = "t", messages = window))

        val all = repository.readAll("c1")
        assertEquals("历史一条都不能少", 7, all.size)
        assertEquals("第 1 条还在", "m1", all.first().id)
        assertEquals("新的一条在最后", "m7", all.last().id)
    }

    @Test
    fun `a paged window that is empty is refused but does not throw`() = runBlocking {
        val dir = tmp.newFolder("chats")
        val repository = repository(dir)
        repository.save(Conversation(id = "c1", title = "t", messages = (1..4).map { msg("m$it") }))

        // 空列表：没有"新增"可言 ⇒ 拒绝写入，但**不抛**（抛了 app 就崩）。
        val result = runCatching {
            repository.save(Conversation(id = "c1", title = "t", messages = emptyList()))
        }
        assertTrue("不许崩：" + result.exceptionOrNull()?.message, result.isSuccess)
        assertEquals("盘上原样", 4, repository.readAll("c1").size)
    }

    @Test
    fun `the meta of an appended window keeps the new title`() = runBlocking {
        val dir = tmp.newFolder("chats")
        val repository = repository(dir)
        val onDisk = (1..5).map { msg("m$it") }
        repository.save(Conversation(id = "c1", title = "旧标题", messages = onDisk))

        repository.save(
            Conversation(id = "c1", title = "新标题", messages = onDisk.takeLast(2) + msg("m6")),
        )

        val meta = repository.conversations.value.first { it.id == "c1" }
        assertEquals("标题要更新", "新标题", meta.title)
        assertEquals("条数要更新", 6, meta.messageCount)
    }

    @Test
    fun `a restored message over an empty log is accepted`() = runBlocking {
        // "新会话发出后 1 秒内进程被杀"的自愈路径：盘上 0 条（meta 0/0），草稿里有一条恢复消息。
        // 这条测试证明那次 save **真的被接受**（不是被"既不是全量也不是尾部"的护栏拒掉）——
        // 否则 `shouldSkipEmptBaseMerge` 里那个例外只是纸面自愈。
        val repository = repository(tmp.root)
        val first = StoredMessage(id = "m1", role = Roles.ASSISTANT, text = "（由「任务被停止」原因，对话中断）")
        val written = repository.save(
            Conversation(
                id = "heal", title = "t", createdAt = 1L, updatedAt = 1L, messages = listOf(first),
            ),
        )
        assertTrue("必须真的写下去", written is ChatRepository.SaveResult.Written)
        val onDisk = repository(tmp.root).apply { load() }.readAll("heal")
        assertEquals("恢复消息必须落盘", listOf("m1"), onDisk.map { it.id })
    }

    @Test
    fun `a shorter list over an existing log is refused and leaves the bytes alone`() = runBlocking {
        // 评审 17 指出：上一个用例（空日志）根本没进护栏分支（`diskHasLog == false` ⇒ 整段跳过），
        // 它只证明了"新建会话能首次写入"。**这条**才真正打到"既不是全量也不是尾部 ⇒ 拒写"那道分支
        // —— 而它在全仓原本 0 条测试。
        val repository = repository(tmp.root)
        repository.save(
            Conversation(
                id = "guard", title = "t", createdAt = 1L, updatedAt = 1L,
                messages = (1..4).map { msg("g$it") },
            ),
        )
        val log = File(tmp.root, "chats/store").listFiles { f -> f.name.startsWith("guard.") && f.name.endsWith(".jsonl") }!!.single()
        val bytes = log.length()

        val outcome = repository.save(
            Conversation(
                id = "guard", title = "t", createdAt = 1L, updatedAt = 1L,
                // **末尾也对不上**（盘上最后是 g4、首条是 g1）：这才是"既不是全量也不是尾部"。
                // 注意：只留 g4 会走"尾部窗口"分支（追加空列表、no-op），并不是拒绝 —— 我第一次就写错了。
                messages = listOf(msg("g2")),
            ),
        )
        // **绑到具体那条分支**，而不是只判"被拒"：拒绝原因来自
        // `ChatRepository.kt:739-746`（"既不是完整历史、也不是盘上末尾的一段"）。
        assertTrue("必须被拒", outcome is ChatRepository.SaveResult.Refused)
        val reason = (outcome as ChatRepository.SaveResult.Refused).reason
        assertTrue(
            "必须是被『既不是全量也不是尾部』那条拒绝，而不是别的路径：$reason",
            reason.contains("既不是完整历史"),
        )
        assertEquals("日志一个字节都不能变", bytes, log.length())
    }
}
