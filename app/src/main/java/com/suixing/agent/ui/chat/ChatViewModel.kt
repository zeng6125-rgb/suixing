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
import com.suixing.agent.data.AppSettings
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
import com.suixing.agent.data.ChatRepository
import com.suixing.agent.data.ConversationLogShrunkException
import com.suixing.agent.data.MessageCache
import com.suixing.agent.llm.LocalNetworkAccess
import com.suixing.agent.llm.LlmClient
import com.suixing.agent.llm.LlmException
import com.suixing.agent.llm.describeError
import com.suixing.agent.service.AgentForegroundService
import com.suixing.agent.ui.settings.withUpstreamDefaults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
/**
 * 正文缓存容量（R2：5 → 8）。
 *
 * 原值 5 是“在几个会话之间来回切不该反复读盘”的估算，但抽屉里的会话通常不止 5 个：
 * 超过 5 个就开始**每次切都冷读**（骨架 + 读盘）。8 覆盖“最近一屏抽屉”的常见来回切。
 * 内存代价：每份是**一页尾部窗口**（OPEN_TURNS 轮），8 份 ≈ 8×一页，与“整份正文常驻”
 * 不是一个量级；要回退只需把这一个数字改回去。
 */
private const val MESSAGE_CACHE_SIZE = 8

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
    /** Pages loaded during this run; copied independently of in-flight stream snapshots. */
    val loadedPrefix: List<StoredMessage> = emptyList(),
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
    /** 此快照所属会话；异步重读统计时，StateFlow 可能仍保留上个会话的值。 */
    val conversationId: String = "",
) {
    internal fun forConversation(chatId: String): SessionStats? =
        takeIf { conversationId == chatId }

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

        internal fun of(
            totals: ChatTotals,
            contextWindow: Int,
            conversationId: String = "",
        ): SessionStats? {
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
                conversationId = conversationId,
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
    private val llm by lazy { Core.llm }
    /** Endpoints already asked in this process, so opening the sheet twice costs one call. */
    private val windowsAsked = mutableSetOf<String>()

    /**
     * 设置的最近快照：`send()` 不是挂起函数，而 `Core.settings.current()` 是挂起的。
     * 初值**用 null 而不是 `AppSettings()`**：默认值的 baseUrl 是空串，拿它判「是不是内网」
     * 会在 DataStore 首次发射前给出错误结论；null 表示「还不知道」，此时不拦。
     */
    private val settingsSnapshot: StateFlow<AppSettings?> = Core.settings.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _state = MutableStateFlow(ChatUiState())

    /** The message list the revision below was stamped for; touched only by the collector. */
    private val revisionStamp = MessageRevisionStamp()

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
        .map(revisionStamp::stamp)
        .stateIn(viewModelScope, SharingStarted.Eagerly, _state.value)

    internal val screenState: StateFlow<ChatUiState> = state
        .projectState { it.screenSnapshot() }

    internal val liveContent: Flow<LiveContent> = _state.map { it.liveContent() }.distinctUntilChanged()
    internal fun currentLiveContent(): LiveContent = _state.value.liveContent()

    /**
     * 流式统计，**独立于 [state]**（审计 3§1 Top1）：引擎的 stats ticker 每 120ms 发一次
     * `RunState.Running(stats())`，`elapsedMs` 走墙钟必变 —— 它原先被并进 ChatUiState，
     * 等于让整个 ChatScreen（58 个子组合）跟着 8.3 次/秒整屏重组。现在只有显示秒数的
     * 小叶子（Composer 的环、顶栏 chip）收集 [liveStats]。
     */
    private val _liveStats = MutableStateFlow<StreamStats?>(null)
    val liveStats: StateFlow<StreamStats?> = _liveStats.asStateFlow()

    /**
     * **请求级**统计：剥掉逐 tick 变化的 `elapsedMs` 后去重 —— 只在一次真实请求
     * （promptTokens/breakXxx/tokensOut 变化）时发射。屏幕 body 的环读数收集这个，
     * 不收 [liveStats]。
     */
    val requestStats: StateFlow<StreamStats?> = _liveStats
        .projectState { it?.copy(tokensOut = 0, thinkingTokens = 0, elapsedMs = 0L, tokPerSec = 0f) }

    /**
     * 「有没有活内容」的布尔：流式气泡开关与 pin 信号都只认它。活内容增长期间
     * state 因 equals 去重不发射，布尔天然稳定；只在开始/结束翻转时重组屏幕。
     */
    val liveActive: StateFlow<Boolean> = _state
        .projectState { it.liveParts.isNotEmpty() || it.reasoning.isNotEmpty() }

    val conversations: StateFlow<List<Conversation>> = Core.chats.conversations

    private var viewportOwner = ""
    private var viewportCapture: (() -> Unit)? = null
    private val _presentedConversation = MutableStateFlow<String?>(null)
    internal val presentedConversation = _presentedConversation.asStateFlow()

    internal fun bindViewport(chatId: String, capture: () -> Unit) {
        viewportOwner = chatId
        viewportCapture = capture
    }

    internal fun unbindViewport(capture: () -> Unit) {
        if (viewportCapture === capture) {
            viewportCapture = null
            viewportOwner = ""
            _presentedConversation.value = null
        }
    }

    internal fun presentViewport(chatId: String) {
        if (chatId == _state.value.conversationId) _presentedConversation.value = chatId
    }

    private fun leaveViewport() {
        if (viewportOwner == _state.value.conversationId) viewportCapture?.invoke()
        _presentedConversation.value = null
    }

    /** Shared per-chat totals; token deltas never enter this subscription. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val sessionStats: Flow<SessionStats?> = combine(screenState, Core.settings.flow, conversations) { snapshot, settings, chats ->
        // The window of the model this conversation would send with, not the app-wide
        // default's: a chat that picked a smaller-window endpoint must not read its own
        // usage against the wrong denominator.
        val assistant = settings.assistant(snapshot.assistantId) ?: settings.activeAssistant()
        val model = settings.choiceFor(snapshot.providerId, snapshot.model, assistant).model
        val window = settings.contextWindowFor(model)
        // **缓存键用盘上的条数/更新时间，不用窗口的条数**：窗口会随「加载更早」变，
        // 而整个会话的总账不变。
        val meta = chats.firstOrNull { it.id == snapshot.conversationId }
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
            SessionStats.of(totals, request.window, request.chat)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
     * 每个会话「当前已加载窗口」的起飞字节位置 + 日志代次。
     *
     * 为什么要有：`readEarlierBefore` 是按**消息 id** 找位置（`offsetOf` 从日志开头整份扫描），
     * 30MB 的会话里每次「加载更早」都要扫最多 30MB。`ChatStore` 的文档本来就写明设计意图是
     * 「用上一次返回的 `startOffset`」，但 ViewModel 一直走的是 id 那条路。
     *
     * 代次用来校验：换代重写（编辑重答）之后旧偏移没有意义，必须回退到按 id 查。
     */
    private val windowStarts = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()

    /**
     * 正在合并草稿的会话（S3）：三个入口可能并发进来，重复合并会各造一份 id ⇒ 撞护栏或触发整份重写。
     * 抽成 `InFlightGuard` 以便单测（`ChatViewModel` 本身构造依赖整个 `Core`，写不了单测）。
     */
    private val mergingLive = InFlightGuard()

    /**
     * The conversation this process last showed, remembered so a new screen instance can adopt
     * it before its first frame instead of waiting for the marker file to be read.
     */
    private var lastOpenedId: String? = null

    /**
     * 最近打开过的会话正文，LRU（容量 [MESSAGE_CACHE_SIZE]）。
     *
     * 逻辑在 `data/MessageCache` 里（纯 Kotlin、可单测）—— 原来这里有一份**内联实现**，
     * 而 `MessageCache` 是另一份**零引用**的重复实现，只有它自己的 5 条测试在用
     * ⇒ 测试测的不是生产代码（评审两次指出）。现在只有一份。
     */
    private val messageCache = MessageCache(MESSAGE_CACHE_SIZE)

    private fun cachedMessages(chatId: String): List<StoredMessage>? = messageCache.get(chatId)

    /**
     * @param diskBacked 这份 `messages` 是不是**已经在磁盘上**（决定合并草稿时能不能清草稿）。
     */
    private fun cacheMessages(
        chatId: String,
        messages: List<StoredMessage>,
        diskBacked: Boolean = false,
    ) {
        messageCache.put(chatId, messages, diskBacked)
    }

    /**
     * 缓存里的那份是否确认已落盘（与内容在同一格、同一把锁里读，**不可能脱节**）。
     *
     * 用 `entries.firstOrNull` 而**不是** `messageCache[chatId]`：后者是 `get`，在
     * `accessOrder = true` 的 LinkedHashMap 上会把这个会话移到"最近使用" ——
     * 读一个标记不该改变淘汰顺序（将来若有清理全量会话的调用，会给后台会话续命）。
     * 容量只有 5，线性找也是常数级。
     */
    private fun cacheIsDiskBacked(chatId: String): Boolean = messageCache.isDiskBacked(chatId)

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
     * inside a coroutine. The same shape as [PendingMessageQueues]' internal monitor.
     *
     * Held for one event and nothing else: no suspending call, no callback into this class
     * except the [removeSteer]/[publishSteers] pair of the 插话 event, which takes
     * the steering queue's monitor — always in that order. Queue methods never call back
     * while holding their monitor, so there is no reverse lock order.
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
     * helpers below; the queue owns the whole read-modify-write under one monitor.
     */
    private val steers = PendingMessageQueues<PendingSteer> { it.id }

    /** The queue of one conversation, read under its own monitor. */
    private fun steersOf(chatId: String): List<PendingSteer> = steers.list(chatId)

    /** Appends a line to a queue — the read and the write are one step. */
    private fun enqueueSteer(chatId: String, line: PendingSteer) {
        steers.add(chatId, line)
    }

    /** Takes a line off a queue by id — the read and the write are one step. */
    private fun removeSteer(chatId: String, id: String) {
        steers.take(chatId, id)
    }

    /** Forgets a conversation's queue entirely. */
    private fun clearSteers(chatId: String) {
        steers.forget(chatId)
    }

    /**
     * Turns typed while a run was going in 排队 mode, waiting for it to finish.
     *
     * The other half of the same question — see [PendingTurn]: a line is either an interruption or
     * the next turn, and this is where the next turn waits. Keyed by conversation so a turn queued
     * in one chat cannot be sent into another, and drained when that chat's run reports itself
     * finished.
     */
    private val pendingTurns = PendingMessageQueues<PendingTurn> { it.id }

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
     * Never held at the same time as a pending-message queue's monitor, nor across a
     * suspending call or a callback into this class.
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
    internal val drafts: StateFlow<Map<String, Draft>> = _drafts.asStateFlow()
    internal fun draftFor(chatId: String): Draft = _drafts.value[chatId] ?: Draft()

    /**
     * The decoded attachment per conversation. The draft keeps only its uri and name
     * (what a restore can mean); the decoded bitmap lives here for the session, so a
     * chip keeps its thumbnail across a drawer switch without re-decoding.
     */
    private val attachmentCache = ConcurrentHashMap<String, List<Attachment>>()

    /** 追加一张图/一个文件到这条会话的附件列（多附件：不再覆盖上一张）。 */
    fun addAttachment(chatId: String, attachment: Attachment) {
        attachmentCache.compute(chatId) { _, current -> current.orEmpty() + attachment }
    }

    /** 删掉附件列里第 [index] 个；删空即移除整条缓存。 */
    fun removeAttachmentAt(chatId: String, index: Int) {
        attachmentCache.computeIfPresent(chatId) { _, current ->
            if (index !in current.indices) current
            else current.filterIndexed { at, _ -> at != index }.takeIf { it.isNotEmpty() }
        }
    }

    fun cacheAttachment(chatId: String, attachment: Attachment?) {
        if (attachment == null) attachmentCache.remove(chatId)
        else attachmentCache[chatId] = listOf(attachment)
    }

    fun attachmentFor(chatId: String): Attachment? = attachmentCache[chatId]?.firstOrNull()

    /** 这条会话当前的全部附件（顺序＝添加顺序＝发送顺序）。 */
    fun attachmentsFor(chatId: String): List<Attachment> = attachmentCache[chatId] ?: emptyList()

    /** Writes [draft] as the conversation's unsent text; the display follows at once. */
    fun onDraftChange(chatId: String, draft: Draft) {
        _drafts.update { it + (chatId to draft) }
    }

    /**
     * 只把**正文**放回输入框，保留这个会话已有的附件等字段。
     *
     * 为什么不能直接 `onDraftChange(chatId, Draft(text = …))`（评审 9 第 2 条）：
     * 那是**全量覆盖**，会把 `imageNames` 清空；而附件 chip 读的是另一个缓存
     * （`attachmentCache`），于是"chip 还在、draft.imageNames 为空" ⇒ 用户点 ✕ 时
     * `removeAt(index)` 撞 `IndexOutOfBoundsException`（主线程崩）。
     */
    private fun backfillDraftText(chatId: String, text: String) {
        _drafts.update { drafts ->
            drafts + (chatId to (drafts[chatId] ?: Draft()).copy(text = text))
        }
    }

    /** Sending empties the conversation's draft; nothing else touches it. */
    fun clearDraft(chatId: String) {
        _drafts.update { it - chatId }
        attachmentCache.remove(chatId)
        // And straight onto the screen when that chat is the one being shown. The collector above
        // would do it a hop later, and a field that keeps its text for even one frame after the user
        // pressed send reads as "it did not send" — which is how the queue was reported twice.
        if (_state.value.conversationId == chatId && _state.value.draft != Draft()) {
            _state.update { it.copy(draft = Draft()) }
        }
    }

    init {
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
                        is RunState.Running -> {
                            _liveStats.value = run.stats
                            // runningHere 只在首帧真翻转；后续 120ms 的 copy 结果 equals，
                            // StateFlow 去重，不再触发屏幕重组。
                            _state.update { it.copy(runningHere = true) }
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
            // Independent disk reads should overlap instead of serializing settings,
            // metadata and the last-opened marker before the transcript can be opened.
            val loadedChats = async(Dispatchers.IO) {
                if (Core.chats.conversations.value.isEmpty()) Core.chats.load()
            }
            val savedOpened = async(Dispatchers.IO) { lastOpenedId ?: Core.chats.readLastOpened() }
            val settings = Core.settings.current()
            val assistantId = settings.activeAssistant()?.id.orEmpty()
            // Only the cold path reads the files: when the conversations are already in
            // memory the state above is the real one, and loading them again would only
            // make the screen wait for something it already has.
            loadedChats.await()
            val conversations = Core.chats.conversations.value
            // The conversation the user was last in — and only if it is still there: the
            // marker can outlive its conversation (deleted from another entry point), and
            // opening a chat that no longer exists would leave the screen on nothing. The
            // in-memory id is preferred because it is what this process last showed; the
            // file is the cold-start answer.
            val reopened = savedOpened.await()?.let { id ->
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
            // 冷启动自动打开这条入口原来**完全不写缓存** ⇒ 水位对它无效（S1 只修了"点开"那一半）。
            // 从盘读到就记成"已落盘"，合并草稿时才敢清。
            if (opened.messages.isNotEmpty()) {
                cacheMessages(existing.id, opened.messages, diskBacked = true)
            }
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
            combine(state.map { it.conversationId }, _drafts) { id, all ->
                // Text has its own composer subscription; attachment changes still update the screen.
                id to (all[id] ?: Draft()).copy(text = "")
            }
                .distinctUntilChanged()
                .collect { (id, draft) ->
                    if (_state.value.conversationId == id && _state.value.draft != draft) {
                        _state.update { it.copy(draft = draft) }
                    }
                }
        }
    }

    /** Returns whether a page request was accepted; [onComplete] reports the actual merge. */
    fun loadEarlier(turns: Int = Core.chats.openTurns(), onComplete: (Boolean) -> Unit = {}): Boolean {
        val chatId = _state.value.conversationId
        if (chatId.isEmpty()) return false
        val shown = _state.value.messages
        if (shown.isEmpty()) return false
        // 已经读到文件开头就别再读了。
        if (_state.value.messageCount in 1..shown.size) return false
        viewModelScope.launch {
            var added = false
            try {
                // **优先用字节偏移**（O(窗口)），只有偏移未知/代次不匹配时才退回按 id 扫全文件。
                val start = windowStarts[chatId]
                val window = if (start != null) {
                    Core.chats.readEarlier(chatId, start.second, turns, expectGen = start.first)
                } else {
                    null
                }
                // 回退路径也把窗口位置记下来：编辑重答会换 gen（旧偏移失效），
                // 不补种的话之后每次翻页都要重扫整个日志（评审 S1）。
                val fallback = if (window == null) {
                    Core.chats.readEarlierBefore(chatId, shown.first().id, turns)
                } else {
                    null
                }
                val older = window?.messages ?: fallback?.messages ?: return@launch
                if (older.isEmpty()) return@launch
                if (_state.value.conversationId != chatId) return@launch
                // **合并后的列表未必都在盘上**（评审 13 的 H1）：`older` 来自盘，但 `current.messages`
                // 可能含"只在内存"的消息（保存失败 / 刚合并进来的恢复消息）。无条件标"已落盘"
                // 会给后面删草稿开一条新路 —— 而草稿恰恰可能是那份内容的唯一副本。
                // 只有当**当前这份列表本身就是那份"确认已落盘"的缓存对象**时，合并结果才全部可证在盘上。
                // **`===` 是这条判据的一部分**：只有"当前列表就是缓存里那个对象"时，
                // 缓存条目的 `diskBacked` 才在描述这份内容。若将来有人在这里插 `.toList()`/`filter`，
                // 身份会静默失配 ⇒ 退化成"不敢清草稿"（方向安全，但没人会知道）。
                val baseWasDiskBacked = cacheIsDiskBacked(chatId) &&
                    cachedMessages(chatId) === _state.value.messages
                // Install the page in the run first. A stream event may already hold a
                // pre-page snapshot; putSession carries these pages over that snapshot.
                var pagedRun: RunSession? = null
                sessions.computeIfPresent(chatId) { _, live ->
                    val merged = prependEarlierPage(live.messages, shown.first(), older)
                    if (merged != null) {
                        live.copy(messages = merged, loadedPrefix = older + live.loadedPrefix)
                            .also { pagedRun = it }
                    } else {
                        live
                    }
                }
                var mergedOut: List<StoredMessage>? = null
                _state.update { current ->
                    // CAS may retry after a stream publication has already shown this page.
                    mergedOut = null
                    val merged = if (current.conversationId == chatId) {
                        prependEarlierPage(current.messages, shown.first(), older)
                    } else null
                    if (merged == null) {
                        current
                    } else {
                        mergedOut = merged
                        current.copy(messages = merged)
                    }
                }
                // 副作用放到 update 之外：`MutableStateFlow.update` 是 CAS 重试循环，lambda 可能被重跑。
                mergedOut?.let {
                    cacheMessages(chatId, it, diskBacked = baseWasDiskBacked)
                    added = true
                }
                // A stream publication can have displayed the installed page before
                // our CAS. That is still a successful page, even though the head changed.
                if (pagedRun != null) added = true
                if (added) {
                    if (window != null && start != null) {
                        windowStarts[chatId] = start.first to window.startOffset
                    } else if (fallback != null) {
                        windowStarts[chatId] = fallback.gen to fallback.startOffset
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "earlier page unavailable for $chatId", failure)
            } finally {
                onComplete(added)
            }
        }
        return true
    }

    /**
     * 有没有必要删掉一个**空会话**：误点"新建"、什么都没发就切走，会在列表里留一个空壳。
     *
     * 四条都要满足才删（任何一条不满足都保留）：
     *  · 盘上这条会话没有消息（`messageCount == 0`）；
     *  · 屏幕上它也没有消息（读到一半的会话不能因为内存里是空就当空壳）；
     *  · 它没有正在跑的 run（跑着的会话删了会把结果丢掉）；
     *  · 它**没有草稿** —— 用户打了字但没发送，那是有内容的，不能丢。
     */
    /** 离开会话时顺手回收"从未使用过"的空会话。失败就算了（下次切走再试）。 */
    private suspend fun discardIfUntouched(chatId: String) {
        if (chatId.isEmpty()) return
        if (sessions[chatId] != null) return
        val snapshot = _state.value
        val inMemory = if (snapshot.conversationId == chatId) snapshot.messages.size else 0
        val stored = Core.chats.conversations.value.firstOrNull { it.id == chatId }?.messageCount ?: return
        val hasDraft = Core.chats.hasLiveDraft(chatId) ||
            !_drafts.value[chatId]?.text.isNullOrBlank() || attachmentsFor(chatId).isNotEmpty()
        if (!shouldDiscardEmptyChat(stored, inMemory, snapshot.runningHere, hasDraft)) return
        runCatching { Core.chats.delete(chatId) }.onSuccess { forgetConversation(chatId) }
    }

    fun newConversation() {
        openingJob?.cancel()
        openingJob = null
        // 上一轮的实时统计挂在这个 ViewModel 上、全局共享：不清掉的话，新建的空会话在第一次
        // stats tick 之前仍会显示**上一个会话**的圆环读数（用户实测：新会话显示 40% / 409.5K）。
        _liveStats.value = null
        viewModelScope.launch {
            // 误点「新建」再点别的会话会留下空壳，所以先回收正被离开的那一个（判据见
            // shouldDiscardEmptyChat：无消息、无草稿、无 run 才删）。
            discardIfUntouched(_state.value.conversationId)
            val settings = Core.settings.current()
            val created = Core.chats.create()
            leaveViewport()
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

    /** Only the latest cold-open read can occupy the repository queue. */
    private var openingJob: Job? = null

    fun openConversation(conversation: Conversation) {
        if (_state.value.conversationId == conversation.id) return
        leaveViewport()
        openingJob?.cancel()
        openingJob = null
        // 与 newConversation 同理：切过去的会话在它自己第一条 stats 到达前，不能顶着上一个
        // 会话的读数（圆环 / 三块占比都读这份）。
        _liveStats.value = null
        // 切走时把"什么都没发过的空会话"回收掉：判据在 shouldDiscardEmptyChat（无消息 /
        // 无草稿 / 无 run 才删），有草稿的会保留 —— 用户打了字没发那是有内容的。
        val leavingId = _state.value.conversationId
        viewModelScope.launch { discardIfUntouched(leavingId) }
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
        val openedState = if (live != null) {
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
        // 排队输入属于会话。后台运行结束时只保留队列，重新打开必须把它放回屏幕；
        // 不能因为没有新 Running→terminal 事件就把用户输入永久留在不可见的表里。
        _state.value = openedState.copy(pendingTurns = pendingTurnsOf(conversation.id))
        // **补读正文**（#30 + LRU 缓存）：上面只按 meta 建了状态，这里把最近 N 轮读进来。
        // 先查缓存：「切走再切回」在几个会话之间来回看是极常见的操作，不该每次都读盘。
        //
        // 条件都是"这个会话还在屏幕上"：用户可能已经切走，那就别再写回状态去覆盖新会话。
        // 只有**没命中缓存**才读盘（#35：命中时上面已经带正文建好状态了）。
        if (cached == null && live == null) openingJob = viewModelScope.launch {
            val opened = Core.chats.readTailWindow(conversation.id)?.let { (conversationTail, startOffset, gen) ->
                windowStarts[conversation.id] = gen to startOffset
                conversationTail
            } ?: Core.chats.readTail(conversation.id)
            if (opened != null && _state.value.conversationId == conversation.id &&
                _state.value.messages.isEmpty()
            ) {
                cacheMessages(conversation.id, opened.messages, diskBacked = true)
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
            // Recovery can write data, so only the read phase is superseded by another tap.
            openingJob = null
            // **尾部读完再合并草稿**（评审 10 的 S4）：这两件事原来各起一个协程，
            // 而草稿是小文件、往往先读完 ⇒ `mergeLiveDraft` 手里 `messages` 还是空
            // ⇒ 两个判重分支都看不到那条消息 ⇒ 又补一条（重复）。串起来之后才真正生效。
            // **必须 gate 在 `opened != null` 上**（评审 12 的 H1）：读盘失败时 `_state.messages`
            // 还是空列表，那不算"来自磁盘"。硬写 true 会让空 base 也获得"可以清草稿"的资格，
            // 而且紧接着的 `save` 在"盘上已有内容"时必被护栏拒绝（既不是全量也不是尾部）⇒ 假红条 +
            // 只剩一条消息的画面。（"盘上本来就 0 条"那个例外见 `shouldSkipEmptBaseMerge`：那时放行安全且能自愈。）
            // 读失败就把草稿留给下一次打开。
            if (opened != null &&
                _state.value.conversationId == conversation.id &&
                Core.chats.hasLiveDraft(conversation.id)
            ) {
                mergeLiveDraft(conversation.id, baseFromDisk = true)
            }
            if (opened != null) resumePendingTurns(conversation.id)
        }
        // A run that was killed left its half-written answer in a draft file: it belongs to
        // this conversation, so it is picked up the moment the conversation is.
        // 绝大多数会话**没有草稿**（`.live.json` 只在一轮跑到一半被杀时才有），
        // 所以先 stat 一下再决定要不要起协程（#35）。
        if (cached != null && Core.chats.hasLiveDraft(conversation.id)) {
            // 命中 LRU 缓存时正文已经在手里（不需要读盘），可以直接合并。
            viewModelScope.launch {
                mergeLiveDraft(conversation.id)
                resumePendingTurns(conversation.id)
            }
        } else if (cached != null) {
            resumePendingTurns(conversation.id)
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

    /**
     * 忘掉一个会话名下**所有**按会话保存的运行时状态（issue #38）。
     *
     * 原来删除会话只清了 `steers`，其余这些表都没有容量上限、也不会被淘汰：
     * `pendingTurns` 最重（排队未发的消息，**含 base64 图片，可达几 MB**），
     * 其次是 `todoBoards`、`lastErrors`、`windowStarts`、`attachmentCache`、LRU 正文缓存。
     * 会话数量只增不减 ⇒ 这些残留会一直堆着。
     */
    private fun forgetConversation(chatId: String) {
        clearSteers(chatId)
        _drafts.update { it - chatId }
        pendingTurns.forget(chatId)
        todoBoards.remove(chatId)
        lastErrors.remove(chatId)
        windowStarts.remove(chatId)
        attachmentCache.remove(chatId)
        messageCache.remove(chatId)
        userStopped -= chatId
    }

    fun deleteConversation(conversation: Conversation) {
        // Published before the conversation goes: a buffered tail is part of an answer that
        // may still be streaming into a chat that is about to disappear from the list.
        flushDeltas(conversation.id)
        // The run owns its conversation until it ends: deleting it would only have the
        // next save write it straight back.
        if (sessions.containsKey(conversation.id)) {
            // **不许静默拒绝**（issue #38）：用户点了删除却毫无反应，分不清是被拒绝还是卡了。
            _state.update {
                if (it.conversationId != conversation.id) it
                else it.copy(error = "这个会话正在执行任务，先停止或等它结束再删除")
            }
            return
        }
        // Nothing left to hand a queued line to.
        clearSteers(conversation.id)
        forgetConversation(conversation.id)
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
        val chatId = _state.value.conversationId
        if (chatId.isEmpty()) return false
        // No global lock: the engine refuses a second run in the same conversation
        // (two runs would interleave into one transcript), and every other
        // conversation is free to start its own run right now.
        if (Core.engine.isRunning(chatId)) return false

        // Android 17（API 37）起，发往内网的请求必须先有 ACCESS_LOCAL_NETWORK，否则只会超时，
        // 还会被重试 5 次后才报「连接超时」——把权限问题伪装成网络问题。这里先拦下来，
        // 直接告诉用户去哪儿授权，不发出那 5 次注定失败的请求。
        val appContext = runCatching { Core.appContext }.getOrNull()
        val settingsNow = settingsSnapshot.value
        if (appContext != null && settingsNow != null &&
            LocalNetworkAccess.blockedByMissingPermission(appContext, settingsNow.baseUrl)
        ) {
            _state.update {
                it.copy(error = "Android 17 起访问局域网模型需要「局域网访问」权限：到「设置 → 模型」点一次" +
                    "「测试连接」会弹出系统授权，允许后再发送。")
            }
            return false
        }

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
        clearDraft(chatId)
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
        if (snapshot.messageCount <= snapshot.messages.size) {
            start(fresh, prompt)
        } else {
            viewModelScope.launch {
                val wire = runCatching { Core.chats.readWire(snapshot.conversationId) }.getOrNull()
                if (_state.value.conversationId != snapshot.conversationId ||
                    Core.engine.isRunning(snapshot.conversationId)
                ) return@launch
                val fullPrompt = wire?.takeIf { it.isNotEmpty() }?.let {
                    Transcript.withoutLastAnswer(Transcript.active(it, snapshot.activeRuns))
                } ?: prompt
                start(fresh, fullPrompt)
            }
        }
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
            val positions = snapshot.messages.firstPositions()
            val index = positions[id] ?: -1
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
                        (positions[checkpoint.toMessageId] ?: -1) >= index
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
        // **顺序很重要**（评审 M-2/M-4）：先落盘、成功了再起这一轮。
        // 原来先改屏幕再 `start()`，而 `start` 的第一件事就是 `saveSession`（allowShrink=false）——
        // 盘上那份的末尾还是**被丢掉的那条旧回答** ⇒ 对不上 ⇒ 护栏拒绝 ⇒ 界面挂一条"本次未写入"，
        // 而编辑其实已经写成功了；顺带模型拿到的还是窗口版。
        viewModelScope.launch {
            val full = runCatching { Core.chats.readAll(snapshot.conversationId) }.getOrNull()
            val at = full?.indexOfFirst { it.id == id } ?: -1
            if (full == null || at < 0) {
                Log.w(TAG, "edit not persisted: full history unavailable for ${snapshot.conversationId}")
                // **把用户刚输入的文字放回输入框**（只改正文，别清掉附件）。
                backfillDraftText(snapshot.conversationId, body)
                _state.update {
                    if (it.conversationId != snapshot.conversationId) it
                    else it.copy(error = "编辑没有写入磁盘：读不到完整历史（已把文字放回输入框）")
                }
                return@launch
            }
            val keptFull = full.take(at) + full[at].copy(text = body, edited = true)
            // **被改的那条若落在某个压缩区间里，那个摘要就已经在说谎**（评审 M-4）：
            // `readWire` 只从最后一个 checkpoint 之后开始发，模型会拿到旧摘要而不是改后的句子。
            // 所以丢掉"覆盖它"的那个 checkpoint 以及**之后的所有** checkpoint（历史改从更早处回放）。
            val positions = full.firstPositions()
            val safeCheckpoints = snapshot.checkpoints
                .takeWhile { cp ->
                    val from = positions[cp.fromMessageId] ?: Int.MAX_VALUE
                    val to = positions[cp.toMessageId] ?: Int.MAX_VALUE
                    at !in from..to
                }
            // 先落盘：`fullHistory = true` 是诚实声明（手里确实是整份），护栏据此放行。
            val written = save(
                chatId = snapshot.conversationId,
                title = snapshot.title,
                messages = keptFull,
                assistantId = snapshot.assistantId,
                activeRuns = snapshot.activeRuns,
                providerId = snapshot.providerId,
                model = snapshot.model,
                reasoningEffort = snapshot.reasoningEffort,
                checkpoints = safeCheckpoints,
                allowShrink = true,
                fullHistory = true,
            )
            if (written == null) {
                // 落盘失败同理：文字回到输入框，别让它连同那段编辑一起消失。
                backfillDraftText(snapshot.conversationId, body)
                return@launch
            }
            // **两次 IO 之后必须复检前置条件**（评审 8 第 4 条）：`readAll` + `save` 在大会话上
            // 是百毫秒到秒级，这期间用户可能切走了会话、或已经发起了另一轮 —— 那时再 `start()`
            // 会拿**旧会话的全文**当新会话的 wire 发出去（回答写进别人的会话），
            // 或与已在跑的那一轮撞车（引擎回 Failed，第二个 run 的 finally 还会把会话摘掉）。
            // **复检失败不能只记日志**（评审 9 第 3 条）：编辑已经落盘了，而
            // `messageCache` 还留着编辑前的窗口 —— 切回来会用旧缓存，再发一条时
            // 「编辑本该丢掉的旧回答」会被当成尾部追加回日志，等于**把编辑撤销**。
            if (_state.value.conversationId != snapshot.conversationId) {
                Log.w(TAG, "edit not started: the conversation changed while saving")
                cacheMessages(snapshot.conversationId, kept, diskBacked = true)
                return@launch
            }
            if (Core.engine.isRunning(snapshot.conversationId)) {
                Log.w(TAG, "edit not started: a run began while saving")
                // 那一轮的 session 是按**编辑前**的状态建的，这里不再起跑；
                // 至少把编辑后的窗口写进缓存 + 把文字还回输入框，并明确告诉用户。
                cacheMessages(snapshot.conversationId, kept, diskBacked = true)
                backfillDraftText(snapshot.conversationId, body)
                _state.update {
                    if (it.conversationId != snapshot.conversationId) it
                    else it.copy(
                        error = "这次编辑已保存，但当前有一轮正在运行，没有立刻重新回答（文字已放回输入框）",
                    )
                }
                return@launch
            }
            val keptIds = kept.mapTo(HashSet()) { it.id }
            // 写成了：更新屏幕，然后**用全文版**起这一轮（模型才看得到改后的句子）。
            _state.update {
                if (it.conversationId != snapshot.conversationId) it
                else it.copy(
                    messages = kept,
                    activeRuns = it.activeRuns.filterKeys { key -> key in keptIds } + (id to fresh),
                    streaming = "",
                    reasoning = "",
                    status = "正在重新回答…",
                    error = null,
                    checkpoints = safeCheckpoints,
                )
            }
            start(fresh, keptFull)
        }
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
        _state.update { it.copy(pendingSteers = steersOf(chatId)) }
    }

    /**
     * 排队 turn 的队列（issue #29）：跟 [steers] 一样的形状 —— UI 线程写（[steer]/
     * [retractTurn]/[promoteTurn]），IO 线程读改写（`handleEvent` 里的 [drainPendingTurns]），
     * 原来的普通 map 会**静默丢更新**（用户排的 turn 凭空消失）。同一把锁把「读-改-写」收成一步。
     *
     * 队列内部同时保护 drain 防重入标记，避免两个入口重复发送。
     */
    /** 队列里的 排队 turn（读，锁内）。 */
    private fun pendingTurnsOf(chatId: String): List<PendingTurn> = pendingTurns.list(chatId)

    /** 入队（锁内；读-改-写合成一步）。 */
    private fun enqueuePendingTurn(chatId: String, turn: PendingTurn) {
        pendingTurns.add(chatId, turn)
    }

    /** 按 id 摘掉一条（锁内）。 */
    private fun removePendingTurn(chatId: String, id: String) {
        pendingTurns.take(chatId, id)
    }

    /** 按 id 取出一条并同时把它摘掉（锁内，合成一步 ⇒ 不会取到别人已经摘走的那条）。 */
    private fun takePendingTurn(chatId: String, id: String): PendingTurn? = pendingTurns.take(chatId, id)

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
     * registered, or no conversation on screen to send into). The queue's drain guard keeps hooks from
     * getting in each other's way; a refused turn is restored at the head, in order.
     */
    private fun drainPendingTurns(chatId: String) {
        val result = pendingTurns.drain(chatId, isDisplayed = {
            val shown = _state.value
            shown.conversationId == chatId && !shown.loading
        }) { next ->
            publishPendingTurns(chatId)
            send(next.text, next.imagesBase64)
        }
        if (result != PendingMessageQueues.DrainResult.REFUSED) return
        publishPendingTurns(chatId)
        // The terminal state can arrive before the engine unregisters. A refused turn stays
        // at the head and gets one retry when nothing is running; a live run drains at its end.
        if (!Core.engine.isRunning(chatId)) {
            viewModelScope.launch {
                delay(RETRY_TURN_MS)
                drainPendingTurns(chatId)
            }
        }
    }

    /** Reopening a completed background chat has no future terminal event to drain its queue. */
    private fun resumePendingTurns(chatId: String) {
        if (!sessions.containsKey(chatId) && !Core.engine.isRunning(chatId)) drainPendingTurns(chatId)
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
                Core.engine.compressNow(history, settings, sessions[chatId]?.checkpoints ?: shown.checkpoints) { checkpoint ->
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
        val job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
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
                    // O(n×m) 的字符串两两比较 → 建一次集合后 O(n+m)。
                    run {
                        val waitingIds = stillWaiting.mapTo(HashSet(stillWaiting.size)) { it.id }
                        carried.filter { it.id in waitingIds }
                    }
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
                    writer.cancelAndJoin()
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
                    // **写成功才清草稿**（评审 M-1，不可逆）：`saveSession` 现在返回结果；
                    // 保存被拒/抛异常时草稿是磁盘上唯一的副本，绝不能删。
                    // **会话已不在时按"没写成"处理**（评审 8 第 1 条）：用户按停止走的是
                    // `RunState.Cancelled` 观察者 —— 它 fire-and-forget 一次保存（返回值被丢）、
                    // 再 `endSession` 把会话摘掉。等这段 `finally` 跑到时 `sessions` 已经 null，
                    // 而那次保存**可能被拒或抛异常** ⇒ 回答盘上一份不剩。宁可留草稿：
                    // 下次打开时 `liveDraftRecovery` 会按前缀判重，不会出现两条。
                    val written = sessions[chatId]?.let { saveSession(it) } ?: false
                    // 性能记录：**这一轮真的发生了什么**。首字延迟、缓存命中、prompt/completion
                    // 都取自本轮落下的那条回答（StoredMessage 本身就带着端点的回报），不另做估算。
                    // 请求体大小目前没有采集点，留 0 —— 监测页会把它显示成「—」，不是「测到零」。
                    val answered = sessions[chatId]?.messages
                        ?.lastOrNull { it.role == Roles.ASSISTANT && it.promptTokens > 0 }
                    val runStats = _liveStats.value
                    if (answered != null || runStats != null) {
                        // **IO 线程落盘**（审计 3§5）：record 里 writeText+delete+renameTo
                        // 是整轮唯一确定的主线程阻塞写；PerfMonitor 的测试契约要求
                        // record 后立即可 load，所以不改成异步类，改在调用点切 IO。
                        withContext(kotlinx.coroutines.Dispatchers.IO) {
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
                    if (written) {
                        Core.chats.clearLive(chatId)
                    } else {
                        Log.w(TAG, "live draft kept for $chatId: the final save did not land")
                    }
                }
            }
        }
        jobs[chatId] = job
        job.invokeOnCompletion { jobs.remove(chatId, job) }
        job.start()
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
                // **不做前后台区分**（用户明确要求）：后台会话必须与前台**完全一致、依旧实时**。
                // 之前这里有一句"后台会话不发布"，那是把行为按"是否在屏幕上"分叉了 —— 不允许。
                // 真正要解决的是"发布这一下有多贵"，而不是"后台干脆不发"：
                // `applyPending` 的 O(n) 拼接/复制仍在 Main 上，那是下一步要搬走的（issue #36 ④）。
            }
            // Whatever has arrived so far, applied in one go. For a non-delta event this is the
            // flush; for a delta it is the publish that got through the interval above.
            pending = takeDeltasLocked(chatId)
        }
        if (hold) return
        val pendingText = pending.first
        val pendingReasoning = pending.second
        // **临界区之后重读一次**（评审 M5）：`live` 是在进入临界区**之前**取的快照，
        // 而 `putSession` 是整体覆盖（`sessions[id] = next`）。若另一线程（主线程的
        // `flushDeltas` / `mergeLiveDraft`）在这中间写入过，用旧快照算出来的 `next`
        // 会把它刚合并进去的内容盖掉 —— 那种丢失只会发生在内存里，不报错也不落盘。
        // 重读把窗口从"整个临界区+applyPending"缩到"两条语句之间"，并保留对方写入的字段。
        val merged = sessions[chatId] ?: live
        val base = applyPending(merged, pendingText, pendingReasoning)
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
                base.copy(error = null)
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
     * 正文文本 = `parts` 里所有 `Text` 块的拼接（**派生值**，不单独存一份）。
     *
     * 存两份的代价是每个 delta 拼两遍整串（O(n²) ×2）与双份内存（issue #38 第 3 条）。
     * 派生只在真的要用时算一次 —— 读它的只有两处：草稿合并、以及"这一轮有没有活内容"的判定。
     */
    private fun RunSession.derivedStreaming(): String {
        var first: String? = null
        var joined: StringBuilder? = null
        for (part in parts) if (part is LivePart.Text) {
            val text = part.text
            if (joined != null) joined.append(text)
            else if (first == null) first = text
            else joined = StringBuilder(first.length + text.length).append(first).append(text)
        }
        return joined?.toString() ?: first.orEmpty()
    }

    /**
     * 这一拍「正文 + 思考」共有多少字符（UTF-16），用于选恢复副本的落盘间隔（R4）。
     *
     * **不能走 [derivedStreaming]**：那条路每拍都重建整段字符串（O(n) 分配），
     * 而这里只想知道“有多长”，所以全部取长度、不取内容 —— `LivePart.Text` 读缓冲长度、
     * `reasoning` 读字符串长度、还没发布的那段用 `DeltaBuffer.sizes()`（同样只给长度）。
     */
    private fun RunSession.draftChars(pending: Pair<Int, Int>): Int =
        parts.sumOf { part -> if (part is LivePart.Text) part.buffer.length() else 0 } +
            reasoning.length + pending.first + pending.second

    /**
     * 把这一拍攒下来的正文/思考并进 [live]。
     *
     * **只做一次 `copy`**：原来每有一种增量就 copy 一次（每次都重建整份 Session）。
     * 一轮跑起来时这个方法每 80ms 调一次，少一次全量复制就少一次分配与 GC 压力；
     * 字段与取值顺序与改动前逐字一致，行为不变。
     *
     * 正文**不再另存一份**（`streaming` 留空，由 [derivedStreaming] 派生）——
     * 那是 issue #38 的第 3 条：同一段文本存两份、每个 delta 各拼一遍。
     */
    private fun applyPending(live: RunSession, text: String, reasoning: String): RunSession {
        val hasText = text.isNotEmpty()
        val hasReasoning = reasoning.isNotEmpty()
        if (!hasText && !hasReasoning) return live
        val opening = hasReasoning && !live.parts.hasOpenThinking(live.segmentStart)
        val parts = when {
            hasText && hasReasoning ->
                live.parts.appendThinking(reasoning, live.segmentStart).appendText(text, live.segmentStart)
            hasText -> live.parts.appendText(text, live.segmentStart)
            else -> live.parts.appendThinking(reasoning, live.segmentStart)
        }
        // **正文只存一份**（issue #38 第 3 条）：原来 `streaming` 与 `parts` 里的 Text 块
        // 各存一份正文，每个 delta 各拼一遍 —— CPU 与内存都是双份。
        // 现在 `streaming` 由 `parts` 派生（见 `derivedStreaming`），写入路径只碰 `parts`。
        return live.copy(
            streaming = "",
            reasoning = if (hasReasoning) live.reasoning + reasoning else live.reasoning,
            parts = parts,
            status = "",
            thinkingStartedAt = if (opening) System.currentTimeMillis() else live.thinkingStartedAt,
        )
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
     * Installs [next] as its conversation's live copy and, when that conversation is
     * the one on screen, copies the live parts of it onto the screen. A run in some
     * other conversation changes nothing here at all — the map keeps it apart, and
     * the display only ever reads its own entry.
     */
    private fun putSession(next: RunSession) {
        val installed = sessions.compute(next.chatId) { _, current ->
            if (current != null && current.runId == next.runId && current.loadedPrefix.size > next.loadedPrefix.size) {
                next.copy(
                    messages = carryEarlierPages(next.messages, next.loadedPrefix.size, current.loadedPrefix),
                    loadedPrefix = current.loadedPrefix,
                )
            } else {
                next
            }
        } ?: return
        // **不在这里刷 LRU 缓存**（#35）：流式期间每 ~80ms 一次 `putSession`，每次都要动缓存
        // （`MessageCache` 内部是一把监视器锁），而切会话时的 `readTail` 读盘也要用同一把 ——
        // 撞在一起就是可感知的抖动。缓存只在「一轮结束」时刷（见 [endSession]）就够：
        // 一轮中途切回来走的是 `sessions`（live 分支），根本不看缓存。
        _state.update { snapshot ->
            // Re-read inside the CAS: pagination can install/publish a newer copy
            // after this call's compute, and a CAS retry must keep that copy.
            val latest = sessions[next.chatId] ?: installed
            if (snapshot.conversationId != latest.chatId) {
                snapshot
            } else {
                snapshot.copy(
                    title = latest.title,
                    messages = latest.messages,
                    activeRuns = latest.activeRuns,
                    streaming = latest.streaming,
                    reasoning = latest.reasoning,
                    status = latest.status,
                    runningTool = latest.runningTool,
                    liveParts = latest.parts,
                    liveRunId = latest.runId,
                    error = latest.error ?: snapshot.error,
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
        _liveStats.value = null
        // **先把攒着的 delta 冲进去，再摘会话**（0.8.333 引入的风险）：后台会话的 delta 现在
        // 只缓冲不发布（屏幕上看不到它，发布只是白做 O(n) 拼接）。而一轮结束是
        // `sessions.remove(chatId)` —— 若此时缓冲里还有没应用的尾巴，那段文字既不会进
        // 将要落盘的消息，也不会再有人来取（`flushDeltas` 找不到会话就直接返回）。
        // 用户看到的就是「回答少了一截」，且没有任何报错。
        flushDeltas(chatId)
        val live = sessions.remove(chatId)
        deltaBuffer.drop(chatId)
        // 一轮结束：`sessions` 里那份没了，切回来只能靠 LRU 缓存 ⇒ 在这里刷一次
        // （#35：原来是在 `putSession` 里每 80ms 刷，太频繁）。
        // **内存版**：可能含保存失败、只在内存的消息 ⇒ 水位显式降级，别拿去清草稿。
        if (live != null) cacheMessages(chatId, live.messages, diskBacked = false)
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
     * Every 1–3s (adaptive — see [liveDraftIntervalMs]), and only when the answer, the thinking
     * or the round count actually changed: one write is an encode plus a file write plus a
     * rename, and tokens can arrive every few milliseconds. A second is the interval where a
     * kill costs the user the last tick instead of the whole answer, while a stalled stream
     * costs nothing at all; a long answer that grows by thousands of characters per second is
     * written less often, because copying the same amount again buys nothing.
     *
     * Because nothing runs after the process is killed, this is the only place the thinking
     * and the finished rounds can be saved at all — see [liveDraftFor].
     */
    private suspend fun writeLiveDraft(chatId: String) {
        var written: LiveDraft? = null
        var previous: RunSession? = null
        var previousPending = 0 to 0
        var messageSource: List<StoredMessage>? = null
        var messageRun = ""
        var runMessages: List<StoredMessage> = emptyList()
        var lastSavedChars = 0
        while (true) {
            // 自适应间隔（R4）：**先量当前还没落盘的增量，再决定睡多久**。
            //
            // 原来这里是 `lastMeasuredChars - lastSavedChars`，而 `lastMeasuredChars` 在同一拍
            // 保存之后被写成与 `lastSavedChars` 相同的值 ⇒ 差值恒为 0 ⇒ 永远 1s，2s/3s 不可达。
            // 增量的正确来源只能是“此刻内容有多长 − 上次真正落盘时多长”，所以量测必须在
            // `delay` **之前**、用不重建字符串的方式（见 [draftChars]）。
            val liveNow = sessions[chatId]
            val pendingNow = if (liveNow == null) 0
            else liveNow.draftChars(deltaBuffer.locked { deltaBuffer.sizes(chatId) }) - lastSavedChars
            delay(liveDraftIntervalMs(pendingChars = pendingNow.coerceAtLeast(0)))
            val live = sessions[chatId] ?: continue
            val pendingSize = deltaBuffer.locked { deltaBuffer.sizes(chatId) }
            val prior = previous
            if (prior != null && prior.runId == live.runId &&
                prior.messages === live.messages && prior.parts === live.parts &&
                prior.reasoning === live.reasoning && previousPending == pendingSize
            ) continue
            // The answer, the thinking beside it, and the rounds this run has already
            // produced. The reasoning and those rounds are not "extra": the session is the
            // only place they exist until the run ends, so a run that never ends would
            // otherwise leave a paragraph with no chain of thought and no tool cards.
            // **把还没发布的那段也算进去**：后台会话的 delta 只进缓冲、不进 `sessions`
            // （0.8.333 的"后台不发布"），而 `live.streaming` 只反映已发布的那些 ——
            // 不补的话，后台跑长回答时草稿永远是旧的/空的，进程一被杀就真丢输出。
            val (pendingText, pendingReasoning) = deltaBuffer.locked { deltaBuffer.peek(chatId) }
            // **组装也放 IO**：`derivedStreaming()` 是 O(正文长度) 的字符串重建，
            // 而原来这一段跑在 `viewModelScope`（Main）上 —— 长回答时每秒在主线程上
            // 重建一次整段正文。`saveLive` 自身已经在 IO 里，这里把整段一起放过去。
            val built = withContext(Dispatchers.IO) {
                if (messageSource !== live.messages || messageRun != live.runId) {
                    runMessages = live.messages.filter { it.runId == live.runId }
                    messageSource = live.messages
                    messageRun = live.runId
                }
                liveDraftFor(
                    chatId = chatId,
                    runId = live.runId,
                    text = live.derivedStreaming() + pendingText,
                    reasoning = live.reasoning + pendingReasoning,
                    messages = live.messages,
                    preparedMessages = runMessages,
                )
            } ?: continue
            if (!built.sameContentAs(written)) {
                Core.chats.saveLive(built)
                written = built
                // 基线只在**真正落盘**后推进；那一刻没有“未落盘增量”了。
                lastSavedChars = built.text.length + built.reasoning.orEmpty().length
            }
            previous = live
            previousPending = pendingSize
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
    private suspend fun mergeLiveDraft(
        chatId: String,
        /**
         * `messages` 这份 base 是不是**刚从磁盘读回来的**（S1）。
         *
         * 冷启动路径的 base 恰恰是刚读回的窗口 —— 只看 `shown == null` 会把它判成"非磁盘来源"，
         * 于是判重命中时草稿**永远不清**（每次打开白读一遍；残留草稿带旧 runId，
         * 将来 `rewrite` 丢过那条消息后会变成一张过时的"中断"卡片）。
         */
        baseFromDisk: Boolean = false,
    ) {
        // **活会话直接返回，且要在读草稿之前**（S4）：运行中每秒都在写草稿，
        // 排在后面等于每次切进来都白读+解析一遍。
        if (sessions[chatId] != null) return
        // 多个入口（冷启动 / 切回 / 缓存命中）可能并发进这里（S3）。
        if (!mergingLive.tryEnter(chatId)) return
        try {
            mergeLiveDraftLocked(chatId, baseFromDisk)
        } finally {
            mergingLive.leave(chatId)
        }
    }

    private suspend fun mergeLiveDraftLocked(chatId: String, baseFromDisk: Boolean) {
        val draft = Core.chats.readLive(chatId) ?: return
        val live = sessions[chatId]
        // **这一轮还活着就不合并**（评审 M2）：草稿是"运行中每秒写一份"的半截正文，
        // 而活着的这一轮由引擎自己收尾、草稿由 `endSession` 清。合并它会把同一段文字
        // 存成两条（一条"（由「任务被停止」，对话中断）"结尾），模型下一轮会看到自己
        // 回答重复；顺带还会把唯一副本清掉。
        // **只要有活会话就不合并**（评审 S-5）：原来只挡"同一个 runId"，
        // 而 `live.runId != draft.runId` 时（上一轮残留的会话对象）仍会合并 ——
        // 那会把上一轮被中断的半截回答（带"（由…原因，对话中断）"尾巴）混进**当前**这一轮。
        // 活着的会话自己会收尾，草稿留给 `endSession`/`finally` 清。
        if (live != null) return
        val shown = _state.value.takeIf { it.conversationId == chatId }
        val stored = Core.chats.conversations.value.firstOrNull { it.id == chatId }
        val messages = shown?.messages ?: stored?.messages.orEmpty()
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
        // Nothing this draft holds is missing from the transcript; clearing it is all that is
        // left.
        if (restored.isEmpty()) {
            // **只有"手里这份来自磁盘"时才清草稿**（评审 10 的 M2）。
            // `messages` 可能来自活会话或 LRU 缓存 —— 里面可能有**保存失败、只在内存里**的消息
            // （`endSession` 把内存版写进了缓存）。判重命中它们就删草稿，等于把"唯一副本"
            // 在一个不可逆的方向上扔掉。宁可留着（下次还能再判一次）。
            // 走到这里 `live` 必为 null（上面已 early-return），所以判据里不需要它 —— 去掉死参数。
            val fromDisk = draftIsDiskBacked(
                shownPresent = shown != null,
                baseFromDisk = baseFromDisk,
                cacheDiskBacked = cacheIsDiskBacked(chatId),
            )
            if (fromDisk) {
                Core.chats.clearLive(chatId)
            } else {
                Log.i(TAG, "live draft kept for $chatId: its content is not disk-backed yet")
            }
            return
        }
        // **base 为空就别动磁盘**（评审 14 建议 6）：`shown` 在合并读盘期间可能变 null，
        // 而 `stored.messages` 恒为空（#30 之后列表只带 meta）⇒ `messages` 是空的 ⇒
        // `next` 只剩那条恢复消息。若此时 save 成功，会把一份**截短的历史**写进缓存并标"已落盘"，
        // 切回来命中缓存、不再读盘 ⇒ 屏幕上只显示那几条。
        // 草稿留着，等下一次真正带着 base 的合并。
        if (shouldSkipEmptBaseMerge(messages.isEmpty(), baseFromDisk, stored?.messageCount)) {
            Log.i(TAG, "live draft kept for $chatId: no base to merge into yet")
            return
        }
        // 原因消费一次即复位（**放在守卫之后**：早退时还没"消费"，别把这次的原因弄丢 ——
        // 否则下一次真合并会把用户主动停止误写成"（由「任务被停止」原因，对话中断）"）。
        cancelReason = "任务被停止"
        val next = messages + restored
        // 自愈时 `_state.messageCount` 还是 0 ⇒ `loadEarlier` 每次触顶都白读一次。
        if (shown != null) {
            _state.update { it.copy(messages = next, messageCount = maxOf(it.messageCount, next.size)) }
        }
        // `save` 返回 null = **没有写下去**（被护栏拒绝或抛异常）⇒ 草稿是唯一副本，不能清。
        val written = save(
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
        if (written != null) {
            // **回写 LRU**（S2）：合并只改了 `_state` 与 `sessions`，而切走再切回走的是缓存 ——
            // 不回写的话，本进程内会少那条刚恢复的消息（盘上是对的，重启才看得见）。
            cacheMessages(chatId, next, diskBacked = true)
            Core.chats.clearLive(chatId)
        } else {
            Log.w(TAG, "live draft kept for $chatId: the merged copy was not written")
        }
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
        // 一趟完成「保序去重 + 取末尾 K 个」：原来 `current + anchored` 拷一次、
        // `distinctBy` 建 LinkedHashSet 再拷一次、`takeLast` 再拷一次。
        // distinctBy 保首次出现，所以先按 id 记首次位置，再取末尾 K 个。
        val unique = ArrayList<ContextCheckpoint>(current.size + 1)
        val seen = HashSet<String>(current.size + 1)
        for (item in current) if (seen.add(item.id)) unique.add(item)
        // `anchored` 是单个 checkpoint（原来靠 `List + 元素` 运算符追加到末尾），
        // 去重规则相同：id 已出现过就不再追加（distinctBy 保首次出现）。
        if (seen.add(anchored.id)) unique.add(anchored)
        val next = if (unique.size <= MAX_CHECKPOINTS) unique
        else unique.subList(unique.size - MAX_CHECKPOINTS, unique.size).toList()
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

    /**
     * 保存这一轮，返回**是否真的写下去了**。
     *
     * 返回值不能被忽略（评审 M-1）：`clearLive` 会删掉草稿，而草稿是"进程被杀后唯一副本"。
     * 保存被拒（`Refused`）或抛异常时若照样清草稿，长回答在磁盘上就**一份都不剩**。
     */
    private suspend fun saveSession(live: RunSession): Boolean {
        val title = save(
            chatId = live.chatId,
            title = live.title,
            messages = live.messages,
            assistantId = live.assistantId,
            activeRuns = live.activeRuns,
            providerId = live.providerId,
            model = live.model,
            checkpoints = live.checkpoints,
        ) ?: return false
        // **写成功就把这份记成"已落盘"的缓存**（评审 13 建议 3）：停止路径走的是
        // `RunState.Cancelled` 观察者 → fire-and-forget 保存 → `endSession` 把水位降级，
        // 等 `finally` 跑到时会话已被摘掉 ⇒ 水位一直是 false ⇒ 水位对"停止留下的草稿"
        // 几乎不生效。这里补上：写成功的那份就是内存这份。
        cacheMessages(live.chatId, live.messages, diskBacked = true)
        val current = sessions[live.chatId]
        if (current != null && title != current.title) {
            putSession(current.copy(title = title))
        }
        return true
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
        /** 配套声明：这次缩短手里的确是**整份历史**（护栏据此放行或拒绝）。 */
        fullHistory: Boolean = false,
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
                fullHistory = fullHistory,
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
            onSuccess = { result ->
                // **被拒绝也要说出来**（评审 M3）：原来 save 返回 Unit，两处"为避免截断而跳过"
                // 只写日志 ⇒ 调用方当成成功 ⇒ 界面一切正常、重启后整轮消失。
                if (result is ChatRepository.SaveResult.Refused) {
                    Log.w(TAG, "save refused for $chatId: ${result.reason}")
                    _state.update {
                        if (it.conversationId != chatId) it
                        else it.copy(error = "本次未写入：${result.reason}")
                    }
                    null
                } else {
                    derived
                }
            },
            onFailure = { cause ->
                // **"变短"不是失败**（评审 S4）：一次停机可能保存两次（`RunState.Cancelled` 分支
                // 一次、引擎 `finally` 一次）。第二次手里的快照更短时 `ChatStore.append` 会抛
                // `ConversationLogShrunkException` —— 而盘上那份其实**更全**，第一次已经写成功了。
                // 把它当错误弹红条，用户会看到一条假的「保存失败」，还会以为内容丢了。
                if (cause is ConversationLogShrunkException) {
                    Log.i(TAG, "save skipped for $chatId: disk already holds a longer log", )
                    null
                } else {
                    Log.w(TAG, "save failed for $chatId: ${cause.message}", cause)
                    _state.update {
                        if (it.conversationId != chatId) it
                        else it.copy(
                            error = "保存失败：${cause.message ?: cause.javaClass.simpleName}",
                        )
                    }
                    null
                }
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
        attachmentCache.clear()
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
    preparedMessages: List<StoredMessage>? = null,
): LiveDraft? {
    val mine = preparedMessages ?: messages.filter { it.runId == runId }
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
 * Check scalar lengths before walking strings/lists. A growing answer then rejects the old
 * draft in O(1); unchanged immutable lists use their identity fast path. Same-length edits
 * still receive exact comparisons: hashes/ids alone could lose the only recovery copy.
 */
internal fun LiveDraft.sameContentAs(other: LiveDraft?): Boolean = other != null &&
    other.chatId == chatId &&
    other.runId == runId &&
    other.text.length == text.length &&
    other.reasoning?.length == reasoning?.length &&
    other.messages.size == messages.size &&
    other.reasoning == reasoning &&
    other.messages == messages &&
    other.text == text

/**
 * 合并草稿时，**base 为空**要不要放弃这一次（先不动磁盘，把草稿留到下次）。
 *
 * 为什么需要这条：`shown` 在"合并读盘期间用户切走"时会变 null，而 `stored.messages` 恒为空
 * （#30 之后会话列表只带 meta）⇒ 手里只剩那条恢复消息。若照旧写盘，会把一份**截短的历史**
 * 写进缓存并标"已落盘"，切回来命中缓存、不再读盘 ⇒ 屏幕只显示那几条。
 *
 * **但有一个例外必须放过**：`baseFromDisk == true`（尾部确实从盘上读到了）且盘上**本来就没有
 * 任何消息**（`listMessageCount == 0`）—— 这时把恢复消息当整份历史写回不可能截断任何东西，
 * 而它正是"新建会话首发后约 1 秒内被杀"那条**本来能自愈**的路径。
 *
 * @param baseEmpty 手里的 base 是否为空
 * @param baseFromDisk base 是否确实来自磁盘（`readTail` 成功，且调用点 gate 在 `opened != null`）
 * @param listMessageCount 会话列表里那份 meta 的条数（**不是磁盘字节数**；未知传 null）。
 *   在 store 的自有不变式下 `count == 0 ⟺ logBytes == 0`（count 与 logBytes 同一个 list 写出），
 *   而真正的字节级把关仍在 `ChatRepository.save` 的 `diskHasLog = logBytes > 0`。
 */
internal fun shouldSkipEmptBaseMerge(
    baseEmpty: Boolean,
    baseFromDisk: Boolean,
    listMessageCount: Int?,
): Boolean {
    if (!baseEmpty) return false
    // 只有"确实从盘读到 + 盘上本来就一条没有"才放过；**拿不准就保守跳过**。
    val diskIsTrulyEmpty = baseFromDisk && listMessageCount == 0
    return !diskIsTrulyEmpty
}

/**
 * 合并草稿时，手里这份 `messages` 是不是**确实在磁盘上**（决定能不能清掉草稿）。
 *
 * 这条判据关系不可逆：判成"是"就去删草稿，而草稿是进程被杀后唯一的副本。所以它必须覆盖
 * 那个容易漏的路径 —— **冷启动时的 base 恰恰是刚从盘上读回来的窗口**（`live == null`
 * 但 `shown != null` 且 `baseFromDisk == true`）⇒ 它就是磁盘来源，可以清。
 * 只有"来自内存缓存"时才是假的。
 *
 * 注意 **`shown == null` 并不等于"盘上有内容"**：按 #30，会话列表只带 meta，冷启动时
 * `stored.messages` 是空的 —— 那种空 base 也不会走清理分支（要求 `restored` 为空），
 * 所以这条判据只需要区分"屏幕上的这份是否来自磁盘"。
 */
internal fun draftIsDiskBacked(
    shownPresent: Boolean,
    baseFromDisk: Boolean,
    /**
     * 缓存里的那份是否**确认已落盘**（S1 的水位）。
     *
     * 这是缓存路径上唯一可信的证据：只看"屏幕上有没有会话"无法区分"这份来自磁盘"与
     * "这份含保存失败、只在内存的消息"。
     */
    cacheDiskBacked: Boolean = false,
): Boolean = !shownPresent || baseFromDisk || cacheDiskBacked

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
    val probe = draft.text.trim().take(LIVE_DRAFT_PROBE_CHARS)
    // **正文为空、只有思考内容**时也要判重（评审 9 建议项）。
    // `emitPartial` 在"还没吐正文"时会把 text="" + reasoning 全文发出来并落盘；
    // 草稿的 text 也是空 ⇒ `probe` 为空 ⇒ 原来判 delivered=false ⇒ 再补一条
    // 带同样思考内容的助理消息 ⇒ 用户看到**重复的思考卡**（而 0.8.372 的 `?: false`
    // 让"用户按停止"这条路每次都会留下草稿，所以更容易走到）。
    // **必须用 `startsWith`，不能用相等**（评审 10 的 M1）：草稿是"最多 1 秒前"的快照，
    // 而落盘那条 = 草稿 + 最后一个 tick 之后到达的增量。只要用户按停止时它还在想
    // （这恰恰是最常见的场景），相等永远不成立 ⇒ 还是会补一张重复的思考卡。
    // 方向与正文分支一致（那边一直用 `startsWith`），注释里也早写明"用相等会把每次正常结束
    // 都判成未落盘"。不做 trim：两边都没有规范化，逐字前缀关系是可靠的。
    val existingIds = HashSet<String>(messages.size)
    val reasoningProbe = draft.reasoning?.takeIf { probe.isEmpty() && it.isNotBlank() }
    var delivered = false
    // One transcript traversal supplies both id de-duplication and the run-local prefix check.
    // Recovery runs once on opening/stopping, not at the periodic writer's frequency.
    for (message in messages) {
        existingIds += message.id
        if (delivered || message.runId != draft.runId || message.role != Roles.ASSISTANT) continue
        delivered = if (reasoningProbe != null) {
            message.reasoning?.startsWith(reasoningProbe) == true
        } else if (probe.isNotEmpty()) {
            message.text.trimmedStartsWith(probe)
        } else {
            false
        }
    }
    val restored = draft.messages.filterNot { it.id in existingIds }
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

/** Equivalent to trim().startsWith(prefix), without copying the remaining long answer. */
private fun String.trimmedStartsWith(prefix: String): Boolean {
    var start = 0
    var end = length
    while (start < end && this[start].isWhitespace()) start++
    while (end > start && this[end - 1].isWhitespace()) end--
    return end - start >= prefix.length && startsWith(prefix, startIndex = start)
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
/**
 * 活跃输出的恢复副本多久落一次盘（R4）。
 *
 * 原文固定 1s：流式时每秒都要**组装整段正文 + 编码 + 写盘 + rename**，长回答下这笔开销
 * 与输出速度成正比地重复。这里按“上次落盘之后又长了多少字”分档：
 *  · 增量小（< 2k 字）：仍 1s —— 被杀最多丢 1s，代价最小；
 *  · 增量中（2k~8k）：2s —— 多丢 1s，换掉一半的组装/编码；
 *  · 增量大（≥ 8k）：3s —— 长回答每秒改变几千字，再密的副本也只是重复搬运同样的量。
 * 纯函数、有单测（LiveDraftIntervalTest）；要改手感只动这三个阈值。
 */
internal fun liveDraftIntervalMs(pendingChars: Int): Long = when {
    pendingChars >= 8_000 -> LIVE_DRAFT_INTERVAL_MS * 3
    pendingChars >= 2_000 -> LIVE_DRAFT_INTERVAL_MS * 2
    else -> LIVE_DRAFT_INTERVAL_MS
}

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
/**
 * 多久把攒着的 delta 发布一次。
 *
 * 0.8.35 从 50ms 提到 80ms 是为了少做 Markdown 重解析（代价随端点速度线性增长）。
 * 但 80ms 的攒批让**一次到达**变成几百字，打字机再快也只能"一块一块蹦"——
 * 用户连续反馈「瞬移」「不要一次到达」。
 *
 * 现在取 40ms（与打字机节拍同频，25 次/秒）：一次到达的量降到 1/2，
 * 每拍放的字数也随之变小，逐字感才回来。重解析次数翻倍仍在可接受范围
 * （0.8.382 实测：光标 480ms 步进后单核 42% → 这一项不是发热主因）。
 */
// 发布节流：**16ms ≈ 每帧一次**（用户：流式"一小段一小段出"、感觉不实时）。
// 原来 40ms ⇒ 每秒最多 25 次，配上网关按几十毫秒批量送 token，屏幕上一跳一跳地长。
// 正文与思考现在直接渲染 `part.text`（不做打字机），节流就是唯一的颗粒度来源，
// 所以收到就发。60 次/秒的 state 更新仍在预算内（一次发布只拼一小段字符串）。
private const val DELTA_PUBLISH_MS = 16L

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

/**
 * 有没有必要删掉一个**空会话**（放顶层是为了能直接单测这条会丢用户数据的判据）。
 *
 * 四条都要满足才删：盘上没消息、内存里也没消息、没有正在跑的 run、**没有草稿**。
 * 删错了是丢用户数据，比留个空壳严重得多。
 */
internal fun shouldDiscardEmptyChat(
    storedCount: Int,
    inMemoryCount: Int,
    running: Boolean,
    hasDraft: Boolean,
): Boolean = storedCount == 0 && inMemoryCount == 0 && !running && !hasDraft
