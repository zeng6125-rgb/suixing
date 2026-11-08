package com.suixing.agent.ui.chat

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.layout.layout
import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.ViewportBookmark
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Immutable projection identities, per-chat window state, and the shared markdown/navigation cache. */
internal class ChatTranscriptProjection(
    val displayedChat: State<String>,
    val windowState: MutableState<Map<String, Int>>,
    val messagePositions: Map<String, Int>,
    val shownTurns: Int,
    val rowsKey: Triple<String, Int, String?>,
    val page: TurnPage,
    val visibleShape: Any,
    val visible: List<ChatRow>,
    val rowKeys: List<String>,
    val rowKeyIndices: Map<String, Int>,
    val viewport: ConversationViewport,
    val rowDocuments: RowMarkdownCache,
    val navigationToken: IntArray,
    val navigationJob: Array<Job?>,
    val errorLine: Boolean,
    val rowOffset: Int,
    val slots: ListSlots,
    val turnIndices: List<Int>,
    val currentReadingTurn: State<Int>,
    val checkpointsByRow: Map<String, List<ContextCheckpoint>>,
    val runElapsed: Map<String, Long>,
) {
    val listState get() = viewport.listState
    val itemCount get() = slots.total
}

@Composable
internal fun rememberChatTranscriptProjection(
    state: ChatUiState,
    positionStorageReady: Boolean,
    openingBookmark: ViewportBookmark?,
    live: Boolean,
): ChatTranscriptProjection {
    // How much of the conversation is drawn. A long chat used to be grouped, measured and
    // turned into rows in full on every state change, however far back the reader was —
    // the whole transcript priced on every streamed round. What is drawn instead is a
    // suffix, in whole turns: always the newest ones, so a streaming answer keeps its place
    // in it, and 加载更早 only ever grows it. The window is a property of the chat being read,
    // so the count is kept **per conversation** (issue #16): `rememberSaveable(key)` did not
    // keep one per chat, it re-initialised on every switch — coming back to A collapsed it to
    // the newest page, the saved scroll index then pointed past the end of a much shorter
    // transcript, and LazyColumn clamped it to the top. Kept saveable for the same reason as
    // before: the composition can be rebuilt (activity recreation) and the reader's 加载更早
    // taps must survive it.
    val displayedChat = rememberUpdatedState(state.conversationId)
    val windowState = rememberSaveable(
        stateSaver = androidx.compose.runtime.saveable.mapSaver<Map<String, Int>>(
            // Other chats retain their in-process window; only the displayed one enters Bundle.
            save = { windows -> windows.filterKeys { it == displayedChat.value } },
            restore = { saved -> saved.mapValues { (_, turns) -> turns as Int } },
        ),
    ) { mutableStateOf(emptyMap<String, Int>()) }
    var shownTurnsByConversation by windowState
    // 同一组合里只建一张 id→index 表，三处共用（issue #54 B-7）。
    val messagePositions = remember(state.conversationId, state.messagesRevision) {
        state.messages.firstPositions()
    }
    val bookmarkWindow = openingBookmark?.let { bookmark ->
        val added = if (bookmark.followLatest) 0L else (state.turnCount.toLong() - bookmark.totalTurns).coerceAtLeast(0)
        (bookmark.shownTurns.toLong() + added).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    } ?: PAGE_TURNS
    val anchorWindow = remember(state.conversationId, state.messagesRevision, openingBookmark) {
        if (openingBookmark == null || openingBookmark.followLatest) PAGE_TURNS else {
            // issue #53 B-2：用一次建表替换「每落一条消息扫一遍全表」的线性查找。
            // 语义保持 indexOfFirst：各自最早命中取最小 ⇒ 与「第一个满足任一条件的下标」等价。
            val positions = messagePositions
            val candidates = buildList {
                positions[openingBookmark.messageId]?.let(::add)
                openingBookmark.nearbyMessageIds.forEach { positions[it]?.let(::add) }
                openingBookmark.runId?.let { rid ->
                    val i = state.messages.indexOfFirst { it.runId == rid }
                    if (i >= 0) add(i)
                }
            }
            val at = candidates.minOrNull() ?: -1
            if (at < 0) PAGE_TURNS else state.messages.asSequence().drop(at)
                .count { it.role == Roles.USER && !it.steer } + 1
        }
    }
    val shownTurns = maxOf(shownTurnsByConversation[state.conversationId] ?: PAGE_TURNS, bookmarkWindow, anchorWindow)
    // row is last.
    //
    // Not the list itself. `remember` compares its keys with `equals`, and a message carries its
    // screenshot as base64 — one picture made each comparison a string compare of hundreds of
    // kilobytes, on every recomposition, which is once per publish (twelve a second) while an
    // answer streams. These walks only move when a row lands, the last one is replaced, or the
    // screen switches conversation; the id is in the key because two conversations can otherwise
    // agree on a shape (two empty ones always do).
    val rowsKey = Triple(
        state.conversationId,
        state.messages.size,
        state.messages.lastOrNull()?.id,
    )
    // [turnPage] reads nothing but each message's role and steer flag, so this key says everything
    // its answer can depend on.
    // **轮数总数要按磁盘上的真实值报**（#30）：`state.messages` 现在只是"最近 N 轮"这个窗口，
    // 用它算出来的 `turns` 就等于窗口里的轮数 ⇒ `earlier` 恒为 0 ⇒ 「加载更早」不出现、
    // 而且「第 N 轮」永远显示成第 1 轮（用户能直接看见的错）。
    // 总数来自落盘 meta 的 `turnCount`（由 ChatStore 维护，**是轮数不是条数**）。
    val page = remember(rowsKey, shownTurns, state.turnCount) {
        val drawn = turnPage(state.messages, shownTurns)
        if (state.turnCount > drawn.turns) drawn.withTotal(state.turnCount) else drawn
    }
    // Tool-only assistant turns carry no text of their own, so their tool calls are
    // folded into the bubble above them instead of standing alone in the transcript.
    // Only the branch the user picked is drawn; the other answers stay on disk.
    // liveRunId is a key AND a filter: the rounds of the run still streaming are
    // already in `messages` (every event is appended there as it lands), so without
    // the filter groupRows drew them as their own settled bubble beside the live one
    // — the double bubble. The filter is what makes the settle frame atomic: the
    // rounds leave the stored list in the same state update that empties liveParts.
    // The transcript itself is keyed by [ChatUiState.messagesRevision], not by the list: `remember`
    // compares keys with `equals`, and a message carries its screenshot as base64 — keyed on the
    // list, every recomposition walked the whole conversation (twelve times a second while an
    // answer streams). The revision is stamped by the view model at the one point every publish
    // goes through, so an edit to a message in the middle of the transcript still lands here.
    // **转录形状 token**：`visible` 每到这些输入变化就重建一次，下游派生只需要知道
    // 「重建过没有」。原来它们直接拿 `visible` 当 `remember` 的键 —— 而 `remember` 比较键用
    // `equals`，即每次重组都要把**每一行的正文、思考、工具输出、图片 base64 深比较一遍**
    // （`StoredMessage`/`Part` 都是 data class，行也是每拍新建的，引用短路的便宜都吃不到）。
    // 流式期每秒几十拍、一页十几轮 ⇒ 主线程上白跑的字符比较。换成这个 token：键仍是原来那几项
    // （标量 + 小 Map），比较是引用相等，真实重建才换新实例。
    val visibleShape = remember(
        state.conversationId,
        state.messagesRevision,
        page,
        state.activeRuns,
        state.runningHere,
        state.liveRunId,
    ) { Any() }
    val visible = remember(visibleShape) {
        // `drop()` 每次都把窗口内的引用复制成新 List；流式期这条路径每 16ms 跑一次，
        // 长会话（上千条）就是每秒几十万次引用拷贝。`subList` 是视图，零拷贝。
        val pageMessages = state.messages.subList(page.from.coerceIn(0, state.messages.size), state.messages.size)
        groupRows(
            pageMessages,
            state.activeRuns,
            state.runningHere,
            // 空串要变 null：`liveRunId` 用空串表示"没有在跑"。
            state.liveRunId.ifEmpty { null },
        )
            .filterNot { it.message.runId == state.liveRunId && state.liveRunId.isNotEmpty() }
    }
    // Long settled bodies are parsed only for the next mount batch. Those rows consume the
    // returned blocks directly; there is no competing full-page parse or global cache prewarm.
    // The physical feed uses the same old-to-new order as this projection.
    val rowKeys = remember(visibleShape) { presentationKeys(state.conversationId, visible) }
    val rowKeyIndices = remember(rowKeys) { rowKeys.withIndex().associate { it.value to it.index } }
    val viewport = remember(state.conversationId, positionStorageReady) {
        ConversationViewport(state.conversationId, openingBookmark)
    }
    val listState = viewport.listState

    // Markdown preparation is cached by message; navigation reads LazyList's own layout.
    // Per-row state publishes preparation without invalidating unrelated messages.
    val rowDocuments = remember(state.conversationId) { RowMarkdownCache() }
    val visibleIds = remember(visibleShape) { visible.mapTo(HashSet()) { it.message.id } }
    SideEffect {
        rowDocuments.retainRows(visibleIds)
    }
    val navigationToken = remember(state.conversationId) { intArrayOf(0) }
    val navigationJob = remember(state.conversationId) { arrayOfNulls<Job>(1) }
    DisposableEffect(navigationJob) {
        onDispose { navigationJob[0]?.cancel() }
    }
    // 回执（落盘的 system 消息）**内联画在它那一轮后面**（用户：「一轮错误就跟在一轮后面」）——
    // MessageRow 的分发里画（Roles.SYSTEM → FailureReceiptRow）。这里只取一遍供去重：同文案的
    // 实时行不再画，一次失败只通知一次（用户报的「两个提醒」）。
    val receipts = remember(state.conversationId, state.messagesRevision, page) {
        state.messages.subList(page.from.coerceIn(0, state.messages.size), state.messages.size)
            .filter { it.role == Roles.SYSTEM }
    }
    // 没有回执的失败（发送阶段错误等）的实时行，画在最后一轮下面。去重见 [isSameFailure]：
    // 只按完全相等比会漏，同一次失败被两条路径各写一遍时屏幕上是两张提示。
    val errorLine = remember(state.error, receipts) {
        state.error?.let { err -> receipts.none { isSameFailure(it.text, err) } } ?: false
    }
    // The 「加载更早」 line sits above the transcript as an item of its own, so every row
    // below it is one item further down than its place in [visible].
    val rowOffset = if (page.hasEarlier) 1 else 0
    // The live card (with its progress caption inside) is one item; the error line and the
    // 「加载更早」 line are items of their own. 底锚下这个数只用于「有没有内容」与插入回底。
    // 槽位账本走纯函数（`ListSlots`，带单测）：这两个数原来是**各写一遍**的，而且已经数得不一致
    // —— `feedPrefix` 数了「压缩中」那一行、`itemCount` 没数。今天少一项只影响 `<= 0` 这类判据，
    // 但列表以后再加一行，另一处必然漏（这条链路已经出过三次同类事故）。
    val slots = ListSlots.of(
        hasLiveCard = live || state.runningHere,
        runningHere = state.runningHere,
        compressing = state.compressingSince > 0L,
        hasErrorLine = errorLine,
        visibleRows = visible.size,
        hasLoadEarlier = page.hasEarlier,
    )
    val itemCount = slots.total

    // The user's own messages are the landmarks of a conversation, so the status line can
    // say "turn 2 of 5" and jump between them without the engine knowing anything about it.
    // Steered lines ride inside the turn they steer, so counting them would draw one
    // landmark per interjection — a question plus two steers is still one turn.
    // The indices are visible coordinates with [rowOffset] included; [fedIndexOf] turns them
    // into the bottom-anchored list's feed indices when the strip jumps. The drawn turns are
    // the tail of the conversation, which is why the labels that name them are numbered from
    // [TurnPage.earlier] rather than from one.
    // Keyed on the drawn rows' shape rather than on the rows: this walk reads each row's role and
    // steer flag and nothing else, while `remember(visible, …)` would compare every row — and a
    // row carries its message, screenshot and all. The two ids catch a list that was rebuilt with
    // the same length but different ends (a branch switch, a paged window that moved).
    val turnIndices = remember(
        visible.size,
        visible.firstOrNull()?.message?.id,
        visible.lastOrNull()?.message?.id,
        rowOffset,
    ) {
        val starts = visible.indices
            .filter {
                visible[it].message.role == Roles.USER && !visible[it].message.steer
            }
            .map { it + rowOffset }
        // **一行用户消息都没认出来时不能留空**：`TurnMarks.current` 见到空表就直接
        // `return newest` ⇒ "当前轮"恒等于最新那一轮 ⇒ 条数永远 5、高亮永远在最下面、
        // 点条也全错位（三种反馈同因）。宁可退化成一"行"一轮，也不能让整条导航条失效。
        if (starts.isEmpty()) visible.indices.map { it + rowOffset } else starts
    }
    // Keep the scrubber's legacy reverse coordinate, while physical rows are chronological.
    // Temporary rows are now at the end; only load-earlier precedes stored messages.
    val feedPrefix = slots.feedPrefix

    // **不在 ChatScreen 组合体里读 `.value`**（issue #26 原因一）：`firstVisibleItemIndex` 滚动时
    // 每跨一个 item 就变一次，一旦在这里读，整个 ChatScreen（1600 行、几十个 Composable 调用）
    // 就要重跑一遍。把读取推迟到 `TurnScrubber` 内部（见下面 `current = { … }`），
    // 跨边界时只有导航条自己重组。用 `remember` 持有 State 对象而不是 `by` —— `by` 一取就立刻读了。
    // Derive the turn itself: crossing assistant/tool rows within a turn should not
    // invalidate the rail. All changing window inputs are keys, never scroll values.
    val currentReadingTurn = remember(
        listState, turnIndices, rowOffset, visible.size, page.drawn, page.turns, live,
    ) {
        derivedStateOf(structuralEqualityPolicy()) {
            val layout = listState.layoutInfo
            val probe = TurnMarks.probeOffset(layout.viewportStartOffset, layout.viewportEndOffset)
            val physicalIndex = layout.visibleItemsInfo.lastOrNull { it.offset <= probe }?.index
                ?: layout.visibleItemsInfo.firstOrNull()?.index ?: 0
            if (live && physicalIndex >= rowOffset + visible.size) {
                page.turns.coerceAtLeast(1)
            } else TurnMarks.currentAtRow(physicalIndex, turnIndices, page.drawn, page.turns)
        }
    }

    // Which compression records belong under which row. A record names the first and last
    // message it folded away, so it is drawn under the row that holds that last message —
    // the place in the transcript where the model stopped seeing the conversation verbatim.
    // **记忆键必须真正决定结果**（本轮对抗评审证伪了初版）：`placeCheckpoints` 的输出是按
    // **每行的 `message.id`** 作键、按**全部轮次 id** 反查锚点，所以形状至少要包含这两样。
    // 初版用 `rowKeys` 是错的 —— 多轮行的 rowKey 只有 `rounds.first().id`（不含 message.id），
    // 于是"末轮换了个 id、首轮没变"这种形态会在键相同的情况下产出不同结果（评审给了反例）。
    // 现在的形状 = 每行的 (message.id, 全部轮次 id)；比较时全是同一批 String 实例，走引用短路。
    // [visible] 作为 `remember` 键会通过 equals 判断是否变化，非空时保持原有结果缓存路径。
    // 空 checkpoint 的结果固定为空，因此直接返回，省去 `remember` 对 `visible` 的比较。
    val checkpointsByRow =
        if (state.checkpoints.isEmpty()) emptyMap() else remember(state.checkpoints, visible) {
            placeCheckpoints(visible, state.checkpoints)
        }
    val runElapsed = remember(rowsKey) {
        // One pass and one map. The old sequence/filter/groupBy/mapValues chain built a
        // list per run and three intermediate collections for a value read per row.
        buildMap<String, Long> {
            state.messages.forEach { message ->
                val run = message.runId
                if (!run.isNullOrEmpty() && message.elapsedMs > 0) {
                    put(run, (get(run) ?: 0L) + message.elapsedMs)
                }
            }
        }
    }

    return ChatTranscriptProjection(
        displayedChat = displayedChat,
        windowState = windowState,
        messagePositions = messagePositions,
        shownTurns = shownTurns,
        rowsKey = rowsKey,
        page = page,
        visibleShape = visibleShape,
        visible = visible,
        rowKeys = rowKeys,
        rowKeyIndices = rowKeyIndices,
        viewport = viewport,
        rowDocuments = rowDocuments,
        navigationToken = navigationToken,
        navigationJob = navigationJob,
        errorLine = errorLine,
        rowOffset = rowOffset,
        slots = slots,
        turnIndices = turnIndices,
        currentReadingTurn = currentReadingTurn,
        checkpointsByRow = checkpointsByRow,
        runElapsed = runElapsed,
    )
}
