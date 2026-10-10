package com.suixing.agent.agent

import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.capability.CapabilityState
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.MemoryEntry
import com.suixing.agent.data.MemoryStore
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.UserProfileStore
import com.suixing.agent.llm.AssistantTurn
import com.suixing.agent.llm.LlmClient
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The multi-run audit's holes, pinned on the JVM.
 *
 * Gate half: the approval gate is a process singleton shared by every run, so its storage
 * is keyed by conversation — two runs asking at once must both stay answerable (the single
 * waiter used to hang the first run forever and lock its conversation), and stopping one
 * run must not answer another run's prompt.
 *
 * Engine half: the Android-bound collaborators (CapabilityHub hooks Shizuku in its init,
 * SkillStore and MemoryStore want a Context, ToolRegistry's only usable constructor takes
 * nine Android dependencies) are MockK stand-ins; the proxy never runs their constructors,
 * so no Android class loads. The tool the registry hands out is a real [Tool], so the
 * loop's own paths run for real. What is pinned:
 *  - a double start of one conversation runs exactly once and the live run stays
 *    registered (the lost update made a running run invisible to isRunning/cancel),
 *  - stateOf mirrors the run live and keeps its terminal value (the per-conversation
 *    flow used to sit at Idle forever),
 *  - cancelling one conversation interrupts only that run: its fake wire call is the one
 *    that gets cancelled, the other conversation keeps streaming.
 */
class ParallelRunTest {

    // ── gate ──────────────────────────────────────────────────────────────────────

    @Test
    fun `two approvals coexist and each resolves its own run`() = runBlocking {
        val gate = ApprovalGate()
        // UNDISPATCHED so each request reaches its await before the next line runs: a
        // plain async would not start until this coroutine suspends.
        val a = async(start = CoroutineStart.UNDISPATCHED) {
            gate.request("a", "run_shell", "ls", ApprovalMode.ASK)
        }
        val b = async(start = CoroutineStart.UNDISPATCHED) {
            gate.request("b", "run_shell", "rm", ApprovalMode.ASK)
        }
        assertEquals("both prompts on screen", setOf("a", "b"), gate.pending.value.keys)

        gate.resolve("a", approved = true)
        gate.resolve("b", approved = false)
        assertTrue("A was allowed", a.await())
        assertFalse("B was refused", b.await())
        assertTrue("no prompt left behind", gate.pending.value.isEmpty())
    }

    @Test
    fun `cancelling one run leaves the other prompt standing`() = runBlocking {
        val gate = ApprovalGate()
        val a = async(start = CoroutineStart.UNDISPATCHED) {
            gate.request("a", "run_shell", "ls", ApprovalMode.ASK)
        }
        val b = async(start = CoroutineStart.UNDISPATCHED) {
            gate.request("b", "run_shell", "rm", ApprovalMode.ASK)
        }

        gate.cancelPending("a")
        assertFalse("A's prompt dies as refused with its run", a.await())
        assertEquals("B's prompt survives", setOf("b"), gate.pending.value.keys)
        assertFalse("B is still waiting on the user", b.isCompleted)

        gate.resolve("b", approved = true)
        assertTrue("B is answered by the user", b.await())
    }

    // ── engine ────────────────────────────────────────────────────────────────────

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

    private fun echoTool(): Tool = object : Tool {
        override val spec = ToolSpec("echo", "echoes", JsonObject(emptyMap()))
        override val tier = TIER_NONE
        override suspend fun run(args: JsonObject) = ToolResult.success("ok")
    }

    private fun autoSettings(model: String = "deepseek-chat") =
        AppSettings().copy(approvalMode = ApprovalMode.AUTO, model = model)

    /**
     * A wire that parks until its own hold completes, and whose fake [Call] completes that
     * hold when the engine cancels it — so [AgentEngine.cancel] has a real thing to cut,
     * exactly as it does against OkHttp in production.
     */
    private class ParkingLlm {
        val started = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        val holds = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        private val cuts = ConcurrentHashMap<String, Boolean>()
        val client: LlmClient = mockk {
            coEvery { complete(any(), any(), any(), any(), any(), any()) } coAnswers {
                val model = firstArg<AppSettings>().model
                // The hold exists before the run is announced, so a test that sees
                // `started` can always find the matching hold.
                val hold = holds.getOrPut(model) { CompletableDeferred() }
                started.getOrPut(model) { CompletableDeferred() }.complete(Unit)
                withTimeout(5000) { hold.await() }
                // What a real cancelled OkHttp call does: the body read throws. The engine
                // has to read that as a stop, not as a failure.
                if (cuts[model] == true) throw IOException("Canceled")
                AssistantTurn(text = "done", toolCalls = emptyList(), finishReason = "stop")
            }
        }

        /** Releases [model]'s hold the way cutting its call would: with an IOException. */
        fun cut(model: String) {
            cuts[model] = true
            holds.getValue(model).complete(Unit)
        }
    }

    /** Waits for the run to reach its LLM call; the map fills in on another thread. */
    private suspend fun awaitStarted(wire: ParkingLlm, model: String? = null) {
        withTimeout(5000) {
            while (true) {
                val started = if (model == null) {
                    wire.started.values.firstOrNull()
                } else {
                    wire.started[model]
                }
                if (started != null) {
                    started.await()
                    return@withTimeout
                }
                delay(10)
            }
        }
    }

    private fun holdFor(wire: ParkingLlm, model: String? = null): CompletableDeferred<Unit> =
        if (model == null) wire.holds.values.first() else wire.holds.getValue(model)

    @Test
    fun `a double start runs once and the live run stays registered`() = runBlocking {
        val wire = ParkingLlm()
        val engine = AgentEngine(llm = wire.client)
        val deps = quietDeps()
        fun start(): Deferred<List<StoredMessage>> = async(Dispatchers.Default) {
            engine.startRun(
                chatId = "c", title = "t", history = emptyList(),
                settings = autoSettings(), registry = registryWith(echoTool()),
                capabilities = deps.capabilities, skills = deps.skills, memory = deps.memory,
                profile = deps.profile, gate = ApprovalGate(), onEvent = {},
            )
        }
        val run = start()
        awaitStarted(wire)
        assertTrue("the run is registered while in flight", engine.isRunning("c"))

        val refused = start().await()
        assertEquals("the double start produced nothing", emptyList<StoredMessage>(), refused)
        assertTrue("and the first run is still registered", engine.isRunning("c"))

        holdFor(wire).complete(Unit)
        run.await()
        assertFalse("the run unregisters when it ends", engine.isRunning("c"))
    }

    @Test
    fun `stateOf mirrors the run live and keeps its terminal value`() = runBlocking {
        val wire = ParkingLlm()
        val engine = AgentEngine(llm = wire.client)
        val deps = quietDeps()
        assertEquals("nothing has run yet", RunState.Idle, engine.stateOf("c").value)
        val run = async(Dispatchers.Default) {
            engine.startRun(
                chatId = "c", title = "t", history = emptyList(),
                settings = autoSettings(), registry = registryWith(echoTool()),
                capabilities = deps.capabilities, skills = deps.skills, memory = deps.memory,
                profile = deps.profile, gate = ApprovalGate(), onEvent = {},
            )
        }
        awaitStarted(wire)
        assertTrue(
            "the chat screen sees the run live through stateOf",
            engine.stateOf("c").value is RunState.Running,
        )
        holdFor(wire).complete(Unit)
        run.await()
        assertTrue(
            "and keeps the terminal state after it ends",
            engine.stateOf("c").value is RunState.Done,
        )
    }

    @Test
    fun `cancelling one conversation interrupts only that run`() = runBlocking {
        val wire = ParkingLlm()
        val engine = AgentEngine(llm = wire.client)
        val deps = quietDeps()
        val a = async(Dispatchers.Default) {
            engine.startRun(
                chatId = "a", title = "a", history = emptyList(),
                settings = autoSettings(model = "a"), registry = registryWith(echoTool()),
                capabilities = deps.capabilities, skills = deps.skills, memory = deps.memory,
                profile = deps.profile, gate = ApprovalGate(), onEvent = {},
            )
        }
        val b = async(Dispatchers.Default) {
            engine.startRun(
                chatId = "b", title = "b", history = emptyList(),
                settings = autoSettings(model = "b"), registry = registryWith(echoTool()),
                capabilities = deps.capabilities, skills = deps.skills, memory = deps.memory,
                profile = deps.profile, gate = ApprovalGate(), onEvent = {},
            )
        }
        awaitStarted(wire, "a")
        awaitStarted(wire, "b")

        assertTrue("A's run is cancelled", engine.cancel("a"))
        // Cutting A's wire throws IOException("Canceled") out of the model call, exactly as
        // OkHttp does in production. A stop must read as Cancelled — not Failed, which would
        // put an error card on screen for a button the user just pressed.
        wire.cut("a")
        a.await()
        assertTrue("A ended cancelled, not failed", engine.stateOf("a").value is RunState.Cancelled)
        assertFalse("A is gone", engine.isRunning("a"))
        assertTrue("B is still in flight", engine.isRunning("b"))
        assertTrue("and still streaming", engine.stateOf("b").value is RunState.Running)

        holdFor(wire, "b").complete(Unit)
        b.await()
        assertTrue("B finished normally", engine.stateOf("b").value is RunState.Done)
        assertFalse("B unregisters when it ends", engine.isRunning("b"))
    }
}
