package com.suixing.agent.agent

import android.content.Context
import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.capability.CapabilityState
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ChatRepository
import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.Conversation
import com.suixing.agent.data.MemoryEntry
import com.suixing.agent.data.MemoryStore
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.UserProfileStore
import com.suixing.agent.llm.AssistantTurn
import com.suixing.agent.llm.ChatMessage
import com.suixing.agent.llm.LlmClient
import com.suixing.agent.skills.Skill
import com.suixing.agent.skills.SkillStore
import com.suixing.agent.tools.ToolRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CheckpointWireIntegrationTest {
    @get:Rule val folder = TemporaryFolder()

    private val capabilities = mockk<CapabilityHub> { every { state } returns MutableStateFlow(CapabilityState()) }
    private val skills = mockk<SkillStore> {
        coEvery { listHeaders() } returns emptyList<Skill>()
        every { catalog(any()) } returns ""
    }
    private val memory = mockk<MemoryStore> { every { entries } returns MutableStateFlow(emptyMap<String, MemoryEntry>()) }
    private val profile = mockk<UserProfileStore> { every { text } returns MutableStateFlow("") }
    private val registry = ToolRegistry::class.java.getDeclaredConstructor().let { it.isAccessible = true; it.newInstance() }

    private fun repository(): ChatRepository {
        val context = mockk<Context>()
        every { context.filesDir } returns folder.root
        return ChatRepository(context)
    }

    private fun conversation(compressed: Boolean): Conversation {
        val messages = (1..6).flatMap { turn ->
            listOf(
                StoredMessage(id = "u$turn", role = Roles.USER,
                    text = if (turn == 1) "项目代号 cedar-kite，发布渠道 internal-only" else "问题 $turn"),
                StoredMessage(id = "a$turn", role = Roles.ASSISTANT, text = "回答 $turn"),
            )
        }
        val checkpoint = ContextCheckpoint("checkpoint", 1L, "u1", "a4",
            "项目代号 cedar-kite；发布渠道 internal-only", 8, 100)
        return Conversation(id = "context", title = "上下文", providerId = "provider", model = "model-a",
            messages = messages, checkpoints = if (compressed) listOf(checkpoint) else emptyList())
    }

    private suspend fun run(
        engine: AgentEngine,
        conversation: Conversation,
        history: List<StoredMessage>,
        resumeCheckpoint: ContextCheckpoint? = null,
    ): List<StoredMessage> = engine.startRun(
        chatId = conversation.id,
        title = conversation.title,
        history = history,
        settings = AppSettings(model = conversation.model!!, subagentsEnabled = false),
        registry = registry,
        capabilities = capabilities,
        skills = skills,
        memory = memory,
        profile = profile,
        gate = ApprovalGate(),
        onEvent = {},
        checkpoints = conversation.checkpoints,
        resumeCheckpoint = resumeCheckpoint,
    )

    @Test fun `switching model keeps the old transcript in the first uncompressed request`() = runBlocking {
        val repository = repository()
        val original = conversation(compressed = false)
        assertEquals(ChatRepository.SaveResult.Written, repository.save(original))
        val selected = original.copy(model = "model-b")
        assertEquals(ChatRepository.SaveResult.Written, repository.save(selected))
        var sentModel = ""
        var request = emptyList<ChatMessage>()
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                sentModel = firstArg<AppSettings>().model
                request = secondArg<List<ChatMessage>>().toList()
                AssistantTurn("完成", emptyList(), "stop")
            }
        }
        val question = StoredMessage(id = "after-switch", role = Roles.USER, text = "沿用之前的发布渠道")
        val wire = repository.readWire(selected.id, selected.checkpoints)
        run(AgentEngine(llm = client), selected, wire.messages + question, wire.checkpoint)

        assertEquals("model-b", sentModel)
        assertTrue(request.any { it.role == Roles.USER && it.text.contains("cedar-kite") })
        assertEquals(question.text, request.last { it.role == Roles.USER }.text)
    }

    @Test fun `the saved summary survives a model switch and every following disk wire request`() = runBlocking {
        val repository = repository()
        var stored = conversation(compressed = true)
        assertEquals(ChatRepository.SaveResult.Written, repository.save(stored))
        val requests = mutableListOf<Pair<String, List<ChatMessage>>>()
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                requests += firstArg<AppSettings>().model to secondArg<List<ChatMessage>>().toList()
                AssistantTurn("完成", emptyList(), "stop")
            }
        }
        val engine = AgentEngine(llm = client)
        run(engine, stored, stored.messages)
        stored = stored.copy(model = "model-b")
        assertEquals(ChatRepository.SaveResult.Written, repository.save(stored))
        val questions = (1..2).map { StoredMessage(id = "switch-$it", role = Roles.USER, text = "按原发布渠道继续 $it") }
        for (question in questions) {
            val diskWire = repository.readWire(stored.id, stored.checkpoints)
            assertFalse("不为摘要重读已经压缩的正文", diskWire.messages.any { it.id == "u2" })
            val produced = run(engine, stored, diskWire.messages + question, diskWire.checkpoint)
            stored = stored.copy(messages = stored.messages + question + produced)
            assertEquals(ChatRepository.SaveResult.Written, repository.save(stored))
        }

        assertEquals(listOf("model-a", "model-b", "model-b"), requests.map { it.first })
        for ((index, request) in requests.withIndex()) {
            val summaries = request.second.filter { it.role == Roles.SYSTEM && it.text.startsWith("[上下文摘要]") }
            assertEquals("请求 $index 必须保留保存的摘要", listOf("[上下文摘要] ${stored.checkpoints.single().summary}"), summaries.map { it.text })
            assertTrue("请求 $index 保留压缩后的尾部", request.second.any { it.text == "问题 5" })
            assertFalse("请求 $index 不重放已经压缩的原文", request.second.any { it.role == Roles.USER && it.text.contains("cedar-kite") })
            if (index > 0) assertEquals(questions[index - 1].text, request.second.last { it.role == Roles.USER }.text)
        }
    }
}
