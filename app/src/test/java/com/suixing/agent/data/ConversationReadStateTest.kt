package com.suixing.agent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationReadStateTest {
    @Test fun `finishing the viewed chat stays read but finishing after switching adds a badge`() {
        var state = ConversationReadState().accept(ConversationReadEvent.Viewing("a"))
        state = state.accept(ConversationReadEvent.Completed("a"))
        assertEquals(emptySet<String>(), state.unread)
        state = state.accept(ConversationReadEvent.Viewing("b"))
            .accept(ConversationReadEvent.Completed("a"))
        assertEquals(setOf("a"), state.unread)
        state = state.accept(ConversationReadEvent.Viewing("a"))
        assertEquals(emptySet<String>(), state.unread)
    }

    @Test fun `parallel chats keep independent badges and leaving the foreground stops acknowledgement`() {
        var state = ConversationReadState().accept(ConversationReadEvent.Viewing("c"))
            .accept(ConversationReadEvent.Completed("a"))
            .accept(ConversationReadEvent.Completed("b"))
            .accept(ConversationReadEvent.Viewing("b"))
        assertEquals(setOf("a"), state.unread)
        state = state.accept(ConversationReadEvent.Viewing(null))
            .accept(ConversationReadEvent.Completed("b"))
        assertEquals(setOf("a", "b"), state.unread)
    }

    @Test fun `restored badges survive startup and opening a different chat`() {
        var state = ConversationReadState(unread = setOf("a", "b"))
        state = state.accept(ConversationReadEvent.Viewing(null))
            .accept(ConversationReadEvent.Viewing("c"))
        assertEquals(setOf("a", "b"), state.unread)
        state = state.accept(ConversationReadEvent.Viewing("a"))
        assertEquals(setOf("b"), state.unread)
    }

    @Test fun `deleting a chat removes its badge and its viewing identity`() {
        val state = ConversationReadState(unread = setOf("a", "b"), viewing = "a")
            .accept(ConversationReadEvent.Deleted("a"))
        assertEquals(setOf("b"), state.unread)
        assertNull(state.viewing)
    }

    @Test fun `empty placeholder identities cannot create or acknowledge real badges`() {
        val state = ConversationReadState(unread = setOf("a"))
            .accept(ConversationReadEvent.Viewing(""))
            .accept(ConversationReadEvent.Completed(" "))
        assertEquals(setOf("a"), state.unread)
        assertNull(state.viewing)
    }
}
