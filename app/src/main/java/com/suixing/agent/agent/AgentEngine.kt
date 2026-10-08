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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import java.io.IOException
import java.util.UUID
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

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    private var waiter: CompletableDeferred<Decision>? = null

    private val alwaysAllowed = mutableSetOf<String>()

    private val alwaysDenied = mutableSetOf<String>()

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

    suspend fun request(toolName: String, args: String, mode: ApprovalMode): Boolean {
        when (mode) {
            ApprovalMode.AUTO -> return true
            ApprovalMode.DENY -> return false
            ApprovalMode.ASK -> Unit
        }
        if (alwaysDenied.contains(toolName)) return false
        if (alwaysAllowed.contains(toolName)) return true
        val deferred = CompletableDeferred<Decision>()
        waiter = deferred
        _pending.value = Request(UUID.randomUUID().toString(), toolName, args)
        val decision = deferred.await()
        _pending.value = null
        waiter = null
        if (decision.always) {
            if (decision.approved) rememberAlways(toolName) else rememberDenied(toolName)
        }
        return decision.approved
    }

    fun resolve(approved: Boolean, always: Boolean = false) {
        waiter?.complete(Decision(approved, always))
    }

    /**
     * Used when the user stops the run while a prompt is on screen. The state is
     * cleared here rather than in [request] because a cancelled coroutine never
     * resumes, which would otherwise leave the dialog on screen forever.
     */
    fun cancelPending() {
        waiter?.complete(Decision(false, false))
        waiter = null
        _pending.value = null
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
 * The agent loop: send the conversation to the model, execute whatever tools it
 * asks for, feed the results back, and repeat until it stops calling tools.
 *
 * It also owns the three things a caller needs to steer a run from outside: the
 * live [runState], [cancel] and [steer]. A caller that only wants the transcript
 * still gets it the old way, through `onEvent` — the two channels are independent,
 * so nothing that listens to events had to change.
 */
class AgentEngine(
    private val llm: LlmClient = LlmClient(),
    private val scope: CoroutineScope? = null,
) {

    private val _runState = MutableStateFlow<RunState>(RunState.Idle)

    /** Live run state; survives whichever screen happens to be on top. */
    val runState: StateFlow<RunState> = _runState.asStateFlow()

    /** 0 = no run has ever started, which is what makes a stale run's writes harmless. */
    private val runSeq = AtomicInteger(0)

    private val running = AtomicBoolean(false)

    private val cancelled = AtomicBoolean(false)

    /** The call currently on the wire; cancelling it is what stops the stream. */
    @Volatile
    private var inFlight: Call? = null

    private val steering = Channel<String>(Channel.UNLIMITED)

    private val outChars = AtomicInteger(0)

    private val thinkingChars = AtomicInteger(0)

    /** When the current run began, in [System.nanoTime] terms; 0 = not running. */
    @Volatile
    private var startedAt = 0L

    /**
     * Ticks the speed readout while a run is live.
     *
     * It runs on a timer rather than on every delta because the UI would otherwise
     * be recomposed once per token; 120 ms is faster than the eye and slower than
     * the stream.
     */
    init {
        scope?.launch {
            while (isActive) {
                if (running.get()) emitRunning()
                delay(STATS_TICK_MS)
            }
        }
    }

    /**
     * Stops the run that is streaming right now.
     *
     * Cancelling the OkHttp call is the part that matters: without it the loop would
     * keep reading tokens off the wire until the model decided to stop, and 「点停即停」
     * would be a lie. The flag is what carries the decision past the call, so a tool
     * that is already executing is not waited on either — the loop checks it at the
     * top of every round and before every tool call.
     *
     * @return whether there was a run to interrupt.
     */
    fun cancel(): Boolean {
        if (!running.get()) return false
        cancelled.set(true)
        inFlight?.cancel()
        return true
    }

    /**
     * Queues a message to be handed to the model at its next turn.
     *
     * Steering is not a second run: the text lands in the same conversation, before
     * the next request goes out, so the model sees it as something the user said
     * while it was working. When nothing is running there is nobody to tell, so the
     * caller gets false and keeps the text.
     *
     * @return whether the text was queued.
     */
    fun steer(text: String): Boolean {
        if (!running.get()) return false
        val body = text.trim()
        if (body.isEmpty()) return false
        return steering.trySend(body).isSuccess
    }

    suspend fun run(
        history: List<StoredMessage>,
        settings: AppSettings,
        registry: ToolRegistry,
        capabilities: CapabilityHub,
        skills: SkillStore,
        memory: MemoryStore,
        gate: ApprovalGate,
        onEvent: (AgentEvent) -> Unit,
    ): List<StoredMessage> {
        val seq = runSeq.incrementAndGet()
        cancelled.set(false)
        outChars.set(0)
        thinkingChars.set(0)
        startedAt = System.nanoTime()
        running.set(true)
        // Published here rather than left to the ticker, so a caller sees Running the
        // moment the run starts even if no ticker was ever started.
        _runState.value = RunState.Running(stats())
        // A queue left over from a previous run would otherwise be injected into this
        // one as if the user had just said it.
        while (steering.tryReceive().isSuccess) Unit

        val produced = mutableListOf<StoredMessage>()
        val wire = mutableListOf<ChatMessage>()
        // Whether a terminal state has been published for this run. The `finally` must
        // not paint Idle over Cancelled or Failed — the UI would lose the one fact it
        // needs to leave the running state.
        var settled = false
        wire += ChatMessage.system(
            systemPrompt(settings, registry, skills.catalog(skills.list()), memory.entries.value),
        )
        sanitize(trim(history, settings.contextWindow)).forEach { appendToWire(wire, it, settings) }

        // 0 = the user did not set a ceiling, so the only stop is the model itself.
        val maxRounds = if (settings.maxToolRounds <= 0) MAX_ROUNDS else settings.maxToolRounds.coerceIn(1, MAX_ROUNDS)
        var round = 0
        try {
            while (round < maxRounds) {
                round++
                // Steering lands here, on the boundary between two model turns: the
                // model has finished the previous turn and has not been asked for the
                // next one, which is the only moment the wire can be edited safely.
                drainSteering(wire)
                if (cancelled.get()) {
                    settled = true
                    finishCancelled(onEvent)
                    return produced
                }

                val turnStartedAt = System.currentTimeMillis()
                val turn = try {
                    llm.complete(
                        settings = settings,
                        messages = wire,
                        tools = registry.all().map { it.spec },
                        onDelta = { piece ->
                            outChars.addAndGet(piece.length)
                            onEvent(AgentEvent.TextDelta(piece))
                        },
                        onReasoning = { piece ->
                            thinkingChars.addAndGet(piece.length)
                            onEvent(AgentEvent.ReasoningDelta(piece))
                        },
                        onCall = { inFlight = it },
                    )
                } catch (error: IOException) {
                    // Cancelling a call surfaces as an IOException on the reading
                    // thread. It is a stop, not a failure, so it is reported as one.
                    if (cancelled.get()) {
                        settled = true
                        finishCancelled(onEvent)
                        return produced
                    }
                    throw error
                } finally {
                    inFlight = null
                }
                val elapsed = System.currentTimeMillis() - turnStartedAt
                val usage = turn.usage
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
                    ).withUsage(usage, elapsed)
                    produced += assistant
                    onEvent(AgentEvent.AssistantMessage(assistant))
                    onEvent(AgentEvent.Finished(turn.text))
                    _runState.value = RunState.Done(stats())
                    settled = true
                    return produced
                }
                val assistant = StoredMessage(
                    id = UUID.randomUUID().toString(),
                    role = Roles.ASSISTANT,
                    text = turn.text,
                    reasoning = thinking,
                    toolCalls = turn.toolCalls.map { StoredToolCall(it.id, it.name, it.arguments) },
                ).withUsage(usage, elapsed)
                produced += assistant
                onEvent(AgentEvent.AssistantMessage(assistant))
                wire += ChatMessage.assistant(turn.text, turn.toolCalls)

                for (call in turn.toolCalls) {
                    // Checked before every call, not after the batch: a stop means the
                    // tools the model asked for in the same breath do not run either.
                    if (cancelled.get()) {
                        settled = true
                        finishCancelled(onEvent)
                        return produced
                    }
                    val argsPreview = call.arguments.take(600)
                    val tool = registry.find(call.name)
                    val result = if (tool == null) {
                        ToolResult.failure("未知工具：${call.name}")
                    } else {
                        val blocked = blockedReason(tool.tier, capabilities)
                        if (blocked != null) {
                            ToolResult.failure(blocked)
                        } else {
                            onEvent(AgentEvent.ToolStarted(call.name, argsPreview))
                            if (!gate.request(call.name, argsPreview, settings.approvalMode)) {
                                ToolResult.failure("用户拒绝了这次调用。")
                            } else {
                                runCatching { tool.run(parseArgs(call.arguments)) }.getOrElse { error ->
                                    if (error is CancellationException) throw error
                                    ToolResult.failure("工具执行异常：${error.message ?: error::class.java.simpleName}")
                                }
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
                    onEvent(AgentEvent.ToolFinished(call.name, result.ok, output))
                }
            }
            onEvent(AgentEvent.Failed("已达到最大工具轮数（$maxRounds），任务未完成。"))
            _runState.value = RunState.Failed("已达到最大工具轮数（$maxRounds），任务未完成。")
            settled = true
            return produced
        } catch (cancellation: CancellationException) {
            // The caller cancelled the coroutine rather than the run; the stream still
            // has to be taken down, and the state still has to say what happened.
            inFlight?.cancel()
            _runState.value = RunState.Cancelled(stats())
            settled = true
            throw cancellation
        } catch (error: Throwable) {
            _runState.value = RunState.Failed(error.message ?: error::class.java.simpleName)
            settled = true
            throw error
        } finally {
            running.set(false)
            inFlight = null
            startedAt = 0L
            if (!settled) emit(seq, RunState.Idle)
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
        _runState.value = RunState.Cancelled(stats())
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

    private fun emitRunning() = emit(runSeq.get(), RunState.Running(stats()))

    private fun emit(seq: Int, state: RunState) {
        // A run that has already been replaced must not paint over its successor's state.
        if (seq == runSeq.get()) _runState.value = state
    }

    private fun stats(): StreamStats {
        val started = startedAt
        val elapsedMs = if (started == 0L) 0L else (System.nanoTime() - started) / 1_000_000L
        val tokens = estimateTokens(outChars.get())
        return StreamStats(
            tokensOut = tokens,
            thinkingTokens = estimateTokens(thinkingChars.get()),
            elapsedMs = elapsedMs,
            tokPerSec = if (elapsedMs <= 0L) 0f else tokens * 1000f / elapsedMs,
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

    companion object {
        private const val MAX_ROUNDS = 60
        private const val MAX_TOOL_OUTPUT = 8_000
        private const val MAX_MEMORY_ENTRIES = 40
        private const val MAX_MEMORY_VALUE = 400

        /** 120 ms is faster than the eye and slower than the stream. */
        private const val STATS_TICK_MS = 120L

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
private fun StoredMessage.withUsage(usage: Usage?, elapsedMs: Long): StoredMessage {
    if (usage == null) return copy(elapsedMs = elapsedMs)
    return copy(
        promptTokens = usage.promptTokens,
        completionTokens = usage.completionTokens,
        reasoningTokens = usage.reasoningTokens,
        cachedTokens = usage.cachedTokens,
        elapsedMs = elapsedMs,
    )
}
