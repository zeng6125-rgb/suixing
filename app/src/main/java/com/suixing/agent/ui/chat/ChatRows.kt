package com.suixing.agent.ui.chat

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Stable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.ripple
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suixing.agent.R
import com.suixing.agent.agent.StreamStats
import com.suixing.agent.agent.estimateTokens
import com.suixing.agent.core.Core
import com.suixing.agent.data.AppSettings
import com.suixing.agent.voice.Dictation
import com.suixing.agent.voice.DictationRecorder
import com.suixing.agent.data.Assistant
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.ProviderConfig
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.Tps
import com.suixing.agent.data.TODO_COMPLETED
import com.suixing.agent.data.TODO_IN_PROGRESS
import com.suixing.agent.data.TodoItem
import com.suixing.agent.data.Transcript
import com.suixing.agent.data.forAssistant
import com.suixing.agent.data.groupModels
import com.suixing.agent.data.label
import com.suixing.agent.data.modelOffers
import com.suixing.agent.data.choiceFor
import com.suixing.agent.ui.assistant.AssistantAvatar
import com.suixing.agent.ui.assistant.AssistantEditor
import com.suixing.agent.ui.assistant.AssistantSheet
import com.suixing.agent.ui.components.CircleActionButton
import com.suixing.agent.ui.components.DrawerButton
import com.suixing.agent.ui.components.FlatIconButton
import com.suixing.agent.ui.components.FlatPainterButton
import com.suixing.agent.ui.components.FullScreenOverlay
import com.suixing.agent.ui.components.LocalPathClick
import com.suixing.agent.ui.components.LocalHeldImePx
import com.suixing.agent.ui.components.LocalSetSelectionActive
import com.suixing.agent.ui.components.LocalOpenSidebar
import com.suixing.agent.ui.components.MarkdownText
import com.suixing.agent.ui.components.PressPill
import com.suixing.agent.ui.components.REASONING_LEVELS
import com.suixing.agent.ui.components.SuixingMark
import com.suixing.agent.ui.components.markdownBody
import com.suixing.agent.ui.components.suixingClickable
import com.suixing.agent.ui.theme.LocalDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

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

    /** The words the model wrote. */
    data class Text(val text: String) : Part

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
            if (rounds.none { it.usageKnown }) return null
            val newest = rounds.last()
            return Usage(
                prompt = newest.promptTokens,
                completion = rounds.sumOf { it.completionTokens },
                reasoning = rounds.sumOf { it.reasoningTokens },
                cached = newest.cachedTokens,
                elapsedMs = rounds.sumOf { it.elapsedMs },
                // 官方多轮聚合＝ΣdecodeTokens/ΣdecodeMs（client.js:8331-8333）：逐轮求 decode 段再相加。
                decodeMs = rounds.sumOf { Tps.decodeMs(it.elapsedMs, it.firstTokenMs) },
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
    val chosen = Transcript.chosenRuns(messages, activeRuns)
    val shown = Transcript.active(messages, activeRuns)
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
                    message.reasoning?.trim()?.takeIf { it.isNotEmpty() }?.let {
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
    val starts = messages.indices.filter {
        messages[it].role == Roles.USER && !messages[it].steer
    }
    if (starts.isEmpty()) return TurnPage(from = 0, turns = 0, drawn = 0)
    val drawn = want.coerceIn(1, starts.size)
    val first = starts.size - drawn
    // A conversation that opens with something other than a question keeps it, as long as its
    // first turn is drawn; from the second turn on, the page starts at the question itself.
    return TurnPage(
        from = if (first == 0) 0 else starts[first],
        turns = starts.size,
        drawn = drawn,
    )
}

internal fun scrubberWindow(turns: Int, current: Int, unlocked: Int): IntRange {
    if (turns <= 0) return IntRange.EMPTY
    val last = turns - 1
    val reading = (last - SCRUBBER_VISIBLE + 1).coerceAtLeast(0)
        .coerceAtMost((current - 1).coerceAtLeast(0))
    val start = (reading - unlocked.coerceAtLeast(0)).coerceAtLeast(0)
    return start until (start + SCRUBBER_VISIBLE).coerceAtMost(turns)
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

/** How many turns the scrubber draws before the window slides up to the newest ones. */
internal const val SCRUBBER_VISIBLE = 6

data class Attachment(
    val base64: String,
    val label: String,
    val preview: ImageBitmap? = null,
    /** 文件附件的落盘路径（图片为 null）。发送时拼进消息正文 —— 见 [withAttachment]。 */
    val filePath: String? = null,
)

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
): RingReading = when {
    // 实测口径优先：网关回报（含按会话保持的最近一次）→ 落盘回报。
    liveReported > 0 -> RingReading(liveReported, estimated = false)
    newestStored > 0 -> RingReading(newestStored, estimated = false)
    // 从没实测过（网关不报用量）才用粗估兜底，并标「约」。
    liveEstimate > 0 -> RingReading(liveEstimate, estimated = true)
    transcriptEstimate > 0 -> RingReading(transcriptEstimate, estimated = true)
    else -> RingReading(0, estimated = false)
}

internal const val TIME_LABEL_GAP_MS = 10 * 60 * 1000L

internal fun needsTimeLabel(previous: StoredMessage?, current: StoredMessage): Boolean {
    if (previous == null) return true
    return current.timestamp - previous.timestamp > TIME_LABEL_GAP_MS
}

