package com.suixing.agent.agent

import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ChildRunConfig
import com.suixing.agent.data.SubagentLaunchOptions
import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.MemoryStore
import com.suixing.agent.data.UserProfileStore
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.TranscriptRedaction
import com.suixing.agent.data.StoredToolCall
import com.suixing.agent.data.Tps
import com.suixing.agent.data.WireSanitize
import com.suixing.agent.llm.ChatMessage
import com.suixing.agent.llm.ToolCallRequest
import com.suixing.agent.skills.SkillStore
import com.suixing.agent.tools.ToolRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import okhttp3.Call
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/** Per-run mutable state. Shared engine services arrive explicitly through [host]. */
internal class AgentRun(
    private val host: AgentRunHost,
    val chatId: String,
    val title: String,
    settings: AppSettings,
    internal val childParentRunId: String,
) {
    internal val llm = host.llm
    internal val goals = host.goals
    internal val goalTools = host.goalTools
    internal val childConfig = host.childConfig
    internal val approvalSourceTitle = host.approvalSourceTitle
    internal val childStates = host.childStates
    internal val subagentReadyChats = host.subagentReadyChats
    private val onCompleted = host.onCompleted

    internal val terminalCaller = com.suixing.agent.terminal.TerminalCaller(chatId, UUID.randomUUID().toString())
    private val _state = MutableStateFlow<RunState>(RunState.Idle)
    private val completionReported = AtomicBoolean(false)
    private val cancellationReported = AtomicBoolean(false)
    @Volatile internal var subagents: SubagentPool? = null
    internal val goalRun = goals?.let(::GoalRun)
    internal val compactionRetry = CompactionRetryGate()
    internal val ledger = RunLedger(settings.maxToolRounds)
    internal var toolStep = 0
    internal fun cancelSubagent(id: String): Boolean = subagents?.cancel(id) ?: false
    internal fun hasSubagentPool(): Boolean = subagents != null && !cancelled.get()
    internal fun previewSubagent(options: SubagentLaunchOptions): ChildRunConfig =
        (subagents ?: error("当前主任务未启用子任务。")).preview(options)
    internal fun createSubagent(task: String, context: String, name: String, options: SubagentLaunchOptions): SubagentSnapshot =
        (subagents ?: error("当前主任务未启用子任务。")).spawn(task, context, name, options = options)
    internal suspend fun awaitSubagentsBeforeFinish() {
        val pool = subagents ?: return
        subagentReadyChats.update { it - chatId }
        if (pool.snapshots().any { it.status == SubagentStatus.QUEUED || it.status == SubagentStatus.RUNNING }) {
            liveLine = "等待子任务完成…"
            publish(RunState.Running(stats().copy(note = "等待子任务完成")))
        }
        pool.sealAndWait()
    }

    /** This conversation's state, from the moment it starts to long after it ends. */
    val state: StateFlow<RunState> = _state.asStateFlow()

    /** When this run began, in [System.nanoTime] terms. What orders concurrent runs. */
    @Volatile
    var startedAt: Long = System.nanoTime()
        internal set

    /**
     * What this run is doing at this instant, for the floating console: which tool is
     * running, that the model is thinking, or the tail of the text it is writing.
     *
     * Deliberately not part of [state]: that flows at its own pace, and this changes on
     * every tool and every delta.
     */
    @Volatile
    var liveLine: String = ""
        internal set

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
    internal var caller: Job? = null

    /** [steer]'s default mode for this run, read from the settings it started with. */
    internal val steerMode: Int = settings.steerMode

    internal val cancelled = AtomicBoolean(false)
    internal val interruptedFileTools = java.util.concurrent.ConcurrentHashMap<String, ToolOutcome>()

    /** The call currently on the wire; cancelling it is what stops this run's stream. */
    @Volatile
    internal var inFlight: Call? = null

    /**
     * The gate this run asks for approval through, kept so [cancel] can take this run's own
     * prompt down with it. Null until [execute] starts — which is also the only stretch in
     * which there can be no prompt of this run's on screen to take down.
     */
    @Volatile
    internal var gate: ApprovalGate? = null

    /**
     * Lines the user slipped in that the model has not read yet, in the order they were said.
     *
     * A map keyed by id rather than a plain queue because the user can still change their
     * mind: a line is retractable right up to the moment it lands in the wire, and that is
     * only possible if the waiting ones can be addressed.
     */
    internal val steering = LinkedHashMap<String, Steer>()
    // Only the run coroutine writes this queue, after a line reaches its safe boundary.
    internal val directTools = ArrayDeque<QueuedDirectTool>()

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
    internal var compressNow = false

    /**
     * Set when an immediate [steer] aborts the round on purpose, so the IOException it
     * produces is read as 「这一轮作废」 rather than as a network failure.
     */
    internal val steeringCut = AtomicBoolean(false)

    /** Prompt tokens the endpoint reported for the last round; 0 = it never did. */
    @Volatile
    internal var lastPromptTokens = 0
    /** 当前轮请求的估算大小，见 [StreamStats.currentPrompt]。每轮定型时刷新。 */
    @Volatile internal var currentPromptEstimate = 0

    /** Wire size in characters when [lastPromptTokens] was recorded, to price the growth. */
    @Volatile
    internal var lastPromptChars = 0

    /**
     * How many screenshots the wire carried when [lastPromptTokens] was recorded.
     *
     * Tracked apart from the character count because an image is priced per image: the base64
     * of one picture would swamp any character-based delta.
     */
    @Volatile
    internal var lastPromptImages = 0

    /** The three parts of the prompt, in estimated tokens, for the UI's breakdown bar. */
    @Volatile
    internal var breakSystem = 0

    @Volatile
    internal var breakTools = 0


    @Volatile
    internal var breakHistory = 0

    /** A one-line note about the run, shown by whoever renders [state]. */
    @Volatile
    internal var contextNote = ""

    internal val outChars = AtomicInteger(0)

    internal val thinkingChars = AtomicInteger(0)

    /**
     * 当前这一轮首个块（正文或思考，任意）到达的 [System.currentTimeMillis] 时刻，0＝本轮还没
     * 出字。每轮开始清零、本轮结束累加进 [decodeMsAccum] 后再清零 —— 所以它在「两轮之间的工具
     * 执行期」是 0，不会被 [stats] 重复计入。与 turn 内的 firstChunkAt 同基（millis）。
     */
    @Volatile
    internal var curTurnFirstChunkMs = 0L

    /**
     * 已结束各轮的 decodeMs 之和（官方多轮聚合＝ΣdecodeTokens/ΣdecodeMs，client.js:8331-8333）。
     * decodeMs＝每轮「首字之后→结束」，**不含** TTFT 等待、也**不含**轮间的工具执行时间 —— 这正是
     * 拿整轮墙钟当分母会系统性低估 TPS 的原因。[stats] 再加上当前轮的实时 decode 段。
     */
    @Volatile
    internal var decodeMsAccum = 0L

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
    internal var liveTokensPerChar = DEFAULT_LIVE_TOKENS_PER_CHAR

    /** 上一次校准时的累计字符数（outChars+thinkingChars），用来算「本轮」的字符增量。 */
    @Volatile
    internal var calibCharsBaseline = 0

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
    fun steer(
        id: String,
        text: String,
        immediate: Boolean,
        imagesBase64: List<String> = emptyList(),
        directTool: ToolCallRequest? = null,
    ): Boolean {
        if (!isLive()) return false
        val body = text.trim()
        if (body.isEmpty() && imagesBase64.isEmpty()) return false
        val line = Steer(id, body, imagesBase64, directTool)
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
    private fun isLive(): Boolean = host.isLive(chatId, this)

    /** Publishes [state] to this run's own flow and to the conversation's durable one. */
    internal fun publish(state: RunState) {
        _state.value = state
        // The per-conversation flow is what the chat screen collects; without this mirror
        // stateOf(chatId) would sit at Idle forever and every per-chat branch in the UI
        // would be dead code.
        host.publishState(chatId, state)
        if (state is RunState.Done && completionReported.compareAndSet(false, true)) {
            onCompleted(chatId)
        }
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
        initialTool: ToolCallRequest? = null,
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
                    initialTool = initialTool,
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
    internal fun finishCancelled(
        onEvent: (AgentEvent) -> Unit,
        partial: CharSequence,
        reasoning: CharSequence = "",
        unanswered: List<StoredToolCall> = emptyList(),
    ) {
        createExitPolicy(onEvent).finish(LoopExit.Cancelled(partial, reasoning, unanswered))
    }

    /** Shared by the running loop and the registration-before-start cancellation race. */
    internal fun createExitPolicy(onEvent: (AgentEvent) -> Unit): LoopExitPolicy = LoopExitPolicy(
        onEvent = onEvent,
        publish = ::publish,
        stats = ::stats,
        prepareCancelled = { exit ->
            // A stop can cross both runLoop and execute; commit its transcript only once.
            if (cancellationReported.compareAndSet(false, true)) {
                emitPartial(onEvent, exit.partial, exit.reasoning, note = ledger.summary().takeIf { it.isNotBlank() })
                unansweredToolMessages(onEvent, exit.unanswered)
                true
            } else false
        },
        prepareCallerCancellation = { exit ->
            // Scope cancellation must still preserve streamed content and answer open tool calls.
            emitPartial(onEvent, exit.partial, exit.reasoning)
            unansweredToolMessages(onEvent, exit.unanswered)
        },
    )

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
                        toolArgs = TranscriptRedaction.argumentsForTranscript(call.name, call.arguments),
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
    private fun emitPartial(
        onEvent: (AgentEvent) -> Unit,
        partial: CharSequence,
        reasoning: CharSequence,
        note: String? = null,
    ) {
        if (partial.isBlank() && reasoning.isBlank() && note.isNullOrBlank()) return
        val noteAsText = partial.isBlank() && reasoning.isBlank()
        onEvent(
            AgentEvent.AssistantMessage(
                StoredMessage(
                    id = UUID.randomUUID().toString(),
                    role = Roles.ASSISTANT,
                    text = if (noteAsText) note.orEmpty() else partial.toString(),
                    reasoning = reasoning.toString().takeIf { it.isNotBlank() },
                    traceNote = note?.takeUnless { noteAsText },
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
    internal fun drainSteering(
        wire: MutableList<ChatMessage>,
        spans: MutableList<WireSpan>,
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
            val source = if (urgent.isEmpty()) steering else urgent
            val lines = source.values.toList()
            // Leave the tail retractable until its own command starts. One failed or cancelled
            // command must not acknowledge every other line waiting behind it.
            val firstCommand = lines.indexOfFirst { it.directTool != null }
            val selected = if (firstCommand < 0) lines else lines.take(firstCommand + 1)
            selected.forEach { source.remove(it.id) }
            selected
        }
        batch.forEach { line ->
            val call = line.directTool
            if (call == null) appendSteer(line, wire, spans, onEvent, settings)
            else directTools.addLast(QueuedDirectTool(call, line))
        }
        return batch.size
    }

    internal fun appendSteer(
        line: Steer,
        wire: MutableList<ChatMessage>,
        spans: MutableList<WireSpan>,
        onEvent: (AgentEvent) -> Unit,
        settings: AppSettings,
    ) {
        val images = line.imagesBase64.takeIf { settings.imageInput }.orEmpty()
        val note = if (line.imagesBase64.isNotEmpty() && images.isEmpty()) {
            "（图片已省略：当前未开启「模型支持图片输入」）"
        } else ""
        val from = wire.size
        wire += ChatMessage.user("（用户在任务进行中说：${line.text}）$note", images)
        spans += WireSpan(line.id, from, wire.size)
        val storedText = line.directTool?.let {
            TranscriptRedaction.commandForTranscript(line.text, it.name, it.arguments)
        } ?: line.text
        onEvent(AgentEvent.Steered(line.id, storedText, line.imagesBase64))
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
    internal fun estimatePromptTokens(wire: List<ChatMessage>): Int {
        if (wire is PromptMessages) return wire.counts.let { estimatePromptTokens(it.totalChars, it.imageMessages) }
        // **一趟同时得两个数**（原来是 `wireChars(wire)` + `count{…}` 各扫一遍全历史）：
        // 大会话几十万字符时这是每轮两遍纯重复。数值与原来逐位相同。
        var chars = 0
        var images = 0
        for (message in wire) {
            chars += messageChars(message)
            if (message.imageCount > 0) images++
        }
        return estimatePromptTokens(chars, images)
    }

    /**
     * 同一个估算，但字符数与图片数由调用方提供 —— 调用点已经遍历过一遍 wire 时用它，
     * 免得为两个数字再各扫一次整份历史。数值与 [estimatePromptTokens] 完全一致。
     */
    internal fun estimatePromptTokens(chars: Int, images: Int): Int {
        val reported = lastPromptTokens
        if (reported <= 0) return chars / 3 + images * IMAGE_TOKENS
        val textDelta = (chars - lastPromptChars).coerceAtLeast(0) / 4
        val imagesDelta = (images - lastPromptImages).coerceAtLeast(0) * IMAGE_TOKENS
        return reported + textDelta + imagesDelta
    }

    internal fun stats(): StreamStats {
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
    // 逻辑搬进 `HistoryTrim`（纯函数，带测试）：三条不变量原来只写在 KDoc 里。
    internal fun trim(messages: List<StoredMessage>, budget: Int): List<StoredMessage> =
        HistoryTrim.apply(messages, budget)

    /**
     * A run stopped in the middle of a tool call leaves an assistant message whose
     * `tool_calls` were never answered. Replaying that to an OpenAI-compatible
     * endpoint is a hard 400, so any such message is dropped before sending.
     */
    // 逻辑搬进 `WireSanitize`（纯函数，带测试）：它守着**两种**会让端点回 400 的形状，
    // 而原来一条测试都没有。
    internal fun sanitize(messages: List<StoredMessage>): List<StoredMessage> =
        WireSanitize.apply(messages)

    companion object {
        internal val SUBAGENT_EXECUTION_SLOTS = Semaphore(3)
        internal val SHARED_TOOL_EXECUTION = SharedToolExecution()
        internal const val CHILD_AGENT_INSTRUCTIONS =
            "You are an independent subagent. Work only on the assigned task and supplied context; " +
                "you do not see the parent conversation. Respect all inherited tool and approval restrictions. " +
                "Do not delegate or modify the parent's task board. Return a concise final result with evidence, " +
                "changed paths when relevant, and any remaining limitation. The parent verifies your result."
        /** How much of the streamed answer the console's live line keeps, in characters. */
        internal const val LIVE_LINE_CHARS = 40

        internal const val MAX_TOOL_OUTPUT = 8_000

        /**
         * How many of the newest messages a compression always keeps verbatim.
         *
         * Six is a turn's worth of context — the user's line, the assistant's answer
         * and a couple of tool results — which is what the model is actively working
         * on when the compression fires.
         */
        internal const val KEEP_RECENT = 6

        /** Below this many messages there is nothing worth summarising. */
        internal const val MIN_MESSAGES = 8

        internal const val ROLE_TOOL = "tool"
        internal const val ROLE_SYSTEM = "system"

        /**
         * Roughly a token per three characters of Chinese, which is where the error
         * hurts most; English runs closer to four. Close enough for a speed readout
         * and for a context budget, and both would rather be over-cautious than late.
         */
        internal fun estimateTokens(chars: Int): Int = chars / 3

        /**
         * live TPS 估算的 fallback「每字符 token 数」，用在第一轮还没有真实 usage 可校准之前。
         *
         * 0.9 是**真机实测**的中位数/均值（deepseek-v4.1-flash 七轮：token/字 mean=0.89、
         * median=0.91；短消息高到 1.6、长消息低到 0.5，因为每条有固定开销 + 边际 ~0.5）。绝不能用
         * companion 的 chars/3（=0.33）—— 那是英文系数，对中文低估约 2.7 倍，正是「明明 200 tok/s、
         * 手机上只有几十」的根因。首轮之后立即被真实 usage 校准值取代。
         */
        internal const val DEFAULT_LIVE_TOKENS_PER_CHAR = 0.9f

        /** 自校准的合理区间：低于/高于就夹住，防一轮畸形数据（如纯工具调用轮字符≈0）把估算带飞。 */
        internal const val MIN_LIVE_TOKENS_PER_CHAR = 0.3f
        internal const val MAX_LIVE_TOKENS_PER_CHAR = 2.5f

        /** 校准至少要这么多字符才可信：太短的轮次字符噪声大、比率会剧烈跳动。 */
        internal const val CALIB_MIN_CHARS = 40
    }
}
