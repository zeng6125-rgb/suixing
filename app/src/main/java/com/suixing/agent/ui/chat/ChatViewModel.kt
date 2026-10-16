package com.suixing.agent.ui.chat

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suixing.agent.agent.AgentEvent
import com.suixing.agent.agent.RunState
import com.suixing.agent.agent.StreamStats
import com.suixing.agent.core.Core
import com.suixing.agent.plugins.PluginRuntime
import com.suixing.agent.data.Assistant
import com.suixing.agent.data.AssistantAvatarStore
import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.Conversation
import com.suixing.agent.data.LiveDraft
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.Tps
import com.suixing.agent.data.TodoItem
import com.suixing.agent.data.Transcript
import com.suixing.agent.data.choiceFor
import com.suixing.agent.data.forAssistant
import com.suixing.agent.llm.LlmClient
import com.suixing.agent.llm.LlmException
import com.suixing.agent.llm.describeError
import com.suixing.agent.service.AgentForegroundService
import com.suixing.agent.ui.settings.withUpstreamDefaults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * What the user has typed but not sent, per conversation. A single global field is
 * what carried half a sentence from one conversation into the next when the drawer
 * switched; keyed by chat id, each conversation restores its own on the way back.
 */
data class Draft(
    val text: String = "",
    /** 文件附件的标记（单个）：路径注入正文，见 ChatScreen.withAttachment。 */
    val attachmentUri: String? = null,
    val attachmentName: String? = null,
    /**
     * 已缓存图片的标签（顺序＝发送顺序）。多附件（用户：「图片为什么只能添加一张」）：图片本体在
     * view model 的 [ChatViewModel.attachmentsFor] 缓存里，这里只放**触发 remember 重读**的标记 ——
     * 与单值时代的 attachmentUri 同一个作用，换成列表而已。
     */
    val imageNames: List<String> = emptyList(),
)

/**
 * A line the user queued while a run was going, before the model has read it.
 *
 * It is not a message yet: nothing reaches the transcript until the engine reports the
 * line was handed over ([AgentEvent.Steered]), and until then the user can still promote,
 * edit or delete it. The queue is deliberately never written to disk — it is the state of
 * a run that is still going, and a restarted process has no such run.
 */
data class PendingSteer(val id: String, val text: String, val imagesBase64: List<String> = emptyList())

/**
 * A whole turn the user sent while a run was going, in 排队 mode.
 *
 * Deliberately not a steer: nothing about it interrupts the run that is going. It waits, and starts
 * a run of its own once the current one is over — the same path the send button takes, so it lands
 * in the transcript as an ordinary user message rather than as a bubble hanging above the composer.
 * The image travels with it because the composer can hold one, and a queued turn that quietly
 * dropped its picture would be a message the user never wrote.
 *
 * Memory only, like [PendingSteer] and for a stronger reason: this queue is about the run that is
 * going right now. If the process dies, that run dies with it, so there is no "afterwards" left to
 * send into — and a copy on disk would come back as the app talking to itself.
 */
data class PendingTurn(val id: String, val text: String, val imagesBase64: List<String> = emptyList())

data class ChatUiState(
    val conversationId: String = "",
    val title: String = "新会话",
    /**
     * True until the conversation to show is known.
     *
     * The screen draws a bare skeleton while this holds, because an empty state on the way
     * to the real conversation reads as "it opened a new chat first and then jumped" — which
     * is exactly what the user saw on every cold start.
     */
    val loading: Boolean = true,
    val messages: List<StoredMessage> = emptyList(),
    /**
     * How many times [messages] has been replaced, as far as the screen is concerned.
     *
     * Stamped by the view model's own [state] flow (see its KDoc), never by the callers that
     * write the list: a number that has to be maintained at twenty sites is a number that will be
     * wrong at one of them, and the symptom is a bubble that keeps the text the user just edited.
     *
     * The screen keys its derivations on this instead of on [messages] itself, because `remember`
     * compares keys with `equals` — and a message carries its screenshot as base64, so a list
     * comparison walked the whole transcript on every recomposition, twelve times a second while
     * an answer streams.
     */
    val messagesRevision: Long = 0,
    /** Which run of each turn is on screen; see [Transcript]. */
    val activeRuns: Map<String, String> = emptyMap(),
    /** The persona this conversation speaks as. */
    val assistantId: String = "",
    /**
     * This conversation's own model pick, or null while it follows the persona and then
     * the app-wide default. The screen draws its tick from the same [AppSettings.choiceFor]
     * chain the run sends with, so what the picker shows is what goes on the wire.
     */
    val providerId: String? = null,
    val model: String? = null,
    /**
     * This conversation's own thinking level, or null while it follows the persona's
     * provider and then the app-wide default. Kept beside the model pick because the two
     * are chosen in the same sheet and belong to the same chat.
     */
    val reasoningEffort: String? = null,
    val streaming: String = "",
    /** The thinking streamed ahead of [streaming], shown in its own block. */
    val reasoning: String = "",
    /**
     * Whether the conversation on screen has a run in flight, straight off the
     * engine's per-chat state. Runs in other conversations are none of this
     * screen's business: nothing here locks, refuses or narrates them.
     */
    val runningHere: Boolean = false,
    val status: String = "",
    /** The tool the run is inside right now; the tool card names it live. */
    val runningTool: String? = null,
    /**
     * Speed of the run that is streaming, straight off the engine's `RunState`. Only the
     * live bubble reads it; once the reply is stored, its own usage numbers are the
     * frozen record and this is ignored.
     */
    val stats: StreamStats? = null,
    /**
     * 圆环面板三行（系统提示词 / 工具定义 / 对话消息）的**当前窗口**读数，不依赖 run：打开面板时
     * 按当前上下文算一次（[refreshContextBreakdown]），所以没有 run 也常驻显示。有 run 时面板优先
     * 用 [stats] 里的实测三块。
     */
    val contextBreakdown: Triple<Int, Int, Int>? = null,
    /** The live bubble's pieces; empty unless this conversation owns the running run. */
    val liveParts: List<LivePart> = emptyList(),
    /**
     * The run whose rounds are streaming. Its stored rounds are hidden from the
     * transcript while it runs — the live bubble already draws them, and showing both is
     * what split one reply into two bubbles that later merged.
     */
    val liveRunId: String = "",
    val error: String? = null,
    /** The unsent text of the conversation on screen; see [Draft]. */
    val draft: Draft = Draft(),
    /** Lines queued for the run that is going, oldest first; see [PendingSteer]. */
    val pendingSteers: List<PendingSteer> = emptyList(),
    /**
     * Lines typed in 排队 mode, waiting to go out as the next turn; see [PendingTurn].
     *
     * Drawn above the composer like [pendingSteers], with its own wording, because the two mean
     * different things: one is being read by the run in flight, the other has not been sent
     * anywhere yet. Not drawing it is what made the user press 插话 over and over — the line
     * vanished into a queue with nothing on screen to show for it.
     */
    val pendingTurns: List<PendingTurn> = emptyList(),
    /**
     * The agent's own task board, as the last `todo_write` left it — drawn above the composer
     * while the run that wrote it is going.
     *
     * Read from `TodoStore` rather than carried in the event: the board is app-wide, its writes
     * are the whole list, and the file is the only copy that cannot drift from what the model
     * believes it has planned. Empty between runs (see [start]), so a plan cannot outlive the
     * run that made it.
     */
    val todos: List<TodoItem> = emptyList(),
    /**
     * This conversation's compression record, oldest first.
     *
     * Carried with the conversation rather than looked up per run: the engine reuses a
     * summary it has already paid for, and a list rebuilt from scratch would fold — and
     * charge for — the same messages again on every turn.
     */
    val checkpoints: List<ContextCheckpoint> = emptyList(),
) {
    /** The transcript as drawn: one run per turn, newest first pick wins. */
    val visible: List<StoredMessage>
        get() = Transcript.active(messages, activeRuns)
            .filterNot { liveRunId.isNotEmpty() && it.runId == liveRunId }
}

/**
 * The conversation a run is writing into.
 *
 * Kept apart from [ChatUiState] because those answer two different questions: the screen
 * shows whichever conversation the user last opened, while the run keeps streaming into
 * the one that started it. Fusing the two is what used to lock the drawer for the whole
 * length of a run — the run's own conversation was the only one the UI could hold.
 */
/**
 * A tool call as it happens.
 *
 * The row exists from the moment the call starts, so a run that spends ten seconds inside
 * a shell command shows what it is doing for those ten seconds instead of a gap.
 */
data class LiveTool(
    val name: String,
    val args: String,
    val startedAt: Long,
    val done: Boolean = false,
    val ok: Boolean = true,
    val elapsedMs: Long = 0,
    /**
     * The stored TOOL message once it lands: its output and screenshot are what the
     * expanded row shows, and without this link a live call's picture never drew.
     */
    val message: StoredMessage? = null,
)

/** One piece of the reply being streamed, in the order the engine produced it. */
sealed interface LivePart {
    /**
     * [tokens] and [elapsedMs] belong to this block alone: they are frozen when the run
     * moves on to a tool or to prose, so two thinking blocks in one run never show the
     * same clock — sharing the run-level tally is what made every block read 23.3s.
     */
    data class Thinking(val text: String, val tokens: Int = 0, val elapsedMs: Long = 0) : LivePart
    data class Tools(val calls: List<LiveTool>) : LivePart
    data class Text(val text: String) : LivePart

    /**
     * A line the user handed over while this run was going.
     *
     * The run's own stored rounds are hidden from the transcript for as long as it streams,
     * because this bubble already draws them — and a steer stored as a message folds into
     * exactly the row that is hidden. Without its own live part the line the user just typed
     * had nowhere to appear until the run ended.
     */
    data class Steer(val message: StoredMessage) : LivePart
}
private data class RunSession(
    val chatId: String,
    val title: String,
    val assistantId: String,
    /** The conversation's own model pick; carried so a save cannot drop it. */
    val providerId: String? = null,
    val model: String? = null,
    /** The conversation's own thinking level; carried so a save cannot drop it. */
    val reasoningEffort: String? = null,
    /** The conversation's compression record; carried so a save cannot drop it. */
    val checkpoints: List<ContextCheckpoint> = emptyList(),
    val runId: String,
    val messages: List<StoredMessage>,
    val activeRuns: Map<String, String>,
    val streaming: String = "",
    val reasoning: String = "",
    val status: String = "",
    val runningTool: String? = null,
    /** When the still-open thinking block started, so its clock is its own. */
    val thinkingStartedAt: Long = 0L,
    /**
     * 当前「段」在 [RunSession.parts] 里的起点下标。段＝最后一个工具行/插话行/轮落盘之后的那一段
     * 流。同一段里思考与正文各自只持一个块（交错流不再把一句话劈开）；跨过切点才是新的一段。
     */
    val segmentStart: Int = 0,
    /** What the live bubble draws, in arrival order. */
    val parts: List<LivePart> = emptyList(),
    val error: String? = null,
)

/**
 * The conversation's own tally, for the drawer's status line.
 *
 * Aggregated from the stored usage rows rather than from the engine: the numbers have to
 * survive the run that produced them, and a reopened conversation has no engine state.
 */
data class SessionStats(
    val turns: Int,
    val steps: Int,
    val tokens: Int,
    val cachePercent: Int,
    val perSecond: Double,
    val contextTokens: Int,
    val contextWindow: Int,
) {
    companion object {
        fun of(messages: List<StoredMessage>, contextWindow: Int): SessionStats? {
            val turns = messages.count { it.role == Roles.USER && !it.steer }
            val steps = messages.count { it.role == Roles.TOOL }
            val prompt = messages.sumOf { it.promptTokens }
            val completion = messages.sumOf { it.completionTokens }
            val cached = messages.sumOf { it.cachedTokens }
            // 官方聚合＝ΣdecodeTokens/ΣdecodeMs：分母只数各轮「首字之后→结束」，不含 TTFT 等待。
            // TOOL 消息 elapsedMs=0，天然不进这个和（sumOf 只累加 assistant 轮的 decode 段）。
            val decode = messages.sumOf { Tps.decodeMs(it.elapsedMs, it.firstTokenMs) }
            val tokens = prompt + completion
            // Nothing to say about an empty conversation, or one whose endpoint never
            // reported usage.
            if (tokens == 0 && steps == 0) return null
            val newest = messages.lastOrNull { it.usageKnown }
            return SessionStats(
                turns = turns,
                steps = steps,
                tokens = tokens,
                // The ratio is worked out in Long and rounded, not `cached * 100 / prompt` on
                // Int: that multiplication overflows on a conversation whose cached tokens pass
                // 21,474,837, and the negative percentage it produced was drawn as no line at
                // all — the user saw 「缓存命中」 vanish. Whole percent, because the ring has
                // room for three digits and a decimal point would not be one of them.
                cachePercent = if (prompt > 0) (cached.toLong() * 100.0 / prompt).roundToInt() else 0,
                perSecond = Tps.of(completion, decode),
                contextTokens = newest?.promptTokens ?: 0,
                contextWindow = contextWindow,
            )
        }
    }
}
class ChatViewModel : ViewModel() {

    // The process-wide instance, not a private one: the floating console has to be
    // able to stop and steer the very run this screen started.
    private val engine = Core.engine
    private val gate = Core.gate
    /** Only used to ask an endpoint what windows its models advertise; see [prefetchWindows]. */
    private val llm = LlmClient()
    /** Endpoints already asked in this process, so opening the sheet twice costs one call. */
    private val windowsAsked = mutableSetOf<String>()

    private val _state = MutableStateFlow(ChatUiState())

    /** The message list the revision below was stamped for; touched only by the collector. */
    private var lastPublishedMessages: List<StoredMessage>? = null
    private var messagesRevision = 0L

    /**
     * What the screen collects.
     *
     * [ChatUiState.messagesRevision] is stamped here rather than at the twenty-odd places that
     * write the state: this is the one point every publish goes through, so no path can forget it
     * — a `++` at each assignment site is exactly the bookkeeping that leaves one out, and a
     * missed one shows a bubble that keeps the text the user just edited. The comparison is by
     * reference, which is exact here: the view model never mutates a list in place (`+`, `map` and
     * `filter` all build new ones), so a new list IS a changed transcript.
     *
     * The screen needs it because `remember` compares its keys with `equals`: keyed on the list
     * itself, every recomposition walked every message — and a message carries its screenshot as
     * base64. With this, the drawn rows are rebuilt when the transcript actually changes and at no
     * other time.
     */
    val state: StateFlow<ChatUiState> = _state
        .map { snapshot ->
            if (snapshot.messages === lastPublishedMessages) {
                snapshot
            } else {
                lastPublishedMessages = snapshot.messages
                snapshot.copy(messagesRevision = ++messagesRevision)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, _state.value)

    val conversations: StateFlow<List<Conversation>> = Core.chats.conversations

    /**
     * What the memo below is keyed on: everything that can move the tally.
     *
     * The last row is named by its id and its three counts rather than by the object itself: a
     * message can carry a screenshot as base64, and comparing two of those on every publish
     * would cost more than the walk this memo exists to avoid. The counts are in the key so a
     * row whose usage was written after it first appeared is still caught.
     */
    private data class StatsKey(
        /**
         * Which conversation the tally belongs to.
         *
         * In the key because two conversations can otherwise agree on a shape — two empty ones
         * always do, and a switch between them would then answer the new chat with the old one's
         * numbers (or with the empty tally a moment before its own rows load).
         */
        val chat: String,
        val rows: Int,
        val lastId: String?,
        val lastPrompt: Int,
        val lastCompletion: Int,
        val lastCached: Int,
        val window: Int,
    )

    /** The tally and the key it was built under; see [sessionStats]. */
    private var statsKey: StatsKey? = null
    private var statsValue: SessionStats? = null

    /**
     * The on-screen conversation's tally for the drawer. distinctUntilChanged matters: the
     * state itself changes on every token, while these numbers change once a reply lands.
     *
     * Memoised, because that is the whole cost of this flow. `SessionStats.of` walks the whole
     * conversation six times (four sums and two counts), and the state it is built from
     * republishes twelve times a second while an answer streams — for a long chat that walk ran
     * on the main thread on every one of those publishes, for numbers that only move when a row
     * lands. A row's counts are written before it is emitted (`withUsage` in the engine) and
     * nothing rewrites them afterwards, so the last row plus the row count is enough to know the
     * tally is unchanged.
     */
    val sessionStats: Flow<SessionStats?> = combine(state, Core.settings.flow) { snapshot, settings ->
        // The window of the model this conversation would send with, not the app-wide
        // default's: a chat that picked a smaller-window endpoint must not read its own
        // usage against the wrong denominator.
        val assistant = settings.assistant(snapshot.assistantId) ?: settings.activeAssistant()
        val model = settings.choiceFor(snapshot.providerId, snapshot.model, assistant).model
        val window = settings.contextWindowFor(model)
        val last = snapshot.messages.lastOrNull()
        val key = StatsKey(
            chat = snapshot.conversationId,
            rows = snapshot.messages.size,
            lastId = last?.id,
            lastPrompt = last?.promptTokens ?: 0,
            lastCompletion = last?.completionTokens ?: 0,
            lastCached = last?.cachedTokens ?: 0,
            window = window,
        )
        if (key != statsKey) {
            statsKey = key
            statsValue = SessionStats.of(snapshot.messages, window)
        }
        statsValue
    }.distinctUntilChanged()

    /** The per-conversation run-state watcher; replaced whenever the display switches. */
    private var stateWatcher: Job? = null

    /**
     * The conversation whose reply the speaker is reading out, or null once it was
     * stopped. The speaker is a single app-wide queue, so the stop button has to know
     * whether the voice it would cut belongs to the chat it is drawn in.
     */
    private var speakingChatId: String? = null

    /**
     * The last failure per conversation, for the runs that ended while another chat was on
     * screen. The run's copy is gone by then and nothing else remembers why it stopped, so
     * the reason is held here until that conversation is opened again.
     */
    private val lastErrors = mutableMapOf<String, String>()

    /**
     * The conversation this process last showed, remembered so a new screen instance can adopt
     * it before its first frame instead of waiting for the marker file to be read.
     */
    private var lastOpenedId: String? = null

    /**
     * Text waiting to be published, per conversation; see [handleEvent] and [DELTA_PUBLISH_MS].
     *
     * Two buffers, not one: the answer and the reasoning land in different parts of the live
     * bubble, and a single buffer would put half a thought into the answer.
     */
    private val textBuffers = mutableMapOf<String, StringBuilder>()
    private val reasoningBuffers = mutableMapOf<String, StringBuilder>()
    private val lastPublishAt = mutableMapOf<String, Long>()

    /**
     * Queued lines per conversation, oldest first.
     *
     * Kept here rather than only in the on-screen state so a queue survives a look at
     * another chat: the run it belongs to keeps going while the user is away, and coming
     * back must still show what is waiting — and still offer to retract it.
     */
    private val steers = mutableMapOf<String, List<PendingSteer>>()

    /**
     * Turns typed while a run was going in 排队 mode, waiting for it to finish.
     *
     * The other half of the same question — see [PendingTurn]: a line is either an interruption or
     * the next turn, and this is where the next turn waits. Keyed by conversation so a turn queued
     * in one chat cannot be sent into another, and drained when that chat's run reports itself
     * finished.
     */
    private val pendingTurns = mutableMapOf<String, List<PendingTurn>>()

    /**
     * Conversations with a queued turn currently being handed to [send].
     *
     * The two drain hooks (the run-state watcher and the run's own `finally`) can fire for the same
     * run end, and the first of them starts a run whose end can fire the second. Re-entrancy is what
     * this closes: a turn is taken off the queue *before* it is sent, so a second entry finds the
     * queue shorter and cannot deliver the same line twice.
     */
    private val draining = mutableSetOf<String>()

    /**
     * How many attempts the run in flight has spent on retries; 0 when none.
     *
     * Set by [AgentEvent.Retrying] and read when the run finally fails, so the give-up line can say
     * how many tries it took. A counter rather than a flag: "已重试 2 次" is the number the user
     * needs, and it is the same number the failure text carries.
     */
    private var retriesThisRun = 0

    /**
     * 这一轮被中断的原因，写进落盘那条半截回答的尾部（用户：「中断提示不要这么简单，由 xxx 原因，
     * 对话中断」）。[stop] 置「用户停止」；引擎自己取消（轮数上限、网关结束等）用默认文案。
     * 消费一次就复位，避免下一次中断顶着上一次的原因。
     */
    private var cancelReason = "任务被停止"

    /**
     * One line about retries, appended to `files/retry-trace.log`.
     *
     * **Why here and not inside `LlmClient.complete`.** The retry decision is made in the client, but
     * the client has no `Context` — it is deliberately a plain OkHttp class with no Android
     * dependency — and `AgentEngine` has none either (it takes everything by injection and does not
     * reference `Core`). The view model is the first place in the chain that has both a `Context`
     * and both ends of the story: [AgentEvent.Retrying] when an attempt is repeated, and
     * [AgentEvent.Failed] when they run out. The give-up line is therefore driven by
     * [retriesThisRun], which [AgentEvent.Retrying] sets — not by matching text in the message.
     *
     * Written off the main thread, and never allowed to fail the run: a diagnostic that can break
     * what it is diagnosing is worse than no diagnostic.
     */
    private fun retryTrace(line: String) {
        val context = runCatching { Core.appContext }.getOrNull() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val file = java.io.File(context.filesDir, RETRY_TRACE_FILE)
                if (file.length() > RETRY_TRACE_MAX_BYTES) {
                    val tail = file.readText().takeLast(RETRY_TRACE_KEEP_CHARS)
                    file.writeText(tail)
                }
                file.appendText("${System.currentTimeMillis()} $line\n")
            }
        }
    }

    /**
     * 每个会话**自己**的板上快照：chatId → 该会话的 run 最近一次 todo 工具事件时文件里的清单。
     *
     * **文件（TodoStore）仍是唯一事实源**：这里只是事件之间的显示缓存，每次 todo 工具事件都从
     * 文件重读、从不参与写（写只走 TodoTools）。按会话持有是因为用户报了反过来的病：A 会话的
     * `todo_write` 事件到达时用户在 B 会话，旧的全局一份把 A 的板子画到了 B 屏幕上（「其它会话的
     * 任务队列出现在另一个会话」）。缓存落后于别的会话的写入正是想要的隔离：一个会话只画自己
     * 的 run 写的计划。开跑时清空自己那格（不继承上一轮的计划），与旧的全局版同一个不变量。
     */
    private val todoBoards = mutableMapOf<String, List<TodoItem>>()

    /**
     * One live copy per running conversation, keyed by chat id. A single slot is what
     * let two parallel runs pour into one bubble: the later start overwrote the
     * earlier run's copy, and the earlier run's events then landed in the later one's
     * transcript. The engine keys its handles the same way; this map is the view
     * model's half of that contract.
     *
     * Concurrent on purpose: stream events reach [handleEvent] on the OkHttp reader
     * thread while the main thread removes and reads the same entry.
     */
    private val sessions = ConcurrentHashMap<String, RunSession>()

    /** One coroutine per running conversation, so stopping one leaves the others alone. */
    private val jobs = mutableMapOf<String, Job>()

    private val _drafts = MutableStateFlow<Map<String, Draft>>(emptyMap())

    /**
     * The decoded attachment per conversation. The draft keeps only its uri and name
     * (what a restore can mean); the decoded bitmap lives here for the session, so a
     * chip keeps its thumbnail across a drawer switch without re-decoding.
     */
    private val attachmentCache = mutableMapOf<String, MutableList<Attachment>>()

    /** 追加一张图/一个文件到这条会话的附件列（多附件：不再覆盖上一张）。 */
    fun addAttachment(chatId: String, attachment: Attachment) {
        attachmentCache.getOrPut(chatId) { mutableListOf() } += attachment
    }

    /** 删掉附件列里第 [index] 个；删空即移除整条缓存。 */
    fun removeAttachmentAt(chatId: String, index: Int) {
        val list = attachmentCache[chatId] ?: return
        if (index !in list.indices) return
        list.removeAt(index)
        if (list.isEmpty()) attachmentCache.remove(chatId)
    }

    fun cacheAttachment(chatId: String, attachment: Attachment?) {
        if (attachment == null) attachmentCache.remove(chatId)
        else attachmentCache[chatId] = mutableListOf(attachment)
    }

    fun attachmentFor(chatId: String): Attachment? = attachmentCache[chatId]?.firstOrNull()

    /** 这条会话当前的全部附件（顺序＝添加顺序＝发送顺序）。 */
    fun attachmentsFor(chatId: String): List<Attachment> = attachmentCache[chatId] ?: emptyList()

    /** Writes [draft] as the conversation's unsent text; the display follows at once. */
    fun onDraftChange(chatId: String, draft: Draft) {
        _drafts.value = _drafts.value + (chatId to draft)
    }

    /** Sending empties the conversation's draft; nothing else touches it. */
    fun clearDraft(chatId: String) {
        _drafts.value = _drafts.value + (chatId to Draft())
        attachmentCache.remove(chatId)
        // And straight onto the screen when that chat is the one being shown. The collector above
        // would do it a hop later, and a field that keeps its text for even one frame after the user
        // pressed send reads as "it did not send" — which is how the queue was reported twice.
        if (_state.value.conversationId == chatId && _state.value.draft != Draft()) {
            _state.update { it.copy(draft = Draft()) }
        }
    }

    init {
        // Adopted before the first frame when this process already knows which conversation
        // the user was in — that is the case where nothing needs reading, so nothing may
        // flash. With no memory of it the coroutine below decides, and until then the screen
        // shows the skeleton rather than a conversation the user is not in. The persona is
        // left blank on purpose: an empty id means "follow the app-wide default", which is
        // exactly what a fresh state wants, and resolving it needs a suspend call this
        // constructor cannot make.
        lastOpenedId?.let { id ->
            Core.chats.conversations.value.firstOrNull { it.id == id }?.let { cached ->
                _state.value = cached.toUiState("").copy(loading = false)
            }
        }
        // The engine keys its runs by conversation, so this screen subscribes to the
        // state of whichever conversation is on display: a run elsewhere neither
        // locks this screen nor narrates itself into its status line.
        viewModelScope.launch {
            state.map { it.conversationId }.distinctUntilChanged().collect { chatId ->
                // One watcher per displayed conversation: switching cancels the old
                // subscription before the new one starts, so two chats never feed one
                // status line.
                stateWatcher?.cancel()
                if (chatId.isEmpty()) return@collect
                stateWatcher = viewModelScope.launch {
                    // Only a real Running → not-Running transition may drain the 排队 queue. Asking
                    // on every emission is what let an unrelated publish touch the queue — and this
                    // flow is not quiet: the run publishes a new Running(stats) every 120ms, so the
                    // collector runs constantly while a run is going.
                    var wasRunning = false
                    Core.engine.stateOf(chatId).collect { run ->
                    when (run) {
                        is RunState.Running -> _state.update {
                            it.copy(runningHere = true, stats = run.stats)
                        }

                        // Back to typing the moment the run is stopped, without waiting
                        // for the stream to wind down on its own. What already arrived
                        // is saved first: the run is over either way, and only this
                        // copy has it.
                        is RunState.Cancelled -> {
                            // Cancelled is terminal and the engine never follows it with
                            // Idle, so the screen has to be released here — otherwise the
                            // composer keeps its stop button and the conversation stays
                            // locked until the user switches away and back.
                            // stats **不**在这里清：清掉后圆环回落到 newestStored＝压缩前那一轮的
                            // 上报值（用户截图里的 90%），正是「一中断就显示之前的」。保留 stats 时
                            // 圆环继续显示本轮实测的 currentPrompt（含压缩后的真实大小）。
                            _state.update { it.copy(runningHere = false) }
                            sessions[chatId]?.let { live ->
                                viewModelScope.launch { saveSession(live) }
                                endSession(chatId, "已停止")
                            }
                        }

                        is RunState.Idle -> _state.update {
                            // **stats 不清**（用户：「进度条那些怎么只有 run 才显示」）：圆环面板的三行
                            // 占比（系统提示词/工具定义/对话消息）读的就是它，清掉后 run 一结束三行
                            // 就消失。它是「这段上下文由什么构成」的最后一次实测，空闲时照旧显示才有
                            // 意义；下一次 run 会覆盖它。runningHere 照旧落回 false。
                            it.copy(runningHere = false)
                        }

                        else -> _state.update { it.copy(runningHere = false) }
                    }
                    // 排队 mode: the turn the user typed during that run is the *next* turn, and
                    // this is the first moment it can be one. Gated on the transition rather than on
                    // the state, so only the end of a run tries — see `wasRunning`.
                    val running = run is RunState.Running
                    if (wasRunning && !running) drainPendingTurns(chatId)
                    wasRunning = running
                }
                }
            }
        }
        viewModelScope.launch {
            val settings = Core.settings.current()
            val assistantId = settings.activeAssistant()?.id.orEmpty()
            // Only the cold path reads the files: when the conversations are already in
            // memory the state above is the real one, and loading them again would only
            // make the screen wait for something it already has.
            if (Core.chats.conversations.value.isEmpty()) Core.chats.load()
            val conversations = Core.chats.conversations.value
            // The conversation the user was last in — and only if it is still there: the
            // marker can outlive its conversation (deleted from another entry point), and
            // opening a chat that no longer exists would leave the screen on nothing. The
            // in-memory id is preferred because it is what this process last showed; the
            // file is the cold-start answer.
            val reopened = (lastOpenedId ?: Core.chats.readLastOpened())?.let { id ->
                conversations.firstOrNull { it.id == id }
            }
            val existing = reopened ?: conversations.firstOrNull()
            if (existing == null) {
                val created = Core.chats.create()
                _state.value = ChatUiState(
                    conversationId = created.id,
                    title = created.title,
                    assistantId = assistantId,
                    loading = false,
                )
            } else {
                _state.value = existing.toUiState(assistantId).copy(loading = false)
            }
            // Recorded here as well as on a switch: a session that only ever read one
            // conversation never wrote the marker, and the next launch would fall back to
            // "most recently written" — which is not where the user was.
            rememberOpened(_state.value.conversationId)
            // Same recovery on a cold start: the run that was streaming when the process
            // died has no session any more, only its draft.
            mergeLiveDraft(_state.value.conversationId)
        }
        // The draft follows the displayed conversation: switching restores that
        // conversation's own unsent text without any action at the switch site.
        viewModelScope.launch {
            combine(state.map { it.conversationId }, _drafts) { id, all -> id to (all[id] ?: Draft()) }
                .distinctUntilChanged()
                .collect { (id, draft) ->
                    if (_state.value.conversationId == id && _state.value.draft != draft) {
                        _state.update { it.copy(draft = draft) }
                    }
                }
        }
    }

    fun newConversation() {
        viewModelScope.launch {
            val settings = Core.settings.current()
            val created = Core.chats.create()
            _state.value = ChatUiState(
                conversationId = created.id,
                title = created.title,
                assistantId = settings.activeAssistant()?.id.orEmpty(),
                loading = false,
            )
            // A brand-new conversation is where the user is now: without this the next
            // launch would reopen whatever was written last, not what they left open.
            rememberOpened(created.id)
        }
    }

    /**
     * Remembers the conversation the user is in, for the next launch.
     *
     * A file of its own rather than a timestamp on the conversation: a transcript can be
     * megabytes, and rewriting one just to say "this is where I was" would be absurd — which
     * is why "touch the conversation so it sorts first" was rejected. Fire and forget: the
     * switch must not wait for a disk write.
     */
    private fun rememberOpened(chatId: String) {
        if (chatId.isEmpty()) return
        lastOpenedId = chatId
        viewModelScope.launch { Core.chats.saveLastOpened(chatId) }
    }

    fun openConversation(conversation: Conversation) {
        // Anything the conversation on screen was still holding is published before the
        // display moves: a buffered tail would otherwise land in the conversation being
        // opened, or be lost with the one being left.
        flushDeltas(_state.value.conversationId)
        val live = sessions[conversation.id]
        // Read-and-clear: a failure that landed while another conversation was on screen
        // is reported the moment its own chat comes back.
        val failure = lastErrors.remove(conversation.id)
        _state.value = if (live != null) {
            // Coming back to the conversation a run owns: pick up what it has streamed
            // since, rather than the older snapshot on disk.
            ChatUiState(
                conversationId = live.chatId,
                title = live.title,
                messages = live.messages,
                activeRuns = live.activeRuns,
                assistantId = live.assistantId,
                providerId = live.providerId,
                model = live.model,
                reasoningEffort = live.reasoningEffort,
                checkpoints = live.checkpoints,
                streaming = live.streaming,
                reasoning = live.reasoning,
                status = live.status,
                runningHere = true,
                // A conversation the screen already has is not "still opening".
                loading = false,
                // The tool it is inside right now: without it the row that says what the
                // run is doing went blank on every switch back.
                runningTool = live.runningTool,
                liveParts = live.parts,
                liveRunId = live.runId,
                error = failure,
                // The queue belongs to the run, not to the screen: coming back to it must
                // still show what is waiting.
                pendingSteers = steers[conversation.id].orEmpty(),
                // And so does the board: it is read from disk on tool events, so a state built
                // here would otherwise be the one place it is missing. 只取这个会话自己那一格。
                todos = todoBoards[conversation.id].orEmpty(),
            )
        } else {
            // A conversation that never picked a persona keeps the one already on screen,
            // which was seeded from the settings when this screen was created.
            conversation.toUiState(_state.value.assistantId).let {
                it.copy(
                    error = failure,
                    // A run that ended with lines still queued keeps them: they are the
                    // user's words, and the rows say they never went out.
                    pendingSteers = steers[conversation.id].orEmpty(),
                )
            }
        }
        // A run that was killed left its half-written answer in a draft file: it belongs to
        // this conversation, so it is picked up the moment the conversation is.
        viewModelScope.launch { mergeLiveDraft(conversation.id) }
        // Where the user is, for the next launch.
        rememberOpened(conversation.id)
    }

    /**
     * Remembers the model this conversation picked — and only this conversation's copy of
     * it. The choice belongs to the chat it was made in, so two conversations can run
     * different endpoints side by side while the app-wide default stays the settings
     * page's to own.
     */
    fun chooseModel(providerId: String, model: String) {
        val chatId = _state.value.conversationId
        if (chatId.isEmpty()) return
        val shown = _state.value
        _state.update { it.copy(providerId = providerId, model = model) }
        // The run's own copy carries the pick as well, or the next save would write the
        // conversation back without it and the run after that would fall back to the
        // persona.
        val live = sessions[chatId]
        live?.let { putSession(it.copy(providerId = providerId, model = model)) }
        viewModelScope.launch {
            // The one save path, so the pick rides along with everything else: it keeps
            // createdAt, derives the title and falls back to what is already on disk.
            val title = save(
                chatId = chatId,
                title = live?.title ?: shown.title,
                messages = live?.messages ?: shown.messages,
                assistantId = live?.assistantId ?: shown.assistantId,
                activeRuns = live?.activeRuns ?: shown.activeRuns,
                providerId = providerId,
                model = model,
            ) ?: return@launch
            if (title != shown.title) {
                _state.update { it.copy(title = title) }
                sessions[chatId]?.let { current -> putSession(current.copy(title = title)) }
            }
        }
    }

    /**
     * Remembers the thinking level this conversation picked — and only this conversation's
     * copy of it. Same rule as [chooseModel]: the level decides how long every answer in
     * this chat takes and what it costs, so it belongs to the chat it was chosen in rather
     * than to the app-wide default every other chat also reads.
     */
    fun chooseReasoning(level: String) {
        val chatId = _state.value.conversationId
        if (chatId.isEmpty()) return
        val shown = _state.value
        _state.update { it.copy(reasoningEffort = level) }
        val live = sessions[chatId]
        live?.let { putSession(it.copy(reasoningEffort = level)) }
        viewModelScope.launch {
            save(
                chatId = chatId,
                title = live?.title ?: shown.title,
                messages = live?.messages ?: shown.messages,
                assistantId = live?.assistantId ?: shown.assistantId,
                activeRuns = live?.activeRuns ?: shown.activeRuns,
                providerId = live?.providerId ?: shown.providerId,
                model = live?.model ?: shown.model,
                reasoningEffort = level,
            )
        }
    }

    /**
     * Fills in the context windows the endpoint advertises for its models.
     *
     * Called when the model sheet opens — the moment the user is looking at models — rather
     * than at app start, so no request goes out for someone who never opens the picker, and
     * at most one per endpoint per process.
     *
     * Values the user set by hand are never touched: [withUpstreamDefaults] only fills gaps,
     * which is what keeps 「I raised this window myself」 from being undone by a refresh. A
     * value already raised stays raised.
     */
    fun prefetchWindows() {
        viewModelScope.launch {
            val settings = Core.settings.current()
            // Every endpoint, not just the one this conversation happens to use: the ring and
            // the settings page both read windows by model id, and a provider the user has not
            // switched to yet would otherwise stay 「unlimited」 forever — which is exactly the
            // complaint about a newly added provider. Still at most one probe per endpoint per
            // process, and still only when the picker is opened.
            val gathered = mutableMapOf<String, Int>()
            for (provider in settings.providers) {
                if (provider.baseUrl.isBlank()) continue
                val key = provider.baseUrl.trimEnd('/') + "|" + provider.apiKey
                if (!windowsAsked.add(key)) continue
                val endpoint = settings.copy(
                    baseUrl = provider.baseUrl,
                    apiKey = provider.apiKey,
                    // /models does not care which model the endpoint copy names; give it one
                    // the provider actually knows so the request shape stays valid.
                    model = provider.enabled.firstOrNull() ?: provider.models.firstOrNull().orEmpty(),
                )
                runCatching { llm.listModelDetails(endpoint) }
                    .onSuccess { details ->
                        for (model in details) {
                            model.contextLength?.let { gathered[model.id] = it }
                        }
                    }
                    // A failed probe is not worth a word on screen: the window simply stays as
                    // it was, and the settings page can be asked to fetch again.
                    .onFailure { windowsAsked.remove(key) }
            }
            if (gathered.isEmpty()) return@launch
            Core.settings.update { stored ->
                stored.copy(modelContexts = stored.modelContexts.withUpstreamDefaults(gathered))
            }
        }
    }

    /**
     * 改名：写盘，并让屏幕上这一份跟着走。
     *
     * 不能只改仓库：这一屏的标题是 [_state] 里的一份拷贝，而每轮结束 [persistDisplayed] 都会把
     * 那份拷贝写回去（[save] 里 `derived = title` 取的就是它）—— 只改仓库的话，用户给**当前
     * 打开的这个会话**改名，下一轮就会被悄悄改回旧标题。别的会话没有这个问题，但两条路都从
     * 这里走，调用方就不必自己判断「改的是不是当前这一个」。
     */
    fun renameConversation(id: String, title: String) {
        val clean = title.trim()
        if (id.isEmpty() || clean.isEmpty()) return
        viewModelScope.launch {
            Core.chats.rename(id, clean)
            if (_state.value.conversationId == id) _state.update { it.copy(title = clean) }
            sessions[id]?.let { putSession(it.copy(title = clean)) }
        }
    }

    fun deleteConversation(conversation: Conversation) {
        // Published before the conversation goes: a buffered tail is part of an answer that
        // may still be streaming into a chat that is about to disappear from the list.
        flushDeltas(conversation.id)
        // The run owns its conversation until it ends: deleting it would only have the
        // next save write it straight back.
        if (sessions.containsKey(conversation.id)) return
        // Nothing left to hand a queued line to.
        steers.remove(conversation.id)
        viewModelScope.launch {
            Core.chats.delete(conversation.id)
            if (conversation.id != _state.value.conversationId) return@launch
            val settings = Core.settings.current()
            val next = Core.chats.conversations.value.firstOrNull()
            _state.value = if (next == null) {
                val created = Core.chats.create()
                ChatUiState(
                    conversationId = created.id,
                    title = created.title,
                    assistantId = settings.activeAssistant()?.id.orEmpty(),
                    loading = false,
                )
            } else {
                next.toUiState(settings.activeAssistant()?.id.orEmpty())
            }
            // The deleted conversation may have been the marker's; the one now on screen is
            // where the user is.
            rememberOpened(_state.value.conversationId)
        }
    }

    // ---------------------------------------------------------------- assistants

    /**
     * Makes [assistant] the face of this conversation — this one only. The app-wide
     * default is a separate decision ([setDefaultAssistant]): a persona tried out in one
     * chat must not silently become what every other chat starts as.
     */
    fun useAssistant(assistant: Assistant) {
        editDisplayed { it.copy(assistantId = assistant.id) }
        viewModelScope.launch { persistDisplayed() }
    }

    /** Makes [assistant] what new conversations start as, leaving this one as it is. */
    fun setDefaultAssistant(assistant: Assistant) {
        viewModelScope.launch {
            Core.settings.update { it.copy(activeAssistantId = assistant.id) }
        }
    }

    fun saveAssistant(assistant: Assistant) {
        viewModelScope.launch {
            // Whether this was a new persona or an edit decides what else may move: saving
            // a brand-new one is an explicit "use this", editing one is not.
            var created = false
            Core.settings.update { settings ->
                val exists = settings.assistants.any { it.id == assistant.id }
                created = !exists
                val list = if (exists) {
                    settings.assistants.map { if (it.id == assistant.id) assistant else it }
                } else {
                    settings.assistants + assistant
                }
                settings.copy(
                    assistants = list,
                    // A brand-new persona becomes the default — that is what saving it
                    // means the first time. Editing an existing one must not move the
                    // default out from under every other conversation.
                    activeAssistantId = if (exists) settings.activeAssistantId else assistant.id,
                )
            }
            // An edit is not a switch: renaming a persona must not quietly change what the
            // conversation on screen is speaking as. Switching is [useAssistant].
            if (created) {
                editDisplayed { it.copy(assistantId = assistant.id) }
                persistDisplayed()
            }
        }
    }

    fun deleteAssistant(assistant: Assistant) {
        viewModelScope.launch {
            var removed = false
            Core.settings.update { settings ->
                // The app always needs one persona to fall back on.
                if (settings.assistants.size <= 1) return@update settings
                removed = true
                val list = settings.assistants.filterNot { it.id == assistant.id }
                settings.copy(
                    assistants = list,
                    activeAssistantId = if (settings.activeAssistantId == assistant.id) {
                        list.first().id
                    } else {
                        settings.activeAssistantId
                    },
                )
            }
            // The persona's pictures go with the persona, and only if it really went: the sweep
            // is by id, so a picture picked and then abandoned by closing the editor — a file
            // settings never heard about — is taken by the same call. Nothing else would ever
            // name it again.
            if (removed) AssistantAvatarStore.deleteAllFor(Core.appContext, assistant.id)
            val settings = Core.settings.current()
            val next = settings.assistant(_state.value.assistantId) ?: settings.activeAssistant()
            editDisplayed { it.copy(assistantId = next?.id.orEmpty()) }
            persistDisplayed()
        }
    }

    // -------------------------------------------------------------------- turns

    /** Returns false when the message was dropped, so the caller keeps the input text. */
    fun send(text: String, imagesBase64: List<String> = emptyList()): Boolean {
        val body = text.trim()
        if (body.isEmpty() && imagesBase64.isEmpty()) return false
        if (_state.value.conversationId.isEmpty()) return false
        // No global lock: the engine refuses a second run in the same conversation
        // (two runs would interleave into one transcript), and every other
        // conversation is free to start its own run right now.
        if (Core.engine.isRunning(_state.value.conversationId)) return false

        val user = StoredMessage(
            id = UUID.randomUUID().toString(),
            role = Roles.USER,
            text = body,
            imagesBase64 = imagesBase64,
        )
        val fresh = UUID.randomUUID().toString()
        _state.update {
            it.copy(
                messages = it.messages + user,
                activeRuns = it.activeRuns + (user.id to fresh),
                streaming = "",
                reasoning = "",
                status = "正在思考中",
                error = null,
            )
        }
        start(fresh, _state.value.messages)
        clearDraft(_state.value.conversationId)
        return true
    }

    /** Answers the last question again, keeping the old reply as a branch. */
    fun regenerate() {
        if (Core.engine.isRunning(_state.value.conversationId)) return
        val snapshot = _state.value
        val active = Transcript.active(snapshot.messages, snapshot.activeRuns)
        val question = active.lastOrNull { it.role == Roles.USER } ?: return
        val prompt = Transcript.withoutLastAnswer(active)
        val fresh = UUID.randomUUID().toString()
        _state.update {
            it.copy(
                activeRuns = it.activeRuns + (question.id to fresh),
                streaming = "",
                reasoning = "",
                status = "正在重新回答…",
                error = null,
            )
        }
        start(fresh, prompt)
    }

    /** Draws another candidate answer for [turnId]; the transcript itself is untouched. */
    fun switchBranch(turnId: String, runId: String) {
        if (_state.value.activeRuns[turnId] == runId) return
        editDisplayed { it.copy(activeRuns = it.activeRuns + (turnId to runId)) }
        viewModelScope.launch { persistDisplayed() }
    }

    /**
     * Rewrites a message in place without asking the model anything. This is how a
     * typo gets fixed, or a long reply gets trimmed down to the part worth keeping.
     *
     * The edit lands as a *new* list with a *new* message instance (`map` + `copy`), which is what
     * makes the screen redraw it: its derivations are keyed on [ChatUiState.messagesRevision], and
     * the revision moves when the list is replaced — by reference, so an edit that keeps every id
     * and the same row count still counts as a change. An in-place mutation here would not.
     */
    fun editMessage(id: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        editDisplayed { snapshot ->
            val index = snapshot.messages.indexOfFirst { it.id == id }
            snapshot.copy(
                messages = snapshot.messages.map { message ->
                    if (message.id == id) message.copy(text = body, edited = true) else message
                },
                // A summary that covers the edited message no longer describes what the model
                // will read, and a stale one would quietly hand it the text from before the
                // fix — exactly the fact the user came back to correct. Only the records that
                // end before the edit are still accurate.
                checkpoints = if (index < 0) {
                    snapshot.checkpoints
                } else {
                    snapshot.checkpoints.filterNot { checkpoint ->
                        snapshot.messages.indexOfFirst { it.id == checkpoint.toMessageId } >= index
                    }
                },
            )
        }
        viewModelScope.launch { persistDisplayed() }
    }

    /**
     * Rewrites the user message [id] and asks again from there. Everything the model
     * said afterwards is dropped: those answers belong to a question that no longer
     * exists, so keeping them would hang them off the wrong prompt.
     */
    fun editAndResend(id: String, text: String) {
        if (Core.engine.isRunning(_state.value.conversationId)) return
        val body = text.trim()
        if (body.isEmpty()) return
        val snapshot = _state.value
        val active = Transcript.active(snapshot.messages, snapshot.activeRuns)
        val index = active.indexOfFirst { it.id == id }
        if (index < 0) return
        val kept = active.take(index) + active[index].copy(text = body, edited = true)
        val fresh = UUID.randomUUID().toString()
        _state.update {
            it.copy(
                messages = kept,
                // Runs whose messages just disappeared would only leak memory.
                activeRuns = it.activeRuns.filterKeys { key -> kept.any { row -> row.id == key } } + (id to fresh),
                streaming = "",
                reasoning = "",
                status = "正在重新回答…",
                error = null,
            )
        }
        start(fresh, kept)
    }

    fun stop() {
        // Only this conversation's reading: the speaker is one app-wide queue, so an
        // unconditional stop here used to cut short a reply another chat was reading out.
        val chatId = _state.value.conversationId
        // 中断原因（用户：「中断提示不要这么简单，由 xxx 原因，对话中断」）：由这里落下的停止
        // 标成「用户停止」，落盘那条半截回答的尾部就写得出原因；引擎自己取消的轮次用默认文案。
        cancelReason = "用户停止"
        if (speakingChatId == chatId) {
            Core.speaker.stop()
            speakingChatId = null
        }
        // This conversation's run, by id: the engine keeps one handle per chat, and
        // stopping whatever happens to be newest would cut a run this screen does
        // not even show. Its approval prompt, if any, is answered "refused" for this
        // run only — another conversation's prompt stays on screen.
        gate.cancelPending(chatId)
        Core.engine.cancel(chatId)
        jobs.remove(chatId)?.cancel()
    }

    /**
     * Takes a line typed while a run is going, and routes it by the mode the run was started with.
     *
     * A line typed while a run is going belongs to **the engine**, which is the only place that
     * knows what 插话模式 means for this run: 立即 cuts the round and is read now, 排队 is carried at
     * the next round boundary and shown in the queue above the composer. The view model does not
     * second-guess it — an earlier version kept its own local queue for 排队 and held the line until
     * the run ended, which swallowed the user's message (nothing sent, nothing shown, no run left to
     * deliver it) and is exactly the 「运行时我插话怎么发不出去了」 that came back from the device.
     *
     * False means "there is nothing to steer" — no run, or a blank line — and the composer falls
     * back to sending it as an ordinary turn.
     */
    fun steer(text: String, imagesBase64: List<String> = emptyList()): Boolean {
        val body = text.trim()
        val chatId = _state.value.conversationId
        if (body.isEmpty() && imagesBase64.isEmpty()) return false
        if (!Core.engine.isRunning(chatId)) return false
        if (!Core.engine.steersImmediately(chatId)) {
            // 排队: the line is the *next turn*, not an interruption. It waits here, is drawn above
            // the composer at once (see publishPendingTurns), and goes out through [send] when this
            // run ends. Its own id, because the row offers the same three actions a queued steer
            // does — 立即插入 (hand it to the run in flight), 编辑 (take it back to the field) and
            // 删除 — and all three have to name the line they act on.
            pendingTurns[chatId] = pendingTurns[chatId].orEmpty() +
                PendingTurn(UUID.randomUUID().toString().take(8), body, imagesBase64)
            publishPendingTurns(chatId)
            // The field is cleared here rather than left to the caller. The composer does clear on
            // a true return, but the device is the verdict on trusting that: the line appeared in
            // the queue and the text stayed in the box, so from where the user sat nothing had been
            // sent at all. Queuing is an acceptance, and an acceptance owns the field it took the
            // line from — the 立即 branch below does the same, for the same reason. Writing Draft()
            // twice is harmless: the composer's own clear is idempotent.
            clearDraft(chatId)
            return true
        }
        val id = UUID.randomUUID().toString().take(8)
        if (!Core.engine.steer(chatId, id, body, imagesBase64 = imagesBase64)) return false
        // No TouchFx.noteActivity here on purpose. The edge light means "the agent is operating the
        // phone right now" — UI automation — and a line the user typed is not that. A steer that
        // kept the light alive would say the phone is being driven while nothing is driving it. The
        // round's own model/tool activity already lights it and the round's end already puts it out.
        steers[chatId] = steers[chatId].orEmpty() + PendingSteer(id, body, imagesBase64)
        publishSteers(chatId)
        clearDraft(chatId)
        return true
    }

    /** Sends a queued line now, cutting the round in flight so the model sees it at once. */
    fun promoteSteer(id: String) {
        val chatId = _state.value.conversationId
        val queued = steers[chatId].orEmpty().firstOrNull { it.id == id } ?: return
        if (Core.engine.promote(chatId, id)) return
        // No round left to interrupt: the line goes out as an ordinary message instead of
        // being silently dropped — but only because the user asked for it now.
        retractSteer(id)
        if (send(queued.text, queued.imagesBase64)) return
        // The send was refused after all — another run in this conversation got there first. The
        // line has already been taken off the board, so putting it back is the only way it is not
        // lost without a word: the row returns and the user can send it again.
        steers[chatId] = steers[chatId].orEmpty() + queued
        publishSteers(chatId)
    }

    /** Drops a queued line before the model ever reads it. */
    fun retractSteer(id: String) {
        val chatId = _state.value.conversationId
        Core.engine.retract(chatId, id)
        steers[chatId] = steers[chatId].orEmpty().filterNot { it.id == id }
        publishSteers(chatId)
    }

    /**
     * Drops one waiting 排队 turn.
     *
     * By id: the row carries three actions and each has to name the line it acts on, so the turn
     * needs the same kind of handle a queued steer has.
     */
    fun retractTurn(chatId: String, id: String) {
        pendingTurns[chatId] = pendingTurns[chatId].orEmpty().filterNot { it.id == id }
        publishPendingTurns(chatId)
    }

    /**
     * 「立即插入」 on a waiting turn: it stops being the next turn and becomes an interruption of the
     * run in flight — the 立即 path, taken deliberately, one line at a time.
     *
     * The line is taken out of the queue first, so a run that ends in the same instant cannot also
     * send it as the next turn. If the engine refuses (nothing running any more, or the line was
     * already retracted), it goes back to the queue rather than disappearing.
     */
    fun promoteTurn(chatId: String, id: String) {
        val queued = pendingTurns[chatId].orEmpty().firstOrNull { it.id == id } ?: return
        pendingTurns[chatId] = pendingTurns[chatId].orEmpty().filterNot { it.id == id }
        publishPendingTurns(chatId)
        if (Core.engine.steer(chatId, id, queued.text, imagesBase64 = queued.imagesBase64)) {
            // It is an interruption now, so it is drawn as one: the same bubble a steer gets.
            steers[chatId] = steers[chatId].orEmpty() + PendingSteer(id, queued.text, queued.imagesBase64)
            publishSteers(chatId)
            return
        }
        pendingTurns[chatId] = pendingTurns[chatId].orEmpty() + queued
        publishPendingTurns(chatId)
    }

    /** Mirrors one conversation's queue onto the screen, when that is the one on screen. */
    private fun publishSteers(chatId: String) {
        if (_state.value.conversationId != chatId) return
        _state.update { it.copy(pendingSteers = steers[chatId].orEmpty()) }
    }

    /** The same, for the 排队 turns waiting to become the next run. */
    private fun publishPendingTurns(chatId: String) {
        if (_state.value.conversationId != chatId) return
        _state.update { it.copy(pendingTurns = pendingTurns[chatId].orEmpty()) }
    }

    /**
     * Starts the next 排队 turn, if the run that was blocking it has really finished.
     *
     * **Taken off the queue first, sent second — and that order is the fix.** An earlier version
     * sent first and dropped the head only when `send` returned true, so any path that left the head
     * in place (a refusal while the old run was still registered, or a second hook racing the first)
     * meant the line was delivered again at the end of the next run, and the one after that: the
     * assistant answered, and then the same line was sent again, for as long as the user watched.
     * Sending twice is the worse failure of the two — a dropped line is visible, because its row is
     * still on screen and the user can send it again, while a repeated one looks like the model
     * talking to itself.
     *
     * So: take it, try to send, and put it back **only** if the send was refused (a run still
     * registered, or no conversation on screen to send into). [draining] keeps two hooks from
     * getting in each other's way; a refused turn is restored at the head, in order.
     */
    private fun drainPendingTurns(chatId: String) {
        if (!draining.add(chatId)) return
        try {
            val next = pendingTurns[chatId].orEmpty().firstOrNull() ?: return
            if (_state.value.conversationId != chatId) return
            pendingTurns[chatId] = pendingTurns[chatId].orEmpty().drop(1)
            publishPendingTurns(chatId)
            if (send(next.text, next.imagesBase64)) return
            // Refused: back to the head, where the next trigger will try again. Nothing is lost and
            // nothing was sent.
            pendingTurns[chatId] = listOf(next) + pendingTurns[chatId].orEmpty()
            publishPendingTurns(chatId)
            // Refused while **nothing is running** is the case that needs help: the engine publishes
            // a run's terminal state before it lets go of the run, so a drain fired by that state can
            // arrive in the gap and be refused — and no further emission comes, because the state
            // flow has already settled. That is 「暂停后再发，助手没有响应」: the turn sat in the queue
            // with nothing left to trigger it. One short retry closes the gap. A refusal while a run
            // *is* going needs none: that run's end drains again.
            if (!Core.engine.isRunning(chatId)) {
                viewModelScope.launch {
                    delay(RETRY_TURN_MS)
                    drainPendingTurns(chatId)
                }
            }
        } finally {
            draining.remove(chatId)
        }
    }

    /**
     * Re-reads the board and puts it on screen — **for the conversation the event belongs to**.
     *
     * There is no flow to collect: [TodoStore] writes the whole list under a lock and nothing
     * else in the app touches it, so the two tool events are the clock. Off the main thread
     * because the board is a file, and this runs on the reader thread that delivers events.
     * 只发给 [chatId] 自己（在屏就同时上屏，不在屏就只进它的缓存）：事件带的是哪个会话的 run，
     * 板子就属于哪个会话 —— 旧的「发给在屏会话」会把 A 的计划画到 B 屏幕上。
     */
    private fun refreshTodos(chatId: String) {
        viewModelScope.launch {
            val board = withContext(Dispatchers.IO) { Core.todos.read() }
            todoBoards[chatId] = board
            if (_state.value.conversationId == chatId) {
                _state.update { it.copy(todos = board) }
            }
        }
    }

    /**
     * Asks the run on screen to fold the conversation at its next turn boundary.
     *
     * Only a flag: the engine consumes it between rounds, so nothing in flight is cut and
     * the answer being written right now still lands. False means no run is going, which is
     * also when compressing would have nothing to do.
     */
    fun requestCompress(): Boolean = Core.engine.requestCompress(_state.value.conversationId)

    /**
     * 圆环面板三行（系统提示词 / 工具定义 / 对话消息）：按**当前**上下文算一次，不依赖 run。
     *
     * 面板打开时调用（用户：「系统提示词就是当前窗口的 tokens 和占比，不是单个 run 的」）—— 没有
     * run 也能看到当前窗口的构成。**这是估算，不是引擎的实测口径**：除数（字符 ÷ 4）、工具表公式
     * （名称 + 描述 + schema 的字符数）、对话消息范围（最后一个 checkpoint 之后 + 摘要本身）都与
     * 引擎一致，但系统提示词只取「人设 + 工具摘要」两项，记忆/画像/技能目录等分量没有计入（引擎的
     * systemPrompt 是 RunHandle 的私有成员，UI 侧够不到）。run 一开始，面板就改用 [ChatUiState.stats]
     * 里引擎的实测三块，估算值被覆盖。计算走 Dispatchers.Default：要拼工具表并遍历历史。
     */
    fun refreshContextBreakdown() {
        val shown = _state.value
        val chatId = shown.conversationId
        viewModelScope.launch {
            val settings = Core.settings.current()
            val assistant = settings.assistant(shown.assistantId)
            val persona = settings.forAssistant(assistant)
            val choice = settings.choiceFor(shown.providerId, shown.model, assistant)
            val effective = persona.copy(
                baseUrl = choice.baseUrl,
                apiKey = choice.apiKey,
                model = choice.model,
                reasoningEffort = shown.reasoningEffort ?: persona.reasoningEffort,
            )
            val registry = Core.tools.excluding(assistant?.disabledTools?.toSet().orEmpty())
            val split = withContext(Dispatchers.Default) {
                val checkpoint = shown.checkpoints.lastOrNull()
                val start = checkpoint?.let { cp ->
                    val at = shown.messages.indexOfFirst { it.id == cp.toMessageId }
                    if (at >= 0) at + 1 else 0
                } ?: 0
                val kept = if (start in 1..shown.messages.lastIndex) {
                    shown.messages.subList(start, shown.messages.size)
                } else {
                    shown.messages
                }
                val historyChars = kept.sumOf { message ->
                    message.text.length +
                        message.toolOutput.orEmpty().length +
                        message.toolArgs.orEmpty().length
                } + (checkpoint?.summary?.length ?: 0)
                val builtinSpecs = registry.all().map { it.spec }
                val pluginSpecs = PluginRuntime.enabledPlugins()
                val toolSpecs = builtinSpecs +
                    PluginRuntime.toolSpecs(pluginSpecs, builtinSpecs.map { it.name }.toSet())
                val tools = toolSpecs.sumOf {
                    it.name.length + it.description.length + it.parameters.toString().length
                } / 4
                val system = (effective.systemPrompt.length + registry.promptSummary().length) / 4
                Triple(system, tools, historyChars / 4)
            }
            _state.update {
                if (it.conversationId == chatId) it.copy(contextBreakdown = split) else it
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun start(runId: String, history: List<StoredMessage>) {
        val shown = _state.value
        val chatId = shown.conversationId
        // Lines left queued by the run that just ended, taken before this one starts: the
        // queue outlives a run, and a line queued from here on is already on its way to the
        // new run, so handing that one over again would deliver it twice.
        val carried = steers[chatId].orEmpty()
        putSession(
            RunSession(
                chatId = chatId,
                title = shown.title,
                assistantId = shown.assistantId,
                providerId = shown.providerId,
                model = shown.model,
                reasoningEffort = shown.reasoningEffort,
                checkpoints = shown.checkpoints,
                runId = runId,
                messages = shown.messages,
                activeRuns = shown.activeRuns,
                status = shown.status,
            ),
        )
        // A new run starts with an empty board on screen. The file still holds the last run's
        // items — and drawing them here would show the previous task's plan wearing the new
        // run's clothes, most visibly for a run that never touches the todo tools at all.
        // The first `todo_write` of this run puts it back. 只清这个会话自己那一格。
        todoBoards[chatId] = emptyList()
        _state.update { it.copy(todos = emptyList()) }
        jobs[chatId] = viewModelScope.launch {
            AgentForegroundService.start(Core.appContext, "随行正在执行任务")
            // The draft writer rides inside the run's own coroutine, so it stops the moment
            // the run does — no separate job to cancel, and no writes after the answer has
            // landed in the transcript. It is cancelled by hand in the finally as well: a
            // `while (true)` loop left to itself would tick once a second for the life of
            // the view model, and the cancel lands before the draft is cleared, so no write
            // can slip in after it.
            val writer = launch { writeLiveDraft(chatId) }
            // Lines still queued from the run that ended: the queue outlives a run, so the new
            // one has to be told about them. Waiting for Running is what makes this work — the
            // engine refuses a steer for a conversation it is not running yet, and startRun
            // below does not return until the run is over.
            if (carried.isNotEmpty()) {
                launch {
                    Core.engine.stateOf(chatId).first { it is RunState.Running }
                    // The queue is the truth: a line retracted while the run was starting is
                    // gone from the table, and gone from the UI, so it must not go out here.
                    carried.filter { queued -> steers[chatId]?.any { it.id == queued.id } == true }
                        .forEach { queued ->
                            // The picture rides along: a steer carries text *and* image, and
                            // re-sending only the text dropped the screenshot without a word.
                            Core.engine.steer(
                                chatId,
                                queued.id,
                                queued.text,
                                immediate = false,
                                imagesBase64 = queued.imagesBase64,
                            )
                        }
                }
            }
            // Decided in the catches, applied once in the finally: there is exactly one
            // place that saves and one that closes the run, whichever way it ended.
            var status = ""
            var failure: String? = null
            try {
                sessions[chatId]?.let { saveSession(it) }
                val settings = Core.settings.current()
                val assistant = settings.assistant(shown.assistantId) ?: settings.activeAssistant()
                // The persona bends the live settings; anything it left blank stays as-is.
                val persona = settings.forAssistant(assistant)
                // The wire comes from the one chain the picker also reads: this
                // conversation's own pick, then the persona's pins, then the app-wide
                // default. Everything else keeps the persona's overrides.
                val choice = settings.choiceFor(shown.providerId, shown.model, assistant)
                val effective = persona.copy(
                    baseUrl = choice.baseUrl,
                    apiKey = choice.apiKey,
                    model = choice.model,
                    // The level follows the same chain as the model: this chat's own pick
                    // wins, then whatever the persona's provider declared, then the
                    // app-wide default the persona already carries.
                    reasoningEffort = shown.reasoningEffort ?: persona.reasoningEffort,
                )
                val registry = Core.tools.excluding(assistant?.disabledTools?.toSet().orEmpty())
                val produced = engine.startRun(
                    chatId = chatId,
                    title = shown.title,
                    history = history,
                    settings = effective,
                    registry = registry,
                    capabilities = Core.capabilities,
                    skills = Core.skills,
                    memory = Core.memory,
                    profile = Core.profile,
                    gate = gate,
                    // Captured per run: the engine's events carry no chat id, so the
                    // closure is what keeps two parallel runs out of each other's
                    // transcript.
                    onEvent = { event -> handleEvent(chatId, event) },
                    // The compressions this conversation has already paid for, and the one
                    // this run is about to make: without both, every turn would fold — and
                    // charge for — the same span again.
                    checkpoints = shown.checkpoints,
                    onCheckpoint = { checkpoint -> recordCheckpoint(chatId, checkpoint) },
                )
                // Only the run's own last reply is read out, not the whole history.
                if (effective.speakReplies) {
                    produced.lastOrNull { it.role == Roles.ASSISTANT && it.text.isNotBlank() }
                        ?.text
                        ?.let {
                            // Remembered before speaking, because the speaker queues the
                            // line and returns at once: the id has to be set by the time
                            // any other screen can reach the stop button.
                            speakingChatId = chatId
                            Core.speaker.speak(it)
                        }
                }
            } catch (cancellation: CancellationException) {
                // RunState.Cancelled usually released the screen first; this is the belt
                // to that pair of braces.
                status = "已停止"
                throw cancellation
            } catch (error: Throwable) {
                // The transport's own words say nothing the user can act on — "timeout",
                // "Connection reset", "Trust anchor for certification path not found" — and they
                // are in English besides. `describeError` walks the cause chain and turns them into
                // what to do about the address, the certificate or the key; it is the same
                // description the settings page's connection test shows, so a failure reads the
                // same wherever it is met.
                //
                // One thing it cannot know is kept: a round the engine retried is re-thrown as an
                // LlmException whose message is the failure's own text — or, when it had none, its
                // class name (AgentEngine's own catch) — followed by the retry note. The note is
                // what is left once that prefix is gone, and it is only taken when it still looks
                // like the note (the engine's own full-width parenthesis); anything else means the
                // message was not shaped as expected, and the description stands alone rather than
                // repeating an English class name underneath itself.
                val note = if (error.cause != null && error is LlmException) {
                    val prefix = error.cause?.let { it.message ?: it::class.java.simpleName }.orEmpty()
                    error.message.orEmpty().removePrefix(prefix).trim().takeIf { it.startsWith("（") }
                        .orEmpty()
                } else {
                    ""
                }
                failure = listOf(describeError(error), note)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                // 「tool name is invalid or duplicated」这类 400 的最小探针全部 200（点号/横杠/重名/
                // 插件全 schema/带未知工具名的历史，两个通道都测过），复现不出确切触发条件 ⇒ 让它
                // 自己报：把这一轮的工具表与历史引用过的工具名写进 trace，下次发生直接读日志定位。
                if (failure.contains("tool name", ignoreCase = true)) {
                    val live = sessions[chatId]
                    val historyCalls = live?.messages
                        ?.flatMap { it.toolCalls }
                        ?.map { it.name }
                        ?.distinct()
                        ?.takeLast(40)
                        .orEmpty()
                    retryTrace(
                        "toolname-400 toolsTable=${Core.tools.names().joinToString(",")} " +
                            "historyCalls=${historyCalls.joinToString(",")}",
                    )
                }
            } finally {
                AgentForegroundService.stop(Core.appContext)
                withContext(NonCancellable) {
                    // First of all: the writer must not run again, or a tick landing after
                    // the clear below would resurrect a draft for an answer already saved.
                    writer.cancel()
                    // 传输层抛异常的失败（400/超时/断连）不走 AgentEvent.Failed，而回执只埋在
                    // Failed 分支 ⇒ 横幅倒计时一过就什么都不剩（用户：「红色回执依旧会消失」）。
                    // 这里补同一张回执，并且**必须走 putSession**：直接给 sessions[chatId] 赋值只会
                    // 落盘、不上屏 —— 上一版就是这么漏的，用户当轮只看到会消失的横幅。putSession
                    // 同时写 sessions 与在屏 state，红卡当场出现；下面的最终 saveSession 再落盘。
                    // Failed 分支已写过回执的（末条已是 SYSTEM）不重复写。
                    if (failure != null) {
                        sessions[chatId]?.let { live ->
                            if (live.messages.lastOrNull()?.role != Roles.SYSTEM) {
                                putSession(
                                    live.copy(
                                        messages = live.messages + StoredMessage(
                                            id = UUID.randomUUID().toString(),
                                            role = Roles.SYSTEM,
                                            text = "这一轮最终失败：$failure",
                                            runId = live.runId,
                                        ),
                                    ),
                                )
                            }
                        }
                    }
                    // A cancelled coroutine refuses to suspend, so the final save has to
                    // run in a NonCancellable block or a stopped run loses its messages.
                    // Whatever the endpoint sent since the last publish belongs to the answer
                    // too: without this flush a stopped run would save all but its tail.
                    flushDeltas(chatId)
                    sessions[chatId]?.let { saveSession(it) }
                    endSession(chatId, status, failure)
                    // The run is over for certain here — this coroutine is the one that called
                    // into the engine, so it has already returned and unregistered. A turn queued
                    // in 排队 mode that the state observer could not start (its Done arrives while
                    // the run is still registered) is started now.
                    drainPendingTurns(chatId)
                    // The answer is in the transcript now, whatever way the run ended: a
                    // draft left behind would come back on the next launch as a second
                    // bubble saying the same thing.
                    Core.chats.clearLive(chatId)
                }
            }
        }
    }

    private fun handleEvent(chatId: String, event: AgentEvent) {
        val live = sessions[chatId] ?: return
        // The model can send hundreds of deltas a second, and every one of them used to
        // republish the state — which re-parses the whole Markdown body, because the render
        // cache is keyed by the text itself and a text that changes every frame is a miss
        // every frame. That is what made the ghosting worse the faster the endpoint wrote.
        // Deltas are buffered and published at most every DELTA_PUBLISH_MS; every other event
        // flushes first, so nothing is ever applied out of order and no tail is lost.
        val textDelta = event is AgentEvent.TextDelta
        val reasoningDelta = event is AgentEvent.ReasoningDelta
        if (textDelta) textBuffers.getOrPut(chatId) { StringBuilder() }.append(event.text)
        if (reasoningDelta) reasoningBuffers.getOrPut(chatId) { StringBuilder() }.append(event.text)
        if (textDelta || reasoningDelta) {
            val now = SystemClock.uptimeMillis()
            if (now - (lastPublishAt[chatId] ?: 0L) < DELTA_PUBLISH_MS) return
            lastPublishAt[chatId] = now
        }
        // Whatever has arrived so far, applied in one go. For a non-delta event this is the
        // flush; for a delta it is the publish that got through the interval above.
        val pendingText = textBuffers.remove(chatId)?.toString().orEmpty()
        val pendingReasoning = reasoningBuffers.remove(chatId)?.toString().orEmpty()
        val base = applyPending(live, pendingText, pendingReasoning)
        val next = when (event) {
            // Already folded into `base` above.
            is AgentEvent.TextDelta -> base

            is AgentEvent.ReasoningDelta -> base

            // The round is stored, but it is not drawn from the transcript: the live
            // bubble keeps drawing it until the run lands, so the reply stays one bubble
            // from the first token to the last.
            is AgentEvent.AssistantMessage -> {
                // 轮落盘＝一段结束：封口思考块，并把段起点推到封口后的末尾 —— 下一轮（或同一 run
                // 的下一段）的思考/正文不会与这一轮的合并成一个块。
                val sealed = base.parts.sealThinking(base.thinkingStartedAt)
                base.copy(
                    messages = base.messages + event.message.copy(runId = base.runId),
                    parts = sealed,
                    segmentStart = sealed.size,
                    // The round is a message now, so the two live buffers start empty: from here
                    // they hold the round still being written, not the run so far. That is what
                    // lets the draft carry the part no message has yet — otherwise it would hand
                    // the whole run back as one paragraph and repeat the rounds already stored.
                    streaming = "",
                    reasoning = "",
                )
            }

            is AgentEvent.ToolStarted -> {
                // 工具行＝一段结束（理由同 [appendText]：工具前后的正文是两段话）。
                val sealed = base.parts.sealThinking(base.thinkingStartedAt).startTool(event.name, event.args)
                base.copy(
                    // 「正在调用」 rather than a bare verb: the line is a sentence the user reads
                    // while waiting, and 「调用 read_file」 read as a log entry.
                    status = "正在调用 ${event.name}",
                    runningTool = event.name,
                    parts = sealed,
                    segmentStart = sealed.size,
                )
            }

            is AgentEvent.ToolMessage -> base.copy(
                messages = base.messages + event.message.copy(runId = base.runId),
                status = "",
                runningTool = null,
                // The result hangs off the call it belongs to, not only off the
                // transcript: the row needs its output and its screenshot.
                parts = base.parts.attachToolMessage(event.message),
            )

            is AgentEvent.ToolFinished -> base.copy(
                runningTool = null,
                parts = base.parts.finishTool(event.name, event.ok),
            )

            // The error rides with the run: it shows on the error card only while that
            // conversation is the one on screen, and travels with it otherwise.
            is AgentEvent.Failed -> {
                // 每一次终止性失败都落盘，**不只是重试用尽的那种**：用户报「为什么写文件就触发」时，
                // 需要看的是网关实际回了什么 —— 而这条消息里现在带着 finish_reason 的**原文**与
                // 它自己报的 completion_tokens（见 AgentEngine 的 length 分支），所以这一行就是那次
                // 复现的原始证据，不必再靠推测。重试次数由 [Retrying] 设下的计数器给出，不解析文案。
                val attempts = retriesThisRun
                retriesThisRun = 0
                retryTrace(
                    "turn-failed" + (if (attempts > 0) " attempts=$attempts" else "") +
                        " message=${event.message}",
                )
                // 最终失败要**像回复一样留在会话里**（用户：「如果最终确实是失败了，需要把失败的
                // 提示类似气泡那样作为回复」）。横幅 10 秒就自动消失、卡片里那行小红字也不落盘，
                // 关掉应用就再也查不到这一轮为什么死 —— 所以把失败文本作为一条消息进 live.messages，
                // run 结束时那次最终 saveSession 自然把它写进文件。角色用 SYSTEM：引擎回放 wire 只
                // 认 user/assistant/tool（AgentEngine:1822-1852），它不会进下一轮的请求；转录单独
                // 画它一行（groupRows 的 SYSTEM 分支）。注入点必须在 endSession 之前 —— finally 的
                // 顺序是 flushDeltas → saveSession → endSession(remove)，这里 live 还在。
                val receipt = StoredMessage(
                    id = UUID.randomUUID().toString(),
                    role = Roles.SYSTEM,
                    text = "这一轮最终失败：${event.message}",
                    runId = base.runId,
                )
                // **插到失败那一轮的最后一条消息后面**（用户：「一轮错误就跟在一轮后面」「为什么
                // 老是放在卡片前面」）：Failed 事件可能晚于下一条消息到达（用户已发下一问/排队已
                // 落地），追加到表尾回执就跑到下一轮卡片前面去了。按 runId 找它那一轮的末条；
                // 找不到（极端乱序）才退回表尾。
                val at = base.messages.indexOfLast { it.runId == base.runId && it.runId.isNotEmpty() }
                val messages = if (at >= 0) {
                    base.messages.subList(0, at + 1) + receipt + base.messages.subList(at + 1, base.messages.size)
                } else {
                    base.messages + receipt
                }
                base.copy(error = event.message, messages = messages)
            }

            // A failed attempt that is being repeated. The run is still going — it is waiting — and
            // without this the screen says nothing at all for the length of the delay, which is
            // exactly how a retrying run got read as one that had stopped. Whatever event comes next
            // overwrites the line.
            is AgentEvent.Retrying -> {
                retriesThisRun = event.attempt
                retryTrace(
                    "retry-start attempt=${event.attempt} max=${event.maxAttempts} " +
                        "code=${event.code ?: "none"} delayMs=${event.delayMs} reason=${event.reason} " +
                        // These two are invariants of the decision, not measurements: a retry is only
                        // taken when nothing arrived on the attempt (or it would have been kept as
                        // content) and when the call was not cancelled. Written out anyway, because a
                        // log reader should not have to know the rule to trust the line.
                        "received=0 cancelled=0",
                )
                base.copy(
                    status = "连接失败，${event.delayMs / 1000} 秒后重试（第 ${event.attempt} 次）· ${event.reason}",
                )
            }

            is AgentEvent.Finished -> {
                retriesThisRun = 0
                live
            }

            // The model has the line now: it leaves the queue and becomes a message in the
            // same breath, so a queued line is never both. Marked `steer` so the transcript
            // keeps reading it as an interruption rather than as the question being answered.
            //
            // It also becomes a live part, and that is the only way the user sees it while the
            // run is going: this run's stored rounds are hidden from the transcript for as
            // long as it streams (the live bubble draws them), and a stored steer folds into
            // exactly the hidden row. The message still lands, because the run is what saves
            // it — it is just not drawable yet.
            //
            // Off `base`, not `live`: the deltas buffered for this event were already taken
            // out of their buffers a few lines up, so building on `live` would have thrown
            // away whatever text and thinking had just arrived.
            is AgentEvent.Steered -> {
                steers[chatId] = steers[chatId].orEmpty().filterNot { it.id == event.id }
                publishSteers(chatId)
                val line = StoredMessage(
                    id = event.id,
                    role = Roles.USER,
                    text = event.text,
                    // The picture travels with the event, so the bubble the user sees is the
                    // message the model was given — and it is stored, so a later run replaying
                    // this history still sends it.
                    imagesBase64 = event.imagesBase64,
                    steer = true,
                    // This run's id, like every other message it writes. The screen hides the
                    // live run's stored rows while the bubble draws them; a steer without the
                    // id slipped past that filter whenever no bubble was open yet (the model
                    // had stored nothing back) and appeared twice — once settled, once live.
                    // Nothing else keys off it: turns are opened by non-steer user messages
                    // alone, and [Transcript.active] keeps every user message whatever its run.
                    runId = base.runId,
                )
                run {
                    // 插话行＝一段结束：用户插进来的这条线把前后的流分成两段。
                    val sealed = base.parts.sealThinking(base.thinkingStartedAt) + LivePart.Steer(line)
                    base.copy(
                        messages = base.messages + line,
                        parts = sealed,
                        segmentStart = sealed.size,
                    )
                }
            }
        }
        putSession(next)
        // The board above the composer: the only two events that can change it are the todo
        // tool starting and finishing, and reading it back here is what makes the row appear,
        // tick and go without anything polling a file. Both ends are read: the write is a
        // finished call's result, and the model may also re-read the board mid-run.
        if (event is AgentEvent.ToolStarted && event.name == TODO_TOOL ||
            event is AgentEvent.ToolFinished && event.name == TODO_TOOL
        ) {
            refreshTodos(chatId)
        }
        // Saved straight away: a handed-over line that only lived in memory would vanish the
        // moment the user switches conversations, which is when they go looking for it.
        if (event is AgentEvent.Steered) viewModelScope.launch { saveSession(next) }
    }

    /**
     * Folds the buffered deltas into a session: the answer first, then the reasoning.
     *
     * Both fields are separate buffers on purpose — they feed different parts of the bubble,
     * and merging them would put half a thought into the answer. Applied in one go, so one
     * publish covers everything that arrived since the last one.
     */
    private fun applyPending(live: RunSession, text: String, reasoning: String): RunSession {
        var next = live
        if (text.isNotEmpty()) {
            next = next.copy(
                streaming = next.streaming + text,
                // 不在这里 seal 思考块：交错流（思考→正文→思考→正文）里正文一到就封口，会把
                // 同一段思考劈成两块、同一段正文劈成两块（真机截图：一句「先说声抱歉…」被一个
                // 2-token 的思考块从中间劈开）。封口只发生在工具行/插话行/本轮落盘那一刻，
                // 与 settled 视图「一条消息＝一个思考块＋一段正文」同形。
                parts = next.parts.appendText(text, next.segmentStart),
                status = "",
            )
        }
        if (reasoning.isNotEmpty()) {
            // 同一段（最后一个工具行/插话行之后）里已有思考块就续进去，它的钟从第一次思考
            // delta 起算；跨过工具行/插话行才是新的一段，钟才重置。
            val opening = !next.parts.hasOpenThinking(next.segmentStart)
            next = next.copy(
                reasoning = next.reasoning + reasoning,
                parts = next.parts.appendThinking(reasoning, next.segmentStart),
                status = "",
                thinkingStartedAt = if (opening) System.currentTimeMillis() else next.thinkingStartedAt,
            )
        }
        return next
    }

    /**
     * Publishes whatever is still buffered for [chatId].
     *
     * Called before anything that reads the session as a whole — saving it, switching away
     * from it — because the buffered text is part of the answer and dropping it here is how a
     * stopped or failed run would lose its last words.
     */
    private fun flushDeltas(chatId: String) {
        val live = sessions[chatId] ?: return
        val text = textBuffers.remove(chatId)?.toString().orEmpty()
        val reasoning = reasoningBuffers.remove(chatId)?.toString().orEmpty()
        if (text.isEmpty() && reasoning.isEmpty()) return
        putSession(applyPending(live, text, reasoning))
    }

    /**
     * Appends a text delta to the prose piece of the **current segment**, or opens one.
     *
     * 判据是「当前段（[segmentStart] 之后）里有没有正文块」，不是「上一个 part 是不是正文」：
     * 交错流（思考→正文→思考→正文）里若按上一个 part 判，一个 2-token 的思考块就会把一句
     * 话劈成两块（真机截图实测）。同一段里正文只持一个块；跨过工具行/插话行/轮落盘才是新段
     * —— 工具前后的正文确实是两段话，合并会把「让我查一下」和查完的回答连成一行。
     */
    private fun List<LivePart>.appendText(delta: String, segmentStart: Int): List<LivePart> {
        val from = segmentStart.coerceIn(0, size)
        val tailIdx = subList(from, size).indexOfLast { it is LivePart.Text }
        if (tailIdx < 0) return this + LivePart.Text(delta)
        val idx = from + tailIdx
        val part = this[idx] as LivePart.Text
        return take(idx) + part.copy(text = part.text + delta) + drop(idx + 1)
    }

    /** 同 [appendText]：同一段里思考只持一个块，交错流不再劈开。 */
    private fun List<LivePart>.appendThinking(delta: String, segmentStart: Int): List<LivePart> {
        val from = segmentStart.coerceIn(0, size)
        val tailIdx = subList(from, size).indexOfLast { it is LivePart.Thinking }
        if (tailIdx < 0) return this + LivePart.Thinking(delta)
        val idx = from + tailIdx
        val part = this[idx] as LivePart.Thinking
        return take(idx) + part.copy(text = part.text + delta) + drop(idx + 1)
    }

    /** 当前段里是否已有思考块（决定这一段的思考钟是续走还是重置）。 */
    private fun List<LivePart>.hasOpenThinking(segmentStart: Int): Boolean {
        val from = segmentStart.coerceIn(0, size)
        return subList(from, size).any { it is LivePart.Thinking }
    }

    /**
     * Freezes the still-open thinking block the moment the run moves on: its own text
     * length stands in for its token count and its own clock stops here, so a later
     * block cannot inherit this one's elapsed time.
     */
    private fun List<LivePart>.sealThinking(startedAt: Long): List<LivePart> {
        val last = lastOrNull() as? LivePart.Thinking ?: return this
        val elapsed = if (startedAt > 0) System.currentTimeMillis() - startedAt else 0L
        return dropLast(1) + last.copy(tokens = last.text.length / 4, elapsedMs = elapsed)
    }

    /** A call joins the round of tools already open, or starts one. */
    private fun List<LivePart>.startTool(name: String, args: String): List<LivePart> {
        val call = LiveTool(name = name, args = args, startedAt = System.currentTimeMillis())
        return when (val last = lastOrNull()) {
            is LivePart.Tools -> dropLast(1) + last.copy(calls = last.calls + call)
            else -> this + LivePart.Tools(listOf(call))
        }
    }

    /**
     * Marks the oldest still-running call of that name as done.
     *
     * Matched by name, not by index: the engine reports the finish by name, and a run that
     * calls the same tool twice must not have the second result close the first row.
     */
    private fun List<LivePart>.finishTool(name: String, ok: Boolean): List<LivePart> = map { part ->
        if (part !is LivePart.Tools) return@map part
        val index = part.calls.indexOfFirst { it.name == name && !it.done }
        if (index < 0) return@map part
        val calls = part.calls.toMutableList()
        val call = calls[index]
        calls[index] = call.copy(
            done = true,
            ok = ok,
            elapsedMs = System.currentTimeMillis() - call.startedAt,
        )
        part.copy(calls = calls)
    }

    /**
     * Hangs a landed TOOL message on the call it reports, matching the way
     * [finishTool] matches: oldest open call of that name. The message carries the
     * output and any screenshot, which only the row that owns it may draw.
     */
    private fun List<LivePart>.attachToolMessage(message: StoredMessage): List<LivePart> = map { part ->
        if (part !is LivePart.Tools) return@map part
        val name = message.toolName.orEmpty()
        val index = part.calls.indexOfFirst { it.name == name && it.message == null }
        if (index < 0) return@map part
        val calls = part.calls.toMutableList()
        calls[index] = calls[index].copy(message = message)
        part.copy(calls = calls)
    }
    /**
     * Installs [next] as its conversation's live copy and, when that conversation is
     * the one on screen, copies the live parts of it onto the screen. A run in some
     * other conversation changes nothing here at all — the map keeps it apart, and
     * the display only ever reads its own entry.
     */
    private fun putSession(next: RunSession) {
        sessions[next.chatId] = next
        _state.update { snapshot ->
            if (snapshot.conversationId != next.chatId) {
                snapshot
            } else {
                snapshot.copy(
                    title = next.title,
                    messages = next.messages,
                    activeRuns = next.activeRuns,
                    streaming = next.streaming,
                    reasoning = next.reasoning,
                    status = next.status,
                    runningTool = next.runningTool,
                    liveParts = next.parts,
                    liveRunId = next.runId,
                    error = next.error ?: snapshot.error,
                )
            }
        }
    }

    /**
     * Drops the run's claim on its conversation. The screen keeps whatever the user was
     * looking at: the run's own conversation loses its streaming state, any other one is
     * left exactly as it was.
     */
    private fun endSession(chatId: String, status: String, error: String? = null) {
        val live = sessions.remove(chatId)
        val failure = error ?: live?.error
        // A run that failed while another conversation was on screen has no state to land
        // in — its own copy is gone a line above — so the reason waits here until that
        // conversation is opened again. Otherwise the user comes back to half an answer
        // and no explanation.
        if (failure != null && _state.value.conversationId != chatId) lastErrors[chatId] = failure
        _state.update { snapshot ->
            if (live != null && snapshot.conversationId == chatId) {
                snapshot.copy(
                    streaming = "",
                    reasoning = "",
                    stats = null,
                    status = status,
                    runningTool = null,
                    liveParts = emptyList(),
                    liveRunId = "",
                    error = failure ?: snapshot.error,
                )
            } else {
                snapshot
            }
        }
    }

    /**
     * Applies [change] to the conversation on screen. While that conversation is the one
     * a run owns, the change goes into the run's copy as well — otherwise the next
     * streamed token would mirror the older messages back over the edit.
     */
    private fun editDisplayed(change: (ChatUiState) -> ChatUiState) {
        _state.update(change)
        val shown = _state.value
        val live = sessions[shown.conversationId] ?: return
        putSession(
            live.copy(
                title = shown.title,
                assistantId = shown.assistantId,
                messages = shown.messages,
                activeRuns = shown.activeRuns,
                // Carried too: an edit can drop a compression record, and a session still
                // holding the old list would write it back at the end of the run.
                checkpoints = shown.checkpoints,
            ),
        )
    }

    /**
     * Keeps the conversation's draft file in step with the answer being streamed.
     *
     * Once a second, and only when the answer, the thinking or the round count actually
     * changed: one write is an encode plus a file write plus a rename, and tokens can arrive
     * every few milliseconds. A second is the interval where a kill costs the user the last
     * tick instead of the whole answer, while a stalled stream costs nothing at all.
     *
     * Because nothing runs after the process is killed, this is the only place the thinking
     * and the finished rounds can be saved at all — see [liveDraftFor].
     */
    private suspend fun writeLiveDraft(chatId: String) {
        var written: LiveDraft? = null
        while (true) {
            delay(LIVE_DRAFT_INTERVAL_MS)
            val live = sessions[chatId] ?: continue
            // The answer, the thinking beside it, and the rounds this run has already
            // produced. The reasoning and those rounds are not "extra": the session is the
            // only place they exist until the run ends, so a run that never ends would
            // otherwise leave a paragraph with no chain of thought and no tool cards.
            val next = liveDraftFor(
                chatId = chatId,
                runId = live.runId,
                text = live.streaming,
                reasoning = live.reasoning,
                messages = live.messages,
            ) ?: continue
            if (next.sameContentAs(written)) continue
            written = next
            Core.chats.saveLive(next)
        }
    }

    /**
     * Turns the draft a killed run left behind back into a message.
     *
     * The rounds and the thinking the draft carries come back with the paragraph: they are
     * what the card was made of, and no other copy of them outlives a run that never saved.
     *
     * Only when the run it belongs to is not already in the transcript: a draft that outlived
     * a clean finish would otherwise return as a second bubble saying the same thing. The
     * order is append, save, then clear — clearing first would lose the text for good if the
     * save never happened.
     */
    private suspend fun mergeLiveDraft(chatId: String) {
        val draft = Core.chats.readLive(chatId) ?: return
        val live = sessions[chatId]
        val shown = _state.value.takeIf { it.conversationId == chatId }
        val stored = Core.chats.conversations.value.firstOrNull { it.id == chatId }
        val messages = live?.messages ?: shown?.messages ?: stored?.messages.orEmpty()
        // The rounds and tool results this run had already produced, put back before the
        // paragraph that was still being written. Until the run ends they exist nowhere else,
        // and leaving them out is what brought the card back as a bare sentence with no chain
        // of thought and no tool row — the answer with its reasoning and its work removed.
        val restored = liveDraftRecovery(draft, messages, cancelReason = cancelReason)
        // 原因消费一次即复位：下一轮若是引擎自己取消，写的就是默认文案而不是这一次的。
        cancelReason = "任务被停止"
        // Nothing this draft holds is missing from the transcript; clearing it is all that is
        // left.
        if (restored.isEmpty()) {
            Core.chats.clearLive(chatId)
            return
        }
        val next = messages + restored
        if (shown != null) _state.update { it.copy(messages = next) }
        live?.let { putSession(it.copy(messages = next)) }
        save(
            chatId = chatId,
            title = live?.title ?: shown?.title ?: stored?.title.orEmpty(),
            messages = next,
            assistantId = live?.assistantId ?: shown?.assistantId ?: stored?.assistantId.orEmpty(),
            activeRuns = live?.activeRuns ?: shown?.activeRuns ?: stored?.activeRuns.orEmpty(),
            providerId = live?.providerId ?: shown?.providerId ?: stored?.providerId,
            model = live?.model ?: shown?.model ?: stored?.model,
            reasoningEffort = live?.reasoningEffort ?: shown?.reasoningEffort ?: stored?.reasoningEffort,
            checkpoints = live?.checkpoints ?: shown?.checkpoints ?: stored?.checkpoints.orEmpty(),
        )
        Core.chats.clearLive(chatId)
    }

    /**
     * Records one compression the engine just made.
     *
     * The record is what lets a later run reuse a summary instead of paying to produce it
     * again, so it is saved the moment it arrives. Newest last and capped: an unbounded
     * history of every compression a long conversation ever made would only grow the file.
     */
    private fun recordCheckpoint(chatId: String, checkpoint: ContextCheckpoint) {
        val live = sessions[chatId]
        val shown = _state.value.takeIf { it.conversationId == chatId }
        val stored = Core.chats.conversations.value.firstOrNull { it.id == chatId }
        val current = live?.checkpoints ?: shown?.checkpoints ?: stored?.checkpoints.orEmpty()
        val next = (current + checkpoint).distinctBy { it.id }.takeLast(MAX_CHECKPOINTS)
        // Mirrored onto every live copy first, so the next save carries it whether the run
        // is still going or has just landed.
        if (shown != null) _state.update { it.copy(checkpoints = next) }
        live?.let { putSession(it.copy(checkpoints = next)) }
        viewModelScope.launch {
            // Read-modify-write of the checkpoints field alone: the run's own copy of the
            // messages is still growing, and saving it here would write a half-finished
            // snapshot back over the transcript. A conversation that is gone has nothing to
            // write to at all — save() upserts, so it would come back as an empty ghost.
            val base = Core.chats.conversations.value.firstOrNull { it.id == chatId }
                ?: return@launch
            save(
                chatId = chatId,
                title = base.title,
                messages = base.messages,
                assistantId = base.assistantId,
                activeRuns = base.activeRuns,
                providerId = base.providerId,
                model = base.model,
                reasoningEffort = base.reasoningEffort,
                checkpoints = next,
            )
        }
    }

    /** Saves the conversation on screen. */
    private suspend fun persistDisplayed() {
        val shown = _state.value
        val title = save(
            chatId = shown.conversationId,
            title = shown.title,
            messages = shown.messages,
            assistantId = shown.assistantId,
            activeRuns = shown.activeRuns,
            providerId = shown.providerId,
            model = shown.model,
            checkpoints = shown.checkpoints,
        ) ?: return
        if (title != shown.title) _state.update { it.copy(title = title) }
    }

    private suspend fun saveSession(live: RunSession) {
        val title = save(
            chatId = live.chatId,
            title = live.title,
            messages = live.messages,
            assistantId = live.assistantId,
            activeRuns = live.activeRuns,
            providerId = live.providerId,
            model = live.model,
            checkpoints = live.checkpoints,
        ) ?: return
        val current = sessions[live.chatId]
        if (current != null && title != current.title) {
            putSession(current.copy(title = title))
        }
    }

    /** Writes one conversation to disk; returns the title it ended up with. */
    private suspend fun save(
        chatId: String,
        title: String,
        messages: List<StoredMessage>,
        assistantId: String,
        activeRuns: Map<String, String>,
        providerId: String?,
        model: String?,
        reasoningEffort: String? = null,
        checkpoints: List<ContextCheckpoint>? = null,
    ): String? {
        if (chatId.isEmpty()) return null
        val previous = Core.chats.conversations.value.firstOrNull { it.id == chatId }
        val derived = if (title.isBlank() || title == "新会话") {
            messages.firstOrNull { it.role == Roles.USER }
                ?.text
                ?.replace('\n', ' ')
                ?.take(18)
                ?.takeIf { it.isNotBlank() }
                ?: title.ifBlank { "新会话" }
        } else {
            title
        }
        Core.chats.save(
            Conversation(
                id = chatId,
                title = derived,
                createdAt = previous?.createdAt ?: System.currentTimeMillis(),
                messages = messages,
                assistantId = assistantId,
                activeRuns = activeRuns,
                // The conversation's own model pick survives every save: dropping it here
                // would silently hand the next run back to the persona. Saves that carry
                // no pick (a message landing, a derived title, the end of a run) fall back
                // to what is already on disk instead of clearing it.
                providerId = providerId ?: previous?.providerId,
                model = model ?: previous?.model,
                // Same trap again: the thinking level is picked in the same sheet as the
                // model, so a save that carries none must inherit the one on disk rather
                // than resetting the chat to the app-wide default.
                reasoningEffort = reasoningEffort ?: previous?.reasoningEffort,
                // Same trap as the model pick: a save that carries no checkpoints must
                // inherit the ones on disk, or every message landing would wipe the record
                // of what was compressed — and the next run would compress and pay again.
                checkpoints = checkpoints ?: previous?.checkpoints ?: emptyList(),
            ),
        )
        return derived
    }

    override fun onCleared() {
        // Every prompt this screen's runs put on screen is answered "refused" as the
        // screen dies; other conversations' prompts belong to their own screens.
        jobs.keys.forEach { gate.cancelPending(it) }
        // viewModelScope cancels its children on clear; these two are the explicit
        // belt to that pair of braces.
        stateWatcher?.cancel()
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        super.onCleared()
    }
}

private fun Conversation.toUiState(fallbackAssistantId: String): ChatUiState = ChatUiState(
    conversationId = id,
    title = title,
    messages = messages,
    activeRuns = activeRuns,
    assistantId = assistantId.ifBlank { fallbackAssistantId },
    providerId = providerId,
    model = model,
    reasoningEffort = reasoningEffort,
    checkpoints = checkpoints,
    // A conversation read from disk is not "still opening": this state is what the skeleton
    // is replaced by, so it must not leave the flag set.
    loading = false,
)

/**
 * The draft to write for a run, or null when this run has nothing worth saving yet.
 *
 * The blank-text case is the one that mattered: a round that is still thinking, or that has
 * only issued a tool call so far, has no answer text at all — and that is precisely the round
 * a stop or a kill interrupts. Skipping it left no draft file behind, and everything the user
 * had watched arrive (the thinking, the tool cards) went with the process, because nothing
 * runs after a kill to save it.
 *
 * [messages] is filtered to this run: the rounds of an earlier answer in the same conversation
 * are already in its transcript, and re-appending them would duplicate them.
 */
internal fun liveDraftFor(
    chatId: String,
    runId: String,
    text: String,
    reasoning: String,
    messages: List<StoredMessage>,
): LiveDraft? {
    val mine = messages.filter { it.runId == runId }
    if (text.isBlank() && reasoning.isBlank() && mine.isEmpty()) return null
    return LiveDraft(
        chatId = chatId,
        runId = runId,
        at = System.currentTimeMillis(),
        text = text,
        reasoning = reasoning.takeIf { it.isNotBlank() },
        messages = mine,
    )
}

/**
 * Whether [other] already holds exactly this content, so the write can be skipped.
 *
 * [LiveDraft.at] is deliberately not compared: it changes on every tick, and comparing it
 * would turn the interval into a write every second for a stream that has gone quiet. The
 * rounds are compared whole rather than by id — a card whose result just arrived replaces the
 * same id, and an id-only check would leave that write to the next tick, or to never.
 */
internal fun LiveDraft.sameContentAs(other: LiveDraft?): Boolean = other != null &&
    other.runId == runId &&
    other.text == text &&
    other.reasoning == reasoning &&
    other.messages == messages

/**
 * What has to be appended to [messages] for the draft a killed run left behind to come back
 * whole: the rounds and tool results it had already produced, then the half-written answer.
 *
 * The thinking rides along with that answer, for the reason the engine hands it over on a
 * stop as well: the chain that produced the paragraph is part of what the user watched arrive.
 *
 * "This text is already in the transcript" is judged by the first [LIVE_DRAFT_PROBE_CHARS]
 * characters, not by the run alone: a run that used a tool has already stored messages under
 * the same runId, and judging by the run would throw the half-written final answer away — the
 * one thing this draft exists to save. The interrupted answer is appended only when the draft
 * is not already there; nothing else can say the answer is unfinished, because StoredMessage
 * has no field for it and a bubble that looks complete reads as the model stopping mid-sentence
 * for no reason.
 */
internal fun liveDraftRecovery(
    draft: LiveDraft,
    messages: List<StoredMessage>,
    /** 中断原因，写进半截回答的尾部（用户：「由 xxx 原因，对话中断」）。 */
    cancelReason: String = "任务被停止",
): List<StoredMessage> {
    val restored = draft.messages.filterNot { written -> messages.any { it.id == written.id } }
    val probe = draft.text.trim().take(LIVE_DRAFT_PROBE_CHARS)
    val delivered = probe.isNotEmpty() && messages.any { message ->
        message.runId == draft.runId &&
            message.role == Roles.ASSISTANT &&
            message.text.trim().startsWith(probe)
    }
    // Nothing left to say only when the answer already landed, or when the draft is empty in
    // every sense. A run stopped while it was still thinking has no answer text at all, and
    // that is not the same as having produced nothing: the thinking — and any tool round
    // restored above — is exactly what the user watched arrive and expects to find again. The
    // marker stands alone as the bubble's text in that case.
    val body = draft.text.trimEnd()
    val line = if (delivered || (probe.isEmpty() && draft.reasoning.isNullOrBlank() && restored.isEmpty())) {
        emptyList()
    } else {
        listOf(
            StoredMessage(
                id = UUID.randomUUID().toString(),
                role = Roles.ASSISTANT,
                text = if (body.isEmpty()) {
                    "（由「$cancelReason」原因，对话中断）"
                } else {
                    body + "\n\n（由「$cancelReason」原因，对话中断）"
                },
                reasoning = draft.reasoning,
                runId = draft.runId,
            ),
        )
    }
    return restored + line
}

/** How many compressions of one conversation are kept; older ones are dropped. */
private const val MAX_CHECKPOINTS = 10

/**
 * How often the answer being streamed is written to the conversation's draft file.
 *
 * A second, not every delta: each write is an encode plus a file write, and a long answer
 * arrives in thousands of deltas. It is the interval where a kill costs the last tick of the
 * answer rather than all of it.
 */
private const val LIVE_DRAFT_INTERVAL_MS = 1000L

/**
 * How often buffered deltas are published to the screen.
 *
 * Under the frame time a reader can notice, and far under the rate a fast endpoint sends at:
 * the state stops changing hundreds of times a second, and with it the Markdown re-parse that
 * every one of those changes forced (the render cache is keyed by the text, so a text that
 * changes every frame is a miss every frame).
 *
 * Eighty rather than fifty is an inference, not a measurement: the user still saw ghosting
 * after the fifty-millisecond version, and the cost that scales with a fast endpoint is the
 * re-parse, so halving the publish rate halves it. Twelve and a half a second is still
 * smoother than reading speed. The measurement-backed fix — reusing the previous parse for
 * the unchanged prefix instead of re-parsing the whole document — needs `MarkdownText` to
 * report where each block starts, which it does not do yet.
 */
private const val DELTA_PUBLISH_MS = 80L

/**
 * How long to wait before trying a refused 排队 turn again.
 *
 * Only used for the refusal that has no other way back: the send was refused although nothing is
 * running, which means the engine is between publishing a run's terminal state and letting go of the
 * run. Long enough for that to finish, short enough that the user does not see a gap.
 */
private const val RETRY_TURN_MS = 300L

/**
 * Where the retry diagnostic goes, and how it is kept from growing without bound.
 *
 * `files/retry-trace.log`, next to `fx-trace.log`: a user reporting 「自动重试真的重试了吗」 can be
 * answered from the file instead of from a claim. Rotated by truncating to the newest
 * [RETRY_TRACE_KEEP_CHARS] once it passes [RETRY_TRACE_MAX_BYTES] — retries are rare, so this is
 * history rather than a ring buffer.
 */
private const val RETRY_TRACE_FILE = "retry-trace.log"
private const val RETRY_TRACE_MAX_BYTES = 128 * 1024L
private const val RETRY_TRACE_KEEP_CHARS = 64 * 1024

/**
 * How much of a draft is compared against the transcript to decide it was already saved.
 *
 * A prefix, not the whole text: the saved answer is the draft plus whatever arrived after the
 * last write, so equality would call every cleanly finished run "not delivered" and duplicate
 * the bubble. 64 characters are far more than any two answers share by accident.
 */
private const val LIVE_DRAFT_PROBE_CHARS = 64

/**
 * The one tool that writes the board (`tools/TodoTools.kt:26`), named here because this is the
 * only place the UI needs to recognise it.
 *
 * A literal rather than a reference: `TodoTools` declares its names inline in its specs and
 * exposes no constant, and a name shared across packages would mean changing a file that this
 * screen has no other business with — the tool's own spec is the source of truth either way.
 */
private const val TODO_TOOL = "todo_write"
