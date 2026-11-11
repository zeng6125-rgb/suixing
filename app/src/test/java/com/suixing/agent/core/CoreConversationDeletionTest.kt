package com.suixing.agent.core

import android.content.Context
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import com.suixing.agent.agent.AgentEngine
import com.suixing.agent.agent.ApprovalGate
import com.suixing.agent.agent.RunState
import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.capability.CapabilityState
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ChatRepository
import com.suixing.agent.data.Conversation
import com.suixing.agent.data.ConversationReads
import com.suixing.agent.data.MemoryStore
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.UsageStore
import com.suixing.agent.data.UserProfileStore
import com.suixing.agent.data.WindowsSafeFileSystem
import com.suixing.agent.llm.AssistantTurn
import com.suixing.agent.llm.LlmClient
import com.suixing.agent.tools.ToolRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoreConversationDeletionTest {
    @get:Rule val folder = TemporaryFolder()
    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notifications = Channel<String>(Channel.UNLIMITED)
    private val deleted = Channel<String>(Channel.UNLIMITED)
    private val started = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()
    private lateinit var reads: ConversationReads
    private lateinit var chats: ChatRepository
    private lateinit var engine: AgentEngine
    private val engineField = Core::class.java.getDeclaredField("engine").apply { isAccessible = true }
    private val usageField = Core::class.java.getDeclaredField("usage").apply { isAccessible = true }
    private val previousEngine = engineField.get(null)
    private val previousUsage = usageField.get(null)

    @Before fun setUp() {
        val context = mockk<Context> { every { filesDir } returns folder.root }
        val preferences = PreferenceDataStoreFactory.create(
            storage = OkioStorage(
                fileSystem = WindowsSafeFileSystem,
                serializer = PreferencesSerializer,
                producePath = { File(folder.root, "reads.preferences_pb").absolutePath.toPath() },
            ),
            scope = owner,
        )
        reads = ConversationReads(
            preferences,
            owner,
            onUnreadCompletion = { notifications.trySend("show:$it") },
            onViewed = { notifications.trySend("cancel:$it") },
        )
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                started.complete(Unit)
                release.await()
                AssistantTurn("done", emptyList(), "stop")
            }
        }
        engine = AgentEngine(llm = client, onCompleted = reads::completed)
        engineField.set(null, engine)
        usageField.set(null, UsageStore(context))
        chats = ChatRepository(
            context,
            beforeDeleted = Core::beforeConversationDeleted,
            onDeleted = { id ->
                deleted.trySend(id)
                reads.forget(id)
            },
        )
    }

    @After fun tearDown() = runBlocking {
        owner.coroutineContext[Job]!!.cancelAndJoin()
        engineField.set(null, previousEngine)
        usageField.set(null, previousUsage)
    }

    private fun start(chat: Conversation) = owner.async {
        val registry = mockk<ToolRegistry> {
            every { all() } returns emptyList()
            every { isHidden(any()) } returns false
            every { promptSummary() } returns ""
        }
        engine.startRun(
            chatId = chat.id,
            title = chat.title,
            history = chat.messages,
            settings = AppSettings().copy(subagentsEnabled = false, autoCompress = false, autoProfile = false),
            registry = registry,
            capabilities = mockk<CapabilityHub> { every { state } returns MutableStateFlow(CapabilityState()) },
            skills = mockk(),
            memory = mockk<MemoryStore> { every { entries } returns MutableStateFlow(emptyMap()) },
            profile = mockk<UserProfileStore> { every { text } returns MutableStateFlow("") },
            gate = ApprovalGate(),
            onEvent = {},
        )
    }

    private suspend fun unread(expected: Set<String>) = withTimeout(5_000) {
        reads.unread.first { it == expected }
    }

    @Test fun `a running conversation cannot be deleted before its normal completion`() = runBlocking {
        withTimeout(10_000) {
            val chat = chats.create("running").copy(
                messages = listOf(StoredMessage("question", "user", "question")),
            )
            assertEquals(ChatRepository.SaveResult.Written, chats.save(chat))
            val log = File(chats.chatFile(chat.id)!!.path)
            val transcript = log.readText()
            val listed = chats.conversations.value
            val run = start(chat)
            started.await()
            assertTrue(engine.isRunning(chat.id))

            val result = runCatching { chats.delete(chat.id) }

            assertTrue("a running conversation must be refused before deletion", result.exceptionOrNull() is IllegalStateException)
            assertTrue(log.isFile)
            assertEquals(transcript, log.readText())
            assertEquals(listed, chats.conversations.value)
            assertEquals(chat.messages, chats.readAll(chat.id))
            assertTrue(engine.isRunning(chat.id))
            assertFalse(run.isCompleted)
            assertTrue(deleted.tryReceive().isFailure)
            assertTrue(notifications.tryReceive().isFailure)
            assertEquals(emptySet<String>(), reads.unread.value)

            release.complete(Unit)
            run.await()
            unread(setOf(chat.id))
            assertTrue(engine.stateOf(chat.id).value is RunState.Done)
            assertFalse(engine.isRunning(chat.id))
            assertEquals("show:${chat.id}", notifications.tryReceive().getOrNull())
            assertTrue(notifications.tryReceive().isFailure)
        }
    }

    @Test fun `an idle conversation still deletes its transcript and completion notification`() = runBlocking {
        withTimeout(10_000) {
            val chat = chats.create("idle")
            val log = File(chats.chatFile(chat.id)!!.path)
            val run = start(chat)
            started.await()
            release.complete(Unit)
            run.await()
            unread(setOf(chat.id))
            assertFalse(engine.isRunning(chat.id))
            assertEquals("show:${chat.id}", notifications.tryReceive().getOrNull())

            chats.delete(chat.id)

            unread(emptySet())
            assertFalse(log.exists())
            assertEquals(emptyList<Conversation>(), chats.conversations.value)
            assertEquals(chat.id, deleted.tryReceive().getOrNull())
            assertEquals("cancel:${chat.id}", notifications.tryReceive().getOrNull())
            assertTrue(notifications.tryReceive().isFailure)
        }
    }
}
