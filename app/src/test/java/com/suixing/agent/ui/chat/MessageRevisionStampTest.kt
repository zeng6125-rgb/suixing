package com.suixing.agent.ui.chat

import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.Roles
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

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

    @Test fun `stateIn uses one stamp collector with concurrent writers and many subscribers`() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val upstream = MutableSharedFlow<ChatUiState>()
            val stamp = MessageRevisionStamp()
            val active = AtomicInteger()
            val overlapped = AtomicInteger()
            val revisions = Collections.synchronizedList(ArrayList<Long>())
            val finished = CompletableDeferred<Unit>()
            val shared = upstream.map { snapshot ->
                if (active.incrementAndGet() != 1) overlapped.incrementAndGet()
                yield() // allow subscribers and writers to run while map is suspended
                val result = stamp.stamp(snapshot)
                revisions += result.messagesRevision
                active.decrementAndGet()
                if (snapshot.status == "finish") finished.complete(Unit)
                result
            }.stateIn(owner, SharingStarted.Eagerly, ChatUiState())
            repeat(8) { owner.launch { shared.collect { yield() } } }
            withTimeout(5_000) {
                upstream.subscriptionCount.first { it == 1 }
                coroutineScope {
                    repeat(4) { writer -> launch(Dispatchers.Default) {
                        repeat(50) { n ->
                            upstream.emit(ChatUiState(messages = listOf(StoredMessage(
                                id = "$writer-$n", role = Roles.USER,
                            ))))
                        }
                    } }
                }
                upstream.emit(ChatUiState(status = "finish"))
                finished.await()
            }
            assertEquals(0, overlapped.get())
            assertEquals(201, revisions.size)
            assertEquals((1L..201L).toList(), revisions.toList())
        } finally {
            owner.cancel()
        }
    }
}
