package com.suixing.agent.ui.chat

import android.os.SystemClock
import android.util.Log
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
import com.suixing.agent.data.PerfRecord
import com.suixing.agent.data.ChatTotals
import com.suixing.agent.data.totalsOfMessage
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
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap
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

/**
 * 最多缓存几个会话的正文（LRU）。用户定的数：在 5 个会话之间来回切不该反复读盘。
 * 超出就淘汰最久没被打开的那一个 —— 与"列表常驻全文"（RSS 413MB 的根因）是两回事。
 */
private const val MESSAGE_CACHE_SIZE = 5

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
    /**
     * 这个会话**总共有多少条消息**（来自落盘 meta，不是 [messages] 的长度）。
     *
     * 为什么要单独一个字段（#30）：打开会话只读最近 N 轮，`messages` 是**窗口**而不是全部，
     * 所以 `messages.size` 不再等于总条数。需要总数的调用方（会话状态工具、轮次）
     * 必须读这个字段，否则会把 12 轮当成整份对话。
     */
    val messageCount: Int = 0,
    /**
     * 这个会话**总共有多少轮**（来自落盘 meta，不是 [messages] 里的轮数）。
     *
     * 导航条用它算「第 N 轮」和「上面还有 N 轮」；[messages] 只是窗口，
     * 用窗口里的轮数会让打开长会话时永远显示第 1 轮（#30 的可见回归）。
     */
    val turnCount: Int = 0,
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
    /**
     * 没有 run 时手动压缩的开始时刻（毫秒）；0＝没在压。
     *
     * 状态行要能说「正在压缩上下文，用时 xx」—— 没有这一位，点了压缩屏幕上没有任何反应
     * （用户：「无 run 的时候手动点压缩，啥响应也没有」）。
     */
    val compressingSince: Long = 0L,
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

    /**
     * 过程留痕（重试）：运行中就要看得见。
     *
     * 这一轮的落盘消息在流式期间被藏在转录之外（live 气泡画的就是它们），所以没有这个 part，
     * 「刚才重试过」要等整轮结束才出现 —— 用户要的恰恰是过程态里的留痕。
     */
    data class Trace(val note: String) : LivePart

    /**
     * 运行中发生的一次上下文压缩。
     *
     * 它必须成为过程区的一个块，而不是气泡下面一行提示：压缩是"这一轮里发生的事"，位置就在
     * 它发生的地方（思考 → 工具 → 压缩 → 继续）。运行结束后转录里另有一张按旧行定位的
     * CheckpointCard 负责回看。
     */
    data class Compress(val checkpoint: ContextCheckpoint) : LivePart
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
/** 「要不要重算会话统计」的请求：会话 id + 盘上内容的指纹 + 上下文窗口。 */
private data class StatsRequest(val chat: String, val key: String, val window: Int)

data class SessionStats(
    val turns: Int,
    val steps: Int,
    val tokens: Int,
    val cachePercent: Double,
    val perSecond: Double,
    val contextTokens: Int,
    val contextWindow: Int,
    /** 三块花费（未缓存输入 / 缓存读取 / 输出），**整个会话**。 */
    val split: SessionSplit = SessionSplit(0, 0, 0),
    /** 整个会话的墙钟合计。 */
    val elapsedMs: Long = 0,
) {
    companion object {
        /**
         * 由**整个会话**的统计量构建（[ChatRepository.sessionTotals] 从 meta 读，O(1)）。
         *
         * **不能**再传界面上的消息列表：那只是 `readTail` 的 12 轮窗口，
         * 加出来的轮数/用量只是"已加载那部分"（用户实测：标题栏的会话统计少了一大截）。
         */
        /**
         * 从一段消息算统计 —— **只用于测试和"手里确实有完整列表"的调用方**。
         *
         * 界面上**不要**用这个重载：`ChatUiState.messages` 是 `readTail` 的窗口，
         * 加出来的只是"已加载那部分"。生产路径走 [of] 的 `ChatTotals` 版本。
         */
        fun of(messages: List<StoredMessage>, contextWindow: Int): SessionStats? =
            of(
                messages.fold(ChatTotals.EMPTY) { acc, message -> acc + totalsOfMessage(message) },
                contextWindow,
            )

        internal fun of(totals: ChatTotals, contextWindow: Int): SessionStats? {
            val prompt = totals.promptTokens
            val completion = totals.completionTokens
            val cached = totals.cachedTokens
            // 官方聚合＝ΣdecodeTokens/ΣdecodeMs。逐轮 `elapsed - firstToken` 恒 ≥ 0，
            // 所以"先求和再相减"与逐轮求和等价。
            val decode = (totals.elapsedMs - totals.firstTokenMs).coerceAtLeast(0L)
            val tokens = prompt + completion
            if (tokens == 0 && totals.steps == 0) return null
            return SessionStats(
                turns = totals.turns,
                steps = totals.steps,
                tokens = tokens,
                // 比率在 Long 里算、不取整：`cached * 100 / prompt` 在 Int 上会在
                // cached 超过 21,474,837 时溢出，历史上那次就画成了"缓存命中整行消失"。
                cachePercent = if (prompt > 0) cached.toLong() * 100.0 / prompt else 0.0,
                perSecond = Tps.of(completion, decode),
                contextTokens = totals.contextTokens,
                contextWindow = contextWindow,
                split = SessionSplit(
                    uncached = (prompt.toLong() - cached.toLong()).coerceAtLeast(0L),
                    cached = cached.toLong(),
                    output = completion.toLong(),
                ),
                elapsedMs = totals.elapsedMs,
            )
        }
    }
}
private const val TAG = "ChatViewModel"

/** 「记住上次打开」的写盘 debounce：快速连点几个会话不该连写几次。 */
private const val LAST_OPENED_DEBOUNCE_MS = 1_200L

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
        // **缓存键用盘上的条数/更新时间，不用窗口的条数**：窗口会随「加载更早」变，
        // 而整个会话的总账不变。
        val meta = Core.chats.conversations.value.firstOrNull { it.id == snapshot.conversationId }
        StatsRequest(
            chat = snapshot.conversationId,
            key = "${meta?.messageCount ?: 0}:${meta?.updatedAt ?: 0}",
            window = window,
        )
    }
        .distinctUntilChanged()
        .mapLatest { request ->
            if (request.chat.isEmpty()) return@mapLatest null
            // 整个会话的统计：读 meta（O(1)）；旧会话第一次读时补算一次。
            val totals = withContext(Dispatchers.IO) {
                runCatching { Core.chats.sessionTotals(request.chat) }.getOrNull()
            } ?: return@mapLatest null
            SessionStats.of(totals, request.window)
        }

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
    // **并发结构**（#36 准备）：run loop 一旦离开主线程，这个表会在事件线程写、在打开会话时读。
    private val lastErrors = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * The conversation this process last showed, remembered so a new screen instance can adopt
     * it before its first frame instead of waiting for the marker file to be read.
     */
    private var lastOpenedId: String? = null

    /**
     * 最近打开过的会话正文，**最多 [MESSAGE_CACHE_SIZE] 个**，LRU 淘汰。
     *
     * 为什么需要它（用户实测）：0.8.316 把列表改成只读 meta 后，`openConversation` 每次都把
     * `_state` 换掉、正文清空，再从磁盘重读 —— 于是「切走再切回」看起来像**又要重新加载**。
     * 只有当前会话驻留内存是对的（54MB 全驻留正是 RSS 413MB 的根因），但**在几个会话之间来回
     * 切**是极常见的操作，每次都读盘是明显的退步。
     *
     * 容量 5（用户定的数）：足够覆盖"在几个会话间来回看"，最坏内存 = 5 × 12 轮，
     * 与"全部常驻"不是一个量级。存的是**读出来的那一页**（`readTail` 的结果），与屏幕一致。
     */
    private val messageCache = object : LinkedHashMap<String, List<StoredMessage>>(
        MESSAGE_CACHE_SIZE, 0.75f, true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<StoredMessage>>?,
        ): Boolean = size > MESSAGE_CACHE_SIZE
    }

    /** [messageCache] 的锁：它会同时被 UI 线程和读盘协程碰到。 */
    private val messageCacheLock = Any()

    private fun cachedMessages(chatId: String): List<StoredMessage>? =
        synchronized(messageCacheLock) { messageCache[chatId] }

    private fun cacheMessages(chatId: String, messages: List<StoredMessage>) {
        if (chatId.isEmpty() || messages.isEmpty()) return
        synchronized(messageCacheLock) { messageCache[chatId] = messages }
    }

    /**
     * Text waiting to be published, per conversation; see [handleEvent] and [DELTA_PUBLISH_MS].
     *
     * Two buffers, not one: the answer and the reasoning land in different parts of the live
     * bubble, and a single buffer would put half a thought into the answer.
     *
     * Concurrent on purpose, and guarded by `DeltaBuffer`'s lock on top of that: the deltas arrive on
     * the reader thread that delivers the stream (the endpoint is read in
     * `Dispatchers.IO`), while the main thread handles the other events of the same run and
     * flushes these very buffers. A plain map written from two of them does not only lose an
     * update — it loses the **tail of an answer**, because the reader thread appends into a
     * buffer the main thread has already taken out, and that text is never published.
     */
    // 攒批缓冲抽成 `DeltaBuffer`（带单测）：它负责三条**沉默失效**的规则 ——
    // 不丢（append 的每个字符最终被 take）、不漏（会话结束时残字必须丢弃，否则会拼到下一轮）、
    // 节流到点必须放行。`ChatViewModel` 里原来这些只能靠人眼守。
    private val deltaBuffer = DeltaBuffer()

    /**
     * Guards the delta buffers **and** the live copy they are published into.
     *
     * One lock for both, because publishing is one step: the buffered text is taken out of
     * the buffer and written into the live copy of the conversation, and the two must not
     * come apart — a live copy written from a stale read throws the text away again.
     *
     * A plain monitor rather than a `Mutex`: the accessors are not suspending functions
     * (the engine's event callback is a plain call), and a `Mutex` can only be taken from
     * inside a coroutine. The same shape as [steersLock].
     *
     * Held for one event and nothing else: no suspending call, no callback into this class
     * except the [removeSteer]/[publishSteers] pair of the 插话 event, which takes
     * [steersLock] — always in that order, and [steersLock] is never held while this one
     * is wanted, so there is no lock order to get wrong.
     */

    /**
     * Appends a piece of the answer and of the thinking to the buffers of [chatId].
     *
     * **Caller holds the delta lock.** A [StringBuilder] that one thread writes while another
     * reads it out of the map is a garbled answer, and a buffer that is taken out of the map
     * while a delta is being appended to it is a delta that is never published.
     */
    private fun appendDeltaLocked(chatId: String, text: String, reasoning: String) =
        deltaBuffer.append(chatId, text.ifEmpty { null }, reasoning.ifEmpty { null })

    /**
     * Whether the buffered text may be published now. The clock is read and set in one step,
     * so two threads cannot both decide that they may publish.
     *
     * **Caller holds the delta lock.**
     */
    private fun mayPublishLocked(chatId: String, now: Long): Boolean =
        deltaBuffer.mayPublish(chatId, now, DELTA_PUBLISH_MS)

    /**
     * Takes the buffered text and thinking of [chatId] out and hands them over.
     *
     * **Caller holds [deltaLock]** — the take and the publish that follows it are one step.
     */
    private fun takeDeltasLocked(chatId: String): Pair<String, String> =
        deltaBuffer.take(chatId)

    /**
     * Queued lines per conversation, oldest first.
     *
     * Kept here rather than only in the on-screen state so a queue survives a look at
     * another chat: the run it belongs to keeps going while the user is away, and coming
     * back must still show what is waiting — and still offer to retract it.
     *
     * Concurrent on purpose, like [sessions]: the composer's callbacks and the run's event
     * callback are not the same thread, and a plain map written from two of them does not
     * only lose an update — it can lose entries of its own. Every access goes through the
     * helpers below, which hold [steersLock] around the whole read-modify-write.
     */
    private val steers = ConcurrentHashMap<String, List<PendingSteer>>()

    /**
     * Guards the read-modify-write of [steers].
     *
     * A plain monitor rather than a `Mutex`: the accessors are not suspending functions
     * (the composer's callbacks and the engine's event callback are both plain calls), and
     * a `Mutex` can only be taken from inside a coroutine. The same shape as the steering
     * queues in the engine, which the UI thread writes while the run's coroutine drains.
     *
     * Held for one map operation and nothing else: no suspending call, no callback into
     * this class. [todoBoardsLock] is never held at the same time, so there is no lock
     * order to get wrong.
     */
    private val steersLock = Any()

    /** The queue of one conversation, read under [steersLock]. */
    private fun steersOf(chatId: String): List<PendingSteer> =
        synchronized(steersLock) { steers[chatId].orEmpty() }

    /** Appends a line to a queue — the read and the write are one step. */
    private fun enqueueSteer(chatId: String, line: PendingSteer) {
        synchronized(steersLock) { steers[chatId] = steers[chatId].orEmpty() + line }
    }

    /** Takes a line off a queue by id — the read and the write are one step. */
    private fun removeSteer(chatId: String, id: String) {
        synchronized(steersLock) {
            steers[chatId] = steers[chatId].orEmpty().filterNot { it.id == id }
        }
    }

    /** Forgets a conversation's queue entirely. */
    private fun clearSteers(chatId: String) {
        synchronized(steersLock) { steers.remove(chatId) }
    }

    /**
     * Turns typed while a run was going in 排队 mode, waiting for it to finish.
     *
     * The other half of the same question — see [PendingTurn]: a line is either an interruption or
     * the next turn, and this is where the next turn waits. Keyed by conversation so a turn queued
     * in one chat cannot be sent into another, and drained when that chat's run reports itself
     * finished.
     */
    private val pendingTurns = ConcurrentHashMap<String, List<PendingTurn>>()

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
     * 哪些会话的运行是**用户按中断按钮**停的：停止不是失败，不写回执、不留红色提示。
     * 按 chatId 记 —— 多个会话可以同时跑，单个布尔会把别的会话的真实失败一起吞掉。
     */
    // **并发结构**（#36 准备）：用户点停（主线程）写，事件线程读。
    private val userStopped: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

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
     *
     * 并发容器 + [todoBoardsLock]：这份缓存由事件线程写（[refreshTodos] 跟着 todo 工具事件
     * 回来）、由界面线程读（[openConversation] 画板子），和 [sessions] 一样不能是普通 map。
     */
    private val todoBoards = ConcurrentHashMap<String, List<TodoItem>>()

    /**
     * Guards the read-modify-write of [todoBoards].
     *
     * Never held at the same time as [steersLock], and never held across a suspending call
     * or a callback into this class — see [steersLock] for why it is a plain monitor.
     */
    private val todoBoardsLock = Any()

    /** The board of one conversation, read under [todoBoardsLock]. */
    private fun todosOf(chatId: String): List<TodoItem> =
        synchronized(todoBoardsLock) { todoBoards[chatId].orEmpty() }

    /** Replaces the board of one conversation — the read and the write are one step. */
    private fun setTodos(chatId: String, board: List<TodoItem>) {
        synchronized(todoBoardsLock) { todoBoards[chatId] = board }
    }

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
    // **并发结构**（#36 准备）：用户操作写，事件线程 `containsKey` 读。
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

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
        // **不再用 cached 直接当正文**（#30）：`load()` 现在只读 meta，`cached.messages` 是空的。
        // 这里只把"在哪个会话"定下来（conversationId/title），正文交给下面那个 IO 协程的
        // `readTail()` —— 它会读出最近 N 轮。`loading = true` 保留，否则首帧会闪一个空会话。
        lastOpenedId?.let { id ->
            Core.chats.conversations.value.firstOrNull { it.id == id }?.let { cached ->
                _state.value = cached.toUiState("").copy(loading = true)
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
                            sessions[chatId]?.let { before ->
                                // **先 flush 再取快照**（可达性已证）：这条分支由引擎的
                                // `RunState.Cancelled`（**状态**观察）触发，**不经过 `handleEvent`**
                                // ⇒ 没有任何地方替它 flush。而攒批最多 80ms 才发一次 ——
                                // 用户点「停止」时若刚到一个 chunk，它还在 `DeltaBuffer` 里。
                                // 原来 `saveSession(live)` 捕获的是 **flush 之前**那个对象，
                                // `endSession` 里的 flush 再写回 `sessions` 也追不回这份快照
                                // ⇒ 那段输出不会进文件（用户看到「最后一句话没了」）。
                                flushDeltas(chatId)
                                val live = sessions[chatId] ?: before
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
                // **正文只读尾部**（#30）：`existing` 来自只读 meta 的列表，`messages` 是空的。
                // `readTail` 读最近 OPEN_TURNS 轮；读不到（老格式且未迁移）时它内部回退到旧文件。
                val opened = Core.chats.readTail(existing.id) ?: existing
                _state.value = opened.toUiState(assistantId).copy(loading = false)
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

    /**
     * Reads one more page of older turns from disk and prepends it to the messages on screen.
     *
     * #30 made opening a chat read only the tail, so "加载更早" can no longer just raise a number in
     * memory — the older messages are not in the list at all. This walks the log backwards from the
     * current window and returns true when something was actually added (the screen then re-anchors
     * on the item it was reading, which it already does on every list change).
     *
     * Returns false when there is nothing older, or the chat has no store copy yet.
     */
    fun loadEarlier(turns: Int = Core.chats.openTurns()): Boolean {
        val chatId = _state.value.conversationId
        if (chatId.isEmpty()) return false
        val shown = _state.value.messages
        if (shown.isEmpty()) return false
        // 已经读到文件开头就别再读了。
        if (_state.value.messageCount in 1..shown.size) return false
        viewModelScope.launch {
            val older = Core.chats.readEarlierBefore(chatId, shown.first().id, turns) ?: return@launch
            if (older.isEmpty()) return@launch
            if (_state.value.conversationId != chatId) return@launch
            _state.update { current ->
                // 双保险：只接在**同一个**头部之前，避免期间列表被替换过。
                if (current.messages.firstOrNull()?.id != shown.first().id) {
                    current
                } else {
                    val merged = older + current.messages
                    cacheMessages(chatId, merged)
                    current.copy(messages = merged)
                }
            }
        }
        return true
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
    /**
     * 记住「上次打开的是哪个」，供下次启动用。
     *
     * **不再每次切会话都写盘**（#35）：一次点击 = 一次 `writeAtomically`（临时文件 +
     * rename + fsync），而它只需要**最终值** —— 快速连点几个会话会连写几次。
     * 现在 debounce 1.2 秒：停下来才写。
     */
    private fun rememberOpened(chatId: String) {
        if (chatId.isEmpty()) return
        lastOpenedId = chatId
        lastOpenedJob?.cancel()
        lastOpenedJob = viewModelScope.launch {
            delay(LAST_OPENED_DEBOUNCE_MS)
            Core.chats.saveLastOpened(chatId)
        }
    }

    /** 排队中的「记住上次打开」写盘（debounce 用）。 */
    private var lastOpenedJob: Job? = null

    fun openConversation(conversation: Conversation) {
        // Anything the conversation on screen was still holding is published before the
        // display moves: a buffered tail would otherwise land in the conversation being
        // opened, or be lost with the one being left.
        flushDeltas(_state.value.conversationId)
        // 后台会话按上面的规则只缓冲不发布 ⇒ 切回来时要先把它攒的 delta 冲进 `sessions`，
        // 否则刚建的界面缺最后一截（而且下一条事件又会把它叠在旧内容后面）。
        flushDeltas(conversation.id)
        val live = sessions[conversation.id]
        // **先查缓存**（#35）：命中就带正文建状态，连读盘协程都不用起。
        val cached = cachedMessages(conversation.id)
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
                pendingSteers = steersOf(conversation.id),
                // And so does the board: it is read from disk on tool events, so a state built
                // here would otherwise be the one place it is missing. 只取这个会话自己那一格。
                todos = todosOf(conversation.id),
            )
        } else {
            // A conversation that never picked a persona keeps the one already on screen,
            // which was seeded from the settings when this screen was created.
            //
            // **正文要自己读**（#30）：传进来的 `conversation` 来自只读 meta 的列表，`messages`
            // 是空的。`openConversation` 不是挂起函数（它是点击回调），所以这里**先按 meta
            // 建状态、带上 loading**，正文由下面那段协程补读 —— 补读期间显示骨架，
            // 不会闪一个空白会话，也不会让 `save()` 把空列表当成"变短"。
            conversation.toUiState(_state.value.assistantId).let { base ->
                // **命中 LRU 缓存就直接带正文**（#35）：原来缓存命中也要先 loading=true
                // 再补读，「切走再切回」会闪一次骨架。
                if (cached != null) {
                    val stored = Core.chats.conversations.value
                        .firstOrNull { it.id == conversation.id }
                    base.copy(
                        messages = cached,
                        messageCount = stored?.messageCount ?: cached.size,
                        turnCount = stored?.turnCount ?: 0,
                        checkpoints = stored?.checkpoints.orEmpty(),
                        loading = false,
                        error = failure,
                        pendingSteers = steersOf(conversation.id),
                    )
                } else {
                    base.copy(
                        loading = true,
                        error = failure,
                        // A run that ended with lines still queued keeps them: they are the
                        // user's words, and the rows say they never went out.
                        pendingSteers = steersOf(conversation.id),
                    )
                }
            }
        }
        // **补读正文**（#30 + LRU 缓存）：上面只按 meta 建了状态，这里把最近 N 轮读进来。
        // 先查缓存：「切走再切回」在几个会话之间来回看是极常见的操作，不该每次都读盘。
        //
        // 条件都是"这个会话还在屏幕上"：用户可能已经切走，那就别再写回状态去覆盖新会话。
        // 只有**没命中缓存**才读盘（#35：命中时上面已经带正文建好状态了）。
        if (cached == null) viewModelScope.launch {
            val opened = Core.chats.readTail(conversation.id)
            if (opened != null && _state.value.conversationId == conversation.id &&
                _state.value.messages.isEmpty()
            ) {
                cacheMessages(conversation.id, opened.messages)
                _state.update {
                    it.copy(
                        messages = opened.messages,
                        checkpoints = opened.checkpoints,
                        messageCount = opened.messageCount,
                        turnCount = opened.turnCount,
                        loading = false,
                    )
                }
            } else if (_state.value.conversationId == conversation.id) {
                _state.update { it.copy(loading = false) }
            }
        }
        // A run that was killed left its half-written answer in a draft file: it belongs to
        // this conversation, so it is picked up the moment the conversation is.
        // 绝大多数会话**没有草稿**（`.live.json` 只在一轮跑到一半被杀时才有），
        // 所以先 stat 一下再决定要不要起协程（#35）。
        if (Core.chats.hasLiveDraft(conversation.id)) {
            viewModelScope.launch { mergeLiveDraft(conversation.id) }
        }
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
        clearSteers(conversation.id)
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
        // **喂给模型的历史必须是完整 wire，不是屏幕上那个窗口**（#30 最要紧的一条）。
        // 打开会话只读最近 OPEN_TURNS 轮，`_state.value.messages` 就是那个窗口；
        // 直接拿它当历史 ⇒ 模型看不到更早的对话，**不报错、只是变笨**，比崩溃难查得多。
        //
        // `send()` 不是挂起函数（它是输入框的回调），所以：屏幕上的历史已经是完整的
        // （`messageCount == messages.size`，短会话）就直接发；否则先进协程把完整 wire 读出来再发。
        val shownMessages = _state.value.messages
        if (_state.value.messageCount <= shownMessages.size) {
            start(fresh, shownMessages)
        } else {
            val chatId = _state.value.conversationId
            viewModelScope.launch {
                // 读盘失败不能让发送带崩（`viewModelScope` 里未捕获的异常会打到主线程）。
                //
                // 降级方式：用屏幕上的历史继续发。**这里刻意不写 `_state.error`** ——
                // `start()` 开头就会 `error = null`（它要清掉上一轮的红条），
                // 在这里写等于白写；而下一次 `start` 之后用户已经看到消息发出去了，
                // 再补一条红条只会让人以为这一轮失败了。上下文不完整是次要的，
                // 而且坏数据本身已经在 `ChatStore` 层被记下（`LogDamaged`/`LogMismatch`）。
                val wire = runCatching { Core.chats.readWire(chatId) }.getOrNull()
                if (_state.value.conversationId != chatId) return@launch
                // 空结果与异常走同一条兜底：用屏幕上这一段。
                // **刚发的这条必须自己拼上去**（P0：用户实测「我发的新对话，助手回的是上一轮
                // 我说的那句」）。
                // 依据（读代码）：`ChatRepository.readWire` 只读盘上的日志（`store.readMeta`
                // → `store.all` / `store.from`），不看内存 `_state`；而这一条此刻还没落盘
                // （落盘在这一轮跑完之后）⇒ history 的最后一条是**上一轮**的用户消息，
                // 模型就去回答它。
                // 兜底用 `shownMessages`（它是在 `_state.update` 之后取的，已含这条）。
                // 防重：万一将来 wire 也带上了它，末条相等就不再拼。
                val filled = wire.orEmpty()
                val history = when {
                    filled.isEmpty() -> shownMessages
                    filled.lastOrNull()?.id == user.id -> filled
                    else -> filled + user
                }
                start(fresh, history)
            }
        }
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
        // **缩短必须在这一刻显式落盘**：editAndResend 真的把改正后的问题之后的内容丢掉了，
        // 这件事只有这里知道。若等后面某一轮 persistDisplayed() 去写，那次调用看到
        // "盘上 6 条、手里 3 条"只会正确地判定为**误传了半页**并拒写（护栏 allowShrink=false），
        // 于是编辑重答永远存不下去。
        viewModelScope.launch {
            save(
                chatId = snapshot.conversationId,
                title = snapshot.title,
                messages = kept,
                assistantId = snapshot.assistantId,
                activeRuns = _state.value.activeRuns,
                providerId = snapshot.providerId,
                model = snapshot.model,
                reasoningEffort = snapshot.reasoningEffort,
                checkpoints = snapshot.checkpoints,
                allowShrink = true,
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
        // 停止不是失败：见 start() 里 catch 的用法（用户：「点击中断按钮中断的不必弹出错误提示」）。
        userStopped += chatId
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
            enqueuePendingTurn(
                chatId,
                PendingTurn(UUID.randomUUID().toString().take(8), body, imagesBase64),
            )
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
        enqueueSteer(chatId, PendingSteer(id, body, imagesBase64))
        publishSteers(chatId)
        clearDraft(chatId)
        return true
    }

    /** Sends a queued line now, cutting the round in flight so the model sees it at once. */
    fun promoteSteer(id: String) {
        val chatId = _state.value.conversationId
        val queued = steersOf(chatId).firstOrNull { it.id == id } ?: return
        if (Core.engine.promote(chatId, id)) return
        // No round left to interrupt: the line goes out as an ordinary message instead of
        // being silently dropped — but only because the user asked for it now.
        retractSteer(id)
        if (send(queued.text, queued.imagesBase64)) return
        // The send was refused after all — another run in this conversation got there first. The
        // line has already been taken off the board, so putting it back is the only way it is not
        // lost without a word: the row returns and the user can send it again.
        // Read-modify-write under the lock: an append that lost the update would drop a line
        // the user had queued in the meantime.
        enqueueSteer(chatId, queued)
        publishSteers(chatId)
    }

    /** Drops a queued line before the model ever reads it. */
    fun retractSteer(id: String) {
        val chatId = _state.value.conversationId
        Core.engine.retract(chatId, id)
        removeSteer(chatId, id)
        publishSteers(chatId)
    }

    /**
     * Drops one waiting 排队 turn.
     *
     * By id: the row carries three actions and each has to name the line it acts on, so the turn
     * needs the same kind of handle a queued steer has.
     */
    fun retractTurn(chatId: String, id: String) {
        removePendingTurn(chatId, id)
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
        val queued = takePendingTurn(chatId, id) ?: return
        publishPendingTurns(chatId)
        if (Core.engine.steer(chatId, id, queued.text, imagesBase64 = queued.imagesBase64)) {
            // It is an interruption now, so it is drawn as one: the same bubble a steer gets.
            enqueueSteer(chatId, PendingSteer(id, queued.text, queued.imagesBase64))
            publishSteers(chatId)
            return
        }
        enqueuePendingTurn(chatId, queued)
        publishPendingTurns(chatId)
    }

    /** Mirrors one conversation's queue onto the screen, when that is the one on screen. */
    private fun publishSteers(chatId: String) {
        if (_state.value.conversationId != chatId) return
        // The queue is read **inside** the update's lambda, under the lock: the state update is
        // a compare-and-set loop, so a lost race re-reads the queue and publishes the newer
        // one. Reading it once before the update would let a later publish overwrite a newer
        // one with an older list.
        _state.update { it.copy(pendingSteers = synchronized(steersLock) { steers[chatId].orEmpty() }) }
    }

    /**
     * 排队 turn 的队列（issue #29）：跟 [steers] 一样的形状 —— UI 线程写（[steer]/
     * [retractTurn]/[promoteTurn]），IO 线程读改写（`handleEvent` 里的 [drainPendingTurns]），
     * 原来的普通 map 会**静默丢更新**（用户排的 turn 凭空消失）。同一把锁把「读-改-写」收成一步。
     *
     * [draining] 也在这把锁里动：它防的是两个 drain 同时发同一条（发两次比丢一条更糟）。
     */
    private val pendingTurnsLock = Any()

    /** 队列里的 排队 turn（读，锁内）。 */
    private fun pendingTurnsOf(chatId: String): List<PendingTurn> =
        synchronized(pendingTurnsLock) { pendingTurns[chatId].orEmpty() }

    /** 入队（锁内；读-改-写合成一步）。 */
    private fun enqueuePendingTurn(chatId: String, turn: PendingTurn) {
        synchronized(pendingTurnsLock) { pendingTurns[chatId] = pendingTurns[chatId].orEmpty() + turn }
    }

    /** 按 id 摘掉一条（锁内）。 */
    private fun removePendingTurn(chatId: String, id: String) {
        synchronized(pendingTurnsLock) {
            pendingTurns[chatId] = pendingTurns[chatId].orEmpty().filterNot { it.id == id }
        }
    }

    /** 按 id 取出一条并同时把它摘掉（锁内，合成一步 ⇒ 不会取到别人已经摘走的那条）。 */
    private fun takePendingTurn(chatId: String, id: String): PendingTurn? =
        synchronized(pendingTurnsLock) {
            val list = pendingTurns[chatId].orEmpty()
            val hit = list.firstOrNull { it.id == id } ?: return@synchronized null
            pendingTurns[chatId] = list.filterNot { it.id == id }
            hit
        }

    /** 取队首并把它摘掉（锁内；发送被拒绝时要放回队首，见 [drainPendingTurns]）。 */
    private fun takeHeadPendingTurn(chatId: String): PendingTurn? =
        synchronized(pendingTurnsLock) {
            val list = pendingTurns[chatId].orEmpty()
            val head = list.firstOrNull() ?: return@synchronized null
            pendingTurns[chatId] = list.drop(1)
            head
        }

    /** 放回队首（发送被拒绝时，锁内）。 */
    private fun restoreHeadPendingTurn(chatId: String, turn: PendingTurn) {
        synchronized(pendingTurnsLock) {
            pendingTurns[chatId] = listOf(turn) + pendingTurns[chatId].orEmpty()
        }
    }

    /** 这个会话正在排空吗（防重入；锁内）。 */
    private fun tryBeginDrain(chatId: String): Boolean =
        synchronized(pendingTurnsLock) { draining.add(chatId) }

    /** 排空结束（锁内）。 */
    private fun endDrain(chatId: String) {
        synchronized(pendingTurnsLock) { draining.remove(chatId) }
    }

    /** The same, for the 排队 turns waiting to become the next run. */
    private fun publishPendingTurns(chatId: String) {
        if (_state.value.conversationId != chatId) return
        _state.update { it.copy(pendingTurns = pendingTurnsOf(chatId)) }
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
        if (!tryBeginDrain(chatId)) return
        try {
            val next = takeHeadPendingTurn(chatId) ?: return
            if (_state.value.conversationId != chatId) return
            publishPendingTurns(chatId)
            if (send(next.text, next.imagesBase64)) return
            // Refused: back to the head, where the next trigger will try again. Nothing is lost and
            // nothing was sent.
            restoreHeadPendingTurn(chatId, next)
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
            endDrain(chatId)
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
            setTodos(chatId, board)
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
     * **没有 run 时**手动压缩：按已落盘的历史压一次，并把结果记成一条压缩记录。
     *
     * 记录落进 [ChatUiState.checkpoints]，转录里就会在它覆盖的那一段位置出现一张压缩块 ——
     * 和工具块、思考块一样是卡片，不用另做一套界面（用户：「就气泡＋压缩块就行了」）。
     * 压完顺手刷新圆环的上下文读数。
     */
    fun compressNow() {
        val shown = _state.value
        val chatId = shown.conversationId
        if (chatId.isEmpty()) return
        // 已经有 run 在跑：交给引擎在轮次边界处理，不在这里并行再压一次。
        if (jobs.containsKey(chatId)) return
        if (compressing.contains(chatId)) return
        compressing += chatId
        // 状态行要立刻有反应：开始时刻进状态，ChatScreen 据此画「正在压缩上下文 · 用时 xx」。
        _state.update { it.copy(compressingSince = System.currentTimeMillis()) }
        viewModelScope.launch {
            try {
                val base = Core.settings.current()
                val assistant = base.assistant(shown.assistantId) ?: base.activeAssistant()
                // 与 start() 走同一条端点链：会话自己的选择 → 人设的固定 → 全局默认。
                val persona = base.forAssistant(assistant)
                val choice = base.choiceFor(shown.providerId, shown.model, assistant)
                val settings = persona.copy(
                    baseUrl = choice.baseUrl,
                    apiKey = choice.apiKey,
                    model = choice.model,
                    // 协议跟着这条链走：会话/人设选了哪个端点，就按那个端点说的话发。
                    protocol = choice.protocol,
                    reasoningEffort = shown.reasoningEffort ?: persona.reasoningEffort,
                )
                val history = sessions[chatId]?.messages ?: shown.messages
                // **锚点也要能在没有 run 时成立**：单独压缩时没有 live 会话，退回当前转录的
                // 最后一条消息 —— 否则压缩块会按"被折叠的最后一条"落到旧历史里（看不见）。
                val anchorId = sessions[chatId]?.messages?.lastOrNull()?.id
                    ?: shown.messages.lastOrNull()?.id.orEmpty()
                Core.engine.compressNow(history, settings) { checkpoint ->
                    recordCheckpoint(chatId, checkpoint.copy(anchorId = anchorId))
                }?.let { reason ->
                    // 压缩没成就把原因说出来（消息太少 / 网络失败 / 模型没给摘要）——
                    // 以前这里把返回值丢了，用户看到的就是"点了没反应"。
                    _state.update { it.copy(error = reason) }
                }
            } finally {
                compressing -= chatId
                _state.update { it.copy(compressingSince = 0L) }
            }
        }
    }

    /** 正在手动压缩的会话，防止连点。 */
    // **并发结构**（#36 准备）：压缩协程写，事件线程判定。
    private val compressing: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

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
                protocol = choice.protocol,
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
        val carried = steersOf(chatId)
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
        setTodos(chatId, emptyList())
        // 新一轮开始：清掉上一轮留下的红色提示，并把"用户停止"标记复位（否则这一轮真失败会被
        // 当成用户中断而不再报出来）。
        userStopped -= chatId
        _state.update { it.copy(todos = emptyList(), error = null) }
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
                    // One snapshot under the lock, not a lookup per line.
                    val stillWaiting = steersOf(chatId)
                    carried.filter { line -> stillWaiting.any { it.id == line.id } }
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
                    // 协议也走这条链：这条会话落在哪个端点，就按那个端点的格式发。
                    // 少了它，Anthropic/Responses 端点会收到 OpenAI 形状的请求体。
                    protocol = choice.protocol,
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
                val described = listOf(describeError(error), note)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                // 用户按了中断按钮：这不是失败。传输层被取消时抛的是 IOException("Canceled") 而不是
                // CancellationException，会掉进这个分支被当成失败 —— 写回执、留红色提示（用户：
                // 「点击中断按钮中断的不必弹出错误提示」）。此时只把状态收成「已停止」，不写回执、不留错误。
                if (chatId in userStopped) {
                    status = "已停止"
                } else {
                    failure = described
                }
                // 「tool name is invalid or duplicated」这类 400 的最小探针全部 200（点号/横杠/重名/
                // 插件全 schema/带未知工具名的历史，两个通道都测过），复现不出确切触发条件 ⇒ 让它
                // 自己报：把这一轮的工具表与历史引用过的工具名写进 trace，下次发生直接读日志定位。
                if (failure?.contains("tool name", ignoreCase = true) == true) {
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
                                            text = FAILURE_RECEIPT_PREFIX + failure,
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
                    // 性能记录：**这一轮真的发生了什么**。首字延迟、缓存命中、prompt/completion
                    // 都取自本轮落下的那条回答（StoredMessage 本身就带着端点的回报），不另做估算。
                    // 请求体大小目前没有采集点，留 0 —— 监测页会把它显示成「—」，不是「测到零」。
                    val answered = sessions[chatId]?.messages
                        ?.lastOrNull { it.role == Roles.ASSISTANT && it.promptTokens > 0 }
                    val runStats = _state.value.stats
                    if (answered != null || runStats != null) {
                        Core.perf.record(
                            PerfRecord(
                                at = System.currentTimeMillis(),
                                conversationId = chatId,
                                ttftMs = answered?.firstTokenMs ?: 0,
                                totalMs = answered?.elapsedMs ?: (runStats?.elapsedMs ?: 0),
                                promptTokens = answered?.promptTokens ?: (runStats?.lastPromptTokens ?: 0),
                                completionTokens = answered?.completionTokens ?: (runStats?.tokensOut ?: 0),
                                cachedTokens = answered?.cachedTokens ?: 0,
                            ),
                        )
                    }
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
        // **一次事件里的「收 delta → 判断该不该发 → 取走缓冲」必须在一个临界区里做完**
        // （issue #29）：这三步分开做时，IO 线程刚 append 完、另一线程的 flush 立刻把缓冲
        // 取走 ⇒ 那一段输出凭空消失；`lastPublishAt` 的「每 DELTA_PUBLISH_MS 最多发一次」
        // 也会被两个线程同时判定为「该发」。
        //
        // 临界区里不做任何挂起调用、也不回调本类 ⇒ 不会死锁。
        var pending: Pair<String, String> = "" to ""
        // `locked` 不是 inline（它要守住 DeltaBuffer 内部的锁），所以**不能**在块里 `return` ——
        // 用 `hold` 标记"这次不发布"，出块后再 return。语义与原来的 `synchronized` + return 一致。
        var hold = false
        deltaBuffer.locked {
            if (textDelta) appendDeltaLocked(chatId, event.text, "")
            if (reasoningDelta) appendDeltaLocked(chatId, "", event.text)
            if (textDelta || reasoningDelta) {
                val now = SystemClock.uptimeMillis()
                // 还没到节拍：缓冲留着，攒到下一次一起发（与改动前一致）。
                if (!mayPublishLocked(chatId, now)) {
                    hold = true
                    return@locked
                }
                // **后台会话不发布**（用户实测：「一个会话在对话中，切到别的会话滑动会卡」）。
                //
                // 屏幕上根本不是它，发布出去只是在 `sessions` 里做 O(n) 字符串拼接 + 列表复制
                // （`applyPending`），而引擎的事件是在 Main 上处理的 ⇒ 正在看另一个会话的人
                // 就感到卡。delta **继续缓冲**（不丢），下一次非 delta 事件（工具行、落盘、
                // 一轮结束）照常 flush；切回来时 `openConversation` 也会先 flush 一次。
                if (_state.value.conversationId != chatId) {
                    hold = true
                    return@locked
                }
            }
            // Whatever has arrived so far, applied in one go. For a non-delta event this is the
            // flush; for a delta it is the publish that got through the interval above.
            pending = takeDeltasLocked(chatId)
        }
        if (hold) return
        val pendingText = pending.first
        val pendingReasoning = pending.second
        val base = applyPending(live, pendingText, pendingReasoning)
        val next = when (event) {
            // Already folded into `base` above.
            is AgentEvent.TextDelta -> base.clearRetryStatus()

            is AgentEvent.ReasoningDelta -> base.clearRetryStatus()

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
                    text = FAILURE_RECEIPT_PREFIX + event.message,
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
                // 用户自己按了中断：取消路径上引擎仍可能报 Failed，那不是失败 —— 不落回执、
                // 不留红色提示（用户：「点击中断按钮中断的不必弹出错误提示」）。
                if (chatId in userStopped) base else base.copy(error = event.message, messages = messages)
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
                    status = RETRY_STATUS_PREFIX +
                        "，${event.delayMs / 1000} 秒后重试（第 ${event.attempt} 次）· ${event.reason}",
                    // 过程块：连续重试更新同一块（「自动重试 1 次」→「自动重试 2 次」），不叠成两行。
                    parts = base.parts.traceRetry("自动重试 ${event.attempt} 次 · ${event.reason}"),
                )
            }

            is AgentEvent.Finished -> {
                retriesThisRun = 0
                // 这一轮成功了：屏幕上不该再挂着任何错误。重试成功的轮次走的是过程留痕
                // （StoredMessage.traceNote），从不设 error；这里清一次是给所有成功路径的保证
                // （用户：「整轮对话是成功的话 就不要有 错误提示卡片存在」）。
                live.copy(error = null)
            }

            // 这一轮作废重来（收到内容之后流断了、而且还能重试）：把本段已经流出去的内容撤掉，
            // 否则重发的那一轮会在气泡里把同一段开头再画一遍。
            AgentEvent.RoundRestarted -> base.copy(
                parts = base.parts.take(base.segmentStart.coerceIn(0, base.parts.size)),
                streaming = "",
                reasoning = "",
            )

            // 压缩摘要正在生成：状态行改说这件事（run 中也要有，用户要求）。结束就清空，
            // 下一句状态由后续事件覆盖。
            is AgentEvent.Compressing -> base.copy(
                status = if (event.on) "正在压缩上下文" else "",
            )

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
                removeSteer(chatId, event.id)
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
        val live = sessions[chatId]
        if (live == null) {
            // 会话已经没了：缓冲里的文字无处可去，**必须丢掉**。
            // 留着它会在同一个会话的**下一轮**被 `takeDeltasLocked` 取走、拼到新内容前面 ——
            // 那是「新回答开头莫名多出一段上一轮的话」这种幽灵 bug，且极难复现。
            deltaBuffer.drop(chatId)
            return
        }
        // 同一个锁：取走与事件线程的 append 互斥（否则刚收到的 delta 会被这次 flush 顺手带走）。
        val (text, reasoning) = deltaBuffer.locked { takeDeltasLocked(chatId) }
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

    /**
     * 重试留痕：连续重试只更新同一块（「自动重试 1 次」→「自动重试 2 次」），不叠成两行。
     */
    private fun List<LivePart>.traceRetry(note: String): List<LivePart> =
        if (lastOrNull() is LivePart.Trace) {
            dropLast(1) + LivePart.Trace(note)
        } else {
            this + LivePart.Trace(note)
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
        // **不在这里刷 LRU 缓存**（#35）：流式期间每 ~80ms 一次 `putSession`，每次抢
        // `messageCacheLock`，而切会话时的 `readTail` 读盘也在同一把锁上 —— 撞在一起就是
        // 可感知的抖动。缓存只在「一轮结束」时刷（见 [endSession]）就够：
        // 一轮中途切回来走的是 `sessions`（live 分支），根本不看缓存。
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
        // **先把攒着的 delta 冲进去，再摘会话**（0.8.333 引入的风险）：后台会话的 delta 现在
        // 只缓冲不发布（屏幕上看不到它，发布只是白做 O(n) 拼接）。而一轮结束是
        // `sessions.remove(chatId)` —— 若此时缓冲里还有没应用的尾巴，那段文字既不会进
        // 将要落盘的消息，也不会再有人来取（`flushDeltas` 找不到会话就直接返回）。
        // 用户看到的就是「回答少了一截」，且没有任何报错。
        flushDeltas(chatId)
        val live = sessions.remove(chatId)
        // 一轮结束：`sessions` 里那份没了，切回来只能靠 LRU 缓存 ⇒ 在这里刷一次
        // （#35：原来是在 `putSession` 里每 80ms 刷，太频繁）。
        if (live != null) cacheMessages(chatId, live.messages)
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
        val restored = liveDraftRecovery(
            draft,
            messages,
            // 用户自己按停：留空串 ⇒ 尾部不写任何说明（见 liveDraftRecovery）。引擎/超时那种
            // 被动中断仍写原因，用户才知道为什么断了。
            cancelReason = if (chatId in userStopped) "" else cancelReason,
        )
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
        // **锚点＝压缩发生那一刻最后一条已落盘消息**（用户：「运行时有这个块，会话完成一折叠就
        // 没有了」）：以前转录里那张卡按「被折叠的最后一条」定位，那是旧历史，跑完就滚到屏幕上方
        // 看不见了。锚在当下，它才和运行中看到的那一块在同一个位置。
        // 调用方**已经给过锚点**（无 run 的手动压缩，:1475）就用它的 —— 以前这里无条件覆盖成
        // `live?.messages…orEmpty()`，无 run 时把好锚点抹成空串，卡片又落回旧历史。
        val anchored = if (checkpoint.anchorId.isNotEmpty()) {
            checkpoint
        } else {
            checkpoint.copy(anchorId = live?.messages?.lastOrNull()?.id.orEmpty())
        }
        val current = live?.checkpoints ?: shown?.checkpoints ?: stored?.checkpoints.orEmpty()
        val next = (current + anchored).distinctBy { it.id }.takeLast(MAX_CHECKPOINTS)
        // Mirrored onto every live copy first, so the next save carries it whether the run
        // is still going or has just landed.
        if (shown != null) _state.update { it.copy(checkpoints = next) }
        live?.let {
            // 运行中的压缩要在过程区**原地**留一个块（用户：「在哪个部分进行的就在哪个部分渲染」：
            // 思考 → 工具 → 压缩 → 继续）。跑完之后由转录里那张 CheckpointCard 接管，锚点见上。
            putSession(
                it.copy(
                    checkpoints = next,
                    parts = it.parts + LivePart.Compress(anchored),
                ),
            )
        }
        viewModelScope.launch {
            // **只写 meta，不碰正文**（#30）：原来这里走 save(messages = base.messages) ——
            // 一次压缩就把整份转录（几十 MB）重写一遍；而且 `load()` 现在不再物化 messages，
            // `base.messages` 是空的，那个调用会被护栏判成「历史变短」。
            // `updateMeta` 只改字段，O(1)，且不可能动到消息。
            Core.chats.updateMeta(id = chatId, checkpoints = next)
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
        /** 只有「编辑重答」传 true：它有意把改正后的问题之后的内容丢掉。 */
        allowShrink: Boolean = false,
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
        // **保存失败不许把 app 崩掉**（真机实测：v0.8.320 一发送就闪退，
        // 堆栈是 `ChatRepository$save` 抛的 `IllegalStateException`，
        // 它在 `viewModelScope.launch` 里没人接 ⇒ 直接崩进程）。
        //
        // 落盘失败是**用户能理解、也能恢复**的事（磁盘满、日志损坏、存储格式改过），
        // 不是"App 该死"的事。所以：接住，把原因写进气泡里的红条，用户看得见。
        val outcome = runCatching {
            Core.chats.save(
                allowShrink = allowShrink,
                conversation = Conversation(
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
        }
        return outcome.fold(
            onSuccess = { derived },
            onFailure = { cause ->
                Log.w(TAG, "save failed for $chatId: ${cause.message}", cause)
                _state.update {
                    if (it.conversationId != chatId) it
                    else it.copy(
                        error = "保存失败：${cause.message ?: cause.javaClass.simpleName}",
                    )
                }
                null
            },
        )
    }

    override fun onCleared() {
        // debounce 中的「记住上次打开」可能还没落盘 —— 页面刚切走就退到后台时很容易撞上。
        // 用 appScope 写（viewModelScope 马上就要被取消），失败也无所谓：它只影响下次启动
        // 先打开哪个会话。
        lastOpenedJob?.cancel()
        lastOpenedId?.takeIf { it.isNotEmpty() }?.let { id ->
            Core.appScope.launch { runCatching { Core.chats.saveLastOpened(id) } }
        }
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
    // **总条数来自 meta**（#30）：`messages` 只是窗口（打开会话读最近 N 轮），
    // 调用方判断"屏幕上的历史是不是完整"就是比这两个数（见 send() 的 wire 分支）。
    messageCount = messageCount,
    turnCount = turnCount,
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
    // 空串＝用户自己按的停止：**一个字都不补**（用户：「用户自行点击的中断不需要错误提示」）。
    // 半截回答原样收尾，读起来就是「我说到这儿就够了」，而不是一次失败。
    val text = if (cancelReason.isBlank()) {
        body
    } else if (body.isEmpty()) {
        "（由「$cancelReason」原因，对话中断）"
    } else {
        body + "\n\n（由「$cancelReason」原因，对话中断）"
    }
    val line = if (delivered || (probe.isEmpty() && draft.reasoning.isNullOrBlank() && restored.isEmpty())) {
        emptyList()
    } else {
        listOf(
            StoredMessage(
                id = UUID.randomUUID().toString(),
                role = Roles.ASSISTANT,
                text = text,
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
 * 重试行写在状态行上的开头。
 *
 * 写和清是同一件事的两端：`AgentEvent.Retrying` 把「连接失败，N 秒后重试（第 K 次）· 原因」
 * 放进状态行，内容一到就由 [clearRetryStatus] 抹掉 —— 重试已经成功了，这一行再挂着，读起来
 * 就像这一轮仍在出错（用户：「整轮对话是成功的话 就不要有 错误提示卡片存在」）。
 */
private const val RETRY_STATUS_PREFIX = "连接失败"

/** 见 [RETRY_STATUS_PREFIX]：重试成功、内容已经到达，那一行就该让位给真正的回答。 */
private fun RunSession.clearRetryStatus(): RunSession =
    if (status.startsWith(RETRY_STATUS_PREFIX)) copy(status = "") else this

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
