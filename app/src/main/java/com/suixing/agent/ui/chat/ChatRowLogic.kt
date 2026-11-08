package com.suixing.agent.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.material3.ripple
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import com.suixing.agent.ui.components.INLINE_ICON_GLYPH
import com.suixing.agent.ui.components.InlineIconAction
import com.suixing.agent.R
import com.suixing.agent.agent.StreamStats
import com.suixing.agent.ui.components.MenuCard
import com.suixing.agent.data.Assistant
import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.FileActivity
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.Tps
import com.suixing.agent.data.TODO_COMPLETED
import com.suixing.agent.data.TodoItem
import com.suixing.agent.data.Transcript
import com.suixing.agent.ui.assistant.AssistantAvatar
import com.suixing.agent.ui.chat.markdown.LocalPathClick
import com.suixing.agent.ui.chat.markdown.MarkdownText
import com.suixing.agent.ui.chat.markdown.MessageSegment
import com.suixing.agent.ui.chat.markdown.splitImageSegments
import com.suixing.agent.ui.components.SuixingMark
import com.suixing.agent.ui.chat.markdown.markdownBody
import com.suixing.agent.ui.theme.LocalUserBubbleColor
import java.util.Locale

internal fun StoredMessage.isVisible(): Boolean = when (role) {
    Roles.USER -> text.isNotBlank() || imageCount > 0
    Roles.ASSISTANT -> text.isNotBlank() || !reasoning.isNullOrBlank()
    Roles.TOOL -> true
    // 失败回执（VM 在 Failed 时写的一条 system 消息）：它存在的意义就是被看见，所以有字就画。
    // 磁盘上此前没有任何 system 消息（全仓 11 处 StoredMessage 构造点 + 真机两份 chat JSON 核实），
    // 上下文摘要只进 wire 不落盘，所以这个分支不会被摘要误触发。
    Roles.SYSTEM -> text.isNotBlank()
    else -> false
}

/**
 * Folds the transcript into bubbles.
 *
 * Every tool result is claimed by the assistant bubble above it, so a reply and the
 * tools it ran read as one block; a text-less assistant turn is skipped rather than
 * drawn as an empty bubble.
 */
internal fun groupRows(
    messages: List<StoredMessage>,
    activeRuns: Map<String, String>,
    running: Boolean,
    /** 正在生成、尚未落盘的 run：让「重新回答」按下的一瞬间就清空旧答案。见 [Transcript.chosenFrom]. */
    pendingRun: String? = null,
): List<ChatRow> {
    val turnOf = Transcript.turnOf(messages)
    val runs = Transcript.runsByTurn(messages, turnOf)
    // `turnOf` 是 O(条数) 的扫描，下面几处都要它 —— 算一次传下去（原来被重复算了 4 次）。
    // 复用上面已经算好的 `runs`：`chosenRuns` 内部会再算一遍 `runsByTurn`，所以把 `chosen` 交进去。
    val chosen = Transcript.chosenFrom(runs, activeRuns, pendingRun)
    val shown = Transcript.active(messages, activeRuns, turnOf, chosen)
    // A steer line is the user talking mid-answer, not a new question: only a real
    // question can be re-answered or own the branch switcher.
    val lastQuestion = shown.lastOrNull { it.role == Roles.USER && !it.steer }?.id.orEmpty()

    // The switcher hangs off the last reply a turn actually drew, so a text-less
    // turn of the model cannot swallow it.
    val tail = HashMap<String, String>()
    shown.forEach { message ->
        val turn = turnOf[message.id].orEmpty()
        if (message.role == Roles.ASSISTANT && message.isVisible() && turn.isNotEmpty()) {
            tail[turn] = message.id
        }
    }

    fun branchFor(message: StoredMessage): Branch? {
        val turn = turnOf[message.id].orEmpty()
        if (turn.isEmpty() || tail[turn] != message.id) return null
        val siblings = runs[turn].orEmpty()
        // Re-answering is only offered on the newest question; older ones would have
        // to invalidate everything below them, which branch switching never does.
        val regenerate = turn == lastQuestion && !running
        if (siblings.size < 2 && !regenerate) return null
        return Branch(
            turnId = turn,
            runs = siblings,
            index = siblings.indexOf(chosen[turn]).coerceAtLeast(0),
            canRegenerate = regenerate,
        )
    }

    val rows = mutableListOf<ChatRow>()
    var previous: StoredMessage? = null
    // The bubble that is still collecting, and the run it belongs to. Another round
    // of that same run folds into it rather than starting a second bubble.
    var openIndex = -1
    var openRun: String? = null

    shown.forEach { message ->
        when {
            message.role == Roles.TOOL -> {
                val index = openIndex
                if (index >= 0) {
                    val open = rows[index]
                    // Consecutive calls share one block; a call that arrives after
                    // thinking or prose starts a new one, so the sequence stays honest.
                    val parts = if (open.parts.lastOrNull() is Part.Tools) {
                        open.parts.dropLast(1) +
                            Part.Tools((open.parts.last() as Part.Tools).calls + ToolLine.of(message))
                    } else {
                        open.parts + Part.Tools(listOf(ToolLine.of(message)))
                    }
                    rows[index] = open.copy(parts = parts)
                } else {
                    // A tool result with no bubble above it still gets drawn — and it opens
                    // the bubble, claiming the run that produced it. The round that asked for
                    // the call can carry neither text nor thinking (a pure tool-call round),
                    // which makes it invisible to the assistant branch below, so nothing is
                    // open by the time its result lands. Leaving this card unclaimed is what
                    // split one answer: every following result of the same run started yet
                    // another card, and the round that finally wrote prose could not fold
                    // back in because its run no longer matched an open one.
                    rows += ChatRow(
                        message = message,
                        showTimeLabel = needsTimeLabel(previous, message),
                        parts = listOf(Part.Tools(listOf(ToolLine.of(message)))),
                    )
                    previous = message
                    openIndex = rows.lastIndex
                    openRun = message.runId.orEmpty()
                }
            }

            message.role == Roles.ASSISTANT && message.isVisible() -> {
                val run = message.runId.orEmpty()
                // Thinking first, then the words: that is the order they arrived in, and
                // a round that only thought contributes only a thinking part.
                val arrived = buildList {
                    // 重试留痕在最前：它发生在这一轮产出任何字之前，排在思考/正文前面才是它真正的位置。
                    message.traceNote?.takeIf { it.isNotBlank() }?.let { add(Part.Trace(it)) }
                    // Preserve the same lines as the live block at hand-over. Trimming
                    // here removed leading/trailing rows under an active reader.
                    message.reasoning?.takeIf { it.isNotBlank() }?.let {
                        add(Part.Thinking(it, message.reasoningTokens, message.elapsedMs, run))
                    }
                    if (message.text.isNotBlank()) add(Part.Text(message.text))
                }
                val index = openIndex
                // A round folds into the bubble already open when its run is that row's run. The
                // comparison used to demand a *non-empty* run as well, and that is the one thing it
                // must not do: `Transcript.BASE_RUN` is the empty string — the run id every message
                // written before branching shares — so two rounds of one legacy answer could never
                // fold, and they drew as two cards, the first of them a process header with no prose
                // under it. That is the whole of this rule; a first version of the fix also let a
                // "tool card with no assistant round yet" be claimed by any round, and the test for
                // it proved the branch unreachable: `groupRows` is fed `Transcript.active(...)`,
                // which keeps one run per turn, so every assistant and tool message of a turn
                // already carries the same run id and the plain comparison folds them all.
                if (index >= 0 && run == openRun) {
                    val open = rows[index]
                    // 相邻同文思考去重（用户截图：「两个一模一样的」）：重试/中断重落盘会把同一段
                    // 思维链再写一次，同一张卡里就出现两个一字不差的思考块。相邻且文本相同只留
                    // 第一个 —— 卡片表头的「思考 N 段」也跟着不再虚高。
                    val fresh = arrived.filterNot { part ->
                        part is Part.Thinking &&
                            (open.parts.lastOrNull() as? Part.Thinking)?.text == part.text
                    }
                    rows[index] = open.copy(
                        message = message,
                        parts = open.parts + fresh,
                        rounds = open.rounds + message,
                        // The switcher only hangs off the reply's last round, which
                        // is this one.
                        branch = branchFor(message) ?: open.branch,
                    )
                } else {
                    rows += ChatRow(
                        message = message,
                        showTimeLabel = needsTimeLabel(previous, message),
                        parts = arrived,
                        branch = branchFor(message),
                    )
                    openIndex = rows.lastIndex
                    openRun = run
                }
                previous = message
            }

            // A text-less assistant turn: nothing to draw, and its tools fall through
            // to the bubble that is already open.
            message.role == Roles.ASSISTANT -> Unit

            // 插话: drawn where it was typed, inside the turn it interrupted, and the
            // bubble stays open so the model's next round appends after it.
            message.role == Roles.USER && message.steer -> {
                val index = openIndex
                if (index >= 0) {
                    val open = rows[index]
                    rows[index] = open.copy(parts = open.parts + Part.Steer(message))
                } else {
                    // Typed before the model had written anything back yet.
                    rows += ChatRow(
                        message = message,
                        showTimeLabel = needsTimeLabel(previous, message),
                        parts = listOf(Part.Steer(message)),
                    )
                }
                previous = message
            }

            message.isVisible() -> {
                rows += ChatRow(
                    message = message,
                    showTimeLabel = needsTimeLabel(previous, message),
                )
                previous = message
                // 失败回执自己成一行、不接管 open 气泡：它后面不该再往里折工具行或正文
                // （run 已经结束）。普通可见消息维持原行为（关掉 open 气泡）。
                if (message.role != Roles.SYSTEM) {
                    openIndex = -1
                    openRun = null
                }
            }

            else -> Unit
        }
    }
    return rows
}

/**
 * How many turns of a conversation are drawn when it is opened, and how many each 「加载更早」
 * adds. Twelve is two of the scrubber's own six-row windows: enough that a chat opens on real
 * scrollback rather than a screenful, small enough that the grouping, the checkpoint map and the
 * turn landmarks are priced on a tail instead of on a transcript that can run to thousands of
 * messages — and the strip can be unlocked a whole window further back before the reader meets
 * the drawn edge and the button takes over.
 */
internal const val PAGE_TURNS = 12

/**
 * The part of a conversation the transcript draws.
 *
 * [from] is the message it starts at, [drawn] of the conversation's [turns] are in it, and
 * [earlier] is what that leaves above it — which is also the number the oldest drawn turn really
 * is, since a turn is what the reader counts in. A page is always a suffix, the newest turns:
 * that is where a conversation is read from and where a streaming answer lives.
 */
internal data class TurnPage(
    val from: Int,
    /**
     * 轮数总数。**默认等于已加载窗口里的轮数**，但打开会话只读最近 N 轮时，
     * 调用方要传**磁盘上的真实总轮数**（`meta.turnCount`）—— 否则 [earlier] 恒为 0，
     * 「加载更早」按钮不出现、而且「第 N 轮」会永远显示成第 1 轮（#30 的可见回归）。
     */
    val turns: Int,
    val drawn: Int,
) {
    /** Turns above the drawn part. */
    val earlier: Int get() = (turns - drawn).coerceAtLeast(0)
    val hasEarlier: Boolean get() = earlier > 0

    /**
     * 同一个已加载窗口，但按**磁盘上的总轮数**报数。
     *
     * `drawn` 是窗口里实际画出来的轮数，`turns` 换成总数后 [earlier] 就变成"上面还有多少轮"，
     * 于是「加载更早」和「第 N 轮」都对了。
     */
    fun withTotal(totalTurns: Int): TurnPage =
        copy(turns = totalTurns.coerceAtLeast(drawn))
}

/**
 * Which page of [messages] a request for [want] turns draws. The cut is made on a turn boundary
 * and never inside one: a run's rounds belong to the card the grouping builds for them, and half
 * of one drawn alone would be a card of tools whose question is not on screen. [want] below a
 * single turn is read as one: a page with nothing in it is not a page.
 */
internal fun turnPage(messages: List<StoredMessage>, want: Int): TurnPage {
    // **一趟扫描 + 环形缓冲**（原先是 `messages.indices.filter{}`：为取最后几个起点，
    // 给整份 transcript 建了一个 Int 列表）。ring 只留最后 cap 个起点下标；
    // 下面取的 `first` 必然 ≥ 总数-cap，所以一定落在 ring 里，取模即得，逐值等价。
    val cap = if (want < 1) 1 else want
    val ring = IntArray(cap)
    var total = 0
    for (i in messages.indices) {
        val m = messages[i]
        if (m.role == Roles.USER && !m.steer) {
            ring[total % cap] = i
            total++
        }
    }
    if (total == 0) return TurnPage(from = 0, turns = 0, drawn = 0)
    val drawn = want.coerceIn(1, total)
    val first = total - drawn
    // A conversation that opens with something other than a question keeps it, as long as its
    // first turn is drawn; from the second turn on, the page starts at the question itself.
    return TurnPage(
        from = if (first == 0) 0 else ring[first % cap],
        turns = total,
        drawn = drawn,
    )
}

/** At most twelve real turns; short conversations do not reserve empty slots. */
internal fun scrubberRowCount(totalTurns: Int, currentTurn: Int): Int =
    totalTurns.coerceIn(0, SCRUBBER_VISIBLE_MAX)

/** Twelve consecutive loaded turns, sliding to include the current global turn. */
internal fun scrubberWindow(
    totalTurns: Int,
    currentTurn: Int,
    drawnTurns: Int,
    unlocked: Int = 0,
): IntRange {
    if (totalTurns <= 0 || drawnTurns <= 0) return IntRange.EMPTY
    val loaded = drawnTurns.coerceAtMost(totalTurns)
    val size = minOf(SCRUBBER_VISIBLE_MAX, loaded)
    val oldest = totalTurns - loaded + 1
    val start = ((currentTurn.coerceIn(oldest, totalTurns) - size / 2)
        .coerceIn(oldest, totalTurns - size + 1) - unlocked.coerceAtLeast(0)).coerceAtLeast(oldest)
    return start..(start + size - 1)
}

/** Every visible slot represents an existing loaded turn, in chronological order. */
internal fun scrubberSlots(window: IntRange): List<Int> {
    if (window.isEmpty()) return emptyList()
    // The rail is capped at twelve, so a long window needs only its tail. Building the full
    // list first allocated one Int per loaded turn on every window change (the scrubber
    // remembers the result, but the allocation still lands during a drag's re-windowing).
    val size = window.last.toLong() - window.first + 1
    if (size <= SCRUBBER_VISIBLE_MAX) return window.toList()
    val start = window.last - SCRUBBER_VISIBLE_MAX + 1
    return List(SCRUBBER_VISIBLE_MAX) { start + it }
}

/**
 * How far the strip has been unlocked by a finger at [y], given how far it already was: the top
 * line gives ground from the moment the finger is on it — the band the hit test calls the top
 * line, its centre half a row either side — and every further row of upward travel above it
 * unlocks one more turn. Monotonic — coming back down the strip does not re-lock the turns it
 * just reached, or the drag would take them away again the moment it turned around.
 */
internal fun scrubberPull(y: Float, rowPx: Float, pitchPx: Float, pull: Int): Int {
    if (pitchPx <= 0f) return pull
    val topBand = rowPx / 2f + pitchPx / 2f
    val overshoot = topBand - y
    if (overshoot < 0f) return pull
    return maxOf(pull, (overshoot / pitchPx).toInt() + 1)
}

/** The rail never expands while pressing or dragging. */
internal const val SCRUBBER_VISIBLE_MAX = 12

/** 看板上画几行。 */
internal const val TODO_ROWS_SHOWN = 3

internal fun todoRows(todos: List<TodoItem>, limit: Int = TODO_ROWS_SHOWN): List<TodoItem> =
    todoBoard(todos, limit).first

/**
 * The strip's rows and the completed tally in one scan.
 *
 * The composer used to call [todoRows] and then `todos.count { completed }` separately: the
 * first stopped at three rows, but the second walked the whole board again. A long plan is
 * exactly when the strip is visible, so the extra pass was on the hot path.
 */
internal fun todoBoard(todos: List<TodoItem>, limit: Int = TODO_ROWS_SHOWN): Pair<List<TodoItem>, Int> {
    val cap = limit.coerceAtLeast(0)
    val open = ArrayList<TodoItem>(minOf(cap, todos.size))
    var done = 0
    for (todo in todos) {
        if (todo.status == TODO_COMPLETED) {
            done++
            continue
        }
        if (open.size < cap) open += todo
    }
    return (if (open.isEmpty()) todos.takeLast(cap) else open) to done
}

/** 失败回执的固定前缀：写入方（ChatViewModel）与去重方（ChatScreen）共用，免得两处各写一遍。 */
internal const val FAILURE_RECEIPT_PREFIX = "这一轮最终失败："

/**
 * 这条回执与当前错误是不是同一件事。**不能只比完全相等**：一次失败的提示可能由两条路径写
 * （引擎的 `Failed` 事件、传输层的 catch），前缀相同而尾串可以不同；精确相等匹配不上时，屏幕上
 * 就会同时出现红色回执卡和红色提示行（用户：「为什么还是有两个错误提示」）。剥掉前缀再比，
 * 且任一侧包含另一侧也算同一件事 —— 少显示一条重复提示，好过把同一次失败报两遍。
 */
internal fun isSameFailure(receipt: String, error: String): Boolean {
    val want = error.trim()
    if (want.isEmpty()) return false
    val body = receipt.trim().removePrefix(FAILURE_RECEIPT_PREFIX).trim()
    if (body.isEmpty()) return false
    return body == want || body.contains(want) || want.contains(body)
}

internal data class RingReading(val tokens: Int, val estimated: Boolean)

internal fun latestContextPrompt(messages: List<StoredMessage>, checkpoint: ContextCheckpoint? = null): Int {
    // A report before the most recent compression belongs to the old prompt. Newer records
    // have an exact transcript anchor, while legacy records only have the compression time.
    val anchor = checkpoint?.anchorId?.takeIf { it.isNotEmpty() }
    val afterAnchor = anchor?.let { id -> messages.indexOfLast { it.id == id } } ?: -1
    for (index in messages.lastIndex downTo afterAnchor + 1) {
        val message = messages[index]
        if (checkpoint != null && afterAnchor < 0 && message.timestamp <= checkpoint.at) continue
        if (message.promptTokens > 0) return message.promptTokens
    }
    return 0
}

internal fun ringReading(
    newestStored: Int,
    persistedContext: Int,
    estimate: Int?,
    compressed: Boolean = false,
): RingReading? = when {
    // The loaded tail includes tool-only rounds and may be newer than the asynchronously read meta.
    newestStored > 0 -> RingReading(newestStored, estimated = false)
    // Meta has a count but no request timestamp, so it cannot prove a report followed compression.
    !compressed && persistedContext > 0 -> RingReading(persistedContext, estimated = false)
    else -> estimate?.coerceAtLeast(0)?.let { RingReading(it, estimated = it > 0) }
}

/**
 * 把三块**估算**的 token 归一到端点**实测**的总数上。
 *
 * 用户报「圆环的三条的对应 token 数字对不上总 token 数」：顶部那个总数是端点回报的实测
 * prompt tokens，而三条（系统提示词 / 工具定义 / 对话消息）是本地按字符数 ÷4 估出来的
 * （见 AgentEngine 的 breakSystem / breakTools / breakHistory）。两个口径不同，加起来当然
 * 对不上。
 *
 * 现在按比例把估算值缩放到实测总数上，**且最后一条吸收舍入误差** ⇒ 三条相加**精确等于**
 * [total]。返回的第三个数就是给「对话消息」那一行的。
 *
 * [total] 为 0（还没有总数）或三块全为 0 时，原样返回，不编造。
 */
internal fun normaliseContextSplit(
    system: Int,
    tools: Int,
    history: Int,
    total: Int,
): Triple<Int, Int, Int> {
    val sum = system + tools + history
    if (sum <= 0 || total <= 0) return Triple(system, tools, history)
    // 先按比例缩放前两条，最后一条用「总数 − 前两条」兜住误差。
    val scaledSystem = system.toLong() * total / sum
    val scaledTools = tools.toLong() * total / sum
    val scaledHistory = (total - scaledSystem - scaledTools).coerceAtLeast(0)
    return Triple(scaledSystem.toInt(), scaledTools.toInt(), scaledHistory.toInt())
}

internal const val TIME_LABEL_GAP_MS = 10 * 60 * 1000L

internal fun needsTimeLabel(previous: StoredMessage?, current: StoredMessage): Boolean {
    if (previous == null) return true
    return current.timestamp - previous.timestamp > TIME_LABEL_GAP_MS
}
