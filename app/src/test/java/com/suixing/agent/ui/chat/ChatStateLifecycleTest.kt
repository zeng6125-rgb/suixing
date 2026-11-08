package com.suixing.agent.ui.chat

import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ChatStateLifecycleTest {
    @Test fun streamingOnlyReplacesTheRunGroup() {
        val original = ChatUiState(conversationId = "chat", draft = Draft("unsent"))
        val next = original.copy(streaming = "answer", reasoningRevision = 1)
        assertSame(original.session, next.session)
        assertSame(original.transcript, next.transcript)
        assertSame(original.input, next.input)
        assertNotSame(original.run, next.run)
        assertSame(next, next.copy())
        assertEquals(original.screenSnapshot(), next.screenSnapshot())
    }

    @Test fun inputAndModelChangesKeepTheirOtherLifecycleGroups() {
        val original = ChatUiState(conversationId = "chat", liveRunId = "run", runningHere = true)
        val draft = original.copy(draft = Draft("typed"))
        assertNotSame(original.input, draft.input)
        assertSame(original.session, draft.session)
        assertSame(original.transcript, draft.transcript)
        assertSame(original.run, draft.run)
        val model = draft.copy(providerId = "provider", model = "model", approvalMode = ApprovalMode.AUTO)
        assertNotSame(draft.session, model.session)
        assertSame(draft.transcript, model.transcript)
        assertSame(draft.run, model.run)
        assertSame(draft.input, model.input)
        assertEquals("provider", model.session.providerId)
        assertEquals("run", model.run.liveRunId)
    }

    @Test fun independentReadersDoNotReceiveTokenOnlyChanges() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = ChatStateStore(scope)
            store.mutable.value = ChatUiState(conversationId = "chat", liveRunId = "run")
            val sessions = mutableListOf<ChatSessionState>()
            val transcripts = mutableListOf<ChatTranscriptState>()
            val inputs = mutableListOf<ChatInputState>()
            val runs = mutableListOf<ChatRunState>()
            scope.launch { store.session.collect { sessions += it } }
            scope.launch { store.transcript.collect { transcripts += it } }
            scope.launch { store.input.collect { inputs += it } }
            scope.launch { store.run.collect { runs += it } }
            val firstTranscript = store.state.value.transcript
            repeat(3) { token ->
                store.mutable.value = store.mutable.value.copy(streaming = "token-$token", reasoningRevision = token + 1)
                yield()
                assertSame(firstTranscript, store.state.value.transcript)
            }
            assertEquals(1, sessions.size)
            assertEquals(1, transcripts.size)
            assertEquals(1, inputs.size)
            assertEquals(4, runs.size)
            store.mutable.value = store.mutable.value.copy(draft = Draft("new input"))
            yield()
            assertEquals(2, inputs.size)
            assertEquals(1, sessions.size)
            assertEquals(1, transcripts.size)
            assertEquals(4, runs.size)
        } finally { scope.cancel() }
    }

    @Test fun replacingAnEqualMessageWindowUsesIdentityWithoutDeepEquality() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = ChatStateStore(scope)
            val message = StoredMessage("q", Roles.USER, "question")
            val first = NoEqualityList(message)
            store.mutable.value = ChatUiState(conversationId = "chat", messages = first)
            yield()
            val revision = store.state.value.messagesRevision
            val second = NoEqualityList(message)
            store.mutable.value = store.mutable.value.copy(messages = second)
            yield()
            assertSame(second, store.state.value.messages)
            assertTrue(store.state.value.messagesRevision > revision)
            assertNotSame(first, second)
        } finally { scope.cancel() }
    }

    @Test fun switchingConversationCreatesAnIsolatedSnapshot() {
        val first = ChatUiState(conversationId = "first", draft = Draft("private"), runningHere = true)
        val second = ChatUiState(conversationId = "second")
        assertEquals("private", first.input.draft.text)
        assertEquals("", second.input.draft.text)
        assertFalse(second.run.runningHere)
        assertEquals("second", second.session.conversationId)
    }

    private class NoEqualityList(private val message: StoredMessage) : AbstractList<StoredMessage>() {
        override val size: Int get() = 1
        override fun get(index: Int): StoredMessage {
            require(index == 0)
            return message
        }
        override fun equals(other: Any?): Boolean = error("Message windows must not use deep equality")
        override fun hashCode(): Int = error("Message windows must not use deep hashing")
    }
}
