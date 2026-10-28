package com.suixing.agent.ui.chat

import com.suixing.agent.data.LiveDraft
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `liveDraftRecovery` 的**判重**：正常结束/停止之后不能把同一段内容再补一遍。
 *
 * 最容易漏的形态（评审 9 建议项）：**正文为空、只有思考内容**。引擎在"还没吐正文"时会把
 * `text = ""` + 思考全文发出来并落盘，草稿的 text 也是空 ⇒ 前缀判重失效 ⇒ 再补一条
 * 带同样思考的助理消息 ⇒ 用户看到**重复的思考卡**。
 */
class LiveDraftRecoveryTest {

    private fun landed(reasoning: String, runId: String = "r1") = StoredMessage(
        id = "m1", role = Roles.ASSISTANT, text = "", reasoning = reasoning, runId = runId,
    )

    @Test
    fun `a thinking-only draft does not duplicate a card that already landed`() {
        val draft = LiveDraft(chatId = "c1", runId = "r1", at = 1L, text = "", reasoning = "我想了很久")
        val restored = liveDraftRecovery(draft, listOf(landed("我想了很久")))
        assertTrue("已经落过同样的思考内容，就不该再补一条", restored.isEmpty())
    }

    @Test
    fun `a thinking-only draft still comes back when nothing landed`() {
        val draft = LiveDraft(chatId = "c1", runId = "r1", at = 1L, text = "", reasoning = "我想了很久")
        val restored = liveDraftRecovery(draft, emptyList())
        assertEquals("没落过就必须补回来（否则思考内容彻底丢了）", 1, restored.size)
        assertEquals("我想了很久", restored.single().reasoning)
    }

    @Test
    fun `an answer whose prefix already landed is not duplicated`() {
        val draft = LiveDraft(chatId = "c1", runId = "r1", at = 1L, text = "这是一段很长的回答正文")
        val stored = StoredMessage(
            id = "m1", role = Roles.ASSISTANT, text = "这是一段很长的回答正文（继续）", runId = "r1",
        )
        assertTrue(liveDraftRecovery(draft, listOf(stored)).isEmpty())
    }

    @Test
    fun `a stale thinking draft does not duplicate a card that grew after the last tick`() {
        // **主场景**（评审 10 的 M1）：草稿是"最多 1 秒前"的快照，落盘那条比它**更长**
        // （停止前最后一秒还在想）。用相等判重会认为"没落盘" ⇒ 再补一张重复的思考卡。
        val draft = LiveDraft(chatId = "c1", runId = "r1", at = 1L, text = "", reasoning = "我想")
        val grown = StoredMessage(
            id = "m1", role = Roles.ASSISTANT, text = "", reasoning = "我想了很久", runId = "r1",
        )
        assertTrue("草稿是前缀 ⇒ 已经落过，不该再补", liveDraftRecovery(draft, listOf(grown)).isEmpty())
    }

    @Test
    fun `a draft that is NOT a prefix still comes back`() {
        // 反向：草稿里有一段盘上没有的内容 ⇒ 必须补回来（不能因为"同 runId 有 thinking"就丢掉）。
        val draft = LiveDraft(chatId = "c1", runId = "r1", at = 1L, text = "", reasoning = "完全不同的想法")
        val other = StoredMessage(
            id = "m1", role = Roles.ASSISTANT, text = "", reasoning = "我想了很久", runId = "r1",
        )
        assertEquals("内容不同 ⇒ 必须恢复", 1, liveDraftRecovery(draft, listOf(other)).size)
    }

    @Test fun `one recovery scan ignores another run and still de-duplicates stored tool ids`() {
        val tool = StoredMessage("tool", Roles.TOOL, runId = "r1", toolOutput = "result")
        val draft = LiveDraft("c1", "r1", 1L, "unfinished", messages = listOf(tool))
        val unrelated = StoredMessage("answer", Roles.ASSISTANT, "unfinished complete", runId = "r2")
        val recovered = liveDraftRecovery(draft, listOf(tool, unrelated))
        assertEquals(1, recovered.size)
        assertEquals(Roles.ASSISTANT, recovered.single().role)
        assertTrue(recovered.single().text.startsWith("unfinished"))
    }

    @Test fun `prefix comparison keeps trim semantics for unicode whitespace`() {
        val draft = LiveDraft("c1", "r1", 1L, "answer")
        val stored = StoredMessage("m", Roles.ASSISTANT, "\u2003\nanswer complete\t\u2003", runId = "r1")
        assertTrue(liveDraftRecovery(draft, listOf(stored)).isEmpty())
    }

    @Test fun `trailing whitespace removed by trim cannot satisfy a longer probe`() {
        val prefix = "x".repeat(63) + " "
        val draft = LiveDraft("c1", "r1", 1L, prefix + "more")
        val shorter = StoredMessage("m", Roles.ASSISTANT, prefix, runId = "r1")
        assertEquals(1, liveDraftRecovery(draft, listOf(shorter)).size)
    }
}
