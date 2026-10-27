package com.suixing.agent.ui.chat

import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.Roles
import org.junit.Assert.*
import org.junit.Test

class MessageRevisionStampTest {
    @Test fun `delta publications keep the same transcript revision`() {
        val stamp = MessageRevisionStamp()
        val source = ChatUiState(messages = listOf(StoredMessage(id = "a", role = Roles.USER, text = "q")))
        val first = stamp.stamp(source)
        repeat(20) { n ->
            assertEquals(first.messagesRevision, stamp.stamp(source.copy(streaming = "x".repeat(n))).messagesRevision)
        }
    }

    @Test fun `an edit with unchanged row ids advances exactly once`() {
        val stamp = MessageRevisionStamp()
        val source = ChatUiState(messages = listOf(StoredMessage(id = "a", role = Roles.USER, text = "before")))
        val first = stamp.stamp(source)
        val edited = source.copy(messages = source.messages.map { it.copy(text = "after") })
        val next = stamp.stamp(edited)
        assertEquals(first.messagesRevision + 1, next.messagesRevision)
        assertEquals(next.messagesRevision, stamp.stamp(edited.copy(status = "done")).messagesRevision)
    }
}
