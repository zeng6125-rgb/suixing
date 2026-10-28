package com.suixing.agent.ui.chat

import com.suixing.agent.data.LiveDraft
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import org.junit.Assert.*
import org.junit.Test

class LiveDraftContentTest {
    private val message = StoredMessage(id = "m", role = Roles.TOOL, toolOutput = "before")
    private val draft = LiveDraft("chat", "run", 1L, "answer", "thinking", listOf(message))

    @Test fun `growing text rejects before walking unchanged message payloads`() {
        val unreadable = object : AbstractList<StoredMessage>() {
            override val size = 1
            override fun get(index: Int): StoredMessage = error("must not read message payload")
        }
        assertFalse(draft.copy(text = "answer grew", messages = unreadable).sameContentAs(draft))
    }

    @Test fun `same length hash collisions are still different recovery content`() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertFalse(draft.copy(text = "Aa").sameContentAs(draft.copy(text = "BB")))
        assertFalse(draft.copy(reasoning = "Aa").sameContentAs(draft.copy(reasoning = "BB")))
    }

    @Test fun `same row id with a new result still requires a draft save`() {
        val edited = draft.copy(messages = listOf(message.copy(toolOutput = "after!")))
        assertFalse(edited.sameContentAs(draft))
    }

    @Test fun `timestamp is ignored but chat and run identity are not`() {
        assertTrue(draft.copy(at = 2L).sameContentAs(draft))
        assertFalse(draft.copy(chatId = "other").sameContentAs(draft))
        assertFalse(draft.copy(runId = "other").sameContentAs(draft))
        assertFalse(draft.sameContentAs(null))
    }

    @Test fun `null thinking differs from empty thinking`() {
        assertFalse(draft.copy(reasoning = null).sameContentAs(draft.copy(reasoning = "")))
    }

    @Test fun `structurally equal immutable snapshots skip an unnecessary save`() {
        assertTrue(draft.copy(
            text = draft.text.toCharArray().concatToString(),
            reasoning = draft.reasoning!!.toCharArray().concatToString(),
            messages = listOf(message.copy()),
        ).sameContentAs(draft))
    }
}
