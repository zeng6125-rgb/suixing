package com.suixing.agent.ui.chat

import com.suixing.agent.agent.ApprovalGate
import com.suixing.agent.agent.RunState
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ChatRepository
import com.suixing.agent.data.Conversation
import com.suixing.agent.data.GoalStore
import com.suixing.agent.data.SettingsRepository
import com.suixing.agent.data.StoredMessage
import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The production runtime can be exercised without constructing Core or an Android ViewModel. */
class ChatRuntimeTest {
    private lateinit var runtimeScope: CoroutineScope

    @Before fun setUp() { runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) }
    @After fun tearDown() { runtimeScope.cancel() }

    private fun environment(scope: CoroutineScope): ChatRuntimeEnvironment {
        val chats = mockk<ChatRepository>(relaxed = true) {
            every { conversations } returns MutableStateFlow(emptyList<Conversation>())
        }
        val settings = mockk<SettingsRepository>(relaxed = true) {
            every { flow } returns MutableStateFlow(AppSettings())
        }
        val goals = mockk<GoalStore>(relaxed = true) {
            every { changes } returns MutableStateFlow(null)
        }
        return mockk(relaxed = true) {
            every { this@mockk.chats } returns chats
            every { this@mockk.settings } returns settings
            every { this@mockk.goals } returns goals
            every { appScope } returns scope
        }
    }

    @Test fun constructionDoesNotStartStorageRecoveryBeforeTheLifecycleAttaches() = runBlocking {
        val environment = environment(runtimeScope)
        val chats = environment.chats
        val goals = environment.goals
        val runtime = ChatRuntime(runtimeScope, environment)
        assertEquals("", runtime.state.value.conversationId)
        coVerify(exactly = 0) { chats.readLastOpened() }
        coVerify(exactly = 0) { goals.read() }
    }

    @Test fun aNotificationConversationCannotBeOverwrittenBySuspendedStartupRecovery(): Unit = runBlocking {
        val environment = environment(runtimeScope)
        val original = Conversation(id = "a", title = "原会话", messageCount = 1)
        val target = Conversation(id = "b", title = "通知会话", messageCount = 1)
        val openedOriginal = original.copy(messages = listOf(StoredMessage("a-message", "user", "原问题")))
        val openedTarget = target.copy(messages = listOf(StoredMessage("b-message", "user", "通知回复")))
        every { environment.chats.conversations } returns MutableStateFlow(listOf(original, target))
        every { environment.engine.stateOf(any()) } returns MutableStateFlow<RunState>(RunState.Idle)
        coEvery { environment.settings.current() } returns AppSettings()
        coEvery { environment.chats.readLastOpened() } returns original.id
        coEvery { environment.chats.readLive(any()) } returns null
        coEvery { environment.chats.readTailWindow(target.id) } returns null
        coEvery { environment.chats.readTail(target.id) } returns openedTarget
        val restoreStarted = CompletableDeferred<Unit>()
        val releaseRestore = CompletableDeferred<Unit>()
        lateinit var recoveryJob: Job
        coEvery { environment.chats.readTail(original.id) } coAnswers {
            recoveryJob = currentCoroutineContext()[Job]!!
            restoreStarted.complete(Unit)
            releaseRestore.await()
            openedOriginal
        }
        val runtime = ChatRuntime(runtimeScope, environment)
        runtime.observe()
        withTimeout(5_000) { restoreStarted.await() }

        runtime.openConversation(target)
        withTimeout(5_000) {
            runtime.state.first { it.conversationId == target.id && !it.loading && it.messages.isNotEmpty() }
        }
        releaseRestore.complete(Unit)
        withTimeout(5_000) { recoveryJob.join() }

        assertEquals(target.id, runtime.state.value.conversationId)
        assertEquals(openedTarget.messages, runtime.state.value.messages)
        assertEquals(target.id, runtime.lastOpenedId)
    }

    @Test fun normalStartupRecoveryRestoresTheSavedConversationAndItsMessages(): Unit = runBlocking {
        val environment = environment(runtimeScope)
        val original = Conversation(id = "a", title = "原会话", messageCount = 1)
        val opened = original.copy(messages = listOf(StoredMessage("a-message", "user", "原问题")))
        every { environment.chats.conversations } returns MutableStateFlow(listOf(original))
        every { environment.engine.stateOf(any()) } returns MutableStateFlow<RunState>(RunState.Idle)
        coEvery { environment.settings.current() } returns AppSettings()
        coEvery { environment.chats.readLastOpened() } returns original.id
        coEvery { environment.chats.readLive(any()) } returns null
        lateinit var recoveryJob: Job
        coEvery { environment.chats.readTail(original.id) } coAnswers {
            recoveryJob = currentCoroutineContext()[Job]!!
            opened
        }
        val runtime = ChatRuntime(runtimeScope, environment)
        runtime.observe()
        withTimeout(5_000) { runtime.state.first { it.conversationId == original.id && !it.loading } }
        withTimeout(5_000) { recoveryJob.join() }

        assertEquals(opened.messages, runtime.state.value.messages)
        assertEquals(original.id, runtime.lastOpenedId)
    }

    @Test fun draftsAndAttachmentsBelongToTheirOwnConversation() = runBlocking {
        val runtime = ChatRuntime(runtimeScope, environment(runtimeScope))
        val first = mockk<Attachment>()
        val second = mockk<Attachment>()
        runtime.onDraftChange("a", Draft("draft a", imageNames = listOf("a")))
        runtime.onDraftChange("b", Draft("draft b", imageNames = listOf("b")))
        runtime.addAttachment("a", first)
        runtime.addAttachment("b", second)
        runtime.backfillDraftText("a", "restored")
        assertEquals(Draft("restored", imageNames = listOf("a")), runtime.draftFor("a"))
        assertEquals("draft b", runtime.draftFor("b").text)
        assertSame(first, runtime.attachmentFor("a"))
        assertSame(second, runtime.attachmentFor("b"))
        runtime.removeAttachmentAt("a", 8)
        assertSame(first, runtime.attachmentFor("a"))
        runtime.removeAttachmentAt("a", 0)
        assertTrue(runtime.attachmentsFor("a").isEmpty())
        assertSame(second, runtime.attachmentFor("b"))
    }

    @Test fun aFileReferencePreservesTheDraftAndItsAttachmentMarkers() = runBlocking {
        val runtime = ChatRuntime(runtimeScope, environment(runtimeScope))
        runtime._state.value = ChatUiState(conversationId = "a")
        runtime.onDraftChange("a", Draft("existing", imageNames = listOf("image")))
        assertTrue(runtime.referenceFile("/workspace/report.txt"))
        val draft = runtime.draftFor("a")
        assertTrue(draft.text.contains("existing"))
        assertTrue(draft.text.contains("/workspace/report.txt"))
        assertEquals(listOf("image"), draft.imageNames)
    }

    @Test fun clearingOneDraftCannotClearAnotherConversationOrItsAttachment() = runBlocking {
        val runtime = ChatRuntime(runtimeScope, environment(runtimeScope))
        val other = mockk<Attachment>()
        runtime._state.value = ChatUiState(conversationId = "a", draft = Draft("shown"))
        runtime.onDraftChange("a", Draft("shown"))
        runtime.onDraftChange("b", Draft("other"))
        runtime.addAttachment("b", other)
        runtime.clearDraft("a")
        assertEquals(Draft(), runtime._state.value.draft)
        assertEquals(Draft(), runtime.draftFor("a"))
        assertEquals("other", runtime.draftFor("b").text)
        assertSame(other, runtime.attachmentFor("b"))
    }

    @Test fun theTranscriptCacheRetainsBothIdentityAndDiskBackedOwnership() = runBlocking {
        val runtime = ChatRuntime(runtimeScope, environment(runtimeScope))
        val messages = listOf(StoredMessage(id = "message", role = "assistant", text = "answer"))
        runtime.cacheMessages("a", messages, diskBacked = true)
        assertSame(messages, runtime.cachedMessages("a"))
        assertTrue(runtime.cacheIsDiskBacked("a"))
        runtime.onDraftChange("a", Draft("typing"))
        assertSame(messages, runtime.cachedMessages("a"))
        runtime.cacheMessages("b", emptyList(), diskBacked = false)
        assertFalse(runtime.cacheIsDiskBacked("b"))
    }

    @Test fun anOldViewportDisposalCannotUnbindTheNewConversation() = runBlocking {
        val runtime = ChatRuntime(runtimeScope, environment(runtimeScope))
        var oldCaptured = 0
        var newCaptured = 0
        val oldCapture = { oldCaptured++; Unit }
        val newCapture = { newCaptured++; Unit }
        runtime.bindViewport("a", oldCapture)
        runtime.bindViewport("b", newCapture)
        runtime.unbindViewport(oldCapture)
        runtime._state.value = ChatUiState(conversationId = "b")
        runtime.presentViewport("a")
        assertNull(runtime.presentedConversation.value)
        runtime.presentViewport("b")
        assertEquals("b", runtime.presentedConversation.value)
        runtime.leaveViewport()
        assertEquals(0, oldCaptured)
        assertEquals(1, newCaptured)
        assertNull(runtime.presentedConversation.value)
    }

    @Test fun disposalCancelsOnlyItsOwnedJobsAndApprovalPrompts() = runBlocking {
        val environment = environment(runtimeScope)
        val gate = mockk<ApprovalGate>(relaxed = true)
        every { environment.gate } returns gate
        val runtime = ChatRuntime(runtimeScope, environment)
        val first = Job()
        val second = Job()
        runtime.jobs["a"] = first
        runtime.jobs["b"] = second
        runtime.dispose()
        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
        assertTrue(runtime.jobs.isEmpty())
        verify(exactly = 1) { gate.cancelPending("a") }
        verify(exactly = 1) { gate.cancelPending("b") }
    }
}
