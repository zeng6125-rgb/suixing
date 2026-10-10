package com.suixing.agent.agent

import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.capability.CapabilityState
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.MemoryEntry
import com.suixing.agent.data.MemoryStore
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.llm.AssistantTurn
import com.suixing.agent.llm.ChatMessage
import com.suixing.agent.llm.LlmClient
import com.suixing.agent.llm.ToolCallRequest
import com.suixing.agent.skills.Skill
import com.suixing.agent.skills.SkillStore
import com.suixing.agent.tools.TIER_NONE
import com.suixing.agent.tools.Tool
import com.suixing.agent.tools.ToolRegistry
import com.suixing.agent.tools.ToolResult
import com.suixing.agent.tools.ToolSpec
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * What a stop leaves behind. [ParallelRunTest] guards the isolation contract — whose run gets
 * cancelled — but not what the stopped run writes: the user watched a chain-of-thought arrive
 * and a tool start, and both used to vanish from the transcript.
 *
 * Everything is asserted on the [AgentEvent] stream the engine already emits, so no internal
 * state is reached into: a stopped round keeps its reasoning, every tool call the model asked
 * for gets an answer, and the next run's replay still holds that turn.
 */
class InterruptPersistenceTest {

    private data class Deps(
        val capabilities: CapabilityHub,
        val skills: SkillStore,
        val memory: MemoryStore,
    )

    private fun quietDeps(): Deps = Deps(
        capabilities = mockk { every { state } returns MutableStateFlow(CapabilityState()) },
        skills = mockk {
            coEvery { list() } returns emptyList<Skill>()
            every { catalog(any()) } returns ""
        },
        memory = mockk {
            every { entries } returns MutableStateFlow(emptyMap<String, MemoryEntry>())
        },
    )

    private fun registryWith(tool: Tool): ToolRegistry = mockk {
        every { all() } returns listOf(tool)
        every { find(tool.spec.name) } returns tool
        every { promptSummary() } returns "- ${tool.spec.name}: test"
    }

    private fun autoSettings() = AppSettings().copy(approvalMode = ApprovalMode.AUTO, model = "deepseek-chat")

    /** A tool that announces it started and then parks: only the stop ends it. */
    private class ParkingTool : Tool {
        override val spec = ToolSpec("slow", "parks until the run is stopped", JsonObject(emptyMap()))
        override val tier = TIER_NONE
        val entered = CompletableDeferred<Unit>()

        override suspend fun run(args: JsonObject): ToolResult {
            entered.complete(Unit)
            CompletableDeferred<Unit>().await()
            return ToolResult.success("never reached")
        }
    }

    private suspend fun startRun(
        engine: AgentEngine,
        deps: Deps,
        registry: ToolRegistry,
        onEvent: (AgentEvent) -> Unit,
        history: List<StoredMessage> = emptyList(),
    ): List<StoredMessage> = engine.startRun(
        chatId = "c",
        title = "t",
        history = history,
        settings = autoSettings(),
        registry = registry,
        capabilities = deps.capabilities,
        skills = deps.skills,
        memory = deps.memory,
        gate = ApprovalGate(),
        onEvent = onEvent,
    )

    /** A model call that asks for [toolCalls] and returns; the tool is what parks. */
    private fun askingLlm(
        wires: MutableList<List<ChatMessage>>,
        turnFor: (call: Int) -> AssistantTurn,
    ): LlmClient = mockk {
        coEvery { complete(any(), any(), any(), any(), any(), any()) } coAnswers {
            val call = wires.size + 1
            wires += secondArg<List<ChatMessage>>()
            turnFor(call)
        }
    }

    @Test
    fun `a stopped round keeps the reasoning it had already streamed`() = runBlocking {
        val events = mutableListOf<AgentEvent>()
        val wires = mutableListOf<List<ChatMessage>>()
        val reasoningSeen = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        var cut = false
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any()) } coAnswers {
                wires += secondArg<List<ChatMessage>>()
                // The round streams its thinking, then sits on the wire until the stop cuts it.
                (invocation.args[4] as (String) -> Unit)("想了一半")
                reasoningSeen.complete(Unit)
                withTimeout(5_000) { hold.await() }
                if (cut) throw IOException("Canceled")
                AssistantTurn(text = "done", toolCalls = emptyList(), finishReason = "stop")
            }
        }
        val engine = AgentEngine(llm = client)
        val deps = quietDeps()

        val run = async(Dispatchers.Default) {
            startRun(engine, deps, registryWith(ParkingTool()), onEvent = { events += it })
        }
        withTimeout(5_000) { reasoningSeen.await() }

        assertTrue("the stop lands", engine.cancel("c"))
        // Cutting the call is what a real stop does to OkHttp: the read throws, and that is the
        // path that carries the round's buffers into the transcript.
        cut = true
        hold.complete(Unit)
        run.await()

        val kept = events.filterIsInstance<AgentEvent.AssistantMessage>().single()
        assertEquals("the thinking the user watched arrive is still there", "想了一半", kept.message.reasoning)
        assertTrue("and it did not invent an answer that never came", kept.message.text.isEmpty())
    }

    @Test
    fun `a tool the stop left hanging is answered in the transcript`() = runBlocking {
        val events = mutableListOf<AgentEvent>()
        val wires = mutableListOf<List<ChatMessage>>()
        val tool = ParkingTool()
        val engine = AgentEngine(
            llm = askingLlm(wires) {
                AssistantTurn(
                    text = "先看看",
                    toolCalls = listOf(ToolCallRequest("call_1", "slow", "{}")),
                    finishReason = "tool_calls",
                )
            },
        )
        val deps = quietDeps()

        val run = async(Dispatchers.Default) {
            startRun(engine, deps, registryWith(tool), onEvent = { events += it })
        }
        withTimeout(5_000) { tool.entered.await() }

        assertTrue("the stop lands while the tool is in flight", engine.cancel("c"))
        run.await()

        val answers = events.filterIsInstance<AgentEvent.ToolMessage>().map { it.message }
        assertEquals("exactly the one call the model asked for is answered", 1, answers.size)
        assertEquals("call_1", answers[0].toolCallId)
        assertEquals("answered as not-run, not as a silent empty result", false, answers[0].toolOk)
        assertTrue("with something a card can show", !answers[0].toolOutput.isNullOrBlank())
    }

    @Test
    fun `the next run's replay still holds the turn the stop interrupted`() = runBlocking {
        val events = mutableListOf<AgentEvent>()
        val wires = mutableListOf<List<ChatMessage>>()
        val tool = ParkingTool()
        val engine = AgentEngine(
            llm = askingLlm(wires) { call ->
                if (call == 1) {
                    AssistantTurn(
                        text = "先看看",
                        toolCalls = listOf(ToolCallRequest("call_1", "slow", "{}")),
                        finishReason = "tool_calls",
                    )
                } else {
                    AssistantTurn(text = "done", toolCalls = emptyList(), finishReason = "stop")
                }
            },
        )
        val deps = quietDeps()

        val first = async(Dispatchers.Default) {
            startRun(engine, deps, registryWith(tool), onEvent = { events += it })
        }
        withTimeout(5_000) { tool.entered.await() }
        engine.cancel("c")
        first.await()

        // What the transcript holds after the stop: the interrupted assistant turn and the
        // answer the engine wrote for the tool call it left hanging.
        val kept = events.mapNotNull {
            when (it) {
                is AgentEvent.AssistantMessage -> it.message
                is AgentEvent.ToolMessage -> it.message
                else -> null
            }
        }
        assertTrue(
            "the interrupted turn is part of the transcript",
            kept.any { message -> message.toolCallId == null },
        )

        startRun(engine, deps, registryWith(ParkingTool()), onEvent = {}, history = kept)

        val replay = wires.last()
        assertTrue(
            "the model is handed back the assistant turn the stop interrupted",
            replay.any { it.role == "assistant" && it.toolCalls.any { call -> call.id == "call_1" } },
        )
        assertTrue(
            "together with the tool answer that keeps the turn from being dropped",
            replay.any { it.role == "tool" && it.toolCallId == "call_1" },
        )
    }
}
