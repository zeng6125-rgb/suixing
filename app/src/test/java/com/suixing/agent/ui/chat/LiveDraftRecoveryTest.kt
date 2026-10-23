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
}
