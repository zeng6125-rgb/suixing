package com.suixing.agent.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import com.suixing.agent.R
import com.suixing.agent.agent.StreamStats
import com.suixing.agent.ui.components.MenuCard
import com.suixing.agent.data.Assistant
import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.Tps
import com.suixing.agent.data.TODO_COMPLETED
import com.suixing.agent.data.TodoItem
import com.suixing.agent.data.Transcript
import com.suixing.agent.ui.assistant.AssistantAvatar
import com.suixing.agent.ui.chat.markdown.MarkdownText
import com.suixing.agent.ui.components.SuixingMark
import com.suixing.agent.ui.chat.markdown.markdownBody
import java.util.Locale

// 模型层：气泡分组、分页、圆环读数、待办行、跳转轨窗口、附件。纯函数，单测覆盖。

/**
 * One bubble of the transcript: the message that owns it, the parts of the reply in the
 * order they arrived, and whether it opens a new time bucket.
 *
 * **@Immutable 是为了让 `MessageRow` 能被跳过**（issue #5）：这个类只装值（`List` 由构建处
 * 重新生成、从不原地改），但编译器把 `List` 判成 unstable，`ChatRow` 于是也 unstable，
 * `MessageRow` 的跳过优化就永远不生效 —— 每个可见项每帧都重组。`visible` 是 `remember` 出来的，
 * 行的**实例身份**在输入没变时保持不变，data class 的 equals 首行是 `this === other`，
 * 所以跳过判定是 O(1)。
 */
@Immutable
internal data class ChatRow(
    val message: StoredMessage,
    val showTimeLabel: Boolean,
    val parts: List<Part> = emptyList(),
    val branch: Branch? = null,
    /**
     * Every round the reply took, oldest first.
     *
     * The engine stores one assistant message per round, so a reply that ran a tool
     * arrives as two or three of them. They are one answer to the user, so they are
     * folded into one bubble here; [message] stays the newest round because that is
     * the one the action row and the time label belong to.
     */
    val rounds: List<StoredMessage> = listOf(message),
) {
    /**
     * 这一轮的总用量。**算一次就够**（issue #5）：原来是 `get() = Usage.of(rounds)`，
     * 每次读都跑 1 次 `none` + 4 次 `sumOf` 全量遍历；`MessageRow` 每次重组都读它，
     * 120Hz 滚动时每个可见项每帧都在重算。
     */
    val usage: Usage? by lazy { Usage.of(rounds) }
}

/**
 * One piece of a reply, in the order it arrived.
 *
 * A single run can think, call tools, think again, call more tools and finally answer.
 * Each of those is its own part and keeps its own block: merging two rounds of thinking
 * into one hides where the model changed its mind, and starting a second bubble for the
 * next round is what used to make one answer arrive as three.
 */
/**
 * One tool call, from either side of the run.
 *
 * The live run and the stored transcript draw the same row, so a call that starts while
 * the model is still working and the same call after it lands are the same widget — which
 * is what stops the tool block from flickering into a different shape when the run ends.
 */
@Immutable
internal data class ToolLine(
    val name: String,
    val args: String,
    val done: Boolean,
    val ok: Boolean,
    val elapsedMs: Long,
    /** The stored result; absent while the call is still running. */
    val message: StoredMessage? = null,
) {
    companion object {
        fun of(message: StoredMessage): ToolLine = ToolLine(
            name = message.toolName.orEmpty(),
            args = message.toolArgs.orEmpty(),
            done = true,
            ok = message.toolOk != false,
            elapsedMs = message.elapsedMs,
            message = message,
        )

        fun of(call: LiveTool): ToolLine = ToolLine(
            name = call.name,
            args = call.args,
            done = call.done,
            ok = call.ok,
            elapsedMs = call.elapsedMs,
            message = call.message,
        )
    }
}

// `@Immutable`：`Tools` 里有 `List<ToolLine>`，不标注时 `Part` 被推断成 unstable ⇒
// `AssistantRow(parts: List<Part>)` 不可跳过 ⇒ 每个流式 tick 重跑全部历史卡。
@Immutable
internal sealed interface Part {
    /**
     * One round of thinking, with that round's own numbers.
     *
     * [runId] is the run that produced it, and it is what makes a think the same block
     * before and after it is stored: the live row and the saved message are two different
     * composables, and only the run id is common to both. Blank for a message stored
     * before runs were keyed.
     */
    data class Thinking(
        val text: String,
        val tokens: Int,
        val elapsedMs: Long,
        val runId: String = "",
    ) : Part

    /** The tool calls made after the part before this one. */
    data class Tools(val calls: List<ToolLine>) : Part

    /**
     * The words the model wrote.
     *
     * [bornAtMs] 只有从流式块映射来的才有意义（issue #46）；历史消息一律 0 = 「很久以前」，
     * 打字机据此直接给全文，不重播。
     */
    data class Text(val text: String, val bornAtMs: Long = 0L) : Part

    /**
     * A line the user typed mid-answer.
     *
     * It is a part of this turn rather than a turn of its own: that is what keeps the
     * bubble whole while still drawing the line exactly where it landed, and it is why
     * the transcript does not read as though the model volunteered something.
     */
    data class Steer(val message: StoredMessage) : Part

    /**
     * 一条过程留痕：这一轮**成功了**，但中途重试过（「自动重试 1 次 · 服务器限流（429）」）。
     *
     * 画在过程区、和思考块/工具块并排，而不是画成红色回执卡 —— 卡片的意思是"这轮失败了"，
     * 而这一轮恰恰是成功的（用户：「这轮对话是成功的，只是中间触发重试了而已」）。
     */
    data class Trace(val note: String) : Part

    /**
     * 一次上下文压缩，画在它发生的位置（思考 → 工具 → 压缩 → 继续）。
     *
     * 与转录里那张 [CheckpointCard] 是同一件事的两种形态：那张按"被折叠的最后一条消息"定位，
     * 用来回看；这一块属于正在跑的这轮，位置就是它真正发生的那一刻。
     */
    data class Compress(val checkpoint: ContextCheckpoint) : Part
}

/**
 * What a reply cost.
 *
 * Context size is cumulative, so only the newest round knows the real prompt size and
 * only its cache count is meaningful; tokens and time add up across rounds instead.
 */
internal data class Usage(
    val prompt: Int = 0,
    val completion: Int = 0,
    val reasoning: Int = 0,
    val cached: Int = 0,
    val elapsedMs: Long = 0,
    /** Σ各轮 decodeMs（首字之后→结束），TPS 的官方分母。[elapsedMs] 是墙钟和，只给「总用时」显示。 */
    val decodeMs: Long = 0,
) {
    val known: Boolean get() = prompt > 0 || completion > 0

    /**
     * 官方口径 TPS：completion（含推理）÷ decodeMs。用 decodeMs 而非 elapsedMs —— 后者含 TTFT
     * 等待，会系统性低估。不可测（decodeMs≤0 或没有输出）返回 0.0。
     */
    val perSecond: Double
        get() = Tps.of(completion, decodeMs)

    companion object {
        fun of(rounds: List<StoredMessage>): Usage? {
            // **一遍遍历算完**：原来是 `none` + 4 次 `sumOf`，同一份列表扫了 5 遍。
            var known = false
            var completion = 0
            var reasoning = 0
            var elapsed = 0L
            var decode = 0L
            for (round in rounds) {
                if (round.usageKnown) known = true
                completion += round.completionTokens
                reasoning += round.reasoningTokens
                elapsed += round.elapsedMs
                // 官方多轮聚合＝ΣdecodeTokens/ΣdecodeMs：逐轮求 decode 段再相加，不是各轮速率取平均。
                decode += Tps.decodeMs(round.elapsedMs, round.firstTokenMs)
            }
            if (!known) return null
            val newest = rounds.last()
            return Usage(
                prompt = newest.promptTokens,
                completion = completion,
                reasoning = reasoning,
                cached = newest.cachedTokens,
                elapsedMs = elapsed,
                decodeMs = decode,
            )
        }
    }
}

/**
 * The `‹ 2/3 ›` switcher under a reply that has siblings, plus 重新回答.
 *
 * [index] is where [runs] the user is looking at, so the arrows are pure
 * navigation and never rewrite the transcript.
 */
internal data class Branch(
    val turnId: String,
    val runs: List<String>,
    val index: Int,
    /** Only the last question in the transcript is worth answering again. */
    val canRegenerate: Boolean,
)

/**
 * Whether a stored message has anything to draw at all.
 *
 * A turn that only asked for tools stores an assistant message with empty text. It
 * still belongs in the wire history, but drawing its bubble is what produced the
 * stray empty bubbles between tool chips.
 */
internal fun StoredMessage.isVisible(): Boolean = when (role) {
    Roles.USER -> text.isNotBlank() || allImages.isNotEmpty()
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
): List<ChatRow> {
    val turnOf = Transcript.turnOf(messages)
    val runs = Transcript.runsByTurn(messages, turnOf)
    // `turnOf` 是 O(条数) 的扫描，下面几处都要它 —— 算一次传下去（原来被重复算了 4 次）。
    // 复用上面已经算好的 `runs`：`chosenRuns` 内部会再算一遍 `runsByTurn`，所以把 `chosen` 交进去。
    val chosen = Transcript.chosenFrom(runs, activeRuns)
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
internal fun scrubberSlots(window: IntRange): List<Int> = window.toList().takeLast(SCRUBBER_VISIBLE_MAX)

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

internal fun todoRows(todos: List<TodoItem>, limit: Int = TODO_ROWS_SHOWN): List<TodoItem> {
    if (limit <= 0) return emptyList()
    val open = todos.filterNot { it.status == TODO_COMPLETED }
    return if (open.isEmpty()) todos.takeLast(limit) else open.take(limit)
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

internal fun ringReading(
    liveReported: Int,
    newestStored: Int,
    liveEstimate: Int,
    transcriptEstimate: Int = 0,
    /** meta 里缓存的「最近一条报了用量的消息」的 prompt（issue #41 真问题 1）。 */
    persistedContext: Int = 0,
): RingReading = when {
    // 实测口径优先：网关回报（含按会话保持的最近一次）→ **会话级权威值** → UI 现找。
    liveReported > 0 -> RingReading(liveReported, estimated = false)
    // **`persistedContext` 必须排在 `newestStored` 前面**（issue #49）：
    // 后者只查可见气泡，纯工具调用的无正文 assistant 轮不会进入气泡的 rounds；若这轮
    // 已上报 usage、后续却失败，就可能只找到更早的可见回答。普通无 usage 的失败本身
    // 不会让两者不同：两条链都会保留最近一次实测，不能把缺少新 usage 当作充分原因。
    // `meta.contextTokens` 由 `ChatStore.appendTail` 维护、语义是「最近一次报了用量的 prompt」，
    // 失败轮**不会把它变小**，而且跨会话切走再回来是 O(1) 读 meta。
    persistedContext > 0 -> RingReading(persistedContext, estimated = false)
    newestStored > 0 -> RingReading(newestStored, estimated = false)
    // 从没实测过（网关不报用量）才用粗估兜底，并标「约」。
    liveEstimate > 0 -> RingReading(liveEstimate, estimated = true)
    transcriptEstimate > 0 -> RingReading(transcriptEstimate, estimated = true)
    else -> RingReading(0, estimated = false)
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

/**
 * What the transcript area holds while the conversation to open is still being read.
 *
 * Deliberately almost nothing: the header is already drawn (it is identical in every state)
 * and the body carries one quiet line. Anything more — the empty state, an input field with
 * its hint — is a conversation the user is not in, which is what made a cold start look like
 * it had opened a new chat first and then jumped to the real one.
 */
@Composable
internal fun LoadingSkeleton() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "加载会话中…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

/**
 * Every model the user ticked used to live in a dropdown here; the 生成设置 sheet
 * owns the list now, so this menu is gone with its last caller.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EmptyHero(onPick: (String) -> Unit, assistant: Assistant? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The app's own mark on its gradient disc: the launcher asset is pure white for
        // the home screen, and white on a light canvas would vanish. A persona with an uploaded
        // picture shows that picture instead — this hero is where the user is about to talk to
        // *it*, and an empty chat was the one assistant surface still showing the app's feather.
        if (assistant?.avatarPath.isNullOrBlank()) {
            SuixingMark(size = 92.dp)
        } else {
            AssistantAvatar(assistant = assistant, size = 92.dp)
        }
        Spacer(Modifier.height(6.dp))
        Text("我们开始吧", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "说一句话，它就能读屏、点击、翻文件、跑命令。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        // A short screen with the keyboard up must not let the suggestions push the
        // hero off: the chip column scrolls inside 40% of the display height.
        val chipMax = with(LocalConfiguration.current) { screenHeightDp.dp } * 0.4f
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = chipMax)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SUGGESTIONS.forEach { suggestion ->
                    Surface(
                        onClick = { onPick(suggestion) },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Text(
                            text = suggestion,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "首次使用请先到「能力」页打开权限。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun MessageRow(
    row: ChatRow,
    assistantName: String,
    assistantFace: String = "",
    /** The persona's uploaded picture, if it has one; see [AssistantAvatar]. */
    assistantAvatarPath: String = "",
    showReasoning: Boolean,
    expandTools: Boolean,
    /** 外观 → 历史思考块默认展开; see [ReasoningBlock.expandReasoning]. */
    expandReasoning: Boolean = true,
    reasoningMaxHeight: Dp,
    /** 外观 → 思考块跟随最新; [ReasoningBlock.followReasoning]. */
    followReasoning: Boolean = true,
    bubbleMaxWidth: Dp,
    contextWindow: Int,
    /** What this row's whole run took; 0 falls back to the message's own round. */
    runElapsedMs: Long = 0L,
    /** 「提供方 / 模型」 of the round this row shows; blank when it cannot be resolved. */
    engineLabel: String = "",
    /** The screen's table of open thinking blocks; see [ReasoningExpansion]. */
    reasoningExpansion: ReasoningExpansion,
    onBranch: (String, String) -> Unit,
    onRegenerate: () -> Unit,
    onEdit: (StoredMessage) -> Unit,
) {
    when (row.message.role) {
        Roles.USER -> Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
        ) {
            UserBubble(message = row.message, onEdit = onEdit)
            MessageActions(message = row.message, onEdit = onEdit)
        }

        // 整张卡片包一层 SelectionContainer（用户：「气泡外层没包」）：只包 MarkdownText 时，
        // 从回答拖到思考块/工具块就断在边界上。**只对已落地的行**加 —— 流式行的守卫见
        // AssistantRow 里 Part.Text 分支的注释（实时气泡每帧重测会闪），那一处不动。
        // 按需挂载（#27/#42③）：平时不包（重卡组合减负），长按武装后才可选中。
        Roles.ASSISTANT -> SelectableWhenArmed {
            AssistantRow(
                // The setting hides thinking entirely; everything else keeps its place in
                // the sequence either way.
                //
                // **记住过滤结果**：`List<Part>` 是 unstable 参数，每次重组都 `filterNot` 出新 list
                // ⇒ 下游（每个 Markdown 块、思考列表、工具卡）永远跳不过。`row.parts` 本身跨重组
                // 是稳定的（`groupRows` 按 revision 记忆），所以这个 remember 会命中。
                parts = remember(IdentityKey(row.parts), showReasoning) {
                    if (showReasoning) row.parts else row.parts.filterNot { it is Part.Thinking }
                },
                streaming = false,
                name = assistantName,
                face = assistantFace,
                avatarPath = assistantAvatarPath,
                branch = row.branch,
                usage = row.usage,
                contextWindow = contextWindow,
                runElapsedMs = runElapsedMs,
                engineLabel = engineLabel,
                message = row.message,
                expandTools = expandTools,
                expandReasoning = expandReasoning,
                reasoningMaxHeight = reasoningMaxHeight,
                followReasoning = followReasoning,
                bubbleMaxWidth = bubbleMaxWidth,
                reasoningExpansion = reasoningExpansion,
                onBranch = onBranch,
                onRegenerate = onRegenerate,
                onEdit = onEdit,
            )
        }

        // 失败回执：run 最终失败时 VM 落盘的一条 system 消息。它不是模型的回答，所以不带头像、
        // 不带操作行 —— 一条全宽的红色小卡，像气泡一样留在会话里，重开还在。
        Roles.SYSTEM -> FailureReceiptRow(text = row.message.text)

        // A tool result with no bubble above it still renders as one, card only. It is
        // `row.parts` rather than the row's own message: once this card is the run's open
        // bubble, the calls that followed it — and a 插话 typed while they ran — live in the
        // parts, and rebuilding from the message alone would draw only the first of them.
        else -> SelectableWhenArmed {
            AssistantRow(
                parts = row.parts,
                streaming = false,
                message = row.message,
                face = assistantFace,
                avatarPath = assistantAvatarPath,
                bubbleMaxWidth = bubbleMaxWidth,
                reasoningExpansion = reasoningExpansion,
            )
        }
    }
}

/**
 * The user's own line, right-aligned.
 *
 * [onEdit] is still what 修改 goes through, but it is reached from the actions row under the
 * bubble — never from a long press: that gesture belongs to text selection, which is what a
 * user reaches for when they want to copy what they wrote.
 */
/** 气泡统一圆角：文件级常量，避免每次组合分配。 */
private val BubbleShape = RoundedCornerShape(24.dp)

@Composable
internal fun UserBubble(message: StoredMessage, onEdit: (StoredMessage) -> Unit) {
    Surface(
        modifier = Modifier
            .widthIn(max = 300.dp),
        shape = BubbleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            // 插话 was typed mid-answer, not as a new question; the badge is how the
            // transcript stays honest about that after the run is over.
            if (message.steer) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        text = "插话",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.height(5.dp))
            }
            message.allImages.forEach { ImageBlock(it) }
            if (message.text.isNotBlank()) {
                // The user's own words are plain text, never parsed as Markdown — someone typing
                // "1. do this" means the list item, not a heading. The style is still the
                // assistant body's, so the two bubbles agree on size and leading.
                // Selectable: long-press is how a phone copies text, and the bubble used to
                // spend that gesture on the edit dialog instead.
                SelectableWhenArmed {
                    Text(message.text, style = markdownBody())
                }
            }
            if (message.edited) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "已修改",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                )
            }
        }
    }
}

/** The small 复制 / 编辑 row under a bubble. */
@Composable
internal fun MessageActions(message: StoredMessage, onEdit: (StoredMessage) -> Unit) {
    // 复制走 [rememberCopyToClipboard]：写剪贴板 + Toast（用户：「复制后给个提示」）。
    val copy = rememberCopyToClipboard()
    Row(
        modifier = Modifier.padding(top = 2.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TinyAction(ImageVector.vectorResource(R.drawable.ic_copy), "复制") {
            copy(message.text)
        }
        TinyAction(ImageVector.vectorResource(R.drawable.ic_square_pen), "修改") { onEdit(message) }
    }
}

@Composable
internal fun TinyAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp),
        )
    }
}

/**
 * The reply's own numbers, in the card's bottom row: two buttons, one for the clock and one
 * for the cost.
 *
 * They are two questions — 「它花了多久」 and 「它用了多少」 — so they are two controls rather
 * than one card with everything in it; each opens its own detail. The numbers come off the
 * stored message, so a reopened conversation shows the same figures it showed when the answer
 * arrived, and a field the endpoint never reported is left out rather than filled with a zero.
 *
 * Both cards open upward: the row sits at the bottom of an answer, and a card opening downward
 * would cover the composer.
 */
@Composable
internal fun TurnMetrics(
    /** What the whole run took, not one round's call. */
    elapsedMs: Long,
    /** How long the endpoint took to say anything at all; 0 when it never streamed. */
    firstTokenMs: Long,
    promptTokens: Int,
    completionTokens: Int,
    cachedTokens: Int,
    /** 「提供方 / 模型」 that produced this round; blank leaves the line out. */
    engineLabel: String = "",
    /** 官方口径 TPS（ΣcompletionTokens / ΣdecodeMs），由调用方从 [Usage.perSecond] 算好传入。 */
    tps: Double = 0.0,
) {
    val total = promptTokens + completionTokens
    if (elapsedMs <= 0 && total <= 0) return
    val scheme = MaterialTheme.colorScheme
    var open by remember { mutableStateOf<MetricCard?>(null) }
    BackHandler(enabled = open != null) { open = null }
    // What the card last showed: the popup stays composed through its exit animation, and an
    // empty card sliding away is worse than the one the user just closed.
    var shown by remember { mutableStateOf(MetricCard.TIME) }
    val elapsed = remember(elapsedMs) {
        String.format(Locale.getDefault(), "%.1f秒", elapsedMs / 1000.0)
    }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (elapsedMs > 0) {
                MetricButton(
                    icon = ImageVector.vectorResource(R.drawable.ic_clock),
                    text = elapsed,
                    label = "用时详情",
                    onClick = { shown = MetricCard.TIME; open = MetricCard.TIME },
                )
            }
            if (total > 0) {
                MetricButton(
                    icon = ImageVector.vectorResource(R.drawable.ic_database),
                    text = remember(total) { "用量 ${shortTokens(total)} tok" },
                    label = "用量详情",
                    onClick = { shown = MetricCard.USAGE; open = MetricCard.USAGE },
                )
            }
        }
        Box(Modifier.align(Alignment.TopStart)) {
            // MenuCard owns the outside-tap layer; this caller handles BACK above.
            val density = LocalDensity.current
            val topBarCardProvider = remember(density) {
                val gap = with(density) { 8.dp.roundToPx() }
                val edge = with(density) { 8.dp.roundToPx() }
                object : androidx.compose.ui.window.PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: androidx.compose.ui.unit.IntRect,
                        windowSize: androidx.compose.ui.unit.IntSize,
                        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                        popupContentSize: androidx.compose.ui.unit.IntSize,
                    ): androidx.compose.ui.unit.IntOffset {
                        // 与触发按钮左沿对齐（原来贴屏幕右边，所以都从右侧出现）。
                        val x = anchorBounds.left.coerceIn(
                            edge,
                            (windowSize.width - edge - popupContentSize.width).coerceAtLeast(edge),
                        )
                        val y = anchorBounds.top - popupContentSize.height - gap
                        return androidx.compose.ui.unit.IntOffset(x, y)
                    }
                }
            }

            MenuCard(
                visible = open != null,
                onDismiss = { open = null },
                alignment = Alignment.TopStart,
                // **出现在按钮下方**（用户截图：原来盖在按钮上/上方）。8dp 间距，横向夹在屏幕内。
                // **provider 必须 remember**（优化清单 chat-P1）：原来这里在组合体里
                // `run { … }` 现造一个匿名对象，每次重组都是新实例 ⇒ `MenuCard` 参数恒变、
                // 弹卡开着时每次都重跑定位。
                popupPositionProvider = topBarCardProvider,
                offset = IntOffset.Zero,
                // The card's own Column already swallows the taps that land on it (see MenuCard).
                focusable = false,
            ) {
                // Intrinsic width: every row is as wide as the widest one, which is what puts the
                // numbers on one column while the popup still hugs its content. The cap is what
                // keeps a long 「提供方 / 模型」 line from pushing the card past the window.
                Column(
                    Modifier
                        .width(IntrinsicSize.Max)
                        .widthIn(
                            min = 208.dp,
                            max = popupCap(264.dp, LocalConfiguration.current.screenWidthDp.dp),
                        ),
                ) {
                when (shown) {
                    MetricCard.TIME -> {
                        if (elapsedMs > 0) RingDetail("总用时", elapsed)
                        if (firstTokenMs > 0) {
                            RingDetail(
                                "首 token 平均（TTFT）",
                                remember(firstTokenMs) {
                                    String.format(
                                        Locale.getDefault(),
                                        "%.1f秒",
                                        firstTokenMs / 1000.0,
                                    )
                                },
                            )
                        }
                        if (tps > 0) {
                            // 官方口径由调用方从 Usage.perSecond（Σcompletion ÷ ΣdecodeMs）算好传进来 ——
                            // 不在这里现算，因为 elapsedMs 是整轮 run 墙钟、completionTokens 是单轮，
                            // 口径错配会把 200 算成 40。显示用官方取整（≥10 整数、<10 一位小数）。
                            val tpsLabel = remember(tps) { "${Tps.format(tps)} tok/s" }
                            RingDetail("输出速度（TPS）", tpsLabel)
                        }
                    }

                    MetricCard.USAGE -> {
                        // Which endpoint answered, and which of its models: the two things every
                        // figure below is read against, and the only line here that is not a
                        // number. Left out when the id matches no endpoint on this device.
                        if (engineLabel.isNotBlank()) RingDetail("提供方 / 模型", engineLabel)
                        if (total > 0) RingDetail("本轮用量", "${shortTokens(total)} tok")
                        if (promptTokens > 0) {
                            RingDetail("输入", "${shortTokens(promptTokens)} tok")
                            RingDetail("缓存读取", "${shortTokens(cachedTokens)} tok")
                            RingDetail(
                                "未缓存输入",
                                "${shortTokens((promptTokens - cachedTokens).coerceAtLeast(0))} tok",
                            )
                            if (cachedTokens > 0) {
                                RingDetail(
                                    "缓存命中",
                                    remember(promptTokens, cachedTokens) {
                                        String.format(
                                            Locale.getDefault(),
                                            "%.2f%%",
                                            cachedTokens * 100.0 / promptTokens,
                                        )
                                    },
                                )
                            }
                        }
                        if (completionTokens > 0) {
                            RingDetail("输出", "${shortTokens(completionTokens)} tok")
                        }
                    }
                }
                }
            }
        }
    }
}

/** Which of the two detail cards [TurnMetrics] is showing. */
internal enum class MetricCard { TIME, USAGE }

/** One icon + value pair of [TurnMetrics], tappable: it opens that number's detail card. */
@Composable
internal fun MetricButton(icon: ImageVector, text: String, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(40.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            )
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            // 15dp, the same as the copy and edit icons beside it: at 14dp these two read as a
            // size smaller than the controls they sit next to.
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * 文本选区容器。
 *
 * **曾经做过「按需武装」（长按超时才挂 `SelectionContainer`）—— 已废弃并回退**：
 * 真机反馈长按复制响应变慢、偶尔长按不出菜单、选区出现后滑动卡。原因是挂载时机错了：
 * 武装发生在长按超时**之后**，而选区手势在超时那一刻已经开始，容器在同一手势中途
 * 出现/嵌套重挂会把选区取消掉。选区容器必须在**内容出现时就在**。
 *
 * 滚动成本的账单因此回到「常驻」—— 但这是用户明确选择的取舍：复制是高频手势，
 * 滚动优化不能拿它换。
 */
/**
 * 是否已经在某个 [SelectionContainer] 里了。
 *
 * 为什么需要：卡片是**两层**包的 —— 外层（本文件的角色分支）包整张卡，内层（ChatBlocks 的
 * 气泡 / 分段）又包一次。两层 `SelectionContainer` = 两个选区管理器，各持一个工具栏状态：
 * 点空白处关掉一个时，另一个会把自己的工具栏**再画一次**（真机：菜单消失后别处又冒出一个
 * 同样的「复制 / 全选」菜单再自动消失）。嵌套对跨块选择也没有帮助 —— 跨块靠的正是**外层**
 * 那一个容器。所以这里只允许最外层真正挂载。
 */
internal val LocalInSelectable = androidx.compose.runtime.compositionLocalOf { false }

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun SelectableWhenArmed(content: @Composable () -> Unit) {
    if (LocalInSelectable.current) {
        // 已经在一个容器里：只透传内容，不再嵌套第二个。
        content()
        return
    }
    val tracker = LocalSelectionFocusTracker.current
    val selectionOwner = remember { Any() }
    val selection = rememberSelectionState()
    val parentToolbar = LocalTextToolbar.current
    val toolbar = remember(selection, parentToolbar) {
        SelectionTextToolbar(parentToolbar) { selection.selectedTexts.any { it.isNotEmpty() } }
    }
    androidx.compose.runtime.DisposableEffect(tracker, selectionOwner) {
        tracker?.register(selectionOwner,
            selected = { selection.selectedTexts.any { it.isNotEmpty() } },
            clear = selection::clear,
        )
        onDispose {
            selection.clear()
            tracker?.remove(selectionOwner)
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalInSelectable provides true,
        LocalTextToolbar provides toolbar,
    ) {
        SelectionContainer(
            state = selection,
            modifier = Modifier
                .onFocusChanged { tracker?.update(selectionOwner, it.hasFocus) }
                .filterTextContextMenuComponents {
                    // A released text owner must not reopen a select-all-only toolbar.
                    // New long-press selection repopulates selectedTexts before showing it.
                    selection.selectedTexts.any { it.isNotEmpty() }
                },
        ) { content() }
    }
}

@Composable
internal fun AssistantRow(
    /** The reply's pieces in arrival order: thinking, tools, prose, thinking again… */
    parts: List<Part>,
    streaming: Boolean,
    /** The assistant's name, for the card's own header line. */
    name: String = "随行",
    /** The persona's emoji face; blank falls back to the brand feather. */
    face: String = "",
    /** The picture the user uploaded for this persona; blank falls back to [face]. */
    avatarPath: String = "",
    /** The tool this reply is waiting on right now, so its card can say so live. */
    runningTool: String? = null,
    branch: Branch? = null,
    usage: Usage? = null,
    /** The engine's live speed, present only while this bubble is the one streaming. */
    liveStats: StreamStats? = null,
    /** The model's window, for the context readout; 0 means it was never set. */
    contextWindow: Int = 0,
    /** What the whole run took; 0 falls back to the message's own round. */
    runElapsedMs: Long = 0L,
    /** Shared with the status line, including the wait before the first response part. */
    runningElapsed: androidx.compose.runtime.State<Long>? = null,
    /** 「提供方 / 模型」 for the usage card; blank leaves that line out. */
    engineLabel: String = "",
    message: StoredMessage? = null,
    runId: String = message?.runId.orEmpty(),
    expandTools: Boolean = false,
    /** 外观 → 历史思考块默认展开; see [ReasoningBlock.expandReasoning]. */
    expandReasoning: Boolean = true,
    reasoningMaxHeight: Dp = 180.dp,
    /** 外观 → 思考块跟随最新; [ReasoningBlock.followReasoning]. */
    followReasoning: Boolean = true,
    /** 外观 → 气泡宽度: the card's cap. [Dp.Infinity] leaves it at the row's own width. */
    bubbleMaxWidth: Dp = Dp.Infinity,
    /** The screen's table of open thinking blocks; see [ReasoningExpansion]. */
    reasoningExpansion: ReasoningExpansion,
    onBranch: (String, String) -> Unit = { _, _ -> },
    onRegenerate: () -> Unit = {},
    onEdit: (StoredMessage) -> Unit = {},
) {
    val processRun = runId.ifBlank { message?.id ?: "unkeyed" }
    val foldSlots = remember(IdentityKey(parts), processRun) { processFoldSlots(parts, processRun) }
    Surface(
        // 外观 → 气泡宽度 first, then fillMaxWidth: the cap has to be the outer
        // constraint, or the inner fill would win and the card could never narrow.
        modifier = Modifier
            .widthIn(max = bubbleMaxWidth)
            .fillMaxWidth(),
        shape = BubbleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        // A hairline instead of a shadow: elevation 0 keeps a long transcript flat, and
        // the outline is what separates a near-white card from a white canvas in light
        // mode, where the two surfaces are 8% apart.
        // 每帧新建 BorderStroke ⇒ remember 住（颜色变才重建；颜色在组合期先读）。
        border = run {
            val outline = MaterialTheme.colorScheme.outlineVariant
            remember(outline) { BorderStroke(1.dp, outline) }
        },
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 10.dp)) {
            // The avatar sits in the card's header rather than beside it: the reply is one
            // object, and an avatar floating outside the card made it two.
            Row(verticalAlignment = Alignment.CenterVertically) {
                // One composable for all three faces — the uploaded picture, the persona's
                // emoji, the brand feather — so this card, the assistant sheet and the editor
                // cannot disagree about what a persona looks like.
                AssistantAvatar(
                    face = face,
                    avatarPath = avatarPath,
                    size = 24.dp,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = name,
                    // 与大总结条（ProcessCard）标题同字号：都用 labelMedium（ChatBlocks.kt:292），
                    // 不再走 metaStyle() 的 12/13 缩放 —— 用户要求两者对齐。
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The same fixed-height process header exists on both sides of hand-over.
            // Completion updates numbers without inserting a new row above the prose.
            Spacer(Modifier.height(6.dp))
            val settled = usage?.takeIf { it.known }
            if (streaming || settled != null || parts.isNotEmpty()) {
                TurnSummary(
                    parts = parts,
                    usage = usage,
                    live = null,
                    streaming = streaming,
                    runningElapsed = runningElapsed,
                    runElapsedMs = runElapsedMs,
                    runningTool = runningTool,
                    runId = processRun,
                    foldSlots = foldSlots,
                    contextWindow = contextWindow,
                    // The run's note, when there was one: it lives on the stats for as long as
                    // the run does, which is exactly the window in which this card is drawn
                    // for a live turn. A settled turn from an earlier launch has no note to
                    // show — the compression record is the separator card's job.
                    note = liveStats?.note.orEmpty(),
                    reasoningMaxHeight = reasoningMaxHeight,
                    followReasoning = followReasoning,
                    // Untouched settled thinks follow the appearance default.
                    expandReasoning = expandReasoning,
                    // 工具块与运行时同一套默认展开设置（用户：「展开时原样展开」）。
                    expandTools = expandTools,
                    // The run identity, not this message id, owns each block's state.
                    messageId = message?.id.orEmpty(),
                    reasoningExpansion = reasoningExpansion,
                    onEdit = onEdit,
                )
                // Keep the seam outside the animation constant. Toggling it
                // immediately made prose move backwards before the height changed.
                Spacer(Modifier.height(6.dp))
            }
            // The last text slot follows the current snapshot; versioned live parts must
            // never be memoized across actual text changes.
            val tailStart = remember(IdentityKey(parts)) { processTailStart(parts) }
            // key(index): without it every part's remembered state was allocated by
            // position, so a new round appearing at the front moved one block's switch onto
            // its neighbour.
            parts.forEachIndexed { index, part ->
                // Guarded before key(index), not inside it: a non-local return out of the
                // key group leaves that group unclosed, and Compose aborts the composition
                // with "Start/end imbalance". Same conditions, evaluated outside the group.
                if (index < tailStart) return@forEachIndexed
                // Each part belongs to exactly one side of the process/tail boundary.
                key(index) {
                    val last = index == parts.lastIndex
                    when (part) {
                        is Part.Thinking -> {
                            // Only the growing block owns the cursor. Earlier thoughts
                            // remain open by default until their whole run settles.
                            val thinkingLive = streaming && last
                            ReasoningBlock(
                                // **直接渲染，不做打字机**（与正文、与 DSH 的流式一致）：
                                // 改文本式的打字机每拍都要重解析 Markdown，而且一旦起点被算成
                                // 全文就完全不逐字（表现随版本漂移）。光标直接贴真实末尾。
                                text = part.text,
                                // A live block is the one still growing; a settled round's
                                // thinking is complete even while the run continues.
                                streaming = thinkingLive,
                                runStreaming = streaming,
                                // A live block keeps its slot for the whole run, and the
                                // stored copy of the same run answers to the same key.
                                blockId = foldSlots[index]!!,
                                expansion = reasoningExpansion,
                                reasoningTokens = part.tokens,
                                elapsedMs = part.elapsedMs,
                                // Whether a block nobody has touched is open is the appearance
                                // setting's business, and the block owns that rule: live ones are
                                // always open, settled ones follow 历史思考块默认展开. A tap on
                                // the header is the only thing that closes one, and the expansion
                                // table remembers it across the hand-over to the stored message.
                                expandReasoning = expandReasoning,
                                maxHeight = reasoningMaxHeight,
                                followReasoning = followReasoning,
                            )
                            if (!last) Spacer(Modifier.height(6.dp))
                        }

                        is Part.Tools -> {
                            // LivePart and groupRows already group adjacent calls. Drawing
                            // each part once also preserves the source order at hand-over.
                            ToolBundle(
                                tools = part.calls,
                                expansion = reasoningExpansion,
                                blockId = foldSlots[index]!!,
                                // Only the newest block can be the one waiting on a tool.
                                runningTool = runningTool.takeIf { streaming && last },
                                startExpanded = expandTools,
                            )
                            if (!last) Spacer(Modifier.height(6.dp))
                        }

                        is Part.Text -> {
                            AssistantProse(part, streaming, readingKey = "$processRun:text:$index",
                                viewport = reasoningExpansion.viewport("$processRun:text:$index"))
                            if (!last) Spacer(Modifier.height(6.dp))
                        }

                        is Part.Steer -> {
                            // Right-aligned, like any user line, but inside the reply: the
                            // indentation is what says "I said this while it was working".
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                UserBubble(message = part.message, onEdit = onEdit)
                            }
                            if (!last) Spacer(Modifier.height(6.dp))
                        }

                        // 重试留痕：与思考块/工具块同一套栅格（36dp 行高 + 24dp 图标槽 + 8dp +
                        // labelMedium），所以图标与标题左边线和它们对齐；间距只用 part 之间
                        // 那 6dp，不再自己加边距、也不做位移。
                        is Part.Trace -> {
                            TraceBlock(part.note)
                            if (!last) Spacer(Modifier.height(6.dp))
                        }

                        // 压缩块：位置就是它发生的地方。
                        is Part.Compress -> {
                            CompressionBlock(part.checkpoint, reasoningExpansion,
                                foldSlots[index]!!)
                            if (!last) Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
            if (message != null && !streaming) {
                // The last block carries no trailing spacer of its own, so the air
                // above this row is its own 10dp.
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ActionBar(
                        branch = branch,
                        onBranch = onBranch,
                        onRegenerate = onRegenerate,
                    )
                    Spacer(Modifier.weight(1f))
                    // The round's clock and cost, right-aligned with the card's own copy and
                    // edit controls: they are all "about this answer", and the row reads as one
                    // group when they share the right edge.
                    TurnMetrics(
                        // The run's own clock when we have it: one round's call is not what
                        // the status line counted while the answer was arriving.
                        elapsedMs = if (runElapsedMs > 0) runElapsedMs else message.elapsedMs,
                        firstTokenMs = message.firstTokenMs,
                        promptTokens = message.promptTokens,
                        completionTokens = message.completionTokens,
                        cachedTokens = message.cachedTokens,
                        engineLabel = engineLabel,
                        // 官方口径 TPS：usage 是这个 run 所有轮的聚合（Σcompletion、ΣdecodeMs），
                        // perSecond 已按 decodeMs 算好。null（端点没报 usage）就不画那一行。
                        tps = usage?.perSecond ?: 0.0,
                    )
                    MessageActions(message = message, onEdit = onEdit)
                }
            }
        }
    }
}

/** Keep typewriter state reads inside the prose rather than the whole reply content. */
@Composable
internal fun AssistantProse(
    part: Part.Text,
    streaming: Boolean,
    typewriter: Boolean = true,
    readingKey: String = "",
    viewport: ReasoningViewport? = null,
) {
    // History is selectable immediately. A newly mounted live block waits one frame,
    // and selection still waits for streaming and the remaining typed text to finish.
    var selectable by remember { mutableStateOf(!streaming) }
    if (!selectable) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            selectable = true
        }
    }
    val liveText = part.bornAtMs > 0L
    val shown = rememberTypedText(
        text = part.text,
        enabled = typewriter && (streaming || liveText),
        startAtZero = typewriter && liveText,
        retained = viewport?.prose,
    )
    androidx.compose.runtime.CompositionLocalProvider(
        LocalReadingTextKey provides readingKey, LocalProseViewport provides viewport) {
    if (streaming || !selectable || shown.length < part.text.length) {
        MarkdownText(
            text = shown,
            // Generation can stop before the typewriter drains. Keep these changing
            // prefixes on the incremental parser and out of the settled document cache.
            settled = !streaming && shown.length == part.text.length,
            cursor = false,
        )
    } else {
        SelectableWhenArmed { MarkdownText(text = part.text) }
    }
    }
}

/**
 * The row under a reply that says "this answer is one of several". Arrows only
 * appear once a second answer exists; re-answering is offered on the newest
 * question alone, because replacing an older answer would orphan everything the
 * conversation built on top of it.
 */
@Composable
internal fun ActionBar(
    branch: Branch?,
    onBranch: (String, String) -> Unit,
    onRegenerate: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.padding(start = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (branch != null && branch.runs.size > 1) {
            val atStart = branch.index <= 0
            val atEnd = branch.index >= branch.runs.lastIndex
            BranchArrow(
                icon = ImageVector.vectorResource(R.drawable.ic_chevron_left),
                label = "上一个回答",
                enabled = !atStart,
                onClick = { onBranch(branch.turnId, branch.runs[branch.index - 1]) },
            )
            Text(
                text = "${branch.index + 1}/${branch.runs.size}",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
            BranchArrow(
                icon = ImageVector.vectorResource(R.drawable.ic_chevron_right),
                label = "下一个回答",
                enabled = !atEnd,
                onClick = { onBranch(branch.turnId, branch.runs[branch.index + 1]) },
            )
        }
        if (branch?.canRegenerate == true) {
            BranchArrow(
                icon = ImageVector.vectorResource(R.drawable.ic_rotate_cw),
                label = "重新回答",
                enabled = true,
                onClick = onRegenerate,
            )
        }
    }
}
