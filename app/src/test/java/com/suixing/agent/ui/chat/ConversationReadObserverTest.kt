package com.suixing.agent.ui.chat

import com.suixing.agent.data.ConversationReadEvent
import com.suixing.agent.data.ConversationReadState
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationReadObserverTest {
    private val unread = ConversationReadState(unread = setOf("a", "b"))
    private val message = StoredMessage(id = "reply", role = Roles.ASSISTANT, text = "done")

    @Test fun `loading with an old body cannot acknowledge the new conversation`() {
        val screen = ChatUiState(conversationId = "a", loading = true, messages = listOf(message))
        assertEquals(unread.unread, unread.accept(ConversationReadEvent.Viewing(screen.readableConversationId())).unread)
    }

    @Test fun `a failed empty tail read cannot acknowledge a conversation with stored messages`() {
        val screen = ChatUiState(conversationId = "a", loading = false, messageCount = 100)
        assertEquals(unread.unread, unread.accept(ConversationReadEvent.Viewing(screen.readableConversationId())).unread)
    }

    @Test fun `opening a loaded body acknowledges only its own completion`() {
        val screen = ChatUiState(conversationId = "a", loading = false, messages = listOf(message))
        assertEquals(setOf("b"), unread.accept(ConversationReadEvent.Viewing(screen.readableConversationId())).unread)
    }
}
