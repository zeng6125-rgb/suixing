package com.suixing.agent.agent

import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.capability.ShellTier
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.STEER_IMMEDIATE
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.MemoryEntry
import com.suixing.agent.data.MemoryStore
import com.suixing.agent.data.UserProfileStore
import com.suixing.agent.data.Roles
import com.suixing.agent.data.SettingsRepository
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.StoredToolCall
import com.suixing.agent.data.Tps
import com.suixing.agent.llm.ChatMessage
import com.suixing.agent.llm.FINISH_CUT
import com.suixing.agent.llm.LlmClient
import com.suixing.agent.llm.LlmException
import com.suixing.agent.llm.ToolCallRequest
import com.suixing.agent.llm.Usage
import com.suixing.agent.plugins.ManifestValidator
import com.suixing.agent.plugins.PluginRuntime
import com.suixing.agent.plugins.HookDecl
import com.suixing.agent.plugins.HookVerdict
import com.suixing.agent.plugins.PluginSpec
import com.suixing.agent.core.Core
import com.suixing.agent.skills.SkillStore
import com.suixing.agent.service.TouchFx
import com.suixing.agent.tools.TIER_ACCESSIBILITY
import com.suixing.agent.tools.TIER_NONE
import com.suixing.agent.tools.TIER_ROOT
import com.suixing.agent.tools.TIER_SHELL
import com.suixing.agent.tools.ToolRegistry
import com.suixing.agent.tools.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import java.io.File
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

    /**
     * One line the user slipped in has just been written into the prompt, in this order.
     *
     * The UI needs this to know a queued line was actually read: until it lands the line is still
     * retractable and belongs nowhere in the transcript, and after it the bubble is history.
     */
    data class Steered(
        val id: String,
        val text: String,
        /**
         * The picture that came with the line, when there was one.
         *
         * It travels because the bubble the user sees is built from this event: without it the
         * transcript would show a steer that silently lost its attachment, while the model had
         * been sent it.
         */
        val imagesBase64: List<String> = emptyList(),
    ) : AgentEvent

    /**
     * An attempt failed and the same request is going out again in [delayMs].
     *
     * The run is still Running — it has not stopped, it is waiting — so the screen needs to say so:
     * a run that goes quiet for the length of a retry delay reads as one that ended, which is how
     * 「发出去，几秒之后就停止」 was reported while the requests were in fact being retried.
     */
    data class Retrying(
        /** Which attempt failed (1 = the first try). */
        val attempt: Int,
        /** How many retries the settings allow. */
        val maxAttempts: Int,
        val delayMs: Long,
        /** Short and user-facing: why the attempt is being repeated. */
        val reason: String,
        /** The HTTP status that failed; null for a transport failure. For the trace, not the UI. */
        val code: Int? = null,
    ) : AgentEvent

    /**
     * 这一轮作废重来：已经流到屏幕上的内容不算数了。
     *
     * 只在"收到内容之后流断了、还能重试"时发出（[LlmClient][com.suixing.agent.llm.LlmClient] 的
     * `onRestart`）。界面收到它就把本段 live parts 撤掉，否则重发的那一轮会把同一段开头再画一遍。
     */
    data object RoundRestarted : AgentEvent

    /**
     * 压缩摘要正在生成（true＝开始，false＝结束）。
     *
     * 没有它，压缩那几秒里状态行停在上一句（「正在调用 xxx」），看着像卡住 —— 用户要求
     * run 中的压缩也要在状态行里说「正在压缩上下文」。
     */
    data class Compressing(val on: Boolean) : AgentEvent
}

/**
 * One line the user slipped in, waiting for the model's next turn.
 *
 * File level rather than a member of the run handle: the parser refuses a class declaration nested
 * inside an `inner class` (`'Class' is prohibited here`), and both this and [WireSpan] are only
 * names for the run's own bookkeeping.
 */
private data class Steer(val id: String, val text: String, val imagesBase64: List<String> = emptyList())

/**
 * Which stored message a stretch of the wire came from: [from] is where it starts and [to] is one
 * past its last entry.
 *
 * The wire carries no ids of its own, and a checkpoint has to be able to name the messages it
 * folded away — a compression that cannot say what it covered can never be resumed from.
 */
private data class WireSpan(val id: String, val from: Int, val to: Int)

/**
 * 一条工具调用的执行结果，与「把它写回 wire」分离（[executeOneTool] 只产这个，[runLoop] 里的
 * commitTool 才碰 wire/spans/produced/openCalls）。
 *
 * 分离是并行的前提：执行阶段（可能很慢、可并发）不碰任何 run 级可变状态；回写阶段在主协程里
 * 按模型请求的顺序逐条做，端点对 tool 消息顺序敏感，回写绝不能乱序。
 */
private data class ToolOutcome(
    val call: ToolCallRequest,
    val result: ToolResult,
    /** 这一条实际跑了多久（毫秒）；被拒/未知工具为 0。 */
    val toolMs: Long,
    /** 给审批与事件用的参数预览（截断过的）。 */
    val argsPreview: String,
)

/**
 * Holds a tool call until the user answers. In ASK mode the agent coroutine
 * parks on a [CompletableDeferred], so the run resumes the moment the user taps
 * approve or reject in the UI.
 *
 * The mode arrives per call from the settings snapshot the run started with, so switching it
 * while a run is in flight does not change that run — the next one obeys the new mode. (The
 * composer's picker tells the user the same thing.)
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
     * **当前这一轮请求**的实际大小（端点报过的真实值 + 自上次上报以来的增量估算）。
     *
     * 与 [lastPromptTokens]（上一轮上报值）的区别：wire 每轮在长，而 lastPromptTokens 要等本轮
     * usage 回来才更新 —— 显示层拿它当「现在多大」会在轮内停在旧值、轮末跳一下。这个字段在
     * 每轮请求定型时（wire 最终、工具表拼完）刷新，轮内稳定、跨轮单调，显示不再一会大一会小
     * （用户报的「上下文窗口一会大一会小」）。首轮还没有任何端点读数时它是 chars 估算。
     */
    val currentPrompt: Int = 0,
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

    /** 画像整理器：收尾挂钩与「重新总结」共用一个，它自己无状态。 */
    private val summarizer = ProfileSummarizer(llm)

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
     * [id] is the caller's handle on the line: it is what [retract] and [promote] address, and it
     * comes back on [AgentEvent.Steered] when the model actually reads it.
     *
     * @return whether the text was taken; false when that conversation is not running.
     */
    fun steer(
        chatId: String,
        id: String,
        text: String,
        immediate: Boolean = runs.value[chatId]?.steerMode == STEER_IMMEDIATE,
        imagesBase64: List<String> = emptyList(),
    ): Boolean = runs.value[chatId]?.steer(id, text, immediate, imagesBase64) ?: false

    /**
     * Whether the run in [chatId] takes a line as an interruption (立即) rather than as something
     * that waits.
     *
     * The chat screen asks this before it decides *where* a typed line goes: in 排队 mode it is not
     * a steer at all, it is the next turn, and it must not be pushed into the run that is going.
     * Reading the run's own snapshot rather than the live setting is the point — a run started in
     * 排队 stays 排队 for its whole life, so the screen and the engine answer the same way.
     * False when nothing is running, which sends the caller down its ordinary send path.
     */
    fun steersImmediately(chatId: String): Boolean = runs.value[chatId]?.steerMode == STEER_IMMEDIATE

    /**
     * Takes a line back before the model has read it.
     *
     * This is the whole reason the queue is keyed by id: a line the user thought better of must
     * be removable while it is still waiting, and once it has landed in the prompt there is no
     * taking it back — so the answer says which of the two happened.
     *
     * @return true when the line was still waiting and is now gone; false once it has been read
     *   (or when that conversation is not running, or the id was never this run's).
     */
    fun retract(chatId: String, id: String): Boolean = runs.value[chatId]?.retract(id) ?: false

    /**
     * Pushes one waiting line to the front, so the model reads it now instead of at the end of
     * the round in flight.
     *
     * Only that line moves: the others stay queued and land on their own boundary. A queue that
     * empties the moment someone else is in a hurry is not a queue.
     *
     * @return true when the line was still waiting and is now at the front; false once it has
     *   been read, or when that conversation is not running.
     */
    fun promote(chatId: String, id: String): Boolean = runs.value[chatId]?.promote(id) ?: false

    /**
     * Asks one conversation's run to fold its oldest context into a summary.
     *
     * The compression itself happens at the next round boundary, the same place every other one
     * happens, so tapping a menu item never cuts the answer being written.
     *
     * @return false when that conversation is not running, so the UI can say so rather than
     *   pretending something was compressed.
     */
    fun requestCompress(chatId: String): Boolean = runs.value[chatId]?.requestCompress() ?: false

    /**
     * 手动「重新总结」用户画像：立刻跑一次，不看轮数门限，也不看 [AppSettings.autoProfile]
     * ——这是用户自己按的。
     *
     * [store] 与 [settings] 由调用方递进来，理由和 [startRun] 一样：引擎不持有任何仓库。
     * 端点在 [settings.profileEndpoint] 里现解析（会话自己的选择 → 助手的固定 → 全局默认），
     * 而 [messages] 是拿来整理的那段对话——画像页给的是最近一次会话。
     */
    suspend fun summarizeProfileNow(
        store: UserProfileStore,
        settings: AppSettings,
        messages: List<StoredMessage>,
    ): ProfileSummary = summarizer.summarize(store, settings.profileEndpoint(), messages)

    /**
     * 收尾挂钩：一轮结束之后，按门限决定要不要重新总结用户画像。
     *
     * 三条门槛缺一不可——设置里开着自动整理、距上次总结的轮数到了
     * [AppSettings.profileTurnThreshold]、没有别的会话还在跑。整理是**另一次请求**，不进主
     * wire；整条链路都在引擎自己的 scope 上跑，失败只当没发生，用户不会被打扰，已有画像也
     * 一个字都不动。
     *
     * 计数在跳过时**不归零**：别的会话还在跑就等下一次收尾，这一轮不会白数。
     */
    private fun scheduleProfileSummary(
        settings: AppSettings,
        profile: UserProfileStore,
        history: List<StoredMessage>,
        produced: List<StoredMessage>,
    ) {
        if (!settings.autoProfile) return
        // 没有 scope = 没人给引擎一条长命的后台通道（构造时就是这么传的），那就没有自动整理。
        val background = scope ?: return
        background.launch {
            val turns = profile.bumpTurn()
            if (turns < settings.profileTurnThreshold) return@launch
            // 别的会话还在跑：让它在自己的收尾处再算。两件事同时用一条线会让两边都慢，
            // 而画像是可以晚一轮的东西。
            if (runs.value.isNotEmpty()) return@launch
            summarizer.summarize(profile, settings, history + produced)
        }
    }

    /**
     * Runs one conversation and returns the messages it produced.
     *
     * The run happens on the caller's coroutine, but on a job of its own underneath it (see
     * [RunHandle.execute]): N conversations running at once is N callers, which is why nothing
     * here launches into a scope of its own. Cancelling the caller's coroutine still ends the
     * run, and so does [cancel] — without taking the caller down with it.
     *
     * A conversation that is already running is refused rather than queued: two runs writing
     * one transcript would interleave two answers into one and leave the user unable to tell
     * which is which. Callers should ask [isRunning] first.
     *
     * [checkpoints] are the compressions this conversation has already been through, oldest first:
     * the newest one that still lines up with [history] is reused instead of being paid for again.
     * [onCheckpoint] is handed every new compression, to be stored on the conversation — the
     * summary has to outlive this run or the next one starts from the full history and re-does it.
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
        profile: UserProfileStore,
        gate: ApprovalGate,
        onEvent: (AgentEvent) -> Unit,
        checkpoints: List<ContextCheckpoint> = emptyList(),
        onCheckpoint: (ContextCheckpoint) -> Unit = {},
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
        // 这一轮产出的消息：收尾的画像整理要连着它一起看这一轮说了什么，所以在这里接一下。
        var produced: List<StoredMessage> = emptyList()
        try {
            produced = handle.execute(
                history = history,
                checkpoints = checkpoints,
                settings = settings,
                registry = registry,
                capabilities = capabilities,
                skills = skills,
                memory = memory,
                profile = profile,
                gate = gate,
                onEvent = onEvent,
                onCheckpoint = onCheckpoint,
            )
            return produced
        } finally {
            // Gone from [runs] before the state settles: a finished run is not a run, and the
            // console must stop offering to stop it. By identity, so a stale finally cannot
            // unregister a newer run that took the same key.
            runs.update { if (it[chatId] === handle) it - chatId else it }
            // 终态已经发布、这一轮也已经不是「在跑的 run」了，才轮到画像整理：它另开一条
            // 协程，不阻塞调用方，也不进这一轮的 wire。
            scheduleProfileSummary(settings, profile, history, produced)
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
    /**
     * **没有 run 时**手动压缩一次（用户：「无 run 的时候用，就气泡＋压缩块就行了」）。
     *
     * 与 run 内的压缩共用同一套切法（[planCompaction]）与同一份提示词（[COMPRESS_PROMPT]），
     * 区别只在于它不碰任何在飞的 wire：按已落盘的历史算一段、交给模型摘要、把结果交回去存成
     * 压缩记录 —— 记录落进 `checkpoints`，转录里就会在它覆盖的位置出现一张压缩块。
     *
     * @return null 表示成功；否则是给用户看的原因。
     */
    suspend fun compressNow(
        history: List<StoredMessage>,
        settings: AppSettings,
        onCheckpoint: (ContextCheckpoint) -> Unit,
    ): String? {
        val plan = planCompaction(history) ?: return "消息太少，没有可压缩的内容"
        val turn = runCatching {
            llm.complete(
                settings = settings,
                messages = listOf(
                    ChatMessage.system(COMPRESS_PROMPT),
                    ChatMessage.user(plan.transcript),
                ),
                tools = emptyList(),
            )
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            return "压缩失败：${error.message ?: error.javaClass.simpleName}"
        }
        val summary = turn.text.trim()
        if (summary.isBlank()) return "压缩失败：模型没有返回摘要"
        val first = plan.coveredIds.firstOrNull() ?: return "没有找到可压缩的消息范围"
        val last = plan.coveredIds.lastOrNull() ?: return "没有找到可压缩的消息范围"
        onCheckpoint(
            ContextCheckpoint(
                id = UUID.randomUUID().toString(),
                at = System.currentTimeMillis(),
                fromMessageId = first,
                toMessageId = last,
                summary = summary,
                messages = plan.foldedMessages,
                // 估算口径与 run 内一致：中文约 3 字符 1 token。
                promptTokens = plan.transcript.length / 3,
            ),
        )
        return null
    }

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

        /**
         * The coroutine driving this run: the run's own job, a child of the caller's, so that
         * stopping the run does not stop whoever asked for it.
         */
        @Volatile
        internal var job: Job? = null

        /**
         * The job that asked for this run — the caller's, not the run's own ([job]).
         *
         * Kept only so a cancellation can tell 「用户停了这一次 run」 from 「调用者的作用域没了」:
         * the first ends the run with what it produced, the second has to travel on as a
         * cancellation or structured concurrency breaks.
         */
        @Volatile
        private var caller: Job? = null

        /** [steer]'s default mode for this run, read from the settings it started with. */
        internal val steerMode: Int = settings.steerMode

        private val cancelled = AtomicBoolean(false)

        /** The call currently on the wire; cancelling it is what stops this run's stream. */
        @Volatile
        private var inFlight: Call? = null

        /**
         * The gate this run asks for approval through, kept so [cancel] can take this run's own
         * prompt down with it. Null until [execute] starts — which is also the only stretch in
         * which there can be no prompt of this run's on screen to take down.
         */
        @Volatile
        private var gate: ApprovalGate? = null

        /**
         * Lines the user slipped in that the model has not read yet, in the order they were said.
         *
         * A map keyed by id rather than a plain queue because the user can still change their
         * mind: a line is retractable right up to the moment it lands in the wire, and that is
         * only possible if the waiting ones can be addressed.
         */
        private val steering = LinkedHashMap<String, Steer>()

        /**
         * Lines the user pushed to the front with 「立即插话」, kept apart from [steering] because
         * being pushed says something about *when* the line lands: the next boundary carries these
         * and leaves the rest queued, so one line's urgency does not drag every other waiting line
         * along with it.
         */
        private val urgent = LinkedHashMap<String, Steer>()

        /** Guards both queues: the UI thread writes them, the run's own coroutine drains them. */
        private val steeringLock = Any()

        /**
         * Set when the user asked for a compression from the 「+」 menu.
         *
         * A request, not an action: folding the oldest context away is done at a round boundary
         * like every other compression, because cutting the answer in flight is what the stop
         * button is for.
         */
        @Volatile
        private var compressNow = false

        /**
         * Set when an immediate [steer] aborts the round on purpose, so the IOException it
         * produces is read as 「这一轮作废」 rather than as a network failure.
         */
        private val steeringCut = AtomicBoolean(false)

        /** Prompt tokens the endpoint reported for the last round; 0 = it never did. */
        @Volatile
        private var lastPromptTokens = 0
        /** 当前轮请求的估算大小，见 [StreamStats.currentPrompt]。每轮定型时刷新。 */
        private var currentPromptEstimate = 0

        /** Wire size in characters when [lastPromptTokens] was recorded, to price the growth. */
        @Volatile
        private var lastPromptChars = 0

        /**
         * How many screenshots the wire carried when [lastPromptTokens] was recorded.
         *
         * Tracked apart from the character count because an image is priced per image: the base64
         * of one picture would swamp any character-based delta.
         */
        @Volatile
        private var lastPromptImages = 0

        /** The three parts of the prompt, in estimated tokens, for the UI's breakdown bar. */
        @Volatile
        private var breakSystem = 0

        @Volatile
        private var breakTools = 0

        /** [breakTools] 是按哪一组工具算出来的：工具集变了就重算（插件热插拔会改它）。 */
        private var breakToolsSig: String? = null

        @Volatile
        private var breakHistory = 0

        /** A one-line note about the run, shown by whoever renders [state]. */
        @Volatile
        private var contextNote = ""

        private val outChars = AtomicInteger(0)

        private val thinkingChars = AtomicInteger(0)

        /**
         * 当前这一轮首个块（正文或思考，任意）到达的 [System.currentTimeMillis] 时刻，0＝本轮还没
         * 出字。每轮开始清零、本轮结束累加进 [decodeMsAccum] 后再清零 —— 所以它在「两轮之间的工具
         * 执行期」是 0，不会被 [stats] 重复计入。与 turn 内的 firstChunkAt 同基（millis）。
         */
        @Volatile
        private var curTurnFirstChunkMs = 0L

        /**
         * 已结束各轮的 decodeMs 之和（官方多轮聚合＝ΣdecodeTokens/ΣdecodeMs，client.js:8331-8333）。
         * decodeMs＝每轮「首字之后→结束」，**不含** TTFT 等待、也**不含**轮间的工具执行时间 —— 这正是
         * 拿整轮墙钟当分母会系统性低估 TPS 的原因。[stats] 再加上当前轮的实时 decode 段。
         */
        @Volatile
        private var decodeMsAccum = 0L

        /**
         * live TPS 用的「每字符 token 数」，由本 run 已完成轮的真实 usage 反推校准。
         *
         * 为什么不用 companion 的 [estimateTokens]（chars/3）：那个 /3 是英文系数，还给**上下文预算**
         * 用（要保守，好让压缩早触发），不能动。但中文实测 ~0.5-0.9 token/字（真机：211 思考字→195
         * completion token），用 /3 会把 live TPS 低估 2-3 倍 —— 用户看到的「明明 200 tok/s、手机上
         * 只有几十」就是这个。每轮结束拿端点报的真 completionTokens ÷ 本轮字符数校准一次，生成中就用
         * 校准值估分子；首轮还没有 usage 时用 [DEFAULT_LIVE_TOKENS_PER_CHAR]。
         */
        @Volatile
        private var liveTokensPerChar = DEFAULT_LIVE_TOKENS_PER_CHAR

        /** 上一次校准时的累计字符数（outChars+thinkingChars），用来算「本轮」的字符增量。 */
        @Volatile
        private var calibCharsBaseline = 0

        /**
         * live 输出 token 估算：用校准过的 [liveTokensPerChar]，不是保守的 chars/3。
         * 只喂 TPS 读数与「出 N」显示；上下文预算仍走 companion 的 [estimateTokens]。
         */
        private fun estimateOutputTokens(chars: Int): Int =
            (chars * liveTokensPerChar).toInt()

    /**
     * Stops this run. See [AgentEngine.cancel] for why the call itself has to be cancelled.
     *
     * @return whether there was anything to interrupt.
     */
    fun cancel(): Boolean {
        if (!isLive()) return false
        // The flag first, then the call, then the coroutine: the decision is visible before
        // either abort lands, so the loop reads the stop as a stop and not as a network failure
        // or as an immediate steer.
        cancelled.set(true)
        // This run's approval prompt, if one is on screen, is answered "refused" for this run only.
        // A cancelled coroutine never resumes the await that is holding it, so without this the
        // card would sit there for a run that is already dead — and stopping from the floating
        // console has no chat screen to do it. Other conversations' prompts stay up.
        gate?.cancelPending(chatId)
        inFlight?.cancel()
        // Cancelling the job is what reaches a tool that is already running. The wire is not the
        // only thing a run can be blocked on: a shell command only notices a stop if its coroutine
        // is cancelled (that is what Shell's polling checks), and the floating console has no
        // coroutine of its own to cancel — so without this, stopping from the console waited for
        // the tool to finish while stopping from the chat screen did not, the same button meaning
        // two different things.
        //
        // Idempotent by construction: the run publishes its own end from whichever branch returns
        // first (see finishCancelled), so a second cancel — or the chat screen's own job.cancel()
        // right after this one — only repeats an already-made decision.
        job?.cancel()
        return true
    }

    /**
     * Queues a line for this run.
     *
     * @return whether the text was taken; false when this run is no longer live or the line is
     *   blank. A line the user says twice keeps both, because each one has its own [id]. A line
     *   that is only a picture is taken too — that is still something the user sent.
     */
    fun steer(id: String, text: String, immediate: Boolean, imagesBase64: List<String> = emptyList()): Boolean {
        if (!isLive()) return false
        val body = text.trim()
        if (body.isEmpty() && imagesBase64.isEmpty()) return false
        val line = Steer(id, body, imagesBase64)
        synchronized(steeringLock) {
            if (immediate) urgent[id] = line else steering[id] = line
        }
        // Queued first, cut second: the line has to be waiting before the round that will carry it
        // is thrown away, or the next drain could run in between and find nothing.
        if (immediate) cutRound()
        return true
    }

    /**
     * Takes a waiting line back, from either queue.
     *
     * @return true when it was still waiting; false once the model has read it, or when no line
     *   with that id was ever waiting here.
     */
    fun retract(id: String): Boolean = synchronized(steeringLock) {
        urgent.remove(id) != null || steering.remove(id) != null
    }

    /**
     * Moves one waiting line to the front, and abandons the round in flight so it is read now.
     *
     * Idempotent in the sense that matters: a line already at the front answers true without
     * cutting a second round. Pressing 「立即插话」 twice must not cost the user two abandoned
     * answers, which is what a promote that always cut would do.
     *
     * @return true when that line is now at the front — including when it already was;
     *   false when it has been read, or is not waiting here at all.
     */
    fun promote(id: String): Boolean {
        if (!isLive()) return false
        // Non-local returns on purpose: `synchronized` is inline, so both of these leave the
        // function with the monitor released.
        synchronized(steeringLock) {
            if (urgent.containsKey(id)) return true
            val line = steering.remove(id) ?: return false
            urgent[id] = line
        }
        cutRound()
        return true
    }

    /**
     * Asks for a compression at the next round boundary.
     *
     * @return true when the request was taken; false when this run is no longer live.
     */
    fun requestCompress(): Boolean {
        if (!isLive()) return false
        compressNow = true
        return true
    }

    /**
     * Abandons the round that is streaming, so the next one carries a line the user wants read
     * now.
     *
     * The same abort [cancel] uses, without the cancelled flag: this ends the round, not the run.
     * [steeringCut] is what tells the loop the resulting IOException is the cut it asked for
     * rather than a network failure, so the round is retried instead of failing the run.
     */
    private fun cutRound() {
        val call = inFlight
        if (call != null) {
            steeringCut.set(true)
            call.cancel()
        }
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
        // Only a run that is still streaming is repainted. The handle stays registered for a few
        // more instructions after it has published how it ended, and a tick landing in that window
        // would put Running back on top of Cancelled — a state nothing would ever clear again, so
        // the conversation would sit there locked with a stop button that has nothing to stop.
        if (isLive() && _state.value is RunState.Running) publish(RunState.Running(stats()))
    }

    /**
     * Runs one conversation on a job of its own and returns what it produced.
     *
     * The split matters: [job] is the run's, not the caller's, so [cancel] can stop a run that is
     * blocked inside a tool without cancelling the coroutine that asked for it. The loop itself is
     * [runLoop], which is where every end of the run is published.
     */
    internal suspend fun execute(
        history: List<StoredMessage>,
        checkpoints: List<ContextCheckpoint>,
        settings: AppSettings,
        registry: ToolRegistry,
        capabilities: CapabilityHub,
        skills: SkillStore,
        memory: MemoryStore,
        profile: UserProfileStore,
        gate: ApprovalGate,
        onEvent: (AgentEvent) -> Unit,
        onCheckpoint: (ContextCheckpoint) -> Unit,
    ): List<StoredMessage> {
        // The run gets a job of its own, a child of the caller's rather than the caller's own.
        // Stopping the run has to reach whatever it is blocked on — a shell command only notices
        // through its coroutine being cancelled — but it must not cancel the caller: the chat
        // screen's launch still has to run its finally and save the transcript, and whoever asked
        // for a run must not die with it.
        val parent = currentCoroutineContext()[Job]
        val runJob = Job(parent)
        job = runJob
        caller = parent
        // Kept so [cancel] can reach this run's approval prompt; the gate itself is shared, and
        // every call into it carries this run's chatId, so one run's stop cannot answer another's.
        this.gate = gate
        try {
            return withContext(runJob) {
                runLoop(
                    history = history,
                    checkpoints = checkpoints,
                    settings = settings,
                    registry = registry,
                    capabilities = capabilities,
                    skills = skills,
                    memory = memory,
                    profile = profile,
                    gate = gate,
                    onEvent = onEvent,
                    onCheckpoint = onCheckpoint,
                )
            }
        } catch (cancellation: CancellationException) {
            // Reachable when the stop lands between registering the run and the loop's first
            // instruction: the loop never ran, so nothing has published how this run ended yet,
            // and the screen would keep waiting for an answer that is never coming.
            if (cancelled.get() && parent?.isCancelled != true) {
                finishCancelled(onEvent, StringBuilder())
                return emptyList()
            }
            throw cancellation
        } finally {
            // A child that never completes keeps its parent in "completing" forever, which would
            // hang an await() on the caller. Completing it is what lets the caller finish.
            runJob.complete()
        }
    }

    /**
     * The run itself, on the run's own job ([job]).
     *
     * Everything it counts or flags is per-handle and starts empty, because the handle was
     * made for this run and is dropped with it.
     */
    private suspend fun runLoop(
        history: List<StoredMessage>,
        checkpoints: List<ContextCheckpoint>,
        settings: AppSettings,
        registry: ToolRegistry,
        capabilities: CapabilityHub,
        skills: SkillStore,
        memory: MemoryStore,
        profile: UserProfileStore,
        gate: ApprovalGate,
        onEvent: (AgentEvent) -> Unit,
        onCheckpoint: (ContextCheckpoint) -> Unit,
    ): List<StoredMessage> {
        // Published here rather than left to the ticker, so a caller sees Running the
        // moment the run starts even if no ticker was ever started.
        publish(RunState.Running(stats()))

        val produced = mutableListOf<StoredMessage>()
        val wire = mutableListOf<ChatMessage>()
        // Which stored message each stretch of the wire came from. The wire has no ids of its own,
        // and a compression has to be able to name the messages it folded away — a checkpoint that
        // cannot say what it covered could never be resumed from by the next run.
        val spans = mutableListOf<WireSpan>()
        // Built once and measured once: the system prompt carries the skills catalogue and
        // the memory entries, so it is both the largest fixed part of the prompt and the one
        // the user can do least about.
        //
        // The catalogue follows the same switch the tools do. A persona can hide `skill_read`,
        // and ChatViewModel passes a registry with those names already filtered out; a
        // catalogue whose entries cannot be read is worse than none at all — it advertises
        // skills, spends context on them, and leaves the model guessing at procedures it is
        // not allowed to look up.
        //
        // Asked through all() rather than find(): "skill_read is not in the visible set" is the
        // question, and it is the same list the prompt is about to summarise.
        val readable = registry.all().any { it.spec.name == "skill_read" }
        val skillCatalog = if (readable) skills.catalog(skills.list()) else ""
        val systemText = systemPrompt(
            settings,
            registry,
            skillCatalog,
            memory.entries.value,
            profile.text.value,
        )
        breakSystem = systemText.length / 4
        wire += ChatMessage.system(systemText)

        // A checkpoint stands in for everything up to its last message. Replaying the whole history
        // and summarising it again would pay for the same summary on every following turn, and the
        // prefix would never match the previous request — which is exactly what the endpoint's
        // prompt cache keys on.
        val window = settings.contextWindowFor(settings.model)
        val checkpoint = checkpoints.lastOrNull()
        val resumeAt = checkpoint?.let { saved -> history.indexOfFirst { it.id == saved.toMessageId } }
        val replayed = if (checkpoint != null && resumeAt != null && resumeAt >= 0) {
            wire += ChatMessage.system("[上下文摘要] ${checkpoint.summary}")
            sanitize(trim(history.drop(resumeAt + 1), window))
        } else {
            // No checkpoint, or one whose message is no longer in the transcript: the transcript is
            // what decides the prompt, so a checkpoint that no longer lines up is ignored rather
            // than half-applied.
            sanitize(trim(history, window))
        }
        // The messages themselves are never removed from the conversation — the usage watermark
        // counts them — which is why the span is found by id and the replay is a slice, not a rewrite.
        replayed.forEach { message ->
            val from = wire.size
            appendToWire(wire, message, settings)
            spans += WireSpan(message.id, from, wire.size)
        }

        // A ceiling of 0 (or less) means the user set none: the loop then ends only when
        // the model stops asking for tools, which is the only limit a long task should
        // have. A positive value is a real ceiling, and the failure below says so.
        val roundLimit = settings.maxToolRounds
        var round = 0
        // Hoisted out of the round on purpose: a stop can land anywhere, including in the outer
        // catch, and what already arrived is then the run's only output. A round-local
        // StringBuilder died with its round, which is how a stopped run showed the user a
        // paragraph that afterwards existed nowhere — not on screen, not on disk.
        var streamed = StringBuilder()
        // The same argument as [streamed], for the thinking: a stopped run kept its paragraph but
        // lost the chain that produced it, so the card came back with an empty reasoning block.
        var reasoning = StringBuilder()
        // Tool calls the model asked for that no result has come back for yet. A stop between the
        // two leaves the call in the transcript with nothing answering it, which drew as an empty
        // tool card — indistinguishable from a tool that returned nothing at all.
        var openCalls: List<StoredToolCall> = emptyList()
        // Retries spent on the round that is currently in flight, so a failure that used them all
        // can say so. Reset per round: the number is about the attempt that finally failed, not
        // about how unlucky the whole run was.
        var retriesThisRound = 0
        // 最近一次重试的原因（「服务器限流（429）」「网络中断」…），与 [retriesThisRound] 同生共死：
        // 这一轮最终成功落盘时，两者一起写成消息上的 [StoredMessage.traceNote]，画成过程块。
        var lastRetryReason = ""
        // 这一轮「已经收到内容、只是流被切断」时的一句话；和重试一起合成 [StoredMessage.traceNote]。
        // null＝没被切断。每轮开头清空，理由同 [retriesThisRound]。
        var cutNote: String? = null

        /**
         * 把一条工具结果**回写**进 run 级状态：transcript、wire、spans、openCalls、live 行与事件。
         *
         * 局部函数（不是成员）：它要写的是 [runLoop] 的局部变量（wire/spans/produced/openCalls），
         * 成员函数够不着。**只在主协程里逐条调用、按模型请求的顺序** —— 端点对 tool 消息顺序敏感，
         * 并行执行的结果也必须排着队回写。执行阶段（[executeOneTool]）不碰这些，所以能并发。
         */
        suspend fun commitTool(outcome: ToolOutcome) {
            val call = outcome.call
            val result = outcome.result
            // 超长输出**不能无声截断**：完整内容落盘、路径写进结果，模型才知道自己没看全、
            // 也才知道去哪取（见 spillToolOutput）。落盘失败时同样会写明"截断了且没存下来"。
            // 切 IO：这一行会 mkdirs + writeText + 列目录清理，原来是跑在 runLoop 的
            // 调用线程上（`viewModelScope` = Main.immediate）⇒ 大输出（>8000 字）时主线程卡一下。
            val spill = withContext(Dispatchers.IO) { spillToolOutput(
                dir = File(Core.appContext.filesDir, "toolout"),
                tool = call.name,
                output = result.output,
                maxKept = MAX_TOOL_OUTPUT,
            ) }
            val output = spill?.kept ?: result.output
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
            // Answered, so a stop from here on must not answer it a second time.
            openCalls = openCalls.filterNot { it.id == call.id }
            val toolAt = wire.size
            wire += ChatMessage.tool(call.id, output)
            spans += WireSpan(stored.id, toolAt, wire.size)
            result.imageBase64?.let { base64 ->
                if (settings.imageInput) {
                    wire += ChatMessage.screenshot("（这是上一步 $call.name 返回的截图）", listOf(base64))
                } else {
                    wire += ChatMessage.user(
                        "（上一步 $call.name 返回了一张截图，但当前模型未开启图片输入，你看不到图像内容。）",
                    )
                }
            }
            liveLine = if (result.ok) {
                String.format(Locale.US, "✓ %s %.1fs", call.name, outcome.toolMs / 1000.0)
            } else {
                "✗ ${call.name}"
            }
            onEvent(AgentEvent.ToolFinished(call.name, result.ok, output))
        }

        try {
            while (roundLimit <= 0 || round < roundLimit) {
                round++
                // Emptied at the top of the round rather than next to the call: by the time the
                // loop comes back here the previous round's text is already an assistant message,
                // and a stop anywhere in this round — even one thrown out of the compression
                // below — must not hand the same paragraph to [finishCancelled] a second time.
                streamed = StringBuilder()
                reasoning = StringBuilder()
                openCalls = emptyList()
                // Steering lands here, on the boundary between two model turns: the
                // model has finished the previous turn and has not been asked for the
                // next one, which is the only moment the wire can be edited safely.
                drainSteering(wire, onEvent, settings)
                // A manual 「压缩」 is spent here, on the boundary: the user asked for the oldest
                // context to be folded away, not for the answer in flight to be cut short.
                val asked = compressNow
                compressNow = false
                compressContext(
                    wire, spans, settings, onCheckpoint, asked,
                    // 摘要要再发一次模型请求，这几秒里状态行必须说"在压缩"，否则看起来像卡死
                    // （用户：「run 的时候状态行也跟上」）。false 时清掉，下一句会覆盖它。
                    onBusy = { onEvent(AgentEvent.Compressing(it)) },
                )
                // Cleared with the round: whatever the last one ended with is over, and a
                // line that outlives its step reads as a stall.
                liveLine = ""
                retriesThisRound = 0
                lastRetryReason = ""
                cutNote = null
                if (cancelled.get()) {
                    finishCancelled(onEvent, streamed, reasoning, openCalls)
                    return produced
                }

                // Measured here, where the wire for this round is final: steering has landed
                // and compression has already run, so the numbers match what is about to be
                // sent rather than what the round started with.
                // 接线点 A（HOST-DEV §5）：内置工具之后**追加启用插件的工具**（`<id>.<tool>`）。
                // 重名的插件工具在 PluginRuntime.toolSpecs 里被跳过并记日志 —— 内置优先，绝不覆盖。
                // 实时读盘：用户刚启用的插件，下一个请求就该出现在工具表里。
                val builtinSpecs = registry.all().map { it.spec } + COMPRESS_CONTEXT_SPEC
                val pluginSpecs = PluginRuntime.enabledPlugins()
                val toolSpecs = builtinSpecs +
                    PluginRuntime.toolSpecs(pluginSpecs, builtinSpecs.map { it.name }.toSet())
                // 接线点 B（HOST-DEV §5）：这一轮的 hooks 快照，与工具表同一次读盘。分发处每条
                // 调用都过一遍 [PluginRuntime.preExecute] —— 每轮读一次目录是刻意的（热插拔），
                // 每条调用查一次 hook 是纯函数、不碰磁盘。
                val hooks = PluginRuntime.hooksFor(pluginSpecs)
                // **工具集没变就不重算**：这一行会把每个工具的 parameters（JsonObject）
                // 完整序列化成字符串、只为取长度 —— 二十来个工具、每轮一次，纯浪费。
                // 但工具集**是会变的**（插件热插拔），所以用「工具名序列」当缓存键：
                // 名字集合相同 ⇒ 表没变 ⇒ 沿用上次的计数；变了就重算。
                val toolSig = toolSpecs.joinToString(",") { it.name }
                if (breakToolsSig != toolSig) {
                    breakToolsSig = toolSig
                    breakTools = toolSpecs.sumOf {
                        it.name.length + it.description.length + it.parameters.toString().length
                    } / 4
                }
                // 三个数字都来自同一份 wire：一次遍历算完，别为每个数字各扫一遍
                // （大会话每轮要扫几十万字符，而这三处原本是三次独立的全量遍历）。
                var historyChars = 0
                var totalChars = 0
                var imageCarriers = 0
                for (message in wire) {
                    val chars = messageChars(message)
                    totalChars += chars
                    if (message.role != ROLE_SYSTEM) historyChars += chars
                    if (message.allImages.isNotEmpty()) imageCarriers++
                }
                breakHistory = historyChars / 4
                // 显示层的「当前请求多大」：wire 在这一刻定型，估算＝端点真实值 + 增量。
                currentPromptEstimate = estimatePromptTokens(totalChars, imageCarriers)
                val turnStartedAt = System.currentTimeMillis()
                // When the first piece of anything arrived: the wait a user actually feels,
                // and one the endpoint's own usage block never reports.
                var firstChunkAt = 0L
                // Cleared here rather than after the call: an abort from `steer` can only
                // belong to the round it interrupted, so nothing else may inherit the flag.
                steeringCut.set(false)
                // The console shows the tail of what is being written; the finished text only
                // exists once the turn ends, which is far too late for a "still working" line.
                val turn = try {
                    llm.complete(
                        settings = settings,
                        // 出站前按预算降级更早的**工具截图**（见 pruneRequestImages）：live 与
                        // rebuild 都经过这一个函数，所以两条路径发出去的 prompt 仍然一致。
                        messages = pruneRequestImages(wire, MAX_REQUEST_IMAGE_CHARS),
                        tools = toolSpecs,
                        onDelta = { piece ->
                            if (firstChunkAt == 0L) firstChunkAt = System.currentTimeMillis()
                            if (curTurnFirstChunkMs == 0L) curTurnFirstChunkMs = System.currentTimeMillis()
                            outChars.addAndGet(piece.length)
                            streamed.append(piece)
                            liveLine = streamed.takeLast(LIVE_LINE_CHARS).toString()
                            onEvent(AgentEvent.TextDelta(piece))
                        },
                        onReasoning = { piece ->
                            if (firstChunkAt == 0L) firstChunkAt = System.currentTimeMillis()
                            if (curTurnFirstChunkMs == 0L) curTurnFirstChunkMs = System.currentTimeMillis()
                            thinkingChars.addAndGet(piece.length)
                            reasoning.append(piece)
                            liveLine = "思考中…"
                            onEvent(AgentEvent.ReasoningDelta(piece))
                        },
                        onCall = { inFlight = it },
                        // Visible rather than silent: a retry means the user is waiting longer for
                        // nothing they can see, and a console line is the difference between
                        // "it is thinking" and "it is stuck".
                        onRetry = { notice ->
                            retriesThisRound = notice.attempt
                            lastRetryReason = notice.reason
                            liveLine = "第 ${notice.attempt} 次重试（${notice.delayMs / 1000}s 后）· ${notice.reason}"
                            // And out to the screen: the console's live line is not where the user
                            // is looking. Without this, a retrying run looks like one that simply
                            // stopped — which is exactly how 「发出去几秒之后就停止」 was reported.
                            onEvent(
                                AgentEvent.Retrying(
                                    attempt = notice.attempt,
                                    maxAttempts = notice.maxAttempts,
                                    delayMs = notice.delayMs,
                                    reason = notice.reason,
                                    code = notice.code,
                                ),
                            )
                        },
                        // 收到内容之后流断了、而且还能重试：这一轮作废重来。先把这一轮已经
                        // 流出去的字清干净（缓冲 + 屏幕），再让客户端重发 —— 否则同一段开头会
                        // 在气泡里出现两遍。屏幕那一半靠 RoundRestarted，VM 把本段 live parts 撤掉。
                        onRestart = {
                            streamed = StringBuilder()
                            reasoning = StringBuilder()
                            openCalls = emptyList()
                            onEvent(AgentEvent.RoundRestarted)
                        },
                    )
                } catch (error: IOException) {
                    // Cancelling a call surfaces as an IOException on the reading
                    // thread. It is a stop, not a failure, so it is reported as one.
                    if (cancelled.get()) {
                            finishCancelled(onEvent, streamed, reasoning, openCalls)
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
                // The stop can land in the gap between clearing `inFlight` above and the next
                // round's `onCall`: in that window there is no call to cancel yet — the payload is
                // still being serialised — so nothing reads the flag, and this round would stream
                // to the end and report the whole run as Done.
                if (cancelled.get()) {
                    finishCancelled(onEvent, streamed, reasoning, openCalls)
                    return produced
                }
                val elapsed = System.currentTimeMillis() - turnStartedAt
                val firstTokenMs = if (firstChunkAt > 0L) firstChunkAt - turnStartedAt else 0L
                // 本轮结束：把它的 decode 段（首字之后→结束）累加进 run 级和，再清零本轮标记，
                // 这样轮间的工具执行期不会被 [stats] 当成 decode 时间。官方多轮聚合＝Σdecode/Σtokens。
                decodeMsAccum += Tps.decodeMs(elapsed, firstTokenMs)
                curTurnFirstChunkMs = 0L
                val usage = turn.usage
                // 用这一轮端点报的**真实** completionTokens 校准 live 估算的每字符 token 数：
                // 本轮字符增量 = 当前累计 − 上次校准时的累计。字符够多、比率落在合理区间才采纳，
                // 否则保留旧值 —— 防一轮畸形数据（纯工具调用轮字符≈0，或极短轮次）把估算带飞。
                // 这修的是「明明 200 tok/s、手机上只有几十」：live 分子此前用 chars/3（英文系数），
                // 对中文低估 ~2.7 倍。校准后从第二轮起 live 读数就贴着真实值。
                usage?.completionTokens?.takeIf { it > 0 }?.let { realTokens ->
                    val totalChars = outChars.get() + thinkingChars.get()
                    val turnChars = totalChars - calibCharsBaseline
                    if (turnChars >= CALIB_MIN_CHARS) {
                        val ratio = (realTokens.toFloat() / turnChars)
                            .coerceIn(MIN_LIVE_TOKENS_PER_CHAR, MAX_LIVE_TOKENS_PER_CHAR)
                        liveTokensPerChar = ratio
                        calibCharsBaseline = totalChars
                    }
                }
                // Kept for the context readout and for pricing the next round's growth:
                // only the endpoint knows how big the prompt really was.
                usage?.promptTokens?.takeIf { it > 0 }?.let { reported ->
                    lastPromptTokens = reported
                    lastPromptChars = wireChars(wire)
                    lastPromptImages = wire.count { it.allImages.isNotEmpty() }
                }
                val thinking = turn.reasoning.takeIf { it.isNotBlank() }
                // A truncated turn can end mid-tool-call, so say so instead of silently
                // presenting half an answer as a finished one.
                if (turn.finishReason == "length") {
                    // **不归因**，只报事实。用户明确说过：他没设上限、上游上下文是 131K，所以
                    // 「达到长度上限」这种说法在他那里是错的 —— 我们并不知道上游的输出上限是多少，
                    // 也就没有资格说"是它撞了上限"。这里只把可观察的事实写清楚：网关给的
                    // finish_reason 原文、以及它自己报的 completion_tokens；有设置上限时才顺带提一句
                    // （是陈述，不是归责）。
                    val produced = turn.usage?.completionTokens?.takeIf { it > 0 }
                    val ours = settings.maxTokens
                    onEvent(
                        AgentEvent.Failed(
                            "上游结束了这一轮（finish_reason=${turn.finishReason}" +
                                (produced?.let { "，它报的 completion_tokens=$it" } ?: "") +
                                (if (ours > 0) "；本轮设置的最大输出是 $ours" else "") +
                                "）。若你并没有设置输出上限，那这是上游模型自己的结束条件" +
                                "（与 131K 那样的上下文窗口是两件事，我们看不到它的输出上限）：" +
                                "把要求拆小一点再试；若是用 write_file 写大文件，让它分几次写" +
                                "（先写头部，再用 `append: true` 追加）。",
                        ),
                    )
                }
                // The stream broke after delivering content (see FINISH_CUT): the round is a
                // success — the answer is real and is kept — but the user should know it stopped
                // early rather than wonder why the reply ends mid-sentence.
                // **用户自己按停不算错误**（用户：「不是我自己中断也算错误吗」）：停止同样把流
                // 切断成 FINISH_CUT，不加这道判定就会给"用户刚按下的按钮"发一张错误回执；每停一次
                // 一张，连停五次就是截图里那五条一模一样的红卡。
                if (turn.finishReason == FINISH_CUT && !cancelled.get()) {
                    // 流在已经收到内容之后被切断：这一轮**是成功的**（回答是真的、也保留），只是没说完。
                    // 以前这里发 `Failed` —— 那会落一张红色回执卡，等于告诉用户"这轮失败了"；用户明确
                    // 要求「整轮对话是成功的话 就不要有 错误提示卡片存在」。现在它和重试一样，变成
                    // 消息上的过程留痕（[StoredMessage.traceNote]），画成过程块。
                    cutNote = "连接中断，这一轮的回答可能不完整"
                }
                if (turn.toolCalls.isEmpty()) {
                    val assistant = StoredMessage(
                        id = UUID.randomUUID().toString(),
                        role = Roles.ASSISTANT,
                        text = turn.text,
                        reasoning = thinking,
                        traceNote = traceNoteOf(retriesThisRound, lastRetryReason, cutNote),
                    ).withUsage(usage, elapsed, settings.model, firstTokenMs)
                    produced += assistant
                    onEvent(AgentEvent.AssistantMessage(assistant))
                    // The answer goes into the wire either way: the finish path below never asks
                    // the model anything again, and the steering path needs it *in front of* the
                    // line the user just said — a steer that read as if it had come before the
                    // answer it interrupted would be answered as the earlier turn.
                    val answerAt = wire.size
                    wire += ChatMessage.assistant(turn.text)
                    spans += WireSpan(assistant.id, answerAt, wire.size)
                    // A line the user slipped in while this round was streaming: this round is not
                    // the last one, so the run takes one more pass to read it. Finishing here
                    // instead would leave a steer bubble on screen that the model never saw, with
                    // nothing telling the user it went nowhere.
                    if (drainSteering(wire, onEvent, settings) > 0) continue
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
                    traceNote = traceNoteOf(retriesThisRound, lastRetryReason, cutNote),
                ).withUsage(usage, elapsed, settings.model, firstTokenMs)
                produced += assistant
                onEvent(AgentEvent.AssistantMessage(assistant))
                val assistantAt = wire.size
                wire += ChatMessage.assistant(turn.text, turn.toolCalls)
                spans += WireSpan(assistant.id, assistantAt, wire.size)
                // This round's text is a message now, so a stop from here on (before a tool call,
                // or while one is running) must not hand the same paragraph to [finishCancelled]
                // again: it would land in the transcript as a second, identical bubble. The
                // reasoning goes with it — it is already on this message — while the calls it asked
                // for are now the ones a stop would leave unanswered.
                streamed = StringBuilder()
                reasoning = StringBuilder()
                openCalls = assistant.toolCalls

                // 并行度（对话配置「工具并行数」）：1＝串行，就是今天的行为，语义一字不改；
                // N>1 时一轮内最多 N 条工具**同时**执行，但结果仍按模型请求的顺序回写 wire ——
                // 端点对 tool 消息的顺序敏感，乱序会让下一轮读到错配的结果。
                // **只在 AUTO 档生效**：ASK 档的授权弹窗每个会话只有一个待决位（ApprovalGate 的
                // waiters 按 chatId 单槽），两条调用同时求授权会互相覆盖、死等；ASK 强制串行。
                val parallel = if (settings.approvalMode == ApprovalMode.AUTO) {
                    settings.toolParallel.coerceAtLeast(1)
                } else {
                    1
                }
                if (parallel <= 1) {
                    for (call in turn.toolCalls) {
                        // Checked before every call, not after the batch: a stop means the
                        // tools the model asked for in the same breath do not run either.
                        if (cancelled.get()) {
                            finishCancelled(onEvent, streamed, reasoning, openCalls)
                            return produced
                        }
                        val outcome = executeOneTool(
                            call, registry, pluginSpecs, hooks, capabilities, gate, settings, chatId, onEvent,
                        ) ?: run {
                            finishCancelled(onEvent, streamed, reasoning, openCalls)
                            return produced
                        }
                        commitTool(outcome)
                    }
                } else {
                    // 分块并发：一块最多 parallel 条同时跑（async 全部起飞），块内按原顺序 await 并
                    // 回写，再放下一块。块与块之间是一道屏障 —— 换来的是「顺序回写」这条硬约束的
                    // 简单实现，且取消检查落在每块开头与每条执行的开头。coroutineScope 保证块里的
                    // async 都收干净才往下走（含异常路径）。
                    for (chunk in turn.toolCalls.chunked(parallel)) {
                        if (cancelled.get()) {
                            finishCancelled(onEvent, streamed, reasoning, openCalls)
                            return produced
                        }
                        val outcomes = coroutineScope {
                            val deferreds = chunk.map { call ->
                                async {
                                    executeOneTool(
                                        call, registry, pluginSpecs, hooks, capabilities, gate, settings, chatId, onEvent,
                                    )
                                }
                            }
                            deferreds.map { it.await() }
                        }
                        for (outcome in outcomes) {
                            if (outcome == null) {
                                // 这条在执行开头看到取消：收尾，同块其余的 await 已返回或已取消。
                                finishCancelled(onEvent, streamed, reasoning, openCalls)
                                return produced
                            }
                            commitTool(outcome)
                        }
                    }
                }
            }
            // Only reachable with a ceiling: unlimited rounds end by returning above.
            onEvent(AgentEvent.Failed("已达到最大工具轮数（$roundLimit），任务未完成。"))
            publish(RunState.Failed("已达到最大工具轮数（$roundLimit），任务未完成。"))
            return produced
        } catch (cancellation: CancellationException) {
            inFlight?.cancel()
            // Two different things land here. A stop the user asked for cancels this run's own
            // job while the caller is still alive, and that caller has every right to the messages
            // the run produced — throwing would hand it a cancellation it never asked for, and
            // would kill the finally of whoever merely asked for a run. A cancellation that came
            // from the caller's own scope is the other thing entirely and still travels as one.
            if (cancelled.get() && caller?.isCancelled != true) {
                finishCancelled(onEvent, streamed, reasoning, openCalls)
                return produced
            }
            // The caller's scope is gone: the stream still has to be taken down, the paragraph
            // that already arrived still has to be kept, and the state still has to say what
            // happened — by hand, because this branch rethrows.
            emitPartial(onEvent, streamed, reasoning)
            unansweredToolMessages(onEvent, openCalls)
            publish(RunState.Cancelled(stats()))
            throw cancellation
        } catch (error: Throwable) {
            // Cutting the wire makes OkHttp throw IOException("Canceled"), so a run the user
            // stopped lands here rather than in the CancellationException branch above. It is
            // the stop working, not a failure: publishing Failed would put an error card on
            // screen for a button the user just pressed, and the caller would be handed a
            // throwable it turns into one too.
            if (cancelled.get()) {
                finishCancelled(onEvent, streamed, reasoning, openCalls)
                return produced
            }
            val message = (error.message ?: error::class.java.simpleName) + retryNote(retriesThisRound)
            publish(RunState.Failed(message))
            // The chat screen shows the *thrown* error's message, so the note has to travel with it
            // too — otherwise the number only ever appears in the console. Wrapped rather than
            // rebuilt: the original rides along as the cause, so nothing about it is lost.
            throw if (retriesThisRound > 0) LlmException(message, error) else error
        } finally {
            inFlight = null
            // Nothing is published here. The run has already said how it ended, and that
            // answer is what the conversation's state flow keeps for whoever still reads it.
        }
    }

    /**
     * 「（已重试 N 次）」 for a failure that used up its retries, empty otherwise.
     *
     * A user who waited through two retries is owed the number: without it the same error reads
     * like the first attempt failed, and the retry setting looks like it did nothing.
     */
    private fun retryNote(retries: Int): String = if (retries > 0) "（已重试 $retries 次）" else ""

    /**
     * 这一轮**成功**、但中途重试过时，留在消息上的那条过程记录。
     *
     * 与 [retryNote] 的分工：那条是失败回执的一部分（「已重试 2 次」说明为什么这轮这么久），
     * 这条是**成功**的过程块（用户：「重试成功要在过程态中留痕……整轮成功就不要有错误提示卡片」）。
     * 没有重试就返回 null —— 不留空块。
     */
    private fun retryTraceNote(retries: Int, reason: String): String? = when {
        retries <= 0 -> null
        reason.isBlank() -> "自动重试 $retries 次后成功"
        else -> "自动重试 $retries 次 · $reason"
    }

    /**
     * 一轮里可能同时发生两件事：先重试，最后又被上游切断。块只有一行，两件事都得说，
     * 所以按发生顺序用「；」连起来；都没有就是 null（不留空块）。
     */
    private fun traceNoteOf(retries: Int, reason: String, cut: String?): String? =
        listOfNotNull(retryTraceNote(retries, reason), cut)
            .takeIf { it.isNotEmpty() }
            ?.joinToString("；")

    /**
     * Publishes the end of a stopped run.
     *
     * It does not throw: the transcript produced so far is still real, the user can
     * see it, and discarding it because the run was stopped halfway would lose the
     * work that was actually done.
     *
     * [partial] is what the round had already streamed when the stop landed. It goes out as a
     * real assistant message so the paragraph the user watched arrive is still there afterwards —
     * on screen and on disk. It is deliberately left out of the run's `produced` list: the only
     * reader of that list is the read-aloud path, and reading a stopped half-sentence out loud is
     * not what the stop button means.
     */
    private fun finishCancelled(
        onEvent: (AgentEvent) -> Unit,
        partial: CharSequence,
        reasoning: CharSequence = "",
        unanswered: List<StoredToolCall> = emptyList(),
    ) {
        emitPartial(onEvent, partial, reasoning)
        // A call the model asked for and the stop arrived before: the transcript keeps the request,
        // so something has to answer it. Without a result the card drew empty, which reads as a tool
        // that returned nothing rather than as one that never ran — and the next run's replay would
        // drop the whole assistant turn for being unanswered.
        unansweredToolMessages(onEvent, unanswered)
        onEvent(AgentEvent.Finished(""))
        publish(RunState.Cancelled(stats()))
    }

    /**
     * Answers, in the transcript, the tool calls a stop left hanging.
     *
     * The output says what happened rather than pretending the tool ran: a card that says 「已中断」
     * is the truth, and it is also what keeps the assistant turn in the next run's replay —
     * [sanitize] drops an assistant message whose `tool_calls` nobody answered, so silence here cost
     * the model the whole turn.
     */
    private fun unansweredToolMessages(onEvent: (AgentEvent) -> Unit, calls: List<StoredToolCall>) {
        calls.forEach { call ->
            onEvent(
                AgentEvent.ToolMessage(
                    StoredMessage(
                        id = UUID.randomUUID().toString(),
                        role = Roles.TOOL,
                        toolName = call.name,
                        toolArgs = call.arguments,
                        toolOutput = INTERRUPTED_TOOL_OUTPUT,
                        toolOk = false,
                        toolCallId = call.id,
                    ),
                ),
            )
        }
    }

    /**
     * Hands the text that had already streamed to the transcript as a real assistant message.
     *
     * Empty means the round's text is already a message of its own (the tool-call branch clears
     * the round's buffer the moment it emits one), so nothing is sent — otherwise the user would
     * see the same paragraph twice.
     *
     * [reasoning] travels with it for the same reason [partial] does: a stop used to keep the answer
     * and throw the thinking away, so the card came back with an empty chain-of-thought block.
     */
    private fun emitPartial(onEvent: (AgentEvent) -> Unit, partial: CharSequence, reasoning: CharSequence) {
        if (partial.isBlank() && reasoning.isBlank()) return
        onEvent(
            AgentEvent.AssistantMessage(
                StoredMessage(
                    id = UUID.randomUUID().toString(),
                    role = Roles.ASSISTANT,
                    text = partial.toString(),
                    reasoning = reasoning.toString().takeIf { it.isNotBlank() },
                ),
            ),
        )
    }

    /**
     * Injects whatever the user typed while the model was working.
     *
     * The text goes in as a user message in the same run — not a new one — because
     * that is exactly what it is: something said in the middle of a task.
     *
     * Each line is announced as it lands ([AgentEvent.Steered]) and in the same order it was
     * written to the wire, so the transcript the user reads is the one the model was given.
     *
     * Returns how many lines landed, so the caller can tell "nothing was said" from "the
     * user just spoke, this round is not the last one".
     */
    private fun drainSteering(
        wire: MutableList<ChatMessage>,
        onEvent: (AgentEvent) -> Unit,
        // The run's own snapshot, passed in rather than read off the class: the constructor
        // parameter is not a property, and `execute`'s own `settings` is the one the round is
        // actually running with.
        settings: AppSettings,
    ): Int {
        // Pushed-to-the-front lines go first, and go alone: the rest were queued, and queued means
        // the boundary after this one. Draining everything here is what would turn one person's
        // urgency into every waiting line landing at once.
        val batch = synchronized(steeringLock) {
            if (urgent.isEmpty()) {
                steering.values.toList().also { steering.clear() }
            } else {
                urgent.values.toList().also { urgent.clear() }
            }
        }
        batch.forEach { line ->
            // The same construction an ordinary user message gets, picture included: a steer with
            // an attachment is still a message the user sent, and the gate is the same one
            // (`imageInput`), with the same sentence when the model cannot be shown the image.
            // 多附件：整列过 imageInput 门；关着时一句说明，模型知道有图但看不到。
            val images = line.imagesBase64.takeIf { settings.imageInput }.orEmpty()
            val note = if (line.imagesBase64.isNotEmpty() && images.isEmpty()) {
                "（图片已省略：当前未开启「模型支持图片输入」）"
            } else {
                ""
            }
            wire += ChatMessage.user("（用户在任务进行中说：${line.text}）$note", images)
            // Announced after the wire, never before: a caller told about a line and then shown a
            // prompt without it would have written a bubble the model never saw.
            onEvent(AgentEvent.Steered(line.id, line.text, line.imagesBase64))
        }
        return batch.size
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
     *
     * The span it folded away is reported through [onCheckpoint] so it can be stored on the
     * conversation: a summary that lives only in this run's wire is paid for again on every
     * following turn. [asked] is a request from the menu, which skips the threshold and the
     * automatic switch but not the round boundary — a menu tap must never cut the answer in flight.
     *
     * The wire this leaves behind has to be exactly the wire a later run rebuilds from the
     * checkpoint — see [appendToWire] for the rule that keeps the two in step. A prefix that differs
     * by one message is a prompt cache miss for everything after it, and the model would be reading
     * a transcript the user never saw.
     */
    private suspend fun compressContext(
        wire: MutableList<ChatMessage>,
        spans: MutableList<WireSpan>,
        settings: AppSettings,
        onCheckpoint: (ContextCheckpoint) -> Unit,
        asked: Boolean,
        /**
         * 摘要生成期间的忙碌通知：true＝开始、false＝结束。
         *
         * 状态行据此显示「正在压缩上下文」—— 压缩要再发一次模型请求，几秒钟里什么都不写，
         * 看起来和卡死一样（用户：「run 的时候状态行也跟上」）。
         */
        onBusy: (Boolean) -> Unit = {},
    ) {
        val window = settings.contextWindowFor(settings.model)
        // A request from the menu needs neither a ceiling nor the automatic switch: the user asked
        // for the oldest context to be folded away, and the threshold is only what decides when
        // that happens on its own.
        if (!asked && (window <= 0 || !settings.autoCompress)) return
        if (wire.size <= MIN_MESSAGES) return
        // Priced before anything is replaced: what the checkpoint reports is how big the wire was
        // when this compression was decided on, not what it shrank to afterwards.
        val before = estimatePromptTokens(wire)
        // The last figure the endpoint itself reported, kept for the note below: when a compression
        // looks wrong, the first question is whether the estimate or the conversation was at fault,
        // and the two numbers side by side answer it without a debugger.
        val realBefore = lastPromptTokens
        // Threshold from the settings snapshot this run was started with, so a change in
        // 设置 applies from the next run on rather than mid-run.
        if (!asked && before <= window * settings.compressAtFraction) return

        // The cut never splits an assistant's tool_calls from their results: a lone
        // tool message is a hard 400 on every OpenAI-compatible endpoint.
        var cut = wire.size - KEEP_RECENT
        while (cut > 1 && wire[cut].role == ROLE_TOOL) cut--
        if (cut <= 1) return

        val older = wire.subList(1, cut).filter { it.role != ROLE_SYSTEM }
        if (older.isEmpty()) return

        val summary = try {
            onBusy(true)
            // The whole span, system notes included: the summary this one replaces has to be folded
            // into it. Otherwise the rebuild that resumes from the checkpoint — which can only carry
            // one summary — would drop the older one and its context with it.
            summarize(wire.subList(1, cut), settings)
        } catch (error: IOException) {
            // A stop during the summary is still a stop: let the caller's check see it
            // rather than reporting the abort as a failure of the whole run.
            if (cancelled.get()) return
            throw error
        } finally {
            inFlight = null
            onBusy(false)
        }
        if (summary.isBlank()) return

        // The span this summary stands for, named by the messages it covered. Only a span that
        // begins and ends on whole messages can be resumed from by a later run, so a message the
        // cut would split is left out of the record — it stays in the tail and gets replayed, which
        // is the safe direction to be wrong in.
        val covered = spans.filter { it.from >= 1 && it.to <= cut }

        // The span's own system messages are not re-inserted: the summary above has already absorbed
        // them, and leaving them out is what makes this wire byte-for-byte the one a later run
        // rebuilds from the checkpoint — a prefix that differs by even one message is a prompt cache
        // miss for everything after it.
        val tail = wire.subList(cut, wire.size).toList()
        // firstOrNull rather than first(): the system message is always at index 0 today, and this
        // function's whole job is to rewrite the wire — a crash here would take the run down over a
        // state that simply has nothing to compress.
        val head = wire.firstOrNull() ?: return
        wire.clear()
        wire += head
        wire += ChatMessage.system("[上下文摘要] $summary")
        wire += tail

        // The spans move with the wire: what was folded away is gone, and what is left now starts
        // after the summary. Without this the next compression would name the wrong messages.
        val shift = 2 - cut
        val kept = spans.filter { it.from >= cut }.map { it.copy(from = it.from + shift, to = it.to + shift) }
        spans.clear()
        spans += kept

        // Counted in transcript messages, not in wire entries: the card that shows this number also
        // shows how many messages the span covers, and the two have to be the same fact. A wire
        // entry that came from nowhere — the screenshot note, a steer line — would make them differ.
        val folded = if (covered.isEmpty()) older.size else covered.size

        // Handed up rather than kept here: the summary has to outlive this run, or the next one
        // starts from the full history and pays to summarise the same messages all over again.
        val first = covered.firstOrNull()
        val last = covered.lastOrNull()
        if (first != null && last != null) {
            onCheckpoint(
                ContextCheckpoint(
                    id = UUID.randomUUID().toString(),
                    at = System.currentTimeMillis(),
                    fromMessageId = first.id,
                    toMessageId = last.id,
                    summary = summary,
                    messages = folded,
                    promptTokens = before,
                ),
            )
        }

        // The next round's estimate has to be priced off the summary, not off the
        // messages it replaced, or the threshold would fire again immediately. With no anchor the
        // next estimate is a plain character count, which is why screenshots are priced per image
        // there rather than by their base64 length.
        lastPromptTokens = 0
        lastPromptChars = 0
        lastPromptImages = 0
        // The estimate and the last real figure travel together: 「设了更大的窗口还是压缩了」 is a
        // question about which of the two was wrong, and the note is the only place the user can see
        // either of them.
        val evidence = if (realBefore > 0) {
            "（估算 ${before / 1000}k / 上轮实际 ${realBefore / 1000}k）"
        } else {
            "（估算 ${before / 1000}k）"
        }
        contextNote = if (asked) {
            "已压缩上下文（手动）：$folded 条消息 → 摘要$evidence"
        } else {
            "已压缩上下文：$folded 条消息 → 摘要$evidence"
        }
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
        if (message.allImages.isNotEmpty()) append("\n  （含截图，已省略）")
    }

    /**
     * How big the next request will be, in the endpoint's own tokens where possible.
     *
     * The last reported prompt size is a fact; only what has been appended since is estimated, at
     * four characters per token. That ratio is right for ASCII and JSON and an *under*estimate for
     * Chinese (a Chinese character is nearer one token than a quarter of one), and an underestimate
     * only ever makes a compression late — never early, which is the failure that matters here.
     *
     * Screenshots are priced per image at [IMAGE_TOKENS], never by their base64 length. This is the
     * whole reason the estimate is trustworthy: a screenshot is a few hundred thousand base64
     * characters and about a thousand tokens, so pricing it as text let a single picture outweigh a
     * 1000k window — a 574k conversation would then compress itself with nothing actually wrong.
     *
     * Before the first usage arrives there is nothing to anchor to, so the whole wire is estimated
     * the pessimistic way.
     */
    private fun estimatePromptTokens(wire: List<ChatMessage>): Int =
        estimatePromptTokens(wireChars(wire), wire.count { it.allImages.isNotEmpty() })

    /**
     * 同一个估算，但字符数与图片数由调用方提供 —— 调用点已经遍历过一遍 wire 时用它，
     * 免得为两个数字再各扫一次整份历史。数值与 [estimatePromptTokens] 完全一致。
     */
    private fun estimatePromptTokens(chars: Int, images: Int): Int {
        val reported = lastPromptTokens
        if (reported <= 0) return chars / 3 + images * IMAGE_TOKENS
        val textDelta = (chars - lastPromptChars).coerceAtLeast(0) / 4
        val imagesDelta = (images - lastPromptImages).coerceAtLeast(0) * IMAGE_TOKENS
        return reported + textDelta + imagesDelta
    }

    private fun wireChars(wire: List<ChatMessage>): Int = wire.sumOf { messageChars(it) }

    /**
     * What one message costs in characters: its text and the arguments of any tool call it asks
     * for. Both travel to the endpoint, and leaving the arguments out would undercount the part
     * of a history that is made almost entirely of tool calls.
     *
     * A screenshot is deliberately *not* counted here, even though its base64 also travels: it is
     * priced per image by [estimatePromptTokens] instead. Counting it as characters made one
     * screenshot — hundreds of thousands of base64 characters, roughly a thousand real tokens —
     * look like a quarter of a million tokens of context.
     */
    private fun messageChars(message: ChatMessage): Int =
        message.text.length + message.toolCalls.sumOf { it.name.length + it.arguments.length }

    private fun stats(): StreamStats {
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        // live 输出 token 用**校准过的**每字符 token 数估（见 liveTokensPerChar），不是保守的
        // chars/3 —— 后者对中文低估 ~2.7 倍，是「200 tok/s 显示成几十」的根因。展示与 TPS 分子都用它。
        val outTokens = estimateOutputTokens(outChars.get())
        val thinkTokens = estimateOutputTokens(thinkingChars.get())
        // 官方口径分母＝decodeMs（首字之后→结束），不含 TTFT 等待、也不含轮间工具执行时间。
        // 已结束各轮的和在 [decodeMsAccum]；当前正在流式的这一轮再加上「本轮首字→现在」。
        val liveDecode = if (curTurnFirstChunkMs > 0L) {
            (System.currentTimeMillis() - curTurnFirstChunkMs).coerceAtLeast(0L)
        } else {
            0L
        }
        val decodeMs = decodeMsAccum + liveDecode
        // 分子＝**全部**输出 token（正文 + 思考）：官方 outputTokens 含 reasoning、不减它，而
        // decodeMs 的时间也含生成思考那段。分子只数正文会在长思考期系统性低估 TPS，故这里加上思考。
        val tpsTokens = outTokens + thinkTokens
        return StreamStats(
            tokensOut = outTokens,
            thinkingTokens = thinkTokens,
            elapsedMs = elapsedMs,
            tokPerSec = Tps.of(tpsTokens, decodeMs).toFloat(),
            lastPromptTokens = lastPromptTokens,
            currentPrompt = currentPromptEstimate,
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
        val assistants = messages.filterNot { message ->
            message.role == Roles.ASSISTANT && message.toolCalls.any { it.id !in answered }
        }
        // The mirror image, and the same 400 from the other side: a tool result whose assistant
        // call has just been dropped — or one that arrived with no call at all — is a `tool`
        // message with nothing that asked for it. Dropping only the assistant would trade one
        // rejected request for another. A half-answered parallel round is how it happens: two
        // calls, one result, then a stop or a kill before the second one lands.
        val asked = assistants
            .filter { it.role == Roles.ASSISTANT }
            .flatMap { it.toolCalls }
            .map { it.id }
            .toSet()
        return assistants.filter { message ->
            if (message.role != Roles.TOOL) return@filter true
            val id = message.toolCallId ?: return@filter false
            id in asked
        }
    }

    /**
     * Writes one stored message onto the wire.
     *
     * This is the *rebuild* path, and it has to produce exactly what the live path produced for the
     * same message: a rebuilt prompt that differs by so much as a wrapper sentence is not the request
     * the model answered, and the prompt cache keys on that prefix. **Any text the live path wraps
     * around a message — the steer marker, the screenshot notes — must be mirrored here**, and any
     * new one added live must be added here in the same commit.
     */
    private fun appendToWire(
        wire: MutableList<ChatMessage>,
        message: StoredMessage,
        settings: AppSettings,
    ) {
        // Images only go out when the configured model can read them; a text-only
        // endpoint rejects the whole request otherwise.
        val imageList = message.allImages.takeIf { settings.imageInput }.orEmpty()
        val note = if (message.allImages.isNotEmpty() && imageList.isEmpty()) {
            "\n（图片已省略：当前未开启「模型支持图片输入」）"
        } else {
            ""
        }
        when (message.role) {
            // A steer reads as an interruption live, so it reads as one here too — the wrapper is
            // what tells the model this was said mid-task rather than asked as a new question.
            Roles.USER ->
                if (message.steer) {
                    // Replayed from a stored steer: same wrapper, and now the same picture and the
                    // same omitted-image note as the live path. Leaving the image out here is what
                    // would make a steer with a photo work in the run it was sent in and silently
                    // lose it in every run that rebuilds this history afterwards.
                    wire += ChatMessage.user("（用户在任务进行中说：${message.text}）$note", imageList)
                } else {
                    wire += ChatMessage.user(message.text + note, imageList)
                }
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
                // The same two sentences the live path writes, chosen by the same condition: with
                // image input on the picture itself goes out, and with it off the model is told a
                // picture existed and that it cannot see it.
                val tool = message.toolName.orEmpty()
                if (imageList.isNotEmpty()) {
                    wire += ChatMessage.screenshot("（这是上一步 $tool 返回的截图）", imageList)
                } else if (message.allImages.isNotEmpty()) {
                    wire += ChatMessage.user(
                        "（上一步 $tool 返回了一张截图，但当前模型未开启图片输入，你看不到图像内容。）",
                    )
                }
            }
        }
    }

    /**
     * Why [tier] cannot run right now, or null when it can. The channel question is the same one
     * the tools ask ([CapabilityHub.shellTier]), so a gate never promises a route the tool below
     * it would refuse — 固定 标准档 or a tier that is not ready yet is reported as such instead of
     * as a missing permission the user already granted.
     */
    private fun blockedReason(tier: String, capabilities: CapabilityHub): String? {
        val state = capabilities.state.value
        return when (tier) {
            TIER_ACCESSIBILITY ->
                // 无障碍是首选通道，但 Shizuku / root 也能完成同一批操作
                // （input / screencap / uiautomator），所以有其中任一即可放行。
                if (state.accessibility || capabilities.shellTier() != ShellTier.LOCAL) {
                    null
                } else {
                    "还不能操作屏幕：请到「能力」页开启无障碍服务，或授权 Shizuku / root 中的任意一个。"
                }
            TIER_SHELL ->
                when {
                    capabilities.shellTier() != ShellTier.LOCAL -> null
                    // Something is granted, so the tier the user fixed is what is blocking: saying
                    // 「缺少 Shizuku（或 root）权限」 here would send them to grant it again.
                    capabilities.preferred.value.isNotEmpty() && (state.shellReady || state.rootReady) ->
                        "这类操作没有可用通道：已固定执行档次，但它还没就绪。" +
                            "请到「能力」页开启你固定的那一档，或把执行档次改回「自动」。"
                    else -> "缺少 Shizuku（或 root）权限：请到「能力」页授权后重试。"
                }
            TIER_ROOT ->
                if (state.rootReady) null else "缺少 root 权限，当前无法执行该工具。"
            else -> null
        }
    }

    private fun parseArgs(raw: String): JsonObject =
        runCatching { Json.parseToJsonElement(raw.ifBlank { "{}" }).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }

    /**
     * 跑**一条**工具调用，只产出 [ToolOutcome]，不碰 run 级的可变状态（wire/spans/produced/
     * openCalls 都在 [runLoop] 的局部里，回写由那边的 commitTool 做）—— 这是并行的前提：
     * 执行阶段（可能很慢）可以多条同时飞，回写阶段在主协程里按模型请求的顺序逐条做。
     *
     * 返回 null ＝ 这一条在开跑前看到取消（[cancelled]），调用方据此收尾。
     */
    private suspend fun executeOneTool(
        call: ToolCallRequest,
        registry: ToolRegistry,
        pluginSpecs: List<PluginSpec>,
        hooks: List<Pair<PluginSpec, HookDecl>>,
        capabilities: CapabilityHub,
        gate: ApprovalGate,
        settings: AppSettings,
        chatId: String,
        onEvent: (AgentEvent) -> Unit,
    ): ToolOutcome? {
        // Checked before the call, not after the batch: a stop means the tools the model
        // asked for in the same breath do not run either.
        if (cancelled.get()) return null
        val argsPreview = call.arguments.take(600)
        // 「压缩上下文」是引擎自己的虚拟工具（用户：「要有压缩上下文的指令工具」）：不进 registry
        // （没有要跑的实现、没有能力档），在分发前拦截 —— 置 [compressNow]，本轮边界上由
        // [compressContext] 消费。它不碰手机也不发网络请求，所以不发 ToolStarted、不进 TouchFx
        // 埋点（边缘光的语义是「在操作手机」，压缩不是）。
        if (call.name == COMPRESS_CONTEXT_TOOL) {
            compressNow = true
            return ToolOutcome(
                call = call,
                result = ToolResult.success(
                    "已请求压缩上下文：本轮结束后，最旧的一段对话会被折成摘要，之后的请求只带摘要与近期消息。",
                ),
                toolMs = 0,
                argsPreview = argsPreview,
            )
        }
        val tool = registry.find(call.name)
        // 接线点 B（HOST-DEV §5）：内置找不到这个名字才落到插件（`<id>_<tool>`）——
        // 与工具表拼装（接线点 A）同一个顺序，内置优先，插件顶不掉 `run_shell` 这类。
        // resolve 也兜底：与 risk/preExecute 同一条理由 —— 插件链路的类静态初始化抛 Error 时，
        // 不该把整轮 run 打死（FEEDBACK v0.8.112 P0#1 真机出现过 ExceptionInInitializerError）。
        val pluginTool = if (tool == null) {
            runCatching { PluginRuntime.resolve(pluginSpecs, call.name) }
                .getOrElse { error ->
                    PluginRuntime.logPluginError("插件工具解析失败（已兜底跳过）", error)
                    null
                }
        } else {
            null
        }
        // 谁真的跑这一条。null ＝ 两边都没有这个名字（模型编了个工具名）。
        val runner: (suspend (JsonObject) -> ToolResult)? = when {
            tool != null -> { args -> tool.run(args) }
            pluginTool != null -> { args ->
                PluginRuntime.execute(pluginTool.first, pluginTool.second, args)
            }
            else -> null
        }
        // 能力档：插件工具的档由 exec 决定 —— `needs_root` 才要 root，其余固定 local
        // （＝本应用身份，不需要额外能力），所以只有 root 那一档会被 blockedReason 拦住。
        val tier = when {
            pluginTool != null ->
                if (pluginTool.second.exec.needsRoot) TIER_ROOT else TIER_NONE
            tool != null -> tool.tier
            else -> null
        }
        // 风险档只有插件工具有（内置工具只有能力档），所以 min_risk 条件对内置一律
        // 不成立；要拦内置工具用 `when.tool` + `when.regex`。
        // **容错兜底**（FEEDBACK v0.8.112 P0#1 的机制面）：risk 推断与 hook 裁决在 runCatching
        // **外面** —— 插件链路上任何类的静态初始化抛 Error（真机出现过
        // ExceptionInInitializerError）都会穿透 executeOneTool、把整轮 run 打死成「这一轮最终
        // 失败」。插件是第三方代码，它的坏不该是整轮的坏：这里兜成一次工具失败，run 照常继续。
        val risk = runCatching { pluginTool?.let { ManifestValidator.riskOf(it.second) } }
            .getOrElse { error ->
                // 兜底吞掉的同时留证：clinit 类 Error 的真因在 cause 链里，不记下来下次复现
                // 还是「没有更多信息」。PluginLog 落插件自己的日志文件。
                PluginRuntime.logPluginError("risk 推断失败（已兜底跳过）", error)
                null
            }
        val verdict = runCatching { PluginRuntime.preExecute(hooks, call.name, argsPreview, risk) }
            .getOrElse { error ->
                PluginRuntime.logPluginError("hook 裁决失败（已兜底放行）", error)
                HookVerdict()
            }
        var toolMs = 0L
        val result = if (runner == null) {
            ToolResult.failure("未知工具：${call.name}")
        } else if (verdict.deny) {
            // 与「能力不足」同一类：跑都没跑，所以不发 ToolStarted。
            ToolResult.failure(verdict.message ?: "插件 hook 拒绝了这次调用。")
        } else {
            val blocked = tier?.let { blockedReason(it, capabilities) }
            if (blocked != null) {
                ToolResult.failure(blocked)
            } else {
                liveLine = "🔧 ${call.name}…"
                onEvent(AgentEvent.ToolStarted(call.name, argsPreview))
                // 边缘光的**单一埋点**（v3 会话制）：所有真正开跑的工具调用（内置 + 插件）都过这里，
                // TouchFx 据此判定「UI 操作＝开会话 / 非 UI 工具＝显式结束会话」。以前埋点散在
                // ShellTools/UiTools 两处，read_file / web_search / 插件工具都不经过 ⇒ 覆盖洞会让
                // 「不控制就不灭」失效。参数原文（未掩码）给 TouchFx 判 run_shell 里是否真碰 UI。
                runCatching { TouchFx.noteToolActivity(Core.appContext, call.name, call.arguments) }
                // AUTO 档**不弹任何授权**（用户多次明确要求）：插件的 require_confirm
                // 不再把 AUTO 升级成「问一次」——它降级为日志（preExecute 里已记）。
                // deny 仍拦（不弹窗，只让那次调用失败并说明原因）；ASK/DENY 档与
                // 「总是允许」照旧。要守卫插件真拦人，把审批档设成「每次询问」。
                val mode = settings.approvalMode
                if (!gate.request(chatId, call.name, argsPreview, mode)) {
                    ToolResult.failure("用户拒绝了这次调用。")
                } else {
                    val startedAt = System.currentTimeMillis()
                    runCatching { runner(parseArgs(call.arguments)) }.getOrElse { error ->
                        if (error is CancellationException) throw error
                        ToolResult.failure("工具执行异常：${error.message ?: error::class.java.simpleName}")
                    }.also { toolMs = System.currentTimeMillis() - startedAt }
                }
            }
        }
        return ToolOutcome(call = call, result = result, toolMs = toolMs, argsPreview = argsPreview)
    }

    private fun systemPrompt(
        settings: AppSettings,
        registry: ToolRegistry,
        skillCatalog: String,
        memory: Map<String, MemoryEntry>,
        profile: String,
    ): String = buildString {
        // 顺序是固定的，**按当前次序编号**（旧文档里的「1 人设」是移动前的号，别再按旧号找）：
        //   0 底座（一句话 + 工作规则）→ 1 工具表 → 2 插件 → 3 禁工具 → 4 技能
        //   → 5 人设 → 6 画像 → 7 记忆
        // 0-4 是产品对模型的硬要求，排在**人设之前**：不该被换一个人设就丢掉，也不该随人设的
        // 语言变；5-7 是「用户自己的文字」，保持原语言。
        append(SYSTEM_PROMPT_BASE)
        append("\n\n## Available tools\n")
        append(registry.promptSummary())
        // 接线点 A（HOST-DEV §5）：让模型知道插件工具也能调，命名与内置一致。
        // 这句是**固定文案**：插件列表是实时变的，而 systemPrompt() 不是 suspend（读盘要 suspend），
        // 所以不在这里读插件；没有启用插件时多说一句也无害（模型看不到那个名字就不会去调）。
        // 英文是刻意的：固定文案的语言不随插件作者的语言变（插件描述本身是什么语言就照原样出现）。
        append(
            "\n\nThe user has enabled plugin-provided tools (named `id.tool`); " +
                "call them the same way as built-in tools.",
        )
        // 禁止档下每一次调用都必然失败（[request] 直接返回 false，模型只会拿到「用户拒绝了
        // 这次调用。」），而上面那张工具表照旧列着。不在这里说明，模型就会一轮轮撞墙、把整轮
        // 时间花在被拒的调用上，而不是直接回答用户。
        if (settings.approvalMode == ApprovalMode.DENY) {
            append(
                "\n\nNote: the user has disabled tool calls. You cannot run any tool this round. " +
                    "Answer with text only, and do not attempt to call a tool.",
            )
        }
        if (skillCatalog.isNotBlank()) {
            append("\n\n")
            append(skillCatalog)
        }
        // 人设。空则整段不出现（只剩底座），不留一个空行。
        val persona = settings.systemPrompt.trim()
        if (persona.isNotEmpty()) {
            append("\n\n")
            append(persona)
        }
        // 画像在记忆**之前**（用户：「画像 在记忆前面吧」）：先交代"这是谁"，再说"我记得什么"。
        // 两段都是模型该知道的事，但一段是用户写的、一段是模型自己写的，分开才不会让模型把自己的
        // 笔记当成用户的自述。空画像整段不出现，不留一个空标题。
        val about = profile.trim()
        if (about.isNotBlank()) {
            append("\n\n## 关于用户（画像）\n")
            append(about)
        }
        val remembered = memoryBlock(memory)
        if (remembered.isNotBlank()) {
            append("\n\n## 你记得的事\n")
            append(remembered)
        }
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

        private const val ROLE_TOOL = "tool"
        private const val ROLE_SYSTEM = "system"

        /**
         * Roughly a token per three characters of Chinese, which is where the error
         * hurts most; English runs closer to four. Close enough for a speed readout
         * and for a context budget, and both would rather be over-cautious than late.
         */
        private fun estimateTokens(chars: Int): Int = chars / 3

        /**
         * live TPS 估算的 fallback「每字符 token 数」，用在第一轮还没有真实 usage 可校准之前。
         *
         * 0.9 是**真机实测**的中位数/均值（deepseek-v4.1-flash 七轮：token/字 mean=0.89、
         * median=0.91；短消息高到 1.6、长消息低到 0.5，因为每条有固定开销 + 边际 ~0.5）。绝不能用
         * companion 的 chars/3（=0.33）—— 那是英文系数，对中文低估约 2.7 倍，正是「明明 200 tok/s、
         * 手机上只有几十」的根因。首轮之后立即被真实 usage 校准值取代。
         */
        private const val DEFAULT_LIVE_TOKENS_PER_CHAR = 0.9f

        /** 自校准的合理区间：低于/高于就夹住，防一轮畸形数据（如纯工具调用轮字符≈0）把估算带飞。 */
        private const val MIN_LIVE_TOKENS_PER_CHAR = 0.3f
        private const val MAX_LIVE_TOKENS_PER_CHAR = 2.5f

        /** 校准至少要这么多字符才可信：太短的轮次字符噪声大、比率会剧烈跳动。 */
        private const val CALIB_MIN_CHARS = 40
    }
}

/**
 * What one screenshot costs the prompt, in tokens.
 *
 * An endpoint charges for an image by its resolution, not by the size of the base64 that carries
 * it: a phone screenshot is a few hundred thousand characters and lands somewhere around a thousand
 * tokens on every vision endpoint in use. A flat thousand is deliberately not a formula — being a
 * few hundred out is nothing next to pricing the picture as text, which was out by two orders of
 * magnitude and made a 574k conversation compress itself against a 1000k window.
 *
 * File level rather than in the engine's companion: the trimmer's estimate below is a top-level
 * function and has to price images the same way.
 */
private const val IMAGE_TOKENS = 1000

/**
 * 「压缩上下文」虚拟工具的名字（用户：「要有压缩上下文的指令工具」）。
 *
 * 它不在 [com.suixing.agent.tools.ToolRegistry] 里：没有要跑的实现、没有能力档，只在
 * [AgentEngine.RunHandle.executeOneTool] 分发前被拦截（置 compressNow，本轮边界消费）。名字进
 * 请求的工具表（接线点 A 追加 [COMPRESS_CONTEXT_SPEC]），模型才调得到它。
 */
const val COMPRESS_CONTEXT_TOOL = "compress_context"

/**
 * 第 0 段：内置底座，**一句话 + 工作规则**，永远排在用户人设之前。
 *
 * 顺序：先说自己是谁（一句话），再说这个产品对模型的硬要求（工作规则）。
 * 为什么规则并进这一段而不是单列一段：它们是同一件事 —— 产品默认的、不随人设变的那部分；
 * 分开写只会在两段之间多一个空段落，读起来像两条互不相干的声明。
 *
 * 英文且固定：与人设用什么语言写无关；用户自己的文字（人设、记忆、画像）仍按原语言出现。
 * 规则内容是原「Tool calls / Planning / Writing files / Context」四段合成的 —— 怎么调工具、
 * 怎么规划、怎么写大文件、什么时候压缩上下文；拆成四段只是历史上一次一条加进来的结果。
 */
private const val SYSTEM_PROMPT_BASE = "You are a helpful software engineer assistant.\n\n" +
    "## Working rules\n" +
    "- Tool calls: arguments must be a valid JSON object, and several tools may be called in parallel. " +
    "When a tool fails, read the error and adjust — do not repeat the same call. " +
    "When the work is done, answer the user in Chinese, briefly saying what you did and what happened.\n" +
    "- Planning: for multi-step work, call todo_write first and rewrite the complete list after each step " +
    "(rewrite, not append) — your context may be compacted, the board will not be.\n" +
    "- Writing files: use write_file. For content over roughly 200 lines or 8KB, write it in parts — " +
    "the head first, then append with `append: true`; do not emit a whole large file in one call " +
    "(that hits the model's own output limit and the round gets truncated).\n" +
    "- Context: when it gets long (a long run, or the user says responses are slow or the context is " +
    "too long), call " + COMPRESS_CONTEXT_TOOL + " — the oldest span is folded into a summary and later " +
    "requests carry that summary plus the recent messages. The current round continues normally."

/** 虚拟工具的规格：无参数。schema 用 OpenAI 的 object 形状，与 [com.suixing.agent.tools.Schema] 同款。 */
internal val COMPRESS_CONTEXT_SPEC = com.suixing.agent.tools.ToolSpec(
    name = COMPRESS_CONTEXT_TOOL,
    description = "Compact the context: fold the oldest part of this conversation into a summary, so " +
        "later requests carry the summary plus the recent messages. Call it when a long run has been " +
        "going for a while, or when the user says responses are slow or the context is too long. " +
        "The current round continues normally afterwards.",
    parameters = com.suixing.agent.tools.Schema.objectOf(emptyList()),
)

/**
 * What a tool card says when the user stopped the run before the tool answered.
 *
 * It is a result, not a placeholder: the transcript has to answer the call it recorded, or the card
 * draws empty and the next run's replay drops the whole assistant turn for being unanswered.
 */
private const val INTERRUPTED_TOOL_OUTPUT = "（已中断：任务被停止，这次调用没有结果）"

/**
 * The same estimate for a stored message, used by the context trimmer: its text, plus a flat
 * [IMAGE_TOKENS] for a screenshot.
 *
 * The screenshot used to be priced by its base64 length, which is the same mistake the wire
 * estimate made: a few hundred thousand characters charged as text against a window of tokens. It
 * did not merely compress early — it made the trimmer believe a handful of messages filled the
 * whole budget and drop the rest of the conversation from the replay, silently.
 *
 * `internal` rather than private because the chat screen's context ring falls back to the same
 * estimate when an endpoint reports no usage: one implementation, so the ring and the trimmer can
 * never disagree about how big the conversation is. Change the divisor here and the ring changes
 * with it — which is the point.
 */
internal fun estimateTokens(message: StoredMessage): Int {
    val text = message.text.length + message.toolOutput.orEmpty().length + message.toolArgs.orEmpty().length
    val image = IMAGE_TOKENS * message.allImages.size
    return text / 3 + image
}

/**
 * Stamps what the round cost onto the message it produced. Endpoints that send no
 * usage block leave the counts at zero, which the UI reads as "unknown" rather than
 * "free".
 *
 * [firstTokenMs] is the wait before anything came back — the number a slow endpoint is
 * judged by, and the one the usage block never carries: it is measured here, between the
 * request going out and the first delta arriving.
 */
private fun StoredMessage.withUsage(
    usage: Usage?,
    elapsedMs: Long,
    model: String,
    firstTokenMs: Long = 0L,
): StoredMessage {
    if (usage == null) {
        return copy(elapsedMs = elapsedMs, model = model, firstTokenMs = firstTokenMs)
    }
    return copy(
        promptTokens = usage.promptTokens,
        completionTokens = usage.completionTokens,
        reasoningTokens = usage.reasoningTokens,
        cachedTokens = usage.cachedTokens,
        elapsedMs = elapsedMs,
        model = model,
        firstTokenMs = firstTokenMs,
    )
}
