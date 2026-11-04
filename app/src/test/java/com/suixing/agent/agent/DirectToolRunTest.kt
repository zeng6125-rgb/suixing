package com.suixing.agent.agent

import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.capability.CapabilityState
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.GoalStore
import com.suixing.agent.data.MemoryEntry
import com.suixing.agent.data.MemoryStore
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.UserProfileStore
import com.suixing.agent.data.TranscriptRedaction
import java.util.concurrent.atomic.AtomicInteger
import com.suixing.agent.llm.AssistantTurn
import com.suixing.agent.llm.ChatMessage
import com.suixing.agent.llm.LlmClient
import com.suixing.agent.llm.ToolCallRequest
import com.suixing.agent.skills.Skill
import com.suixing.agent.skills.SkillStore
import com.suixing.agent.tools.GoalTools
import com.suixing.agent.tools.ToolRegistry
import com.suixing.agent.tools.ToolGroup
import com.suixing.agent.tools.ToolSpec
import com.suixing.agent.tools.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DirectToolRunTest {
    @get:Rule val folder = TemporaryFolder()
    private val capabilities = mockk<CapabilityHub> {
        every { state } returns MutableStateFlow(CapabilityState())
    }
    private val skills = mockk<SkillStore> {
        coEvery { listHeaders() } returns emptyList<Skill>()
        every { catalog(any()) } returns ""
    }
    private val memory = mockk<MemoryStore> {
        every { entries } returns MutableStateFlow(emptyMap<String, MemoryEntry>())
    }
    private val profile = mockk<UserProfileStore> { every { text } returns MutableStateFlow("") }
    private fun registry(goals: GoalStore): ToolRegistry = ToolRegistry::class.java.getDeclaredConstructor().let {
        it.isAccessible = true
        it.newInstance()
    }.also { it.add(GoalTools(goals)) }

    @Test fun `explicit goal is persisted and in the first model request with its tool card`() = runBlocking {
        val goals = GoalStore(folder.newFile("goal.json"))
        val registry = registry(goals)
        val events = mutableListOf<AgentEvent>()
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                val wire = secondArg<List<ChatMessage>>()
                assertEquals("finish the migration", goals.read().text)
                assertTrue(wire.first().text.contains("## Current goal\nfinish the migration"))
                assertEquals("/goal finish the migration", wire.first { it.role == "user" }.text)
                val call = wire.single { it.role == "assistant" }.toolCalls.single()
                assertEquals("explicit-goal", call.id)
                assertEquals("goal", call.name)
                assertEquals("explicit-goal", wire.single { it.role == "tool" }.toolCallId)
                assertTrue(wire.single { it.role == "tool" }.text.contains("finish the migration"))
                AssistantTurn("starting the migration", emptyList(), "stop")
            }
        }
        val engine = AgentEngine(llm = client, goals = goals)
        val produced = engine.startRun("chat", "title",
            listOf(StoredMessage("user", Roles.USER, "/goal finish the migration")),
            AppSettings(approvalMode = ApprovalMode.AUTO, subagentsEnabled = false, autoProfile = false, autoCompress = false),
            registry, capabilities, skills, memory, profile, ApprovalGate(), { events += it },
            initialTool = ToolCallRequest("explicit-goal", "goal", """{"text":"finish the migration"}"""))
        assertEquals(listOf(Roles.ASSISTANT, Roles.TOOL, Roles.ASSISTANT), produced.map { it.role })
        assertEquals("explicit-goal", produced[0].toolCalls.single().id)
        assertEquals("explicit-goal", produced[1].toolCallId)
        assertEquals("starting the migration", produced.last().text)
        assertTrue(events.any { it is AgentEvent.ToolStarted && it.name == "goal" })
        assertTrue(events.any { it is AgentEvent.ToolFinished && it.name == "goal" && it.ok })
        assertFalse(engine.isRunning("chat"))
        coVerify(exactly = 1) { client.complete(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `approval waits with a tool card before changing the goal or contacting the model`() = runBlocking {
        withTimeout(5000) {
            val goals = GoalStore(folder.newFile("approval-goal.json"))
            goals.write("old goal")
            val gate = ApprovalGate()
            val client = mockk<LlmClient> {
                coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                    assertEquals("approved goal", goals.read().text)
                    AssistantTurn("approved", emptyList(), "stop")
                }
            }
            val engine = AgentEngine(llm = client, goals = goals)
            val events = mutableListOf<AgentEvent>()
            val running = async {
                engine.startRun("chat", "title", listOf(StoredMessage("u", Roles.USER, "/goal approved goal")),
                    AppSettings(approvalMode = ApprovalMode.ASK, subagentsEnabled = false, autoProfile = false, autoCompress = false),
                    registry(goals), capabilities, skills, memory, profile, gate, { events += it },
                    initialTool = ToolCallRequest("approve-goal", "goal", """{"text":"approved goal"}"""))
            }
            gate.pending.first { "chat" in it }
            assertEquals("old goal", goals.read().text)
            assertTrue(events.any { it is AgentEvent.ToolStarted && it.name == "goal" })
            coVerify(exactly = 0) { client.complete(any(), any(), any(), any(), any(), any(), any(), any()) }
            gate.resolve("chat", approved = true)
            assertEquals("approved", running.await().last().text)
            assertTrue(gate.pending.value.isEmpty())
        }
    }

    @Test fun `a command steered into a live run executes at the next safe boundary`() = runBlocking {
        withTimeout(5000) {
            val goals = GoalStore(folder.newFile("steered-goal.json"))
            goals.write("old goal")
            val firstRequest = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var requests = 0
            val client = mockk<LlmClient> {
                coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                    if (++requests == 1) {
                        firstRequest.complete(Unit)
                        release.await()
                        AssistantTurn("first answer", emptyList(), "stop")
                    } else {
                        val wire = secondArg<List<ChatMessage>>()
                        assertEquals("live goal", goals.read().text)
                        assertTrue(wire.first().text.contains("## Current goal\nlive goal"))
                        assertTrue(wire.any { it.role == "user" && it.text.contains("/goal live goal") })
                        assertTrue(wire.any { it.role == "tool" && it.toolCallId == "live-command" })
                        AssistantTurn("continuing with the new goal", emptyList(), "stop")
                    }
                }
            }
            val engine = AgentEngine(llm = client, goals = goals)
            val events = mutableListOf<AgentEvent>()
            val running = async {
                engine.startRun("chat", "title", listOf(StoredMessage("u", Roles.USER, "start")),
                    AppSettings(approvalMode = ApprovalMode.AUTO, subagentsEnabled = false, autoProfile = false, autoCompress = false),
                    registry(goals), capabilities, skills, memory, profile, ApprovalGate(), { events += it })
            }
            firstRequest.await()
            assertTrue(engine.steer("chat", "steer", "/goal live goal", immediate = false,
                directTool = ToolCallRequest("live-command", "goal", """{"text":"live goal"}""")))
            assertEquals("old goal", goals.read().text)
            release.complete(Unit)
            val result = running.await()
            assertEquals(2, requests)
            assertEquals(1, result.count { it.role == Roles.TOOL && it.toolCallId == "live-command" })
            assertEquals("continuing with the new goal", result.last().text)
            assertTrue(events.indexOfFirst { it is AgentEvent.Steered } < events.indexOfFirst { it is AgentEvent.ToolStarted })
        }
    }

    @Test fun `stopping an explicit command answers its tool card without issuing a model request`() = runBlocking {
        withTimeout(5000) {
            val goals = GoalStore(folder.newFile("stopped-goal.json"))
            val entered = CompletableDeferred<Unit>()
            val registry = registry(goals).also { it.add(object : ToolGroup() {
                override val specs = listOf(ToolSpec("parking", "wait until cancelled", JsonObject(emptyMap())))
                override suspend fun execute(name: String, args: JsonObject): ToolResult {
                    entered.complete(Unit)
                    return CompletableDeferred<ToolResult>().await()
                }
            }) }
            val client = mockk<LlmClient>(relaxed = true)
            val engine = AgentEngine(llm = client, goals = goals)
            val events = mutableListOf<AgentEvent>()
            val running = async {
                engine.startRun("chat", "title", listOf(StoredMessage("u", Roles.USER, "/parking")),
                    AppSettings(approvalMode = ApprovalMode.AUTO, subagentsEnabled = false, autoProfile = false, autoCompress = false),
                    registry, capabilities, skills, memory, profile, ApprovalGate(), { events += it },
                    initialTool = ToolCallRequest("stopped-command", "parking", "{}"))
            }
            entered.await()
            assertTrue(engine.cancel("chat"))
            running.await()
            val result = events.filterIsInstance<AgentEvent.ToolMessage>().single().message
            assertEquals("stopped-command", result.toolCallId)
            assertEquals(false, result.toolOk)
            assertEquals("（已中断：任务被停止，这次调用没有结果）", result.toolOutput)
            assertFalse(engine.isRunning("chat"))
            coVerify(exactly = 0) { client.complete(any(), any(), any(), any(), any(), any(), any(), any()) }
        }
    }

    @Test fun `a failed command does not acknowledge a later goal in the same urgent batch`() = runBlocking {
        withTimeout(5000) {
            val goals = GoalStore(folder.newFile("batch-goal.json"))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val tools = registry(goals).also { it.add(object : ToolGroup() {
                override val specs = listOf(ToolSpec("parking", "wait", JsonObject(emptyMap())),
                    ToolSpec("failure", "fail", JsonObject(emptyMap())))
                override suspend fun execute(name: String, args: JsonObject): ToolResult = if (name == "parking") {
                    entered.complete(Unit)
                    release.await()
                    ToolResult.success("ready")
                } else ToolResult.failure("not found")
            }) }
            val events = mutableListOf<AgentEvent>()
            val client = mockk<LlmClient> {
                coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } returns
                    AssistantTurn("", listOf(ToolCallRequest("first-tool", "parking", "{}")), "tool_calls")
            }
            val engine = AgentEngine(llm = client, goals = goals)
            val running = async {
                engine.startRun("chat", "title", listOf(StoredMessage("u", Roles.USER, "start")),
                    AppSettings(approvalMode = ApprovalMode.AUTO, subagentsEnabled = false, autoProfile = false, autoCompress = false),
                    tools, capabilities, skills, memory, profile, ApprovalGate(), { events += it })
            }
            entered.await()
            assertTrue(engine.steer("chat", "failed-input", "/failure", immediate = true,
                directTool = ToolCallRequest("failed-call", "failure", "{}")))
            assertTrue(engine.steer("chat", "later-input", "/goal later goal", immediate = true,
                directTool = ToolCallRequest("later-call", "goal", """{"text":"later goal"}""")))
            release.complete(Unit)
            running.await()
            assertTrue(events.any { it is AgentEvent.Steered && it.id == "failed-input" })
            assertFalse("the unstarted goal was removed from its queue", events.any { it is AgentEvent.Steered && it.id == "later-input" })
            assertTrue(goals.read().text.isEmpty())
            assertFalse(events.any { it is AgentEvent.ToolStarted && it.name == "goal" })
        }
    }

    @Test fun `stopping a command keeps the unstarted tail unacknowledged and retractable`() = runBlocking {
        withTimeout(5000) {
            val goals = GoalStore(folder.newFile("cancel-batch-goal.json"))
            val firstEntered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val directEntered = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val tools = registry(goals).also { it.add(object : ToolGroup() {
                override val specs = listOf(ToolSpec("parking", "wait", JsonObject(emptyMap())))
                override suspend fun execute(name: String, args: JsonObject): ToolResult {
                    if (calls.incrementAndGet() == 1) {
                        firstEntered.complete(Unit)
                        release.await()
                        return ToolResult.success("ready")
                    }
                    directEntered.complete(Unit)
                    return CompletableDeferred<ToolResult>().await()
                }
            }) }
            val events = mutableListOf<AgentEvent>()
            val client = mockk<LlmClient> {
                coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } returns
                    AssistantTurn("", listOf(ToolCallRequest("first-tool", "parking", "{}")), "tool_calls")
            }
            val engine = AgentEngine(llm = client, goals = goals)
            val running = async {
                engine.startRun("chat", "title", listOf(StoredMessage("u", Roles.USER, "start")),
                    AppSettings(approvalMode = ApprovalMode.AUTO, subagentsEnabled = false, autoProfile = false, autoCompress = false),
                    tools, capabilities, skills, memory, profile, ApprovalGate(), { events += it })
            }
            firstEntered.await()
            assertTrue(engine.steer("chat", "long-input", "/parking", immediate = true,
                directTool = ToolCallRequest("long-call", "parking", "{}")))
            assertTrue(engine.steer("chat", "later-input", "/goal later goal", immediate = true,
                directTool = ToolCallRequest("later-call", "goal", """{"text":"later goal"}""")))
            release.complete(Unit)
            directEntered.await()
            assertFalse(events.any { it is AgentEvent.Steered && it.id == "later-input" })
            assertTrue("queued tail must still be retractable", engine.retract("chat", "later-input"))
            assertTrue(engine.cancel("chat"))
            running.await()
            assertFalse(events.any { it is AgentEvent.Steered && it.id == "later-input" })
            assertTrue(events.filterIsInstance<AgentEvent.ToolMessage>().any {
                it.message.toolCallId == "long-call" && it.message.toolOk == false })
        }
    }

    @Test fun `stored command text is masked while its real arguments still reach execution and this model request`() = runBlocking {
        val goals = GoalStore(folder.newFile("private-command.json"))
        val secret = "clipboard-private-hunter2"
        val command = ToolCallRequest("private-call", "clipboard_write", """{"text":"$secret"}""")
        val user = StoredMessage("u", Roles.USER,
            TranscriptRedaction.commandForTranscript("/clipboard_write $secret", command.name, command.arguments))
        val tools = registry(goals).also { it.add(object : ToolGroup() {
            override val specs = listOf(ToolSpec("clipboard_write", "set", JsonObject(emptyMap())))
            override suspend fun execute(name: String, args: JsonObject): ToolResult {
                assertEquals("\"$secret\"", args["text"].toString())
                return ToolResult.success("clipboard updated")
            }
        }) }
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                val wire = secondArg<List<ChatMessage>>()
                assertFalse(wire.first { it.role == "user" }.text.contains(secret))
                assertTrue(wire.first { it.role == "assistant" }.toolCalls.single().arguments.contains(secret))
                AssistantTurn("done", emptyList(), "stop")
            }
        }
        val result = AgentEngine(llm = client, goals = goals).startRun("chat", "title", listOf(user),
            AppSettings(approvalMode = ApprovalMode.AUTO, subagentsEnabled = false, autoProfile = false, autoCompress = false),
            tools, capabilities, skills, memory, profile, ApprovalGate(), {}, initialTool = command)
        assertFalse((listOf(user) + result).joinToString().contains(secret))
    }

    @Test fun `denied explicit tool keeps a failed result card and does not start the model`() = runBlocking {
        val goals = GoalStore(folder.newFile("goal.json"))
        goals.write("keep the old goal")
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } returns
                AssistantTurn("must not run", emptyList(), "stop")
        }
        val gate = ApprovalGate()
        val engine = AgentEngine(llm = client, goals = goals)
        val result = engine.startRun("chat", "title",
            listOf(StoredMessage("user", Roles.USER, "/goal replace")),
            AppSettings(approvalMode = ApprovalMode.DENY, subagentsEnabled = false, autoProfile = false, autoCompress = false),
            registry(goals), capabilities, skills, memory, profile, gate, {},
            initialTool = ToolCallRequest("denied-goal", "goal", """{"text":"replace"}"""))
        assertEquals("keep the old goal", goals.read().text)
        assertEquals(2, result.size)
        assertEquals(Roles.TOOL, result.last().role)
        assertEquals(false, result.last().toolOk)
        assertEquals("denied-goal", result.last().toolCallId)
        assertTrue(gate.pending.value.isEmpty())
        assertFalse(engine.isRunning("chat"))
        coVerify(exactly = 0) { client.complete(any(), any(), any(), any(), any(), any(), any(), any()) }
    }
}
