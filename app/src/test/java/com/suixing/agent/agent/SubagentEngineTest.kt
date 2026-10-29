package com.suixing.agent.agent

import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.capability.CapabilityState
import com.suixing.agent.data.*
import com.suixing.agent.llm.AssistantTurn
import com.suixing.agent.llm.ChatMessage
import com.suixing.agent.llm.LlmClient
import com.suixing.agent.llm.ToolCallRequest
import com.suixing.agent.skills.Skill
import com.suixing.agent.skills.SkillStore
import com.suixing.agent.tools.*
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class SubagentEngineTest {
    private val capabilities = mockk<CapabilityHub> { every { state } returns MutableStateFlow(CapabilityState()) }
    private val skills = mockk<SkillStore> {
        coEvery { listHeaders() } returns emptyList<Skill>()
        every { catalog(any()) } returns ""
    }
    private val memory = mockk<MemoryStore> { every { entries } returns MutableStateFlow(emptyMap<String, MemoryEntry>()) }
    private val profile = mockk<UserProfileStore> { every { text } returns MutableStateFlow("") }
    private fun registry(): ToolRegistry = ToolRegistry::class.java.getDeclaredConstructor().let {
        it.isAccessible = true
        it.newInstance()
    }.also { registry -> registry.add(object : ToolGroup() {
        override val specs = listOf(ToolSpec("echo", "echo", JsonObject(emptyMap())))
        override suspend fun execute(name: String, args: JsonObject) = ToolResult.success("echo-ok")
    }) }

    @Test fun `real parent loop runs independent children in parallel and collects approved results`() = runBlocking {
        val parentTurns = AtomicInteger()
        val childTurns = ConcurrentHashMap<String, AtomicInteger>()
        val bothChildren = CompletableDeferred<Unit>()
        val gate = ApprovalGate()
        val approvals = async {
            val pending = withTimeout(5000) { gate.pending.first { it.size == 2 } }
            assertTrue(pending.values.all { it.sourceTitle?.contains("子任务：") == true })
            pending.keys.forEach { gate.resolve(it, approved = true) }
        }
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                val settings = firstArg<AppSettings>()
                val wire = secondArg<List<ChatMessage>>()
                if (settings.subagentsEnabled) {
                    when (parentTurns.incrementAndGet()) {
                        1 -> AssistantTurn("", listOf(
                            ToolCallRequest("spawn-a", "spawn_agent", """{"task":"alpha","context":"only-alpha","name":"A"}"""),
                            ToolCallRequest("spawn-b", "spawn_agent", """{"task":"beta","context":"only-beta","name":"B"}""")), "tool_calls")
                        2 -> AssistantTurn("", listOf(ToolCallRequest("wait", "wait_agents", "{}")), "tool_calls")
                        else -> {
                            val results = wire.last { it.role == "tool" }.text
                            assertTrue(results.contains("result-alpha"))
                            assertTrue(results.contains("result-beta"))
                            AssistantTurn("combined-result", emptyList(), "stop")
                        }
                    }
                } else {
                    assertEquals(ApprovalMode.ASK, settings.approvalMode)
                    assertFalse(thirdArg<List<ToolSpec>>().any { it.name in SUBAGENT_TOOL_NAMES })
                    assertFalse(wire.any { it.text.contains("parent-secret") })
                    val input = wire.first { it.role == "user" }.text
                    val task = if (input.contains("alpha")) "alpha" else "beta"
                    assertTrue(input.contains("only-$task"))
                    assertFalse(input.contains(if (task == "alpha") "only-beta" else "only-alpha"))
                    val call = childTurns.computeIfAbsent(task) { AtomicInteger() }.incrementAndGet()
                    if (call == 1) {
                        if (childTurns.size == 2) bothChildren.complete(Unit)
                        withTimeout(5000) { bothChildren.await() }
                        AssistantTurn("", listOf(ToolCallRequest("echo-$task", "echo", "{}")), "tool_calls")
                    } else AssistantTurn("result-$task", emptyList(), "stop")
                }
            }
        }
        val engine = AgentEngine(llm = client)
        val result = withTimeout(10000) {
            engine.startRun("parent", "parent title", listOf(StoredMessage("user", Roles.USER, "parent-secret")),
                AppSettings(approvalMode = ApprovalMode.ASK, subagentsEnabled = true, maxToolRounds = 8), registry(),
                capabilities, skills, memory, profile, gate, onEvent = {})
        }
        approvals.await()
        assertEquals("combined-result", result.last().text)
        assertEquals(2, childTurns.size)
        assertTrue(engine.subagentsFlow.value.isEmpty())
        assertTrue(gate.pending.value.isEmpty())
        assertFalse(engine.isRunning("parent"))
    }

    @Test fun `live compression and checkpoint replay preserve the identical request prefix`() = runBlocking {
        val history = (1..20).map { StoredMessage("m$it", Roles.USER, "message-$it ".repeat(100)) }
        val settings = AppSettings(subagentsEnabled = false, approvalMode = ApprovalMode.AUTO, autoCompress = false)
        val registry = registry()
        var normalCalls = 0
        var compressed: List<ChatMessage> = emptyList()
        var replay: List<ChatMessage> = emptyList()
        val checkpoints = mutableListOf<ContextCheckpoint>()
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                val wire = secondArg<List<ChatMessage>>()
                if (thirdArg<List<ToolSpec>>().isEmpty()) AssistantTurn("saved decisions and unfinished work", emptyList(), "stop")
                else when (++normalCalls) {
                    1 -> AssistantTurn("", listOf(ToolCallRequest("compact", COMPRESS_CONTEXT_TOOL, "{}")), "tool_calls")
                    2 -> { compressed = wire.toList(); AssistantTurn("answer", emptyList(), "stop") }
                    else -> { replay = wire.toList(); AssistantTurn("next answer", emptyList(), "stop") }
                }
            }
        }
        val engine = AgentEngine(llm = client)
        val produced = engine.startRun("first", "title", history, settings, registry,
            capabilities, skills, memory, profile, ApprovalGate(), {}, onCheckpoint = { checkpoints += it })
        assertEquals(1, checkpoints.size)
        engine.startRun("second", "title", history + produced, settings, registry,
            capabilities, skills, memory, profile, ApprovalGate(), {}, checkpoints = checkpoints)
        assertEquals(compressed, replay.take(compressed.size))
        assertEquals("[上下文摘要] saved decisions and unfinished work", replay[1].text)
    }

    @Test fun `manual compression refuses truncated summaries without changing checkpoint`() = runBlocking {
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } returns
                AssistantTurn("partial", emptyList(), "length")
        }
        val history = (1..20).map { StoredMessage("m$it", Roles.USER, "text".repeat(100)) }
        var committed = false
        val reason = AgentEngine(llm = client).compressNow(history, AppSettings()) { committed = true }
        assertFalse(committed)
        assertTrue(reason.orEmpty().contains("未提交"))
    }

    @Test fun `parent stop actively aborts child HTTP rather than waiting for blocking read`() = runBlocking {
        val childStarted = CompletableDeferred<Unit>()
        val readReleased = CompletableDeferred<Unit>()
        val parentTurns = AtomicInteger()
        val call = mockk<okhttp3.Call> {
            every { cancel() } answers { readReleased.complete(Unit); Unit }
        }
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                if (firstArg<AppSettings>().subagentsEnabled) {
                    if (parentTurns.incrementAndGet() == 1) AssistantTurn("",
                        listOf(ToolCallRequest("spawn", "spawn_agent", """{"task":"blocking child"}""")), "tool_calls")
                    else awaitCancellation()
                } else {
                    arg<(okhttp3.Call) -> Unit>(5)(call)
                    childStarted.complete(Unit)
                    // Simulate a blocking body read that ignores coroutine cancellation.
                    withContext(NonCancellable) { withTimeout(4000) { readReleased.await() } }
                    throw java.io.IOException("Canceled")
                }
            }
        }
        val engine = AgentEngine(llm = client)
        val parent = async(Dispatchers.Default) {
            engine.startRun("parent", "title", emptyList(), AppSettings(approvalMode = ApprovalMode.AUTO), registry(),
                capabilities, skills, memory, profile, ApprovalGate(), {})
        }
        withTimeout(5000) { childStarted.await() }
        assertTrue(engine.cancel("parent"))
        withTimeout(2000) { parent.await() }
        assertTrue(readReleased.isCompleted)
        assertTrue(engine.subagentsFlow.value.isEmpty())
    }
}
