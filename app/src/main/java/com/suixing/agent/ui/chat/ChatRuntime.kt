package com.suixing.agent.ui.chat

import kotlinx.coroutines.CoroutineScope
import com.suixing.agent.agent.StreamStats
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.Conversation
import com.suixing.agent.data.TodoItem
import com.suixing.agent.data.choiceFor
import com.suixing.agent.data.MessageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Shared state and scope; navigation, input, run execution and persistence live in focused modules. */
internal class ChatRuntime(
    internal val viewModelScope: CoroutineScope,
    internal val environment: ChatRuntimeEnvironment,
) {

    // The process-wide instance, not a private one: the floating console has to be
    // able to stop and steer the very run this screen started.
    internal val engine = environment.engine
    internal val gate = environment.gate
    /** Only used to ask an endpoint what windows its models advertise; see [prefetchWindows]. */
    internal val llm by lazy { environment.llm }
    /** Endpoints already asked in this process, so opening the sheet twice costs one call. */
    internal val windowsAsked = mutableSetOf<String>()

    /**
     * 设置的最近快照：`send()` 不是挂起函数，而 `environment.settings.current()` 是挂起的。
     * 初值**用 null 而不是 `AppSettings()`**：默认值的 baseUrl 是空串，拿它判「是不是内网」
     * 会在 DataStore 首次发射前给出错误结论；null 表示「还不知道」，此时不拦。
     */
    internal val settingsSnapshot: StateFlow<AppSettings?> = environment.settings.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    internal val chatState = ChatStateStore(viewModelScope)
    internal val _state = chatState.mutable
    val state: StateFlow<ChatUiState> = chatState.state
    internal val screenState: StateFlow<ChatUiState> = chatState.screen
    internal val sessionState: StateFlow<ChatSessionState> = chatState.session
    internal val transcriptState: StateFlow<ChatTranscriptState> = chatState.transcript
    internal val runState: StateFlow<ChatRunState> = chatState.run
    internal val inputState: StateFlow<ChatInputState> = chatState.input
    internal val liveContent: Flow<LiveContent> = chatState.live
    internal fun currentLiveContent(): LiveContent = chatState.currentLive()

    /**
     * 流式统计，**独立于 [state]**（审计 3§1 Top1）：引擎的 stats ticker 每 120ms 发一次
     * `RunState.Running(stats())`，`elapsedMs` 走墙钟必变 —— 它原先被并进 ChatUiState，
     * 等于让整个 ChatScreen（58 个子组合）跟着 8.3 次/秒整屏重组。现在只有显示秒数的
     * 小叶子（Composer 的环、顶栏 chip）收集 [liveStats]。
     */
    internal val _liveStats = MutableStateFlow<StreamStats?>(null)
    val liveStats: StateFlow<StreamStats?> = _liveStats.asStateFlow()

    /**
     * 「有没有活内容」的布尔：流式气泡开关与 pin 信号都只认它。活内容增长期间
     * state 因 equals 去重不发射，布尔天然稳定；只在开始/结束翻转时重组屏幕。
     */
    val liveActive: StateFlow<Boolean> = chatState.active

    val conversations: StateFlow<List<Conversation>> = environment.chats.conversations

    internal var viewportOwner = ""
    internal var viewportCapture: (() -> Unit)? = null
    internal val _presentedConversation = MutableStateFlow<String?>(null)
    internal val presentedConversation = _presentedConversation.asStateFlow()

    /** Shared per-chat totals; token deltas never enter this subscription. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val sessionStats: Flow<SessionStats?> = combine(screenState, environment.settings.flow, conversations) { snapshot, settings, chats ->
        // The window of the model this conversation would send with, not the app-wide
        // default's: a chat that picked a smaller-window endpoint must not read its own
        // usage against the wrong denominator.
        val assistant = settings.assistant(snapshot.assistantId) ?: settings.activeAssistant()
        val model = settings.choiceFor(snapshot.providerId, snapshot.model).model
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
                runCatchingCancellable { environment.chats.sessionTotals(request.chat) }.getOrNull()
            } ?: return@mapLatest null
            SessionStats.of(totals, request.window, request.chat)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The per-conversation run-state watcher; replaced whenever the display switches. */
    internal var stateWatcher: Job? = null

    /**
     * The conversation whose reply the speaker is reading out, or null once it was
     * stopped. The speaker is a single app-wide queue, so the stop button has to know
     * whether the voice it would cut belongs to the chat it is drawn in.
     */
    internal var speakingChatId: String? = null

    /**
     * The last failure per conversation, for the runs that ended while another chat was on
     * screen. The run's copy is gone by then and nothing else remembers why it stopped, so
     * the reason is held here until that conversation is opened again.
     */
    // **并发结构**（#36 准备）：run loop 一旦离开主线程，这个表会在事件线程写、在打开会话时读。
    internal val lastErrors = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * 每个会话「当前已加载窗口」的起飞字节位置 + 日志代次。
     *
     * 为什么要有：`readEarlierBefore` 是按**消息 id** 找位置（`offsetOf` 从日志开头整份扫描），
     * 30MB 的会话里每次「加载更早」都要扫最多 30MB。`ChatStore` 的文档本来就写明设计意图是
     * 「用上一次返回的 `startOffset`」，但 ViewModel 一直走的是 id 那条路。
     *
     * 代次用来校验：换代重写（编辑重答）之后旧偏移没有意义，必须回退到按 id 查。
     */
    internal val windowStarts = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()

    /**
     * 正在合并草稿的会话（S3）：三个入口可能并发进来，重复合并会各造一份 id ⇒ 撞护栏或触发整份重写。
     * 抽成 `InFlightGuard` 以便单测（`ChatViewModel` 本身构造依赖整个 `Core`，写不了单测）。
     */
    internal val mergingLive = InFlightGuard()

    /**
     * The conversation this process last showed, remembered so a new screen instance can adopt
     * it before its first frame instead of waiting for the marker file to be read.
     */
    internal var lastOpenedId: String? = null

    /**
     * 最近打开过的会话正文，LRU（容量 [MESSAGE_CACHE_SIZE]）。
     *
     * 逻辑在 `data/MessageCache` 里（纯 Kotlin、可单测）—— 原来这里有一份**内联实现**，
     * 而 `MessageCache` 是另一份**零引用**的重复实现，只有它自己的 5 条测试在用
     * ⇒ 测试测的不是生产代码（评审两次指出）。现在只有一份。
     */
    internal val messageCache = MessageCache(MESSAGE_CACHE_SIZE)

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
    internal val deltaBuffer = DeltaBuffer()

    /**
     * 每个会话一个**尾部补发**任务（issue #59 A4）。
     *
     * 节流判定只会在**下一个事件到来**时重跑 —— 所以「一次 publish 之后 1ms 再来一个 token、
     * 然后连接长时间空闲」时，那个 token 要等到下一个非 delta 事件（可能是几秒后的网络超时
     * 或流结束）才显示。这里在「被节流按住」的那一刻排一个一次性定时器，到点自行 flush。
     *
     * 每个会话只有一个（`map` 里已有的活跃任务直接复用），流结束/切会话/停止时由
     * [cancelTailFlush] 取消，避免迟到的定时器把上一轮的 delta 并进下一轮。
     */
    internal val tailFlushJobs = HashMap<String, Job>()

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
    internal val steers = PendingMessageQueues<PendingSteer> { it.id }

    /**
     * Turns typed while a run was going in 排队 mode, waiting for it to finish.
     *
     * The other half of the same question — see [PendingTurn]: a line is either an interruption or
     * the next turn, and this is where the next turn waits. Keyed by conversation so a turn queued
     * in one chat cannot be sent into another, and drained when that chat's run reports itself
     * finished.
     */
    internal val pendingTurns = PendingMessageQueues<PendingTurn> { it.id }

    /**
     * How many attempts the run in flight has spent on retries; 0 when none.
     *
     * Set by [AgentEvent.Retrying] and read when the run finally fails, so the give-up line can say
     * how many tries it took. A counter rather than a flag: "已重试 2 次" is the number the user
     * needs, and it is the same number the failure text carries.
     */
    internal var retriesThisRun = 0

    /**
     * 这一轮被中断的原因，写进落盘那条半截回答的尾部（用户：「中断提示不要这么简单，由 xxx 原因，
     * 对话中断」）。[stop] 置「用户停止」；引擎自己取消（轮数上限、网关结束等）用默认文案。
     * 消费一次就复位，避免下一次中断顶着上一次的原因。
     */
    internal var cancelReason = "任务被停止"

    /**
     * 哪些会话的运行是**用户按中断按钮**停的：停止不是失败，不写回执、不留红色提示。
     * 按 chatId 记 —— 多个会话可以同时跑，单个布尔会把别的会话的真实失败一起吞掉。
     */
    // **并发结构**（#36 准备）：用户点停（主线程）写，事件线程读。
    internal val userStopped: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Each conversation retains the exact board delivered by its last successful tool write. */
    internal val todoBoards = ConcurrentHashMap<String, List<TodoItem>>()

    /**
     * Guards the read-modify-write of [todoBoards].
     *
     * Never held at the same time as a pending-message queue's monitor, nor across a
     * suspending call or a callback into this class.
     */
    internal val todoBoardsLock = Any()

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
    internal val sessions = ConcurrentHashMap<String, RunSession>()

    /** One coroutine per running conversation, so stopping one leaves the others alone. */
    // **并发结构**（#36 准备）：用户操作写，事件线程 `containsKey` 读。
    internal val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /**
     * Closes the gap between accepting a message and the engine reporting Running
     * (issue #76 F-06). Main-thread only, like the other UI-side maps here.
     */
    internal val sendGate = SendPreparationGate()

    internal val _drafts = MutableStateFlow<Map<String, Draft>>(emptyMap())
    internal val drafts: StateFlow<Map<String, Draft>> = _drafts.asStateFlow()

    /**
     * The decoded attachment per conversation. The draft keeps only its uri and name
     * (what a restore can mean); the decoded bitmap lives here for the session, so a
     * chip keeps its thumbnail across a drawer switch without re-decoding.
     */
    internal val attachmentCache = ConcurrentHashMap<String, List<Attachment>>()

    internal val goal = environment.goals.changes

    /** 在途回收去重（见 [discardIfUntouched]）；单线程 viewModelScope 内读写，无需同步。 */
    internal val discardInFlight = mutableSetOf<String>()

    /** 排队中的「记住上次打开」写盘（debounce 用）。 */
    internal var lastOpenedJob: Job? = null

    /** 导航意图代次（issue #67 P0-1）：新建/打开共用，最新的说了算。 */
    internal val navIntents = NavigationIntents()

    /** Only the latest cold-open read can occupy the repository queue. */
    internal var openingJob: Job? = null

    internal var approvalSaveJob: Job? = null
    // 切走再立即切回时也使用最新选择，不能等待异步元数据写入后才隔离。
    internal val conversationApprovalChoices = ConcurrentHashMap<String, ApprovalMode>()

    /** 正在手动压缩的会话，防止连点。 */
    // **并发结构**（#36 准备）：压缩协程写，事件线程判定。
    internal val compressing: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 本轮压缩摘要请求的起点：结束后要用它算出「已压缩上下文，用时 x.xs」。 */
    internal var compressStartedAt: Long = 0L

    internal var contextOverheadJob: Job? = null

    /**
     * 正文文本 = `parts` 里所有 `Text` 块的拼接（**派生值**，不单独存一份）。
     *
     * 存两份的代价是每个 delta 拼两遍整串（O(n²) ×2）与双份内存（issue #38 第 3 条）。
     * 派生只在真的要用时算一次 —— 读它的只有两处：草稿合并、以及"这一轮有没有活内容"的判定。
     */
    internal fun RunSession.derivedStreaming(): String {
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
    internal fun RunSession.draftChars(pending: Pair<Int, Int>): Int =
        // 正文与思考**都在 `parts` 里**（`Text.buffer` / `Thinking.buffer`），逐块取长度即可，
        // 不必再读一份字符串副本（issue #57 §3）。
        parts.sumOf { part ->
            when (part) {
                is LivePart.Text -> part.buffer.length()
                is LivePart.Thinking -> part.buffer.length()
                else -> 0
            }
        } + pending.first + pending.second
}
