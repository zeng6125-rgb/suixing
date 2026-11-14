package com.suixing.agent.service

import com.suixing.agent.data.Conversation
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TailReadGateTest {

    private fun chat(id: String, count: Int = 3, updated: Long = 100L) =
        Conversation(id = id, title = id, messageCount = count, updatedAt = updated)

    private fun message(text: String) =
        StoredMessage(id = text, role = Roles.USER, text = text, timestamp = 1L)

    @Test fun `unchanged metadata reuses the successfully loaded tail`() = runBlocking {
        val gate = TailReadGate()
        var reads = 0
        repeat(100) {
            assertEquals(listOf(message("history")), gate.read(chat("a")) {
                reads++
                listOf(message("history"))
            })
        }
        assertEquals(1, reads)
    }

    @Test fun `new messages and switching chats replace the cached tail`() = runBlocking {
        val gate = TailReadGate()
        var reads = 0
        for (conversation in listOf(chat("a"), chat("a", 4), chat("a", 4, 200), chat("b"), chat("a"))) {
            val expected = listOf(message("tail ${conversation.id} ${conversation.messageCount} ${conversation.updatedAt}"))
            assertEquals(expected, gate.read(conversation) { reads++; expected })
        }
        assertEquals(5, reads)
    }

    @Test fun `a cancelled tail read is retried for the same metadata`() = runBlocking {
        val gate = TailReadGate()
        val started = CompletableDeferred<Unit>()
        val cancelled = launch {
            gate.read(chat("a")) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        cancelled.cancelAndJoin()
        assertEquals(listOf(message("restored")), gate.read(chat("a")) { listOf(message("restored")) })
    }

    @Test fun `overlapping same chat renders wait for loaded history`() = runBlocking {
        val gate = TailReadGate()
        val release = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            gate.read(chat("a")) {
                release.await()
                listOf(message("history"))
            }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            gate.read(chat("a")) { error("same tail should share the completed read") }
        }
        try {
            assertFalse("another render must not receive the empty cache", second.isCompleted)
            release.complete(Unit)
            assertEquals(listOf(message("history")), first.await())
            assertEquals(listOf(message("history")), second.await())
        } finally {
            release.complete(Unit)
            first.cancelAndJoin()
            second.cancelAndJoin()
        }
    }

    @Test fun `overlapping different chats never reuse the other chats history`() = runBlocking {
        val gate = TailReadGate()
        val release = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            gate.read(chat("a")) {
                release.await()
                listOf(message("a history"))
            }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            gate.read(chat("b")) { listOf(message("b history")) }
        }
        try {
            release.complete(Unit)
            first.await()
            assertEquals(listOf(message("b history")), second.await())
            assertEquals(
                listOf(message("b history")),
                gate.read(chat("b")) { error("b history should remain cached under b metadata") },
            )
        } finally {
            release.complete(Unit)
            first.cancelAndJoin()
            second.cancelAndJoin()
        }
    }
}
