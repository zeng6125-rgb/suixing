package com.suixing.agent.agent

import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ChildRunConfig
import com.suixing.agent.data.SubagentLaunchOptions
import com.suixing.agent.data.STEER_IMMEDIATE
import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.GoalStore
import com.suixing.agent.data.MemoryStore
import com.suixing.agent.data.UserProfileStore
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.llm.ChatMessage
import com.suixing.agent.llm.LlmClient
import com.suixing.agent.llm.ToolCallRequest
import com.suixing.agent.skills.SkillStore
import com.suixing.agent.tools.GoalTools
import com.suixing.agent.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

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
    private val onCompleted: (String) -> Unit = {},
    private val approvalSourceTitle: String? = null,
    /** Durable objective shared with the chat UI. */
    private val goals: GoalStore? = null,
    private val childConfig: ChildRunConfig? = null,
) {

    private val runs = MutableStateFlow<Map<String, RunHandle>>(emptyMap())
    private val goalTools = goals?.let(::GoalTools)

    /** 画像整理器：收尾挂钩与「重新总结」共用一个，它自己无状态。 */
    private val summarizer = ProfileSummarizer(llm)

    /** The runs in flight right now, keyed by conversation id. */
    val runsFlow: StateFlow<Map<String, RunHandle>> = runs.asStateFlow()
    private val childStates = MutableStateFlow<Map<String, List<SubagentSnapshot>>>(emptyMap())
    internal val subagentsFlow = childStates.asStateFlow()
    private val subagentReadyChats = MutableStateFlow<Set<String>>(emptySet())
    private val runHost: AgentRunHost = AgentRunHost(
        llm = llm,
        goals = goals,
        goalTools = goalTools,
        childConfig = childConfig,
        approvalSourceTitle = approvalSourceTitle,
        childStates = childStates,
        subagentReadyChats = subagentReadyChats,
        isLive = { chatId, run -> runs.value[chatId]?.runtime === run },
        publishState = { chatId, state -> stateFlowOf(chatId).value = state },
        onCompleted = onCompleted,
    )
    internal val subagentAvailabilityFlow = subagentReadyChats.asStateFlow()
    internal fun cancelSubagent(chatId: String, id: String): Boolean = runs.value[chatId]?.cancelSubagent(id) ?: false
    internal fun canCreateSubagent(chatId: String): Boolean =
        chatId in subagentReadyChats.value && runs.value[chatId]?.hasSubagentPool() == true
    internal fun previewSubagent(chatId: String, options: SubagentLaunchOptions): ChildRunConfig =
        (runs.value[chatId] ?: error("请先启动主任务，再创建子任务。")).previewSubagent(options)
    internal fun createSubagent(
        chatId: String,
        task: String,
        context: String = "",
        name: String = "",
        options: SubagentLaunchOptions = SubagentLaunchOptions(),
    ): SubagentSnapshot = (runs.value[chatId] ?: error("主任务已结束。")).createSubagent(task, context, name, options)

    /**
     * Per-conversation state that outlives its run.
     *
     * Kept apart from [runs] because the UI needs the *terminal* state: the usage line
     * freezes on `Done`, and a flow that vanished along with the run would take the numbers
     * with it.
     */
    private val states = mutableMapOf<String, java.lang.ref.WeakReference<MutableStateFlow<RunState>>>()
    private var stateAccesses = 0
    private val recentStates = object : LinkedHashMap<String, MutableStateFlow<RunState>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableStateFlow<RunState>>?): Boolean = size > 16
    }

    /**
     * The state of one conversation, whether or not it is running right now.
     *
     * A flow held by any caller keeps its identity across runs. Recent terminal states are
     * retained strongly; older unobserved flows may be reclaimed without breaking a collector.
     */
    fun stateOf(chatId: String): StateFlow<RunState> = stateFlowOf(chatId)

    /** The writable side of [stateOf], for the runs that publish into it. */
    private fun stateFlowOf(chatId: String): MutableStateFlow<RunState> = synchronized(states) {
        // Publishing one token must not scan every conversation ever opened.
        if (++stateAccesses % 64 == 0) states.entries.removeAll { it.value.get() == null }
        val flow = states[chatId]?.get() ?: MutableStateFlow(runs.value[chatId]?.state?.value ?: RunState.Idle).also {
            states[chatId] = java.lang.ref.WeakReference(it)
        }
        recentStates[chatId] = flow
        flow
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
        directTool: ToolCallRequest? = null,
    ): Boolean = runs.value[chatId]?.steer(id, text, immediate, imagesBase64, directTool) ?: false

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
        initialTool: ToolCallRequest? = null,
        runId: String = UUID.randomUUID().toString(),
    ): List<StoredMessage> {
        // Check and register in one critical section: two coroutines starting the same
        // conversation (a double tap, or an isRunning check preempted before start) would
        // otherwise both pass the guard and write one transcript from two loops.
        val handle = synchronized(runs) {
            val existing = runs.value[chatId]
            if (existing != null) {
                null
            } else {
                val created = RunHandle(chatId, title, settings, runId)
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
                initialTool = initialTool,
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
                // Suspend without periodic wakeups until there is a live run again.
                if (runs.value.isEmpty()) runs.first { it.isNotEmpty() }
                runs.value.values.forEach { it.tick() }
                delay(STATS_TICK_MS)
            }
        }
    }

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
        checkpoints: List<ContextCheckpoint> = emptyList(),
        onCheckpoint: (ContextCheckpoint) -> Unit,
    ): String? {
        val plan = withContext(Dispatchers.Default) { planCompaction(history, checkpoints = checkpoints) }
            ?: return "没有足够的新增内容可压缩，已有摘要会继续复用"
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
        val attempt = checkedSummary(turn.text, turn.finishReason, plan.transcript.length)
        if (!attempt.commit) return "压缩未提交：${attempt.why}，已保留原文"
        val summary = attempt.text
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

    /**
     * Compatibility handle for chat and console callers. Execution and mutable state belong to
     * [AgentRun]; this small inner shell preserves the existing `engine.RunHandle(...)` entry.
     */
    inner class RunHandle(
        chatId: String,
        title: String,
        settings: AppSettings,
        childParentRunId: String = UUID.randomUUID().toString(),
    ) {
        internal val runtime: AgentRun = AgentRun(runHost, chatId, title, settings, childParentRunId)
        val chatId: String get() = runtime.chatId
        val title: String get() = runtime.title
        val state: StateFlow<RunState> get() = runtime.state
        val startedAt: Long get() = runtime.startedAt
        val liveLine: String get() = runtime.liveLine
        internal val terminalCaller get() = runtime.terminalCaller
        internal val steerMode: Int get() = runtime.steerMode
        internal var job: Job?
            get() = runtime.job
            set(value) { runtime.job = value }

        fun cancel(): Boolean = runtime.cancel()
        fun steer(
            id: String,
            text: String,
            immediate: Boolean,
            imagesBase64: List<String> = emptyList(),
            directTool: ToolCallRequest? = null,
        ): Boolean = runtime.steer(id, text, immediate, imagesBase64, directTool)
        fun retract(id: String): Boolean = runtime.retract(id)
        fun promote(id: String): Boolean = runtime.promote(id)
        fun requestCompress(): Boolean = runtime.requestCompress()
        internal fun cancelSubagent(id: String): Boolean = runtime.cancelSubagent(id)
        internal fun hasSubagentPool(): Boolean = runtime.hasSubagentPool()
        internal fun previewSubagent(options: SubagentLaunchOptions): ChildRunConfig = runtime.previewSubagent(options)
        internal fun createSubagent(task: String, context: String, name: String, options: SubagentLaunchOptions): SubagentSnapshot =
            runtime.createSubagent(task, context, name, options)
        internal fun publish(state: RunState) = runtime.publish(state)
        internal fun tick() = runtime.tick()
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
            initialTool: ToolCallRequest? = null,
        ): List<StoredMessage> = runtime.execute(
            history, checkpoints, settings, registry, capabilities, skills, memory, profile,
            gate, onEvent, onCheckpoint, initialTool,
        )
    }

    private companion object {
        /** 120 ms is faster than the eye and slower than the stream. */
        const val STATS_TICK_MS = 120L
    }
}
