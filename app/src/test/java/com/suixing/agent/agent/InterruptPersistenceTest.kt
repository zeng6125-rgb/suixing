package com.suixing.agent.agent

import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.capability.CapabilityState
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ChatRepository
import com.suixing.agent.data.LiveDraft
import com.suixing.agent.data.MemoryEntry
import com.suixing.agent.data.MemoryStore
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.StoredToolCall
import com.suixing.agent.data.UserProfileStore
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
import com.suixing.agent.ui.chat.liveDraftFor
import com.suixing.agent.ui.chat.liveDraftRecovery
import com.suixing.agent.ui.chat.sameContentAs
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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
        val profile: UserProfileStore,
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
        profile = mockk { every { text } returns MutableStateFlow("") },
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
        profile = deps.profile,
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

    /**
     * The other half of the same stop. The chat screen cancels the run's job as well as its
     * call, so the parked read comes back as a [kotlinx.coroutines.CancellationException]
     * instead of an IOException — a second branch that has to keep the very same buffers.
     */
    @Test
    fun `a stop that cancels the job rather than the call keeps the reasoning too`() = runBlocking {
        val events = mutableListOf<AgentEvent>()
        val wires = mutableListOf<List<ChatMessage>>()
        val reasoningSeen = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val client = mockk<LlmClient> {
            coEvery { complete(any(), any(), any(), any(), any(), any()) } coAnswers {
                wires += secondArg<List<ChatMessage>>()
                (invocation.args[4] as (String) -> Unit)("想了一半")
                reasoningSeen.complete(Unit)
                // No cut(): the run's own job is cancelled, which is what the chat screen does.
                withTimeout(5_000) { hold.await() }
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
        run.await()

        val kept = events.filterIsInstance<AgentEvent.AssistantMessage>().single()
        assertEquals("the thinking is kept on this path as well", "想了一半", kept.message.reasoning)
        assertTrue("and still no answer is invented", kept.message.text.isEmpty())
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

    // ---- What a kill leaves on disk. Nothing runs after one, so the draft writer is the
    // ---- only chance the thinking and the tool cards get, and it used to skip the round
    // ---- that had no answer text yet — which is exactly the round a kill interrupts.

    @get:Rule
    val folder = TemporaryFolder()

    private fun repository(): ChatRepository {
        val context = mockk<android.content.Context>()
        every { context.filesDir } returns folder.root
        return ChatRepository(context)
    }

    /** The round the writer used to skip: thinking streamed, no answer text yet. */
    @Test
    fun `a round that has only thought so far is still written to the draft`() {
        val draft = liveDraftFor(
            chatId = "c1",
            runId = "r1",
            text = "",
            reasoning = "我在想",
            messages = emptyList(),
        )

        assertNotNull("thinking with no answer text is worth saving, not skipping", draft)
        assertEquals("我在想", draft?.reasoning)
        assertTrue("and with nothing written yet, the paragraph stays empty", draft?.text.orEmpty().isEmpty())
    }

    /** A tick with new thinking, or a finished tool card, is a new write. */
    @Test
    fun `the writer skips a tick that changed nothing and writes one that did`() {
        val quiet = liveDraftFor("c1", "r1", text = "", reasoning = "我在想", messages = emptyList())
        assertTrue(
            "an unchanged tick must not pay for another file write",
            quiet!!.sameContentAs(quiet.copy(at = quiet.at + 1_000)),
        )
        assertTrue(
            "the timestamp alone changing is not content",
            quiet.sameContentAs(liveDraftFor("c1", "r1", text = "", reasoning = "我在想", messages = emptyList())),
        )

        val thoughtMore = liveDraftFor("c1", "r1", text = "", reasoning = "我在想，还要看文件", messages = emptyList())
        assertTrue("more thinking is a change", !thoughtMore!!.sameContentAs(quiet))

        val answered = liveDraftFor("c1", "r1", text = "看完了", reasoning = "我在想", messages = emptyList())
        assertTrue("the first answer text is a change", !answered!!.sameContentAs(quiet))

        val withRound = liveDraftFor(
            "c1",
            "r1",
            text = "",
            reasoning = "我在想",
            messages = listOf(StoredMessage(id = "m1", role = Roles.ASSISTANT, toolCalls = listOf(StoredToolCall(id = "call_1", name = "read_file", arguments = "{}")), runId = "r1")),
        )
        assertTrue("a finished round is a change", !withRound!!.sameContentAs(quiet))
    }

    /**
     * The user's case, end to end and without the screen: a run streams thinking, calls a
     * tool, gets its result, starts a second round, and the process dies. The next launch has
     * only the draft, so this is the round trip that decides whether the thinking and the tool
     * cards are still there — the assistant message has to carry its reasoning and the tool
     * call has to have its answer.
     */
    @Test
    fun `an interrupted run comes back with its reasoning and its tool round on a reload`() = runBlocking {
        val chats = repository()
        val call = StoredMessage(
            id = "m1",
            role = Roles.ASSISTANT,
            text = "先看看",
            toolCalls = listOf(StoredToolCall(id = "call_1", name = "read_file", arguments = "{}")),
            runId = "r1",
        )
        val result = StoredMessage(
            id = "m2",
            role = Roles.TOOL,
            text = "文件内容",
            toolName = "read_file",
            toolOutput = "文件内容",
            toolOk = true,
            toolCallId = "call_1",
            runId = "r1",
        )
        val alreadyStored = listOf(call, result)
        // The second round: thinking has begun and a sentence has come out of it, but the round
        // never finished. Its rounds are the first round's, which only the draft has — the
        // transcript is written when a run ends.
        val draft = liveDraftFor(
            chatId = "c1",
            runId = "r1",
            text = "我再想一下这个文件",
            reasoning = "读完了，我再想一下",
            messages = alreadyStored,
        )
        assertNotNull("the kill lands while the round is still thinking", draft)

        chats.saveLive(draft!!)
        val killed = listOf(StoredMessage(id = "q1", role = Roles.USER, text = "看看文件"))

        val reloaded = chats.readLive("c1")
        assertNotNull("the draft outlives the process that wrote it", reloaded)
        assertEquals("读完了，我再想一下", reloaded?.reasoning)

        val transcript = killed + liveDraftRecovery(reloaded!!, killed)

        val interrupted = transcript.last()
        assertEquals(Roles.ASSISTANT, interrupted.role)
        assertEquals(
            "the thinking the user watched arrive is on the card, not gone",
            "读完了，我再想一下",
            interrupted.reasoning,
        )
        assertTrue("and the card says it was cut off", interrupted.text.endsWith("（中断）"))

        val restoredCall = transcript.firstOrNull { message -> message.toolCalls.any { it.id == "call_1" } }
        val restoredAnswer = transcript.firstOrNull { message -> message.toolCallId == "call_1" }
        assertNotNull("the tool card comes back", restoredCall)
        assertNotNull("with its result, or the row would read as a call that never returned", restoredAnswer)
        assertEquals("文件内容", restoredAnswer?.toolOutput)

        chats.clearLive("c1")
        assertNull("once it is in the transcript the draft is not read twice", chats.readLive("c1"))
    }

    /**
     * The mirror of the case above: the run was killed *after* its answer landed. Appending
     * the draft again would show the user the same paragraph twice, so the 64-character
     * fingerprint of a delivered answer has to keep it out.
     */
    @Test
    fun `an answer that already landed is not appended a second time`() {
        val body = "已经写完的回答，前面这段是草稿的前缀，".repeat(8) + "并且还要更长一些"
        val landed = listOf(
            StoredMessage(id = "a1", role = Roles.ASSISTANT, text = body, runId = "r1"),
        )
        val draft = LiveDraft(
            chatId = "c1",
            runId = "r1",
            at = 5L,
            text = body.take(40),
            reasoning = "想过了",
            // The round behind that answer, already in the transcript under the same id.
            messages = landed,
        )

        assertTrue(
            "a delivered answer is not appended again",
            liveDraftRecovery(draft, landed).isEmpty(),
        )
    }

    /**
     * Stopped while it was still thinking. There is no answer text at all, and that used to be
     * the end of it: the marker was appended only when the draft had text, so a run killed
     * mid-thought came back as nothing — the thinking the user had been watching on screen was
     * exactly what the interruption threw away. The marker is the whole bubble in that case.
     */
    @Test
    fun `a run stopped while it was only thinking comes back as the marker and its thinking`() {
        val draft = LiveDraft(
            chatId = "c1",
            runId = "r1",
            at = 5L,
            text = "",
            reasoning = "我先想想这个文件在哪",
            messages = emptyList(),
        )

        val recovered = liveDraftRecovery(draft, emptyList())

        assertEquals("one card comes back", 1, recovered.size)
        assertEquals("（中断）", recovered.single().text)
        assertEquals("我先想想这个文件在哪", recovered.single().reasoning)

        assertTrue(
            "a draft with neither thinking nor an answer stays out of the transcript",
            liveDraftRecovery(draft.copy(reasoning = ""), emptyList()).isEmpty(),
        )
    }
}
