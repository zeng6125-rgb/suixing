package com.suixing.agent.ui.chat

import com.suixing.agent.agent.AgentEvent
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ChatRepository
import com.suixing.agent.data.Conversation
import com.suixing.agent.data.GoalStore
import com.suixing.agent.data.LiveDraft
import com.suixing.agent.data.Roles
import com.suixing.agent.data.SettingsRepository
import com.suixing.agent.data.StoredMessage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLiveConcurrencyTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val chats = mockk<ChatRepository>(relaxed = true) {
        every { conversations } returns MutableStateFlow(emptyList<Conversation>())
    }
    private val settings = mockk<SettingsRepository>(relaxed = true) {
        every { flow } returns MutableStateFlow(AppSettings())
    }
    private val goals = mockk<GoalStore>(relaxed = true) {
        every { changes } returns MutableStateFlow(null)
    }
    private val runtime = ChatRuntime(scope, mockk<ChatRuntimeEnvironment>(relaxed = true) {
        every { this@mockk.chats } returns this@ChatLiveConcurrencyTest.chats
        every { this@mockk.settings } returns this@ChatLiveConcurrencyTest.settings
        every { this@mockk.goals } returns this@ChatLiveConcurrencyTest.goals
        every { appScope } returns scope
    })

    @After fun tearDown() { scope.cancel() }

    @Test fun `a tail flush preserves tool events delivered while it applies text`() {
        val applying = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val firstRead = AtomicBoolean(true)
        val text = LivePart.Text("A")
        val parts = object : AbstractList<LivePart>() {
            override val size = 1
            override fun get(index: Int): LivePart {
                if (Thread.currentThread().name == "tail-flush" && firstRead.compareAndSet(true, false)) {
                    applying.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                }
                return text
            }
        }
        install(parts = parts)
        val flush = Worker("tail-flush") { runtime.flushDeltas("chat") }
        var event: Worker? = null
        try {
            assertTrue("the flush must reach the live parts", applying.await(5, TimeUnit.SECONDS))
            event = Worker("tool-event") {
                runtime.handleEvent("chat", AgentEvent.ToolStarted("read_file", "{}"))
                runtime.handleEvent("chat", AgentEvent.ToolMessage(StoredMessage(
                    id = "tool-result", role = Roles.TOOL, toolName = "read_file", text = "Result",
                )))
            }
            event.awaitCompletionOrSerialization()
        } finally {
            resume.countDown()
            flush.await()
            event?.await()
        }
        val saved = runtime.sessions.getValue("chat")
        assertTrue("the flush must not erase a delivered tool result", saved.messages.any { it.id == "tool-result" })
        assertTrue("the live tool block must remain", saved.parts.any { it is LivePart.Tools })
        assertEquals("AB", text.text)
    }

    @Test fun `a draft counts a pending delta once when it is published during snapshot creation`() = runBlocking {
        val projecting = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val firstRead = AtomicBoolean(true)
        val question = StoredMessage(id = "question", role = Roles.USER, text = "Question")
        val messages = object : AbstractList<StoredMessage>() {
            override val size = 1
            override fun get(index: Int): StoredMessage {
                if (firstRead.compareAndSet(true, false)) {
                    projecting.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                }
                return question
            }
        }
        install(messages = messages)
        val saved = CompletableDeferred<LiveDraft>()
        coEvery { chats.saveLive(any()) } coAnswers { saved.complete(firstArg()) }
        val writer = scope.launch { runtime.writeLiveDraft("chat") }
        var flush: Worker? = null
        try {
            assertTrue("the writer must reach background projection", projecting.await(5, TimeUnit.SECONDS))
            flush = Worker("draft-flush") { runtime.flushDeltas("chat") }
            flush.awaitCompletionOrSerialization()
            resume.countDown()
            flush.await()
            assertEquals("the published tail must not be appended a second time", "AB", withTimeout(5_000) { saved.await() }.text)
        } finally {
            resume.countDown()
            flush?.await()
            writer.cancelAndJoin()
        }
    }

    private fun install(
        parts: List<LivePart> = listOf(LivePart.Text("A")),
        messages: List<StoredMessage> = emptyList(),
    ) {
        runtime.putSession(RunSession(
            chatId = "chat", title = "Chat", assistantId = "", runId = "run",
            messages = messages, activeRuns = emptyMap(), parts = parts,
        ))
        runtime.deltaBuffer.locked { runtime.deltaBuffer.append("chat", "B", null) }
    }

    private class Worker(name: String, block: () -> Unit) {
        private val failure = AtomicReference<Throwable?>()
        private val thread = Thread({
            try { block() } catch (error: Throwable) { failure.set(error) }
        }, name).apply { start() }

        fun awaitCompletionOrSerialization() {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            // Release the first worker once the second has either finished or waits for its monitor.
            // This permits the fixed implementation to serialize both operations without deadlocking.
            while (thread.isAlive && thread.state != Thread.State.BLOCKED && System.nanoTime() < until) {
                Thread.yield()
            }
            assertTrue("the competing operation must run or wait for the first", !thread.isAlive || thread.state == Thread.State.BLOCKED)
        }

        fun await() {
            thread.join(5_000)
            assertFalse("the worker must complete", thread.isAlive)
            failure.get()?.let { throw AssertionError("worker failed", it) }
        }
    }
}
