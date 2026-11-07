package com.suixing.agent.agent

import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.capability.CapabilityState
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.ChildRunConfig
import com.suixing.agent.data.MemoryEntry
import com.suixing.agent.data.MemoryStore
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.UserProfileStore
import com.suixing.agent.llm.AssistantTurn
import com.suixing.agent.llm.ChatMessage
import com.suixing.agent.llm.LlmClient
import com.suixing.agent.llm.ToolCallRequest
import com.suixing.agent.skills.Skill
import com.suixing.agent.skills.SkillStore
import com.suixing.agent.tools.ToolGroup
import com.suixing.agent.tools.ToolRegistry
import com.suixing.agent.tools.ToolResult
import com.suixing.agent.tools.ToolSpec
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** Exercise the real loop and approval boundary, rather than duplicating ledger/config helpers. */
class AgentWorkflowVerificationTest {
    private val capabilities = mockk<CapabilityHub> { every { state } returns MutableStateFlow(CapabilityState()) }
    private val skills = mockk<SkillStore> {
        coEvery { listHeaders() } returns emptyList<Skill>()
        every { catalog(any()) } returns ""
    }
    private val memory = mockk<MemoryStore> { every { entries } returns MutableStateFlow(emptyMap<String, MemoryEntry>()) }
    private val profile = mockk<UserProfileStore> { every { text } returns MutableStateFlow("") }

    private fun registry(toolName: String = "echo", execute: (JsonObject) -> ToolResult = { ToolResult.success("same result") }): ToolRegistry =
        ToolRegistry::class.java.getDeclaredConstructor().let { it.isAccessible = true; it.newInstance() }.also { registry ->
            registry.add(object : ToolGroup() {
                override val specs = listOf(ToolSpec(toolName, "fixture", JsonObject(emptyMap())))
                override suspend fun execute(name: String, args: JsonObject) = execute(args)
            })
        }

    private suspend fun run(engine: AgentEngine, registry: ToolRegistry, settings: AppSettings,
        events: (AgentEvent) -> Unit = {}): List<StoredMessage> =
        engine.startRun("verification", "Verification", emptyList(), settings, registry,
            capabilities, skills, memory, profile, ApprovalGate(), events)

    @Test fun `a repeated parallel tool batch is fully answered before warning and the loop still finishes`() = runBlocking {
        val rounds = AtomicInteger()
        val calls = AtomicInteger()
        val events = CopyOnWriteArrayList<AgentEvent>()
        var prefix = emptyList<ChatMessage>()
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                val wire = secondArg<List<ChatMessage>>()
                if (rounds.incrementAndGet() == 1) {
                    prefix = wire.toList()
                    AssistantTurn("", (1..3).map { ToolCallRequest("call-$it", "echo", "{}") }, "tool_calls")
                } else {
                    assertEquals(prefix, wire.take(prefix.size))
                    assertEquals(listOf("tool", "tool", "tool", "user"), wire.takeLast(4).map { it.role })
                    assertTrue(wire.last().text.contains("重复调用提醒"))
                    assertTrue(wire.last().text.contains("3 次"))
                    AssistantTurn("finished after warning", emptyList(), "stop")
                }
            }
        }
        val result = withTimeout(10_000) {
            run(AgentEngine(llm = client), registry { calls.incrementAndGet(); ToolResult.success("same result") },
                AppSettings(approvalMode = ApprovalMode.AUTO, subagentsEnabled = false, maxToolRounds = 30, toolParallel = 3), events::add)
        }
        assertEquals(3, calls.get())
        assertEquals(2, rounds.get())
        assertEquals("finished after warning", result.last().text)
        assertEquals(1, events.filterIsInstance<AgentEvent.Finished>().size)
        assertTrue(events.none { it is AgentEvent.Failed })
    }

    @Test fun `stopping after a committed tool emits one visible summary and retains the partial text`() = runBlocking {
        val rounds = AtomicInteger()
        val waiting = CompletableDeferred<Unit>()
        val events = CopyOnWriteArrayList<AgentEvent>()
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                if (rounds.incrementAndGet() == 1) AssistantTurn("", listOf(ToolCallRequest("call", "echo", "{}")), "tool_calls")
                else {
                    arg<(String) -> Unit>(3)("partial answer")
                    waiting.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        val engine = AgentEngine(llm = client)
        val running = async(Dispatchers.Default) {
            run(engine, registry(), AppSettings(approvalMode = ApprovalMode.AUTO, subagentsEnabled = false), events::add)
        }
        withTimeout(5_000) { waiting.await() }
        assertTrue(engine.cancel("verification"))
        withTimeout(5_000) { running.await() }
        val summaries = events.filterIsInstance<AgentEvent.AssistantMessage>().filter { it.message.traceNote?.contains("本次尝试") == true }
        assertEquals(1, summaries.size)
        assertEquals("partial answer", summaries.single().message.text)
        assertTrue(summaries.single().message.traceNote.orEmpty().contains("1 次工具调用"))
        assertTrue(summaries.single().message.traceNote.orEmpty().contains("echo"))
        assertEquals(1, events.filterIsInstance<AgentEvent.Finished>().size)
    }

    @Test fun `a child denies forbidden actions and unknown tools before executing side effects`() = runBlocking {
        val actions = mutableListOf<String>()
        val rounds = AtomicInteger()
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                assertEquals(listOf("ui_virtual_screen"), thirdArg<List<ToolSpec>>().map { it.name })
                if (rounds.incrementAndGet() == 1) AssistantTurn("", listOf(
                    ToolCallRequest("allowed", "ui_virtual_screen", """{"action":"status"}"""),
                    ToolCallRequest("blocked", "ui_virtual_screen", """{"action":"close"}"""),
                    ToolCallRequest("hidden", "write_file", """{"path":"should-not-exist"}"""),
                ), "tool_calls") else {
                    val results = secondArg<List<ChatMessage>>().filter { it.role == "tool" }
                    assertEquals(3, results.size)
                    assertTrue(results[1].text.contains("不允许"))
                    assertTrue(results[2].text.contains("不允许"))
                    AssistantTurn("done", emptyList(), "stop")
                }
            }
        }
        val child = AgentEngine(llm = client, childConfig = ChildRunConfig(
            allowedTools = listOf("ui_virtual_screen"), allowedActions = mapOf("ui_virtual_screen" to listOf("status"))))
        withTimeout(10_000) {
            run(child, registry("ui_virtual_screen") { args ->
                actions += (args["action"] as JsonPrimitive).content
                ToolResult.success("status response")
            }, AppSettings(approvalMode = ApprovalMode.AUTO, subagentsEnabled = false))
        }
        assertEquals(listOf("status"), actions)
    }

    @Test fun `normal parent completion waits for an accepted child even without an explicit wait tool`() = runBlocking {
        val childStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val parentRounds = AtomicInteger()
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                if (firstArg<AppSettings>().subagentsEnabled) {
                    if (parentRounds.incrementAndGet() == 1) AssistantTurn("",
                        listOf(ToolCallRequest("spawn", "spawn_agent", """{"task":"independent"}""")), "tool_calls")
                    else AssistantTurn("parent answer", emptyList(), "stop")
                } else {
                    childStarted.complete(Unit)
                    release.await()
                    AssistantTurn("child answer", emptyList(), "stop")
                }
            }
        }
        val engine = AgentEngine(llm = client)
        val running = async(Dispatchers.Default) {
            run(engine, registry(), AppSettings(approvalMode = ApprovalMode.AUTO, subagentsEnabled = true))
        }
        try {
            withTimeout(5_000) { childStarted.await() }
            withTimeout(5_000) {
                engine.runsFlow.value.getValue("verification").state.first {
                    it is RunState.Running && it.stats.note == "等待子任务完成"
                }
            }
            assertFalse(running.isCompleted)
            assertFalse(engine.canCreateSubagent("verification"))
            release.complete(Unit)
            assertEquals("parent answer", withTimeout(5_000) { running.await() }.last().text)
        } finally { release.complete(Unit); running.cancel() }
    }
}
