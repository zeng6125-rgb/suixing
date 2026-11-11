package com.suixing.agent.ui.chat

import android.content.Context
import com.suixing.agent.agent.AgentEngine
import com.suixing.agent.agent.ApprovalGate
import com.suixing.agent.agent.planCompaction
import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.capability.CapabilityState
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ChatRepository
import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.Conversation
import com.suixing.agent.data.GoalStore
import com.suixing.agent.data.MemoryEntry
import com.suixing.agent.data.MemoryStore
import com.suixing.agent.data.ProviderConfig
import com.suixing.agent.data.Roles
import com.suixing.agent.data.SettingsRepository
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.UserProfileStore
import com.suixing.agent.llm.AssistantTurn
import com.suixing.agent.llm.ChatMessage
import com.suixing.agent.llm.LlmClient
import com.suixing.agent.service.AgentForegroundService
import com.suixing.agent.skills.Skill
import com.suixing.agent.skills.SkillStore
import com.suixing.agent.tools.ToolRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatModelSwitchHistoryTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        mockkObject(AgentForegroundService.Companion)
        every { AgentForegroundService.start(any(), any()) } returns Unit
        every { AgentForegroundService.stop(any()) } returns Unit
    }

    @After fun tearDown() {
        scope.cancel()
        unmockkObject(AgentForegroundService.Companion)
    }

    @Test fun `switching models keeps the saved summary across consecutive paged sends`() = verifyHistory(2)

    @Test fun `new rounds never make a partial window replace the saved history`() = verifyHistory(1)

    @Test fun `an accepted question cannot fill a missing historical message`() = verifyHistory(1, firstReply = false)

    private fun verifyHistory(summaryStartTurn: Int, firstReply: Boolean = true) = runBlocking {
        val context = mockk<Context>(relaxed = true) { every { filesDir } returns folder.root }
        val repository = ChatRepository(context)
        val messages = (1..13).flatMap { turn ->
            val turnMessages = listOf(
                StoredMessage("u$turn", Roles.USER,
                    if (turn == summaryStartTurn) "项目代号 cedar-kite，发布渠道 internal-only" else "问题 $turn"),
                StoredMessage("a$turn", Roles.ASSISTANT, "回答 $turn"),
            )
            if (turn == 1 && !firstReply) turnMessages.take(1) else turnMessages
        }
        val plan = checkNotNull(planCompaction(messages.drop((summaryStartTurn - 1) * 2)))
        val checkpoint = ContextCheckpoint("checkpoint", 1L, plan.coveredIds.first(), plan.coveredIds.last(),
            "项目代号 cedar-kite；发布渠道 internal-only", plan.foldedMessages, 100)
        assertEquals("u$summaryStartTurn", checkpoint.fromMessageId)
        assertEquals(ChatRepository.SaveResult.Written, repository.save(Conversation(
            id = "context", title = "上下文", providerId = "provider", model = "model-a",
            messages = messages, checkpoints = listOf(checkpoint),
        )))
        val opened = checkNotNull(repository.readTail("context"))
        assertEquals(messages.size, opened.messageCount)
        assertEquals(24, opened.messages.size)
        assertEquals("u2", opened.messages.first().id)

        val settingsFlow = MutableStateFlow(AppSettings(
            model = "model-a", subagentsEnabled = false,
            providers = listOf(ProviderConfig("provider", baseUrl = "https://example.com",
                models = listOf("model-a", "model-b"), enabled = listOf("model-a", "model-b"))),
        ))
        val settings = mockk<SettingsRepository> {
            every { flow } returns settingsFlow
            coEvery { current() } coAnswers { settingsFlow.value }
            coEvery { update(any()) } coAnswers {
                settingsFlow.value = firstArg<(AppSettings) -> AppSettings>()(settingsFlow.value)
                true
            }
        }
        val requests = mutableListOf<Pair<String, List<ChatMessage>>>()
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                requests += firstArg<AppSettings>().model to secondArg<List<ChatMessage>>().toList()
                AssistantTurn("完成", emptyList(), "stop")
            }
        }
        val capabilities = mockk<CapabilityHub> { every { state } returns MutableStateFlow(CapabilityState()) }
        val skills = mockk<SkillStore> {
            coEvery { listHeaders() } returns emptyList<Skill>()
            every { catalog(any()) } returns ""
        }
        val memory = mockk<MemoryStore> { every { entries } returns MutableStateFlow(emptyMap<String, MemoryEntry>()) }
        val profile = mockk<UserProfileStore> { every { text } returns MutableStateFlow("") }
        val goals = mockk<GoalStore>(relaxed = true) { every { changes } returns MutableStateFlow(null) }
        val registry = ToolRegistry::class.java.getDeclaredConstructor().let { it.isAccessible = true; it.newInstance() }
        val engine = AgentEngine(llm = client)
        val environment = mockk<ChatRuntimeEnvironment>(relaxed = true) {
            every { appContext } returns context
            every { appScope } returns scope
            every { this@mockk.engine } returns engine
            every { gate } returns ApprovalGate()
            every { this@mockk.settings } returns settings
            every { chats } returns repository
            every { this@mockk.goals } returns goals
            every { this@mockk.capabilities } returns capabilities
            every { this@mockk.skills } returns skills
            every { this@mockk.memory } returns memory
            every { this@mockk.profile } returns profile
            every { tools } returns registry
        }
        val runtime = ChatRuntime(scope, environment)
        runtime._state.value = ChatUiState(
            conversationId = opened.id, title = opened.title, messages = opened.messages,
            messageCount = opened.messageCount, turnCount = opened.turnCount,
            providerId = opened.providerId, model = opened.model, checkpoints = opened.checkpoints,
        )
        runtime.chooseModel("provider", "model-b")
        withTimeout(10_000) { repository.conversations.first { chats -> chats.any { it.id == "context" && it.model == "model-b" } } }

        val questions = listOf("沿用原发布渠道，继续第一步", "沿用原发布渠道，继续第二步")
        val acceptedSizes = mutableListOf<Int>()
        for ((index, question) in questions.withIndex()) {
            acceptedSizes += runtime.state.value.messages.size + 1
            assertTrue(runtime.send(question))
            withTimeout(10_000) {
                while (requests.size <= index || runtime.sendGate.isPreparing("context") || runtime.jobs.containsKey("context")) delay(1)
            }
        }
        assertEquals(listOf(25, 27), acceptedSizes)
        assertEquals(messages.size, runtime.state.value.messageCount)
        assertEquals(listOf("model-b", "model-b"), requests.map { it.first })
        val summaryCounts = requests.map { (_, messages) ->
            messages.count { it.role == Roles.SYSTEM && it.text.startsWith("[上下文摘要]") }
        }
        for ((index, request) in requests.withIndex()) {
            val summaries = request.second.filter { it.role == Roles.SYSTEM && it.text.startsWith("[上下文摘要]") }
            assertEquals("第 ${index + 1} 次请求必须携带原摘要；各次摘要数量=$summaryCounts",
                listOf("[上下文摘要] ${checkpoint.summary}"), summaries.map { it.text })
            assertEquals(questions[index], request.second.last { it.role == Roles.USER }.text)
        }
    }
}
