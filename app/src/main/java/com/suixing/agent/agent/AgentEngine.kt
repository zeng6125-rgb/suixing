package com.suixing.agent.agent

import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.MemoryEntry
import com.suixing.agent.data.MemoryStore
import com.suixing.agent.data.Roles
import com.suixing.agent.data.SettingsRepository
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.StoredToolCall
import com.suixing.agent.llm.ChatMessage
import com.suixing.agent.llm.LlmClient
import com.suixing.agent.llm.ToolCallRequest
import com.suixing.agent.llm.Usage
import com.suixing.agent.skills.SkillStore
import com.suixing.agent.tools.TIER_ACCESSIBILITY
import com.suixing.agent.tools.TIER_ROOT
import com.suixing.agent.tools.TIER_SHELL
import com.suixing.agent.tools.ToolRegistry
import com.suixing.agent.tools.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/** Everything the UI needs to render while the agent is working. */
sealed interface AgentEvent {
    data class TextDelta(val text: String) : AgentEvent

    /** The model's thinking, when the endpoint streams it apart from the answer. */
    data class ReasoningDelta(val text: String) : AgentEvent

    data class AssistantMessage(val message: StoredMessage) : AgentEvent

    data class ToolStarted(val name: String, val args: String) : AgentEvent

    data class ToolMessage(val message: StoredMessage) : AgentEvent

    data class ToolFinished(val name: String, val ok: Boolean, val output: String) : AgentEvent

    data class Failed(val message: String) : AgentEvent

    data class Finished(val text: String) : AgentEvent
}

/**
 * Holds a tool call until the user answers. In ASK mode the agent coroutine
 * parks on a [CompletableDeferred], so the run resumes the moment the user taps
 * approve or reject in the UI.
 *
 * 「总是允许」 and 「总是拒绝」 are written to settings rather than kept in a field,
 * because a decision that evaporates on restart is not a memory, it is a nuisance.
 */
class ApprovalGate(
    private val settings: SettingsRepository? = null,
    private val scope: CoroutineScope? = null,
) {
    data class Request(val id: String, val toolName: String, val args: String)

    private data class Decision(val approved: Boolean, val always: Boolean)

    private val _pending = MutableStateFlow<Map<String, Request>>(emptyMap())
    val pending: StateFlow<Map<String, Request>> = _pending.asStateFlow()

    /** One waiter per run: two conversations asking at once must not overwrite each other. */
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<Decision>>()

    private val alwaysAllowed = ConcurrentHashMap.newKeySet<String>()

    private val alwaysDenied = ConcurrentHashMap.newKeySet<String>()

    private val _alwaysAllowed = MutableStateFlow<Set<String>>(emptySet())

    private val _alwaysDenied = MutableStateFlow<Set<String>>(emptySet())

    /** The tools the user said 「总是允许」 to, for the settings list. */
    val alwaysAllowedTools: StateFlow<Set<String>> = _alwaysAllowed.asStateFlow()

    /** The tools the user said 「总是拒绝」 to, for the settings list. */
    val alwaysDeniedTools: StateFlow<Set<String>> = _alwaysDenied.asStateFlow()

    init {
        scope?.launch {
            val saved = runCatching { settings?.current() }.getOrNull() ?: return@launch
            alwaysAllowed.addAll(saved.alwaysAllowedTools)
            alwaysDenied.addAll(saved.alwaysDeniedTools)
            // A tool the user both allowed and denied at some point resolves to the
            // refusal: the deny list only ever grows by an explicit tap, so it is the
            // more deliberate of the two answers.
            alwaysAllowed.removeAll(alwaysDenied)
            _alwaysAllowed.value = alwaysAllowed.toSet()
            _alwaysDenied.value = alwaysDenied.toSet()
        }
    }

    suspend fun request(chatId: String, toolName: String, args: String, mode: ApprovalMode): Boolean {
        when (mode) {
            ApprovalMode.AUTO -> return true
            ApprovalMode.DENY -> return false
            ApprovalMode.ASK -> Unit
        }
        if (alwaysDenied.contains(toolName)) return false
        if (alwaysAllowed.contains(toolName)) return true
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Decision>()
        waiters[chatId] = deferred
        _pending.update { it + (chatId to Request(id, toolName, args)) }
        val decision = deferred.await()
        waiters.remove(chatId)
        _pending.update { it - chatId }
        if (decision.always) {
            if (decision.approved) rememberAlways(toolName) else rememberDenied(toolName)
        }
        return decision.approved
    }

    fun resolve(chatId: String, approved: Boolean, always: Boolean = false) {
        waiters[chatId]?.complete(Decision(approved, always))
    }

    /**
     * Used when the user stops one run while its prompt is on screen. The state is
     * cleared here rather than in [request] because a cancelled coroutine never
     * resumes, which would otherwise leave the dialog on screen forever. Other runs'
     * prompts stay up: stopping A must not answer B's question for the user.
     */
    fun cancelPending(chatId: String) {
        waiters.remove(chatId)?.complete(Decision(false, false))
        _pending.update { it - chatId }
    }

    /** Forgets every remembered tool. */
    fun forgetAlways() {
        alwaysAllowed.clear()
        _alwaysAllowed.value = emptySet()
        persist()
    }

    /** Forgets one remembered tool, from the settings list. */
    fun forgetAlways(toolName: String) {
        if (!alwaysAllowed.remove(toolName)) return
        _alwaysAllowed.value = alwaysAllowed.toSet()
        persist()
    }

    /** Forgets every tool the user answered 「总是拒绝」 to. */
    fun forgetDenied() {
        alwaysDenied.clear()
        _alwaysDenied.value = emptySet()
        persist()
    }

    /** Forgets one refused tool, from the settings list. */
    fun forgetDenied(toolName: String) {
        if (!alwaysDenied.remove(toolName)) return
        _alwaysDenied.value = alwaysDenied.toSet()
        persist()
    }

    private fun rememberAlways(toolName: String) {
        alwaysDenied.remove(toolName)
        _alwaysDenied.value = alwaysDenied.toSet()
        if (!alwaysAllowed.add(toolName)) return
        _alwaysAllowed.value = alwaysAllowed.toSet()
        persist()
    }

    private fun rememberDenied(toolName: String) {
        alwaysAllowed.remove(toolName)
        _alwaysAllowed.value = alwaysAllowed.toSet()
        if (!alwaysDenied.add(toolName)) return
        _alwaysDenied.value = alwaysDenied.toSet()
        persist()
    }

    private fun persist() {
        val repository = settings ?: return
        val allowed = alwaysAllowed.sorted()
        val denied = alwaysDenied.sorted()
        (scope ?: return).launch {
            runCatching { repository.update { it.copy(alwaysAllowedTools = allowed, alwaysDeniedTools = denied) } }
        }
    }
}

/**
 * How fast a run is going, in the only unit a streaming UI can show live.
 *
 * The token counts are estimates: the exact numbers only exist on the server and
 * arrive once, with the last chunk. Counting decoded text instead means the speed
 * readout moves while the model is still talking, which is the whole point of it.
 */
data class StreamStats(
    val tokensOut: Int,
    val thinkingTokens: Int,
    val elapsedMs: Long,
    val tokPerSec: Float,
    /**
     * The prompt size the endpoint itself reported for the last round; 0 until it
     * reports one. This is the only honest measure of 「上下文用了多少」 — counting
     * the wire's characters would be a guess, and this number is handed to us.
     */
    val lastPromptTokens: Int = 0,
    /**
     * Where the prompt goes, split three ways, for the breakdown bar in the UI.
     *
     * Estimated from characters, so the three do not have to add up to [lastPromptTokens]:
     * one is counted and the other is reported. They are still worth having, because the
     * question the bar answers — 「是历史太长，还是工具太多」 — only needs proportions.
     */
    val breakSystem: Int = 0,
    val breakTools: Int = 0,
    val breakHistory: Int = 0,
    /**
     * One line about the run that is not a statistic, e.g. that the context was just
     * compressed. It rides here rather than in a new event because this flow is
     * already the live channel both the chat screen and the floating console read.
     */
    val note: String = "",
)

/** What the engine is doing right now, for anything outside the chat screen. */
sealed interface RunState {
    data object Idle : RunState

    /** Emitted continuously while a run streams. */
    data class Running(val stats: StreamStats) : RunState

    data class Done(val stats: StreamStats) : RunState

    data class Cancelled(val stats: StreamStats) : RunState

    data class Failed(val message: String) : RunState
}

/**
 * The agent loop: send the conversation to the model, execute whatever tools it asks
 * for, feed the results back, and repeat until it stops calling tools.
 *
 * Runs are keyed by conversation. Every chat gets its own [RunHandle], so two chats can
 * work at the same time without touching each other's wire, stream or stop button. What is
 * shared is only what has no per-run meaning: the HTTP client, the tool registry, the gate.
 */
class AgentEngine(
    private val llm: LlmClient = LlmClient(),
    private val scope: CoroutineScope? = null,
) {

    private val runs = MutableStateFlow<Map<String, RunHandle>>(emptyMap())

    /** The runs in flight right now, keyed by conversation id. */
    val runsFlow: StateFlow<Map<String, RunHandle>> = runs.asStateFlow()

    /**
     * Per-conversation state that outlives its run.
     *
     * Kept apart from [runs] because the UI needs the *terminal* state: the usage line
     * freezes on `Done`, and a flow that vanished along with the run would take the numbers
     * with it.
     */
    private val states = mutableMapOf<String, MutableStateFlow<RunState>>()

    /**
     * The state of one conversation, whether or not it is running right now.
     *
     * The same flow is returned for the same id for the life of the process, so a screen can
     * collect it once and keep it across runs.
     */
    fun stateOf(chatId: String): StateFlow<RunState> = stateFlowOf(chatId)

    /** The writable side of [stateOf], for the runs that publish into it. */
    private fun stateFlowOf(chatId: String): MutableStateFlow<RunState> = synchronized(states) {
        states.getOrPut(chatId) { MutableStateFlow(RunState.Idle) }
    }

    fun isRunning(chatId: String): Boolean = runs.value.containsKey(chatId)

    /** The run that started most recently: the one the floating console drives. */
    fun newestRun(): RunHandle? = runs.value.values.maxByOrNull { it.startedAt }

    /**
     * Stops one conversation's run.
     *
     * Cancelling the OkHttp call is the part that matters: without it the loop would keep
     * reading tokens off the wire until the model decided to stop, and 「点停即停」 would be a
     * lie. The flag carries the decision past the call, so a tool that is already running is
     * not waited on either — the loop checks it at the top of every round and before every
     * tool call. Other conversations are untouched.
     *
     * @return whether that conversation had a run to interrupt.
     */
    fun cancel(chatId: String): Boolean = runs.value[chatId]?.cancel() ?: false

    /**
     * Says something to one conversation's run.
     *
     * Queued steering (the default) lands on the next round boundary: the model finishes what
     * it is saying and reads the line before being asked for the next step. Immediate steering
     * cuts the stream instead, so the line is read now — worth it when the model is heading the
     * wrong way, and lossy because whatever it was in the middle of is discarded.
     *
     * The default is taken from that run's own settings rather than fixed at `false`, so
     * [AppSettings.steerMode] decides without every caller having to look it up.
     *
     * @return whether the text was taken; false when that conversation is not running.
     */
    fun steer(
        chatId: String,
        text: String,
        immediate: Boolean = runs.value[chatId]?.steerMode == STEER_IMMEDIATE,
    ): Boolean = runs.value[chatId]?.steer(text, immediate) ?: false

    /**
     * Runs one conversation and returns the messages it produced.
     *
     * The caller's coroutine is the run's coroutine: N conversations running at once is N
     * callers, which is why nothing here launches into a scope of its own. Cancelling that
     * coroutine ends the run (see [cancel] for the polite version).
     *
     * A conversation that is already running is refused rather than queued: two runs writing
     * one transcript would interleave two answers into one and leave the user unable to tell
     * which is which. Callers should ask [isRunning] first.
     */
    suspend fun startRun(
        chatId: String,
        title: String,
        history: List<StoredMessage>,
        settings: AppSettings,
        registry: ToolRegistry,
        capabilities: CapabilityHub,
        skills: SkillStore,
        memory: MemoryStore,
        gate: ApprovalGate,
        onEvent: (AgentEvent) -> Unit,
    ): List<StoredMessage> {
        // Check and register in one critical section: two coroutines starting the same
        // conversation (a double tap, or an isRunning check preempted before start) would
        // otherwise both pass the guard and write one transcript from two loops.
        val handle = synchronized(runs) {
            val existing = runs.value[chatId]
            if (existing != null) {
                null
            } else {
                val created = RunHandle(chatId, title, settings)
                runs.update { it + (chatId to created) }
                created
            }
        }
        if (handle == null) {
            // The refusal goes to the caller that asked twice, not to the run in flight:
            // publishing Failed onto the live handle would flip a healthy run to Failed
            // on the chat screen just because someone double-tapped send.
            onEvent(AgentEvent.Failed("这个会话已经有任务在跑了，先停下或等它结束。"))
            return emptyList()
        }
        try {
            return handle.execute(history, settings, registry, capabilities, skills, memory, gate, onEvent)
        } finally {
            // Gone from [runs] before the state settles: a finished run is not a run, and the
            // console must stop offering to stop it. By identity, so a stale finally cannot
            // unregister a newer run that took the same key.
            runs.update { if (it[chatId] === handle) it - chatId else it }
        }
    }

    /**
     * Ticks every live run's speed readout.
     *
     * It runs on a timer rather than on every delta because the UI would otherwise be
     * recomposed once per token; 120 ms is faster than the eye and slower than the stream.
     */
    init {
        scope?.launch {
            while (isActive) {
                runs.value.values.forEach { it.tick() }
                delay(STATS_TICK_MS)
            }
        }
    }

    /**
     * One conversation's run: everything that used to be engine-wide lives here, which is what
     * lets two conversations run at the same time.
     *
     * An inner class on purpose — the loop needs the engine's prompt building, tool registry and
     * HTTP client, and those really are shared.
     */
    inner class RunHandle(
        val chatId: String,
        val title: String,
        settings: AppSettings,
    ) {
        private val _state = MutableStateFlow<RunState>(RunState.Idle)

        /** This conversation's state, from the moment it starts to long after it ends. */
        val state: StateFlow<RunState> = _state.asStateFlow()

        /** When this run began, in [System.nanoTime] terms. What orders concurrent runs. */
        @Volatile
        var startedAt: Long = System.nanoTime()
            private set

        /**
         * What this run is doing at this instant, for the floating console: which tool is
         * running, that the model is thinking, or the tail of the text it is writing.
         *
         * Deliberately not part of [state]: that flows at its own pace, and this changes on
         * every tool and every delta.
         */
        @Volatile
        var liveLine: String = ""
            private set

        /** The coroutine driving this run, for anything that needs to look at it. */
        @Volatile
        internal var job: Job? = null

        /** [steer]'s default mode for this run, read from the settings it started with. */
        internal val steerMode: Int = settings.steerMode

        private val cancelled = AtomicBoolean(false)

        /** The call currently on the wire; cancelling it is what stops this run's stream. */
        @Volatile
        private var inFlight: Call? = null

        private val steering = Channel<String>(Channel.UNLIMITED)

        /**
         * Set when an immediate [steer] aborts the round on purpose, so the IOException it
         * produces is read as 「这一轮作废」 rather than as a network failure.
         */
        private val steeringCut = AtomicBoolean(false)

        /** Prompt tokens the endpoint reported for the last round; 0 = it never did. */
        @Volatile
        private var lastPromptTokens = 0

        /** Wire size in characters when [lastPromptTokens] was recorded, to price the growth. */
        @Volatile
        private var lastPromptChars = 0

        /** The three parts of the prompt, in estimated tokens, for the UI's breakdown bar. */
        @Volatile
        private var breakSystem = 0

        @Volatile
        private var breakTools = 0

        @Volatile
        private var breakHistory = 0

        /** A one-line note about the run, shown by whoever renders [state]. */
        @Volatile
        private var contextNote = ""

        private val outChars = AtomicInteger(0)

        private val thinkingChars = AtomicInteger(0)

    /**
     * Stops this run. See [AgentEngine.cancel] for why the call itself has to be cancelled.
     *
     * @return whether there was anything to interrupt.
     */
    fun cancel(): Boolean {
        if (!isLive()) return false
        cancelled.set(true)
        inFlight?.cancel()
        return true
    }

    /**
     * Says something to this run.
     *
     * Queued steering (the default) lands on the next round boundary: the model finishes what
     * it is saying and reads the line before being asked for the next step. Immediate steering
     * cuts the stream instead, so the line is read now — worth it when the model is heading the
     * wrong way, and lossy because whatever it was in the middle of is discarded.
     *
     * @return whether the text was taken; false when this run is no longer live.
     */
    fun steer(text: String, immediate: Boolean): Boolean {
        if (!isLive()) return false
        val body = text.trim()
        if (body.isEmpty()) return false
        if (immediate) {
            // The same abort `cancel()` uses, without the cancelled flag: this ends the
            // round, not the run. The flag tells the loop the resulting IOException is
            // the cut it asked for, so the round is retried instead of failing the run.
            val call = inFlight
            if (call != null) {
                steeringCut.set(true)
                call.cancel()
            }
        }
        return steering.trySend(body).isSuccess
    }

    /** Whether this handle is still the registered run for its conversation. */
    private fun isLive(): Boolean = runs.value[chatId] === this

    /** Publishes [state] to this run's own flow and to the conversation's durable one. */
    internal fun publish(state: RunState) {
        _state.value = state
        // The per-conversation flow is what the chat screen collects; without this mirror
        // stateOf(chatId) would sit at Idle forever and every per-chat branch in the UI
        // would be dead code.
        stateFlowOf(chatId).value = state
    }

    /** Repaints the speed readout while this run streams. */
    internal fun tick() {
        if (isLive()) publish(RunState.Running(stats()))
    }

    /**
     * The loop itself, run once on the caller's coroutine.
     *
     * Everything it counts or flags is per-handle and starts empty, because the handle was
     * made for this run and is dropped with it.
     */
    internal suspend fun execute(
        history: List<StoredMessage>,
        settings: AppSettings,
        registry: ToolRegistry,
        capabilities: CapabilityHub,
        skills: SkillStore,
        memory: MemoryStore,
        gate: ApprovalGate,
        onEvent: (AgentEvent) -> Unit,
    ): List<StoredMessage> {
        job = currentCoroutineContext()[Job]
        // Published here rather than left to the ticker, so a caller sees Running the
        // moment the run starts even if no ticker was ever started.
        publish(RunState.Running(stats()))

        val produced = mutableListOf<StoredMessage>()
        val wire = mutableListOf<ChatMessage>()
        // Built once and measured once: the system prompt carries the skills catalogue and
        // the memory entries, so it is both the largest fixed part of the prompt and the one
        // the user can do least about.
        val systemText = systemPrompt(settings, registry, skills.catalog(skills.list()), memory.entries.value)
        breakSystem = systemText.length / 4
        wire += ChatMessage.system(systemText)
        sanitize(trim(history, settings.contextWindowFor(settings.model))).forEach { appendToWire(wire, it, settings) }

        // A ceiling of 0 (or less) means the user set none: the loop then ends only when
        // the model stops asking for tools, which is the only limit a long task should
        // have. A positive value is a real ceiling, and the failure below says so.
        val roundLimit = settings.maxToolRounds
        var round = 0
        try {
            while (roundLimit <= 0 || round < roundLimit) {
                round++
                // Steering lands here, on the boundary between two model turns: the
                // model has finished the previous turn and has not been asked for the
                // next one, which is the only moment the wire can be edited safely.
                drainSteering(wire)
                compressContext(wire, settings)
                // Cleared with the round: whatever the last one ended with is over, and a
                // line that outlives its step reads as a stall.
                liveLine = ""
                if (cancelled.get()) {
                    finishCancelled(onEvent)
                    return produced
                }

                // Measured here, where the wire for this round is final: steering has landed
                // and compression has already run, so the numbers match what is about to be
                // sent rather than what the round started with.
                val toolSpecs = registry.all().map { it.spec }
                breakTools = toolSpecs.sumOf {
                    it.name.length + it.description.length + it.parameters.toString().length
                } / 4
                breakHistory = wire.filter { it.role != ROLE_SYSTEM }.sumOf { messageChars(it) } / 4
                val turnStartedAt = System.currentTimeMillis()
                // Cleared here rather than after the call: an abort from `steer` can only
                // belong to the round it interrupted, so nothing else may inherit the flag.
                steeringCut.set(false)
                // The console shows the tail of what is being written; the finished text only
                // exists once the turn ends, which is far too late for a "still working" line.
                val streamed = StringBuilder()
                val turn = try {
                    llm.complete(
                        settings = settings,
                        messages = wire,
                        tools = toolSpecs,
                        onDelta = { piece ->
                            outChars.addAndGet(piece.length)
                            streamed.append(piece)
                            liveLine = streamed.takeLast(LIVE_LINE_CHARS).toString()
                            onEvent(AgentEvent.TextDelta(piece))
                        },
                        onReasoning = { piece ->
                            thinkingChars.addAndGet(piece.length)
                            liveLine = "思考中…"
                            onEvent(AgentEvent.ReasoningDelta(piece))
                        },
                        onCall = { inFlight = it },
                    )
                } catch (error: IOException) {
                    // Cancelling a call surfaces as an IOException on the reading
                    // thread. It is a stop, not a failure, so it is reported as one.
                    if (cancelled.get()) {
                            finishCancelled(onEvent)
                        return produced
                    }
                    // An immediate steer aborts the stream the same way, but nothing is
                    // wrong: this round is abandoned so the next one can carry the line.
                    // Rethrowing here would kill the run the user just spoke to.
                    if (steeringCut.get()) {
                        steeringCut.set(false)
                        continue
                    }
                    throw error
                } finally {
                    inFlight = null
                }
                val elapsed = System.currentTimeMillis() - turnStartedAt
                val usage = turn.usage
                // Kept for the context readout and for pricing the next round's growth:
                // only the endpoint knows how big the prompt really was.
                usage?.promptTokens?.takeIf { it > 0 }?.let { reported ->
                    lastPromptTokens = reported
                    lastPromptChars = wireChars(wire)
                }
                val thinking = turn.reasoning.takeIf { it.isNotBlank() }
                // A truncated turn can end mid-tool-call, so say so instead of silently
                // presenting half an answer as a finished one.
                if (turn.finishReason == "length") {
                    onEvent(AgentEvent.Failed("模型输出达到长度上限被截断，内容可能不完整。"))
                }
                if (turn.toolCalls.isEmpty()) {
                    val assistant = StoredMessage(
                        id = UUID.randomUUID().toString(),
                        role = Roles.ASSISTANT,
                        text = turn.text,
                        reasoning = thinking,
                    ).withUsage(usage, elapsed, settings.model)
                    produced += assistant
                    onEvent(AgentEvent.AssistantMessage(assistant))
                    onEvent(AgentEvent.Finished(turn.text))
                    publish(RunState.Done(stats()))
                    return produced
                }
                val assistant = StoredMessage(
                    id = UUID.randomUUID().toString(),
                    role = Roles.ASSISTANT,
                    text = turn.text,
                    reasoning = thinking,
                    toolCalls = turn.toolCalls.map { StoredToolCall(it.id, it.name, it.arguments) },
                ).withUsage(usage, elapsed, settings.model)
                produced += assistant
                onEvent(AgentEvent.AssistantMessage(assistant))
                wire += ChatMessage.assistant(turn.text, turn.toolCalls)

                for (call in turn.toolCalls) {
                    // Checked before every call, not after the batch: a stop means the
                    // tools the model asked for in the same breath do not run either.
                    if (cancelled.get()) {
                            finishCancelled(onEvent)
                        return produced
                    }
                    val argsPreview = call.arguments.take(600)
                    val tool = registry.find(call.name)
                    var toolMs = 0L
                    val result = if (tool == null) {
                        ToolResult.failure("未知工具：${call.name}")
                    } else {
                        val blocked = blockedReason(tool.tier, capabilities)
                        if (blocked != null) {
                            ToolResult.failure(blocked)
                        } else {
                            liveLine = "🔧 ${call.name}…"
                            onEvent(AgentEvent.ToolStarted(call.name, argsPreview))
                            if (!gate.request(chatId, call.name, argsPreview, settings.approvalMode)) {
                                ToolResult.failure("用户拒绝了这次调用。")
                            } else {
                                val startedAt = System.currentTimeMillis()
                                runCatching { tool.run(parseArgs(call.arguments)) }.getOrElse { error ->
                                    if (error is CancellationException) throw error
                                    ToolResult.failure("工具执行异常：${error.message ?: error::class.java.simpleName}")
                                }.also { toolMs = System.currentTimeMillis() - startedAt }
                            }
                        }
                    }
                    val output = result.output.take(MAX_TOOL_OUTPUT)
                    val stored = StoredMessage(
                        id = UUID.randomUUID().toString(),
                        role = Roles.TOOL,
                        text = "",
                        toolName = call.name,
                        toolArgs = call.arguments,
                        toolOutput = output,
                        toolOk = result.ok,
                        toolCallId = call.id,
                        imageBase64 = result.imageBase64,
                    )
                    produced += stored
                    onEvent(AgentEvent.ToolMessage(stored))
                    wire += ChatMessage.tool(call.id, output)
                    result.imageBase64?.let { base64 ->
                        if (settings.imageInput) {
                            wire += ChatMessage.user("（这是上一步 $call.name 返回的截图）", base64)
                        } else {
                            wire += ChatMessage.user(
                                "（上一步 $call.name 返回了一张截图，但当前模型未开启图片输入，你看不到图像内容。）",
                            )
                        }
                    }
                    liveLine = if (result.ok) {
                        String.format(Locale.US, "✓ %s %.1fs", call.name, toolMs / 1000.0)
                    } else {
                        "✗ ${call.name}"
                    }
                    onEvent(AgentEvent.ToolFinished(call.name, result.ok, output))
                }
            }
            // Only reachable with a ceiling: unlimited rounds end by returning above.
            onEvent(AgentEvent.Failed("已达到最大工具轮数（$roundLimit），任务未完成。"))
            publish(RunState.Failed("已达到最大工具轮数（$roundLimit），任务未完成。"))
            return produced
        } catch (cancellation: CancellationException) {
            // The caller cancelled the coroutine rather than the run; the stream still
            // has to be taken down, and the state still has to say what happened.
            inFlight?.cancel()
            publish(RunState.Cancelled(stats()))
            throw cancellation
        } catch (error: Throwable) {
            // Cutting the wire makes OkHttp throw IOException("Canceled"), so a run the user
            // stopped lands here rather than in the CancellationException branch above. It is
            // the stop working, not a failure: publishing Failed would put an error card on
            // screen for a button the user just pressed, and the caller would be handed a
            // throwable it turns into one too.
            if (cancelled.get()) {
                finishCancelled(onEvent)
                return produced
            }
            publish(RunState.Failed(error.message ?: error::class.java.simpleName))
            throw error
        } finally {
            inFlight = null
            // Nothing is published here. The run has already said how it ended, and that
            // answer is what the conversation's state flow keeps for whoever still reads it.
        }
    }

    /**
     * Publishes the end of a stopped run.
     *
     * It does not throw: the transcript produced so far is still real, the user can
     * see it, and discarding it because the run was stopped halfway would lose the
     * work that was actually done.
     */
    private fun finishCancelled(onEvent: (AgentEvent) -> Unit) {
        onEvent(AgentEvent.Finished(""))
        publish(RunState.Cancelled(stats()))
    }

    /**
     * Injects whatever the user typed while the model was working.
     *
     * The text goes in as a user message in the same run — not a new one — because
     * that is exactly what it is: something said in the middle of a task.
     */
    private fun drainSteering(wire: MutableList<ChatMessage>) {
        while (true) {
            val text = steering.tryReceive().getOrNull() ?: return
            wire += ChatMessage.user("（用户在任务进行中说：$text）")
        }
    }

    /**
     * Summarises the oldest part of the conversation once the prompt is close to the
     * window the user set, so a long task does not end in a hard 400.
     *
     * The alternative — dropping the old messages — is what the trimmer already does
     * for the initial replay, and it is worse in the middle of a task: the model
     * forgets the decision it made ten steps ago and starts over. A summary keeps the
     * conclusions, the paths and the decisions, which is what the model actually
     * needs to carry on.
     *
     * The newest [KEEP_RECENT] messages are never touched: they are the work in
     * progress, and summarising the tool result the model is reading right now would
     * be self-defeating.
     */
    private suspend fun compressContext(
        wire: MutableList<ChatMessage>,
        settings: AppSettings,
    ) {
        val window = settings.contextWindowFor(settings.model)
        if (window <= 0 || !settings.autoCompress) return
        if (wire.size <= MIN_MESSAGES) return
        if (estimatePromptTokens(wire) <= window * COMPRESS_AT) return

        // The cut never splits an assistant's tool_calls from their results: a lone
        // tool message is a hard 400 on every OpenAI-compatible endpoint.
        var cut = wire.size - KEEP_RECENT
        while (cut > 1 && wire[cut].role == ROLE_TOOL) cut--
        if (cut <= 1) return

        val older = wire.subList(1, cut).filter { it.role != ROLE_SYSTEM }
        if (older.isEmpty()) return

        val summary = try {
            summarize(older, settings)
        } catch (error: IOException) {
            // A stop during the summary is still a stop: let the caller's check see it
            // rather than reporting the abort as a failure of the whole run.
            if (cancelled.get()) return
            throw error
        } finally {
            inFlight = null
        }
        if (summary.isBlank()) return

        val pinned = wire.subList(1, cut).filter { it.role == ROLE_SYSTEM }
        val tail = wire.subList(cut, wire.size).toList()
        val head = wire.first()
        wire.clear()
        wire += head
        wire += ChatMessage.system("[上下文摘要] $summary")
        wire += pinned
        wire += tail

        // The next round's estimate has to be priced off the summary, not off the
        // messages it replaced, or the threshold would fire again immediately.
        lastPromptTokens = 0
        lastPromptChars = 0
        contextNote = "已压缩上下文：${older.size} 条消息 → 摘要"
        // Published at once rather than waiting for the next tick, so the note is on
        // screen while the summary is still the news.
        tick()
    }

    /** One call to the same endpoint, asking only for prose: no tools, no streaming UI. */
    private suspend fun summarize(older: List<ChatMessage>, settings: AppSettings): String {
        val transcript = older.joinToString("\n") { renderForSummary(it) }
        val turn = llm.complete(
            settings = settings,
            messages = listOf(
                ChatMessage.system(COMPRESS_PROMPT),
                ChatMessage.user(transcript),
            ),
            tools = emptyList(),
            onCall = { inFlight = it },
        )
        return turn.text.trim()
    }

    private fun renderForSummary(message: ChatMessage): String = buildString {
        append(message.role).append(": ")
        append(message.text)
        message.toolCalls.forEach { call ->
            append("\n  [调用工具 ").append(call.name).append(' ').append(call.arguments).append(']')
        }
        if (message.imageBase64 != null) append("\n  （含一张截图，已省略）")
    }

    /**
     * How big the next request will be, in the endpoint's own tokens where possible.
     *
     * The last reported prompt size is a fact; only what has been appended since is
     * estimated, at four characters per token for the mixed Chinese-and-JSON traffic
     * an agent produces. Before the first usage arrives there is nothing to anchor to,
     * so the whole wire is estimated the pessimistic way.
     */
    private fun estimatePromptTokens(wire: List<ChatMessage>): Int {
        val chars = wireChars(wire)
        val reported = lastPromptTokens
        if (reported <= 0) return chars / 3
        return reported + (chars - lastPromptChars).coerceAtLeast(0) / 4
    }

    private fun wireChars(wire: List<ChatMessage>): Int = wire.sumOf { messageChars(it) }

    /**
     * What one message costs in characters: its text, its images, and the arguments of any
     * tool call it asks for. All three travel to the endpoint, and leaving the arguments out
     * would undercount the part of a history that is made almost entirely of tool calls.
     */
    private fun messageChars(message: ChatMessage): Int =
        message.text.length + message.imageBase64.orEmpty().length +
            message.toolCalls.sumOf { it.name.length + it.arguments.length }

    private fun stats(): StreamStats {
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        val tokens = estimateTokens(outChars.get())
        return StreamStats(
            tokensOut = tokens,
            thinkingTokens = estimateTokens(thinkingChars.get()),
            elapsedMs = elapsedMs,
            tokPerSec = if (elapsedMs <= 0L) 0f else tokens * 1000f / elapsedMs,
            lastPromptTokens = lastPromptTokens,
            breakSystem = breakSystem,
            breakTools = breakTools,
            breakHistory = breakHistory,
            note = contextNote,
        )
    }

    /**
     * Drops the oldest turns until the replay fits [budget] tokens, when the user
     * capped the context.
     *
     * The unit is the one a desktop agent uses — a window — rather than a message
     * count, because one screenshot or one long tool dump can outweigh fifty short
     * exchanges. The estimate is deliberately cheap and a little pessimistic; the
     * exact count is the server's to report, and it does, in the usage line.
     *
     * Trimming from the front is done on turn boundaries: a transcript that opens
     * with a tool result has no assistant turn to attach it to, and endpoints reject
     * that outright, so the cut moves forward to the first user message.
     */
    private fun trim(messages: List<StoredMessage>, budget: Int): List<StoredMessage> {
        if (budget <= 0 || messages.isEmpty()) return messages
        val kept = ArrayDeque<StoredMessage>()
        var used = 0
        for (message in messages.asReversed()) {
            val cost = estimateTokens(message)
            // The newest message always goes out, however large it is.
            if (kept.isNotEmpty() && used + cost > budget) break
            kept.addFirst(message)
            used += cost
        }
        if (kept.size == messages.size) return messages
        val tail = kept.toList()
        val start = tail.indexOfFirst { it.role == Roles.USER }
        return if (start <= 0) tail else tail.subList(start, tail.size)
    }

    /**
     * A run stopped in the middle of a tool call leaves an assistant message whose
     * `tool_calls` were never answered. Replaying that to an OpenAI-compatible
     * endpoint is a hard 400, so any such message is dropped before sending.
     */
    private fun sanitize(messages: List<StoredMessage>): List<StoredMessage> {
        val answered = messages.filter { it.role == Roles.TOOL }.mapNotNull { it.toolCallId }.toSet()
        return messages.filterNot { message ->
            message.role == Roles.ASSISTANT && message.toolCalls.any { it.id !in answered }
        }
    }

    private fun appendToWire(
        wire: MutableList<ChatMessage>,
        message: StoredMessage,
        settings: AppSettings,
    ) {
        // Images only go out when the configured model can read them; a text-only
        // endpoint rejects the whole request otherwise.
        val image = message.imageBase64.takeIf { settings.imageInput }
        val note = if (message.imageBase64 != null && image == null) {
            "\n（图片已省略：当前未开启「模型支持图片输入」）"
        } else {
            ""
        }
        when (message.role) {
            Roles.USER -> wire += ChatMessage.user(message.text + note, image)
            Roles.SYSTEM -> wire += ChatMessage.system(message.text)
            Roles.ASSISTANT -> wire += ChatMessage.assistant(
                message.text,
                message.toolCalls.map { ToolCallRequest(it.id, it.name, it.arguments) },
            )
            Roles.TOOL -> {
                wire += ChatMessage.tool(
                    message.toolCallId ?: message.toolName.orEmpty(),
                    message.toolOutput.orEmpty(),
                )
                if (image != null) wire += ChatMessage.user("（上一步工具的截图）", image)
            }
        }
    }

    private fun blockedReason(tier: String, capabilities: CapabilityHub): String? {
        val state = capabilities.state.value
        return when (tier) {
            TIER_ACCESSIBILITY ->
                // 无障碍是首选通道，但 Shizuku / root 也能完成同一批操作
                // （input / screencap / uiautomator），所以有其中任一即可放行。
                if (state.accessibility || state.shellReady || state.rootReady) {
                    null
                } else {
                    "还不能操作屏幕：请到「能力」页开启无障碍服务，或授权 Shizuku / root 中的任意一个。"
                }
            TIER_SHELL ->
                if (state.shellReady || state.rootReady) null else "缺少 Shizuku（或 root）权限：请到「能力」页授权后重试。"
            TIER_ROOT ->
                if (state.rootReady) null else "缺少 root 权限，当前无法执行该工具。"
            else -> null
        }
    }

    private fun parseArgs(raw: String): JsonObject =
        runCatching { Json.parseToJsonElement(raw.ifBlank { "{}" }).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }

    private fun systemPrompt(
        settings: AppSettings,
        registry: ToolRegistry,
        skillCatalog: String,
        memory: Map<String, MemoryEntry>,
    ): String = buildString {
        append(settings.systemPrompt.trim())
        append("\n\n## 可用工具\n")
        append(registry.promptSummary())
        if (skillCatalog.isNotBlank()) {
            append("\n\n")
            append(skillCatalog)
        }
        val remembered = memoryBlock(memory)
        if (remembered.isNotBlank()) {
            append("\n\n## 你记得的事\n")
            append(remembered)
        }
        append(
            "\n\n调用工具时，参数必须是合法 JSON 对象。一次可以并行调用多个工具。" +
                "工具返回失败时先分析原因，不要重复同样的调用。" +
                "任务完成后，用简洁的中文向用户说明做了什么、结果如何。",
        )
        // The board is the one piece of state that outlives the context window, so the prompt
        // has to say so: a model that keeps its plan in its head loses it the first time the
        // history is summarised.
        append(
            "\n多步任务先用 todo_write 列清单，每完成一步重写一遍完整清单（不是追加）：" +
                "你的上下文会被压缩，任务板不会。",
        )
    }

    /**
     * Renders the agent's own notes into the prompt, so they are simply *known*
     * rather than something the model has to remember to look up.
     *
     * Bounded on purpose: memory is unbounded user data, and a note that grows to
     * megabytes would otherwise eat the whole context window on every request.
     */
    private fun memoryBlock(memory: Map<String, MemoryEntry>): String =
        memory.entries
            .sortedBy { it.key }
            .take(MAX_MEMORY_ENTRIES)
            .joinToString("\n") { (key, entry) ->
                "- $key: ${entry.value.take(MAX_MEMORY_VALUE)}"
            }
    }

    companion object {
        /** How much of the streamed answer the console's live line keeps, in characters. */
private const val LIVE_LINE_CHARS = 40

private const val MAX_TOOL_OUTPUT = 8_000
        private const val MAX_MEMORY_ENTRIES = 40
        private const val MAX_MEMORY_VALUE = 400

        /** 120 ms is faster than the eye and slower than the stream. */
        private const val STATS_TICK_MS = 120L

        /** 1 = 立即插话 in [AppSettings.steerMode]. */
        private const val STEER_IMMEDIATE = 1

        /**
         * How many of the newest messages a compression always keeps verbatim.
         *
         * Six is a turn's worth of context — the user's line, the assistant's answer
         * and a couple of tool results — which is what the model is actively working
         * on when the compression fires.
         */
        private const val KEEP_RECENT = 6

        /** Below this many messages there is nothing worth summarising. */
        private const val MIN_MESSAGES = 8

        /** Compress at 80% of the window: the next round has to fit in what is left. */
        private const val COMPRESS_AT = 0.8

        private const val ROLE_TOOL = "tool"
        private const val ROLE_SYSTEM = "system"

        private const val COMPRESS_PROMPT =
            "把下面这段 agent 执行历史压缩成要点摘要。保留：已经得出的结论、涉及的文件路径与命令、" +
                "已经做出的决定及其原因、尚未完成的事项。丢掉：寒暄、重复的尝试、被推翻的中间过程。" +
                "直接输出摘要正文，不要加任何开场白或标题。"

        /**
         * Roughly a token per three characters of Chinese, which is where the error
         * hurts most; English runs closer to four. Close enough for a speed readout
         * and for a context budget, and both would rather be over-cautious than late.
         */
        private fun estimateTokens(chars: Int): Int = chars / 3
    }
}

/**
 * The same estimate for a stored message, used by the context trimmer: a screenshot
 * costs its base64 length, everything else its text.
 */
private fun estimateTokens(message: StoredMessage): Int {
    val text = message.text.length + message.toolOutput.orEmpty().length + message.toolArgs.orEmpty().length
    return (text + (message.imageBase64?.length ?: 0)) / 3
}

/**
 * Stamps what the round cost onto the message it produced. Endpoints that send no
 * usage block leave the counts at zero, which the UI reads as "unknown" rather than
 * "free".
 */
private fun StoredMessage.withUsage(usage: Usage?, elapsedMs: Long, model: String): StoredMessage {
    if (usage == null) return copy(elapsedMs = elapsedMs, model = model)
    return copy(
        promptTokens = usage.promptTokens,
        completionTokens = usage.completionTokens,
        reasoningTokens = usage.reasoningTokens,
        cachedTokens = usage.cachedTokens,
        elapsedMs = elapsedMs,
        model = model,
    )
}
