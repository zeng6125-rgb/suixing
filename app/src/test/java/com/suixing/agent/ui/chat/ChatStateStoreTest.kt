package com.suixing.agent.ui.chat

import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test

class ChatStateStoreTest {
    @Test fun changingDraftOrStreamingDoesNotRestampOrReemitTheTranscript() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = ChatStateStore(scope)
            val messages = listOf(StoredMessage("q", Roles.USER, "question"))
            store.mutable.value = ChatUiState(conversationId = "chat", messages = messages)
            yield()
            val revision = store.state.value.messagesRevision
            store.mutable.value = store.mutable.value.copy(draft = Draft("draft"))
            yield()
            assertEquals(revision, store.state.value.messagesRevision)
            assertSame(messages, store.state.value.messages)
            val screens = mutableListOf<ChatUiState>()
            val reader = scope.launch { store.screen.collect { screens += it } }
            store.mutable.value = store.mutable.value.copy(streaming = "first", reasoningRevision = 1)
            store.mutable.value = store.mutable.value.copy(streaming = "second", reasoningRevision = 2)
            yield()
            assertTrue(store.active.value)
            assertEquals(1, screens.size)
            assertEquals("", screens.single().streaming)
            store.mutable.value = store.mutable.value.copy(messages = messages + StoredMessage("a", Roles.ASSISTANT, "answer"))
            yield()
            assertTrue(store.state.value.messagesRevision > revision)
            assertEquals(2, screens.size)
            reader.cancelAndJoin()
        } finally { scope.cancel() }
    }
}
