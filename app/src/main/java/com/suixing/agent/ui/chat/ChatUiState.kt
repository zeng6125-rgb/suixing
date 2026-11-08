package com.suixing.agent.ui.chat

import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.TodoItem
import com.suixing.agent.data.Transcript

/**
 * Lifecycle groups share unchanged snapshots. The flat constructor/copy keep existing callers
 * atomic while session, transcript, run and input can be collected independently.
 */
class ChatUiState(
    val session: ChatSessionState,
    val transcript: ChatTranscriptState,
    val run: ChatRunState,
    val input: ChatInputState,
) {
    constructor(
        conversationId: String = "",
        title: String = "新会话",
        /**
         * True until the conversation to show is known.
         *
         * The screen draws a bare skeleton while this holds, because an empty state on the way
         * to the real conversation reads as "it opened a new chat first and then jumped" — which
         * is exactly what the user saw on every cold start.
         */
        loading: Boolean = true,
        messages: List<StoredMessage> = emptyList(),
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
        messagesRevision: Long = 0,
        /**
         * 这个会话**总共有多少条消息**（来自落盘 meta，不是 [messages] 的长度）。
         *
         * 为什么要单独一个字段（#30）：打开会话只读最近 N 轮，`messages` 是**窗口**而不是全部，
         * 所以 `messages.size` 不再等于总条数。需要总数的调用方（会话状态工具、轮次）
         * 必须读这个字段，否则会把 12 轮当成整份对话。
         */
        messageCount: Int = 0,
        /**
         * 这个会话**总共有多少轮**（来自落盘 meta，不是 [messages] 里的轮数）。
         *
         * 导航条用它算「第 N 轮」和「上面还有 N 轮」；[messages] 只是窗口，
         * 用窗口里的轮数会让打开长会话时永远显示第 1 轮（#30 的可见回归）。
         */
        turnCount: Int = 0,
        /** Which run of each turn is on screen; see [Transcript]. */
        activeRuns: Map<String, String> = emptyMap(),
        /** The persona this conversation speaks as. */
        assistantId: String = "",
        /**
         * This conversation's own model pick, or null while it follows the persona and then
         * the app-wide default. The screen draws its tick from the same [AppSettings.choiceFor]
         * chain the run sends with, so what the picker shows is what goes on the wire.
         */
        providerId: String? = null,
        model: String? = null,
        /**
         * This conversation's own thinking level, or null while it follows the persona's
         * provider and then the app-wide default. Kept beside the model pick because the two
         * are chosen in the same sheet and belong to the same chat.
         */
        reasoningEffort: String? = null,
        /** null 跟随全局默认；聊天页的选择只属于当前会话。 */
        approvalMode: ApprovalMode? = null,
        streaming: String = "",
        /**
         * 思考正文的**修订号**，不是正文本身（issue #57 §3）。
         *
         * 原来这里存 `String`，每个 delta 都 `live.reasoning + delta` —— O(当前长度) 的整串复制，
         * 16ms 一拍，长思考就是平方级分配。正文本来就在 `parts` 的 `Thinking.buffer` 里，
         * 这里只留一个自增整数：
         *  · 判等：整数变化 ⇒ `ChatUiState` 判不等 ⇒ 状态照常发射。**不能只靠 parts**：
         *    缓冲是可变对象、列表引用不变，那条触发器正是当年「整屏冻结」的成因；
         *  · 判空：`> 0` ⇔ 有过思考；
         *  · 落盘：真正要正文时从 `parts` 取一次快照（本来就在 IO 上）。
         */
        reasoningRevision: Int = 0,
        /**
         * Whether the conversation on screen has a run in flight, straight off the
         * engine's per-chat state. Runs in other conversations are none of this
         * screen's business: nothing here locks, refuses or narrates them.
         */
        runningHere: Boolean = false,
        /**
         * 没有 run 时手动压缩的开始时刻（毫秒）；0＝没在压。
         *
         * 状态行要能说「正在压缩上下文，用时 xx」—— 没有这一位，点了压缩屏幕上没有任何反应
         * （用户：「无 run 的时候手动点压缩，啥响应也没有」）。
         */
        compressingSince: Long = 0L,
        status: String = "",
        /** The persistent objective shared by every conversation, shown above the composer. */
        /** The tool the run is inside right now; the tool card names it live. */
        runningTool: String? = null,
        /** Estimated system prompt and tool definitions, independent of the running request. */
        contextOverhead: Pair<Int, Int>? = null,
        /** The live bubble's pieces; empty unless this conversation owns the running run. */
        liveParts: List<LivePart> = emptyList(),
        /**
         * The run whose rounds are streaming. Its stored rounds are hidden from the
         * transcript while it runs — the live bubble already draws them, and showing both is
         * what split one reply into two bubbles that later merged.
         */
        liveRunId: String = "",
        error: String? = null,
        /** The unsent text of the conversation on screen; see [Draft]. */
        draft: Draft = Draft(),
        /** Lines queued for the run that is going, oldest first; see [PendingSteer]. */
        pendingSteers: List<PendingSteer> = emptyList(),
        /**
         * Lines typed in 排队 mode, waiting to go out as the next turn; see [PendingTurn].
         *
         * Drawn above the composer like [pendingSteers], with its own wording, because the two mean
         * different things: one is being read by the run in flight, the other has not been sent
         * anywhere yet. Not drawing it is what made the user press 插话 over and over — the line
         * vanished into a queue with nothing on screen to show for it.
         */
        pendingTurns: List<PendingTurn> = emptyList(),
        /** The last board from this conversation, kept on screen until the next run starts. */
        todos: List<TodoItem> = emptyList(),
        /**
         * This conversation's compression record, oldest first.
         *
         * Carried with the conversation rather than looked up per run: the engine reuses a
         * summary it has already paid for, and a list rebuilt from scratch would fold — and
         * charge for — the same messages again on every turn.
         */
        checkpoints: List<ContextCheckpoint> = emptyList(),
    ) : this(
        session = ChatSessionState(conversationId, title, loading, assistantId, providerId, model, reasoningEffort, approvalMode, contextOverhead),
        transcript = ChatTranscriptState(messages, messagesRevision, messageCount, turnCount, activeRuns, checkpoints),
        run = ChatRunState(streaming, reasoningRevision, runningHere, compressingSince, status, runningTool, liveParts, liveRunId, error),
        input = ChatInputState(draft, pendingSteers, pendingTurns, todos),
    )

    val conversationId: String get() = session.conversationId
    val title: String get() = session.title
    val loading: Boolean get() = session.loading
    val messages: List<StoredMessage> get() = transcript.messages
    val messagesRevision: Long get() = transcript.messagesRevision
    val messageCount: Int get() = transcript.messageCount
    val turnCount: Int get() = transcript.turnCount
    val activeRuns: Map<String, String> get() = transcript.activeRuns
    val assistantId: String get() = session.assistantId
    val providerId: String? get() = session.providerId
    val model: String? get() = session.model
    val reasoningEffort: String? get() = session.reasoningEffort
    val approvalMode: ApprovalMode? get() = session.approvalMode
    val streaming: String get() = run.streaming
    val reasoningRevision: Int get() = run.reasoningRevision
    val runningHere: Boolean get() = run.runningHere
    val compressingSince: Long get() = run.compressingSince
    val status: String get() = run.status
    val runningTool: String? get() = run.runningTool
    val contextOverhead: Pair<Int, Int>? get() = session.contextOverhead
    val liveParts: List<LivePart> get() = run.liveParts
    val liveRunId: String get() = run.liveRunId
    val error: String? get() = run.error
    val draft: Draft get() = input.draft
    val pendingSteers: List<PendingSteer> get() = input.pendingSteers
    val pendingTurns: List<PendingTurn> get() = input.pendingTurns
    val todos: List<TodoItem> get() = input.todos
    val checkpoints: List<ContextCheckpoint> get() = transcript.checkpoints

    fun copy(
        conversationId: String = this.conversationId,
        title: String = this.title,
        loading: Boolean = this.loading,
        messages: List<StoredMessage> = this.messages,
        messagesRevision: Long = this.messagesRevision,
        messageCount: Int = this.messageCount,
        turnCount: Int = this.turnCount,
        activeRuns: Map<String, String> = this.activeRuns,
        assistantId: String = this.assistantId,
        providerId: String? = this.providerId,
        model: String? = this.model,
        reasoningEffort: String? = this.reasoningEffort,
        approvalMode: ApprovalMode? = this.approvalMode,
        streaming: String = this.streaming,
        reasoningRevision: Int = this.reasoningRevision,
        runningHere: Boolean = this.runningHere,
        compressingSince: Long = this.compressingSince,
        status: String = this.status,
        runningTool: String? = this.runningTool,
        contextOverhead: Pair<Int, Int>? = this.contextOverhead,
        liveParts: List<LivePart> = this.liveParts,
        liveRunId: String = this.liveRunId,
        error: String? = this.error,
        draft: Draft = this.draft,
        pendingSteers: List<PendingSteer> = this.pendingSteers,
        pendingTurns: List<PendingTurn> = this.pendingTurns,
        todos: List<TodoItem> = this.todos,
        checkpoints: List<ContextCheckpoint> = this.checkpoints,
    ): ChatUiState {
        val nextSession = if (conversationId == this.conversationId &&
            title == this.title &&
            loading == this.loading &&
            assistantId == this.assistantId &&
            providerId == this.providerId &&
            model == this.model &&
            reasoningEffort == this.reasoningEffort &&
            approvalMode == this.approvalMode &&
            contextOverhead == this.contextOverhead
        ) session else ChatSessionState(
            conversationId, title, loading, assistantId, providerId, model,
            reasoningEffort, approvalMode, contextOverhead,
        )
        val nextTranscript = if (messages === this.messages &&
            messagesRevision == this.messagesRevision &&
            messageCount == this.messageCount &&
            turnCount == this.turnCount &&
            activeRuns === this.activeRuns &&
            checkpoints === this.checkpoints
        ) transcript else ChatTranscriptState(messages, messagesRevision, messageCount, turnCount, activeRuns, checkpoints)
        val nextRun = if (streaming == this.streaming &&
            reasoningRevision == this.reasoningRevision &&
            runningHere == this.runningHere &&
            compressingSince == this.compressingSince &&
            status == this.status &&
            runningTool == this.runningTool &&
            liveParts === this.liveParts &&
            liveRunId == this.liveRunId &&
            error == this.error
        ) run else ChatRunState(
            streaming, reasoningRevision, runningHere, compressingSince, status,
            runningTool, liveParts, liveRunId, error,
        )
        val nextInput = if (draft == this.draft &&
            pendingSteers === this.pendingSteers &&
            pendingTurns === this.pendingTurns &&
            todos === this.todos
        ) input else ChatInputState(draft, pendingSteers, pendingTurns, todos)
        return if (nextSession === session && nextTranscript === transcript &&
            nextRun === run && nextInput === input
        ) this else ChatUiState(nextSession, nextTranscript, nextRun, nextInput)
    }

    override fun equals(other: Any?): Boolean = this === other || other is ChatUiState &&
        session == other.session && transcript == other.transcript &&
        run == other.run && input == other.input

    override fun hashCode(): Int =
        ((session.hashCode() * 31 + transcript.hashCode()) * 31 + run.hashCode()) * 31 + input.hashCode()

    override fun toString(): String =
        "ChatUiState(session=$session, transcript=$transcript, run=$run, input=$input)"

    /**
     * The transcript as drawn: one run per turn, newest first pick wins.
     *
     * **生产代码零调用**（UI 用 [Transcript] 自己的推导），唯一消费者是
     * SteerRenderTest —— 它是「流式中的 steer 不得出现在转录里」这条规则的可执行规范。
     * 不要在生产里用它：每读一次都对整条转录重算一遍。
     */
    val visible: List<StoredMessage>
        get() = Transcript.active(messages, activeRuns)
            .filterNot { liveRunId.isNotEmpty() && it.runId == liveRunId }
}
