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
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.runtime.mutableFloatStateOf

/**
 * Which line a finger at [y] is on. Rows are found by their centre rather than by their box,
 * so the air between two lines belongs to whichever is nearer — that is what allows the strip
 * to be packed tighter than a fingertip is wide.
 */
internal fun scrubberRowFor(y: Float, rowPx: Float, pitchPx: Float, rows: Int): Int {
    if (rows <= 1) return 0
    return ((y - rowPx / 2f) / pitchPx).roundToInt().coerceIn(0, rows - 1)
}

/**
 * The turns the strip draws: [SCRUBBER_VISIBLE] lines ending at the newest turn, slid up so the
 * turn being read is on them, and — while a finger has pulled past the top line — slid up
 * [unlocked] turns further, so the turns above the window can be reached.
 *
 * The window is a fixed number of lines that moves rather than one that grows. Growing it would
 * change the rail's height mid-drag, and the rail is centred: every line would shift down by
 * half of what was added, under a finger that is holding one of them. Sliding keeps every line
 * where it is and changes only which turn each one names, so the line under the finger is always
 * the line the label is on.
 */

/** The rail's width, and so every row's hit width: the longest line the rail draws. */
internal val SCRUBBER_RAIL = 24.dp

/**
 * One row's box. Shorter than the old 18dp because the finger is matched to the nearest line
 * instead of having to land inside a box — the rows no longer need to be big enough to hit,
 * only far enough apart to tell apart.
 */
internal val SCRUBBER_ROW = 14.dp

/** Air between rows: half the old 6dp, which was most of what made the strip read as sparse. */
internal val SCRUBBER_GAP = 3.dp

/** How far the finger travels before a press turns into a drag. Aether's own threshold. */
internal val SCRUBBER_DRAG_THRESHOLD = 18.dp

/** Drifting this far left of the rail drops the jump. Aether's own exit tolerance. */
internal val SCRUBBER_EXIT_TOLERANCE = 28.dp

/**
 * How long a still finger waits before the label appears. Shorter than the platform's long
 * press because a scrubber has to answer at once, but long enough that a plain tap on a line
 * never flashes a label on its way to jumping.
 */
internal const val SCRUBBER_LONG_PRESS_MS = 220L

/** The label's fade in, and its fade out once the finger lifts. Aether's own pair. */
internal const val SCRUBBER_LABEL_IN_MS = 150
internal const val SCRUBBER_LABEL_OUT_MS = 110

/**
 * How long the label takes to glide to the line the finger has reached. Deliberately the same
 * 110ms as the line's own growth above, so the two land together and the label never outruns
 * the line it names.
 */
internal const val SCRUBBER_SELECT_MS = 110

/** Air between the rail and the label. */
internal val SCRUBBER_LABEL_GAP = 6.dp

/** The label's longest line, so it never covers the bubble it floats over. */
internal val SCRUBBER_LABEL_MAX = 180.dp

/**
 * The label bubble's own cap: [SCRUBBER_LABEL_MAX], or what is left of the window once the rail it
 * sits beside, its gap and the two margins are taken out.
 *
 * The bubble is drawn to the rail's left and the rail hugs the right edge, so a flat 180dp ran off
 * the left of a narrow window — the split-screen case, where the window is not the display.
 */
internal fun scrubberLabelMax(windowWidth: Dp): Dp = minOf(
    SCRUBBER_LABEL_MAX,
    windowWidth - POPUP_EDGE * 2 - SCRUBBER_RAIL - SCRUBBER_LABEL_GAP,
)

/** How far a line grows once its label is out, and while it is merely touched. */
internal const val SCRUBBER_PRESS_SCALE = 1.4f
internal const val SCRUBBER_TOUCH_SCALE = 1.12f

/** Aether's motion curve, borrowed for the row scale and the label's entry and exit. */
internal val SCRUBBER_EASING = CubicBezierEasing(0.22f, 0.84f, 0.18f, 1f)

/** How far a queued line is inset from the pill on each side, so it reads as narrower. */
internal val STEER_QUEUE_INSET = 12.dp

/**
 * Where the context went.
 *
 * The three shares come from the engine's own estimate for the run in flight; a stored
 * reply has no such numbers, so for it the dialog shows only the header — better a short
 * dialog than three invented rows.
 */
@Composable
internal fun ContextBreakdownDialog(
    tokens: Int,
    window: Int,
    live: StreamStats?,
    onDismiss: () -> Unit,
) {
    val percent = if (window > 0) tokens * 100 / window else 0
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
        title = { Text(if (window > 0) "上下文已用 $percent%" else "上下文用量") },
        text = {
            Column {
                Text(
                    text = if (window > 0) {
                        "~${tokens / 1000}k / ${window / 1000}k"
                    } else {
                        "~${tokens / 1000}k"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (window > 0) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { (percent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                live?.let { stats ->
                    val parts = listOf(
                        Triple("系统提示词", stats.breakSystem, MaterialTheme.colorScheme.primary),
                        Triple("工具定义", stats.breakTools, MaterialTheme.colorScheme.secondary),
                        Triple("对话消息", stats.breakHistory, MaterialTheme.colorScheme.tertiary),
                    )
                    // Shares are measured against their own sum, not against the endpoint's
                    // prompt count: the engine's estimate covers messages and tools, and
                    // forcing it onto that other total would invent a remainder.
                    val total = parts.sumOf { it.second }
                    Spacer(Modifier.height(14.dp))
                    parts.forEach { (label, value, tone) ->
                        ContextBreakdownRow(label, value, total, tone)
                    }
                }
            }
        },
    )
}

@Composable
internal fun ContextBreakdownRow(label: String, value: Int, total: Int, tone: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(tone),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "~${value / 1000}k",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.width(10.dp))
        // One bar per row, sized by its share of the three.
        Box(
            Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            val fraction = if (total > 0) value.toFloat() / total else 0f
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(tone),
            )
        }
    }
}

@Composable
internal fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 520, delayMillis = index * 150),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                Modifier
                    .padding(end = 5.dp)
                    .size(7.dp)
                    .alpha(alpha)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
internal fun BlinkingCursor() {
    // **不要用 `rememberInfiniteTransition` + tween**：那会让界面按**屏幕刷新率**持续重绘。
    // 真机实测（0.8.382，一轮在跑、手指不动）：15 秒 3194 帧 ≈ 213 fps、单核 42%
    // —— 长回答/长工具调用时就是持续发热与耗电，而同屏还有悬浮窗在同时动画。
    // 闪烁只需要"两档"，用 480ms 的步进替代：观感一样，重绘从 ~120 次/秒降到 ~2 次/秒。
    val alpha = remember { mutableFloatStateOf(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(480)
            alpha.floatValue = if (alpha.floatValue > 0.5f) 0.15f else 1f
        }
    }
    Text(
        text = "▍",
        modifier = Modifier.alpha(alpha.floatValue),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * The live run's progress line: the engine's own sentence, plus how long the run has been
 * going. The clock lives in here — one tick a second re-composes this caption and nothing
 * else, and the elapsed time is read off the wall clock on each tick, so a line that is
 * re-created (a switch away and back) still shows the true time rather than restarting.
 */
/**
 * 把内容上移 [amount] 吃进列表间距（spacedBy 的 16dp）里，布局高度同步减掉这一段 ——
 * 「无气泡时状态行的上间隙」要从 16 压到 10 的那 6dp 就从这里来。
 *
 * 不用负 padding：`Modifier.padding` 收负值不可靠（supervisor 指出会抛异常）。这里只依赖两件
 * 官方且极常见的行为：子节点可以放在负坐标（`Modifier.offset` 同源的 place），以及布局尺寸非负
 * （[amount] 与高度差都 coerce 到 ≥0，不会量出负尺寸）。
 */
internal fun Modifier.liftIntoGap(amount: Dp): Modifier = layout { measurable, constraints ->
    val lift = amount.roundToPx().coerceAtLeast(0)
    val placeable = measurable.measure(constraints)
    layout(placeable.width, (placeable.height - lift).coerceAtLeast(0)) {
        placeable.place(0, -lift)
    }
}

@Composable
internal fun RunStatusLine(
    text: String,
    /** When the run began, or 0 while the effect that records it has yet to run. */
    startedAt: Long,
    /** False for the compression note: that is a fact, not a progress bar. */
    timed: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(startedAt) {
        if (startedAt <= 0) return@LaunchedEffect
        while (true) {
            delay(1000)
            tick++
        }
    }
    val caption = remember(tick, startedAt, text, timed) {
        if (!timed || startedAt <= 0) text
        else "$text，用时 ${formatRunElapsed(System.currentTimeMillis() - startedAt)}"
    }
    Text(
        text = caption,
        style = statusLineStyle(),
        color = color,
        // 不截断（用户：「错误的提示要显示全」）。重试/失败那行是这条状态线写的（VM 的
        // Retrying/Failed 分支），一行省略号会把「服务器限流（429），用…」的后半句吃掉，
        // 而那一半正是用户要读的原因。允许换行；它本来就短，只有失败原因会长。
        modifier = modifier,
    )
}

@Composable
internal fun TimeLabel(timestamp: Long) {
    Text(
        text = formatTime(timestamp),
        modifier = Modifier.fillMaxWidth(),
        // The label's distance from its bubble is decided by the caller (3dp inside the
        // item's Column); this only tightens the text's own line box, so the glyphs sit
        // closer to the top of their slot than labelSmall's 16sp box would put them.
        style = MaterialTheme.typography.labelSmall.copy(lineHeight = 12.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
internal fun Composer(
    input: String,
    onInput: (String) -> Unit,
    /**
     * Takes a queued line back out of the queue and into the composer — its text **and its
     * picture**. A callback of its own rather than `onInput(text)`: the attachment lives in the
     * view model's cache, not in this composable, so only the caller can put both halves back.
     */
    onRestoreSteer: (PendingSteer) -> Unit,
    running: Boolean,
    /** The live run's stats; the context ring reads its note. */
    stats: StreamStats?,
    settings: AppSettings,
    /** The persona this conversation runs with, not the app-wide active one. */
    assistantId: String,
    /** The window the context ring divides by; 0 hides the ring (no denominator). */
    contextWindow: Int,
    /** Tokens the newest round filled of that window, and how many of them were cached. */
    contextUsed: Int,
    /** True when [contextUsed] is this app's own estimate rather than the endpoint's count. */
    contextEstimated: Boolean = false,
    /**
     * 圆环面板三行（系统提示词 / 工具定义 / 对话消息）的当前窗口读数，不依赖 run：空闲时由
     * [onContextPanelOpen] 触发上层计算后从这里进来。
     */
    contextBreak: Triple<Int, Int, Int>? = null,
    /** 面板打开时回调：上层按当前上下文算一次三行。 */
    onContextPanelOpen: () -> Unit = {},
    /** Everything this conversation has ever spent, for the ring's detail card. */
    sessionTokens: Int,
    /** The text-based floor for the ring's cumulative row, when nothing was ever priced. */
    sessionEstimate: Int = 0,
    /** That spend in the endpoint's own three parts, for the same card. */
    sessionSplit: SessionSplit,
    /** How many questions that tally was built from. */
    sessionTurns: Int,
    /** The session's own output speed, cache hit rate and wall clock, for the ring's card. */
    sessionPerSecond: Double,
    sessionCachePercent: Double,
    sessionElapsedMs: Long,
    /** This conversation's own model pick, or null while it follows persona/defaults. */
    conversationProviderId: String?,
    conversationModel: String?,
    /** This conversation's own thinking level, or null while it follows the defaults. */
    conversationReasoningEffort: String?,
    onPickModel: (ProviderConfig, String) -> Unit,
    /** Asks the endpoint once what context windows its models advertise; see prefetchWindows. */
    onPrefetchWindows: () -> Unit,
    /** Writes a thinking level onto this conversation alone. */
    onChooseReasoning: (String) -> Unit,
    attachments: List<Attachment>,
    /** 删附件列里第几个；多附件后「清空」就是逐个删到空。 */
    onRemoveAttachment: (Int) -> Unit,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickFile: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    /** Queues a line for the running model; false means it was dropped. */
    onSteer: (String, List<String>) -> Boolean,
    /** Asks the live run to fold the conversation at its next turn boundary. */
    onRequestCompress: () -> Boolean,
    /**
     * **没有 run 时**直接压一次（按已落盘历史）。菜单项在 [onRequestCompress] 返回 false
     * 时改走这条 —— 以前这条根本没接，于是"点了没反应"（用户报的正是这个）。
     */
    onCompressNow: () -> Unit,
    /** Lines queued for the run that is going, oldest first. */
    pendingSteers: List<PendingSteer>,
    /**
     * Lines typed in 排队 mode, waiting to be the next turn. Drawn in the same stack as
     * [pendingSteers] and through the same row — same three actions — but the row is told these are
     * next turns, so it says 「下一轮发送」 instead of calling them interruptions.
     */
    pendingTurns: List<PendingTurn>,
    /** 「立即插入」 on a waiting turn: hand it to the run in flight as a steer. */
    onPromoteTurn: (String) -> Unit,
    /** Drops one waiting turn. */
    onRetractTurn: (String) -> Unit,
    /**
     * The agent's own task board, as its last `todo_write` left it. Drawn above the queue, and
     * only while this run is going: the board is the run's plan, not a second transcript.
     */
    todos: List<TodoItem> = emptyList(),
    /** 点「任务 2/5」那一行：完整清单在 ChatScreen 里画（这个 composable 拿不到那个状态）。 */
    onOpenTodos: () -> Unit,
    /** Sends a queued line now, cutting the round in flight. */
    onPromoteSteer: (String) -> Unit,
    /** Drops a queued line before the model reads it; 「修改」 uses it too. */
    onRetractSteer: (String) -> Unit,
    onFocused: () -> Unit,
    onMic: () -> Unit,
    /** 设置里的「语音输入」：关着的时候这一排里连麦克风都不画。 */
    voiceInput: Boolean,
    /** 工具审批 mode in force, and the picker that changes it; see [ApprovalMode]. */
    approvalMode: ApprovalMode,
    onPickApproval: (ApprovalMode) -> Unit,
    /** The empty provider list's way out: straight into the model settings page. */
    onOpenSettings: () -> Unit,
    /** Scrolls the transcript to its newest line; the stack's own corner button. */
    jumpToLatest: () -> Unit,
    /** Shown while the transcript is scrolled away from its end. */
    showJump: Boolean,
    /**
     * The stack's own measured height, so the list can pad by the room it truly takes out of the
     * transcript (the caller adds the strip under it and its own 12dp of air).
     */
    onHeightChanged: (Dp) -> Unit,
    /** Called once the stack has been placed for this frame: the caller's follow pin rides it. */
    onPlaced: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // 发送后键盘退避（用户：「抬起输入法发送时，对话框也会挡住」）：不收键盘的话 composer 悬在
    // 键盘上方、把列表尾的状态行盖在后面。发送＝这句话说完了，收键盘让整条栈落回去；「停止」
    // 不收（那一下没有发送任何文字，用户的手还在输入上）。
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val dismissKeyboard = {
        keyboardController?.hide()
        focusManager.clearFocus()
    }
    val density = LocalDensity.current
    // The window's own width, which is what a popup has to fit inside. `screenWidthDp` is the
    // window's, not the display's, so a card that fits here also fits a split-screen window.
    val windowWidth = LocalConfiguration.current.screenWidthDp.dp
    val screenWidthPx = with(density) { windowWidth.toPx() }
    val popupEdgePx = with(density) { POPUP_EDGE_AIR.toPx() }
    var menuOpen by remember { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }
    // The approval mode's own card, anchored to its button like the other two.
    var approvalOpen by remember { mutableStateOf(false) }
    var approvalAnchorLeft by remember { mutableStateOf(0f) }
    var approvalWidth by remember { mutableStateOf(0) }
    // The model capsule's right edge in window pixels: the sheet is aligned to it so the card
    // hangs off the button that opened it rather than off the free space around it. Measured
    // here because the capsule is this row's child, and read by [GenerationSheet].
    var capsuleRight by remember { mutableStateOf(0f) }
    // Where the model button's centre sits, in window pixels: the 生成设置 sheet is centred
    // on it rather than on the composer or the screen, which is what "right above the model
    // button" means. Read from the layout, because the row's paddings and the label's own
    // width decide it.
    val ctx = LocalContext.current
    // The two cards this row opens are focus-less (see [MenuCard]'s focusable): a focusable
    // popup window takes the keyboard away from the field, the shell then lays the composer
    // out a keyboard lower, and the card stays anchored to where the composer was — 250dp
    // above the button it belongs to, which is the 偏上 the user photographed. A focus-less
    // window cannot see a tap outside its own bounds and cannot see BACK, so both are caught
    // here. The scrim is a window of its own, composed before the cards: windows stack in
    // composition order, so a scrim composed after them would cover them instead.
    if (menuOpen || sheetOpen || approvalOpen) {
        Popup(properties = PopupProperties(focusable = false)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        menuOpen = false
                        sheetOpen = false
                        approvalOpen = false
                    },
            )
        }
    }
    // Focus-less popups do not receive BACK either: without this the press falls through to
    // the activity and finishes it.
    BackHandler(enabled = menuOpen || sheetOpen || approvalOpen) {
        menuOpen = false
        sheetOpen = false
        approvalOpen = false
    }
    // Nothing here paints a background: the transcript scrolls right down to the gesture
    // bar behind the pill, so the composer reads as something floating over the
    // conversation rather than an opaque strip that cuts it off.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // The gesture bar's strip, but never on top of the keyboard: when the keyboard is
            // up it already covers that strip, and padding for it as well pushed the card a
            // navigation bar's height above the keyboard — the band the user photographed.
            // The shell's clamped keyboard height is what to subtract (clamped, so the
            // platform's end-of-animation rebound cannot flicker the band back for a frame).
            .padding(
                bottom = with(density) {
                    (WindowInsets.navigationBars.getBottom(this) - LocalHeldImePx.current)
                        .coerceAtLeast(0)
                        .toDp()
                },
            )
            .padding(horizontal = 16.dp)
            // The air under the pill, named: the list's own padding is this plus the strip
            // above, and it is measured here, inside the padding (see COMPOSER_BOTTOM_PAD).
            .padding(top = 6.dp, bottom = COMPOSER_BOTTOM_PAD)
            .onSizeChanged { size -> onHeightChanged(with(density) { size.height.toDp() }) }
            // Placed for this frame: the caller's follow pin is dispatched from here.
            .onGloballyPositioned { onPlaced() },
    ) {
        Column {
            // 「回到最新」按钮**不在这里**（用户报的「往上滑一点点就跳」）：它原来在本列里占
            // 48dp + 12dp，一出现就把 composer 撑高 60dp ⇒ 底部让位跟着变大 ⇒ 整条转录被顶上去
            // 60dp。现在画在 composer 之外的兄弟节点上（见 ChatScreen 里的 jumpToLatest 浮钮），
            // 高度不参与 [onHeightChanged] 的测量。
            // No progress line here any more: the run's caption lives in the transcript,
            // where the answer is going to appear. Pinned above the pill it read as a
            // second, competing status area — and it carried no clock, so the wait looked
            // untimed while the user was staring at it.
            // 多附件一排：每张 chip 可单独删（onRemoveAttachment 报下标），横向可滚 —— 不限张数时
            // 一排装不下也不能把 composer 撑高。
            if (attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = STEER_QUEUE_INSET)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    attachments.forEachIndexed { index, item ->
                        AttachmentChip(attachment = item, onRemove = { onRemoveAttachment(index) })
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            // The agent's own board, above the queue: what it is doing right now, then what the
            // user has queued for it. Same inset and spacing as the queue rows below, because
            // they answer the same question — "what is going on" — and a board in different
            // clothes would read as another app's card.
            //
            // Drawn only while this conversation's run is going: the plan belongs to the run
            // that made it, and once the answer has landed the transcript is the record. The
            // board itself is emptied when a run starts (see ChatViewModel.start), so a run that
            // never touches the todo tools cannot inherit the last one's list.
            if (running && todos.isNotEmpty()) {
                val rows = todoRows(todos)
                val done = todos.count { it.status == TODO_COMPLETED }
                // 一行，只一行：进行中的那条（没有就取第一条待办），右边是完成数/总数。用户的话：
                // 「不要堆多行，就一行就行了」——整条清单改由点击这一行打开（见 TodoSheet）。
                val current = rows.firstOrNull { it.status == TODO_IN_PROGRESS } ?: rows.firstOrNull()
                if (current != null) {
                    // 与排队行同一个内缩：它们同属「输入框上方那一列」，宽度必须一致。
                    Box(Modifier.padding(horizontal = STEER_QUEUE_INSET)) {
                        TodoQueueRow(
                            item = current,
                            tally = "$done/${todos.size}",
                            onClick = onOpenTodos,
                        )
                    }
                }
                // Tight to what follows, exactly as the queue is: these rows belong to the field
                // they sit above, not to the transcript behind them.
                Spacer(Modifier.height(3.dp))
            }
            // Queued lines sit directly above the field they came from: the user's own words
            // on their way to the model, not messages yet — so they are inset from the pill
            // (visibly narrower than it, which is what stops them reading as part of it) and
            // each one carries its own actions as icons.
            // 排队 turns first: they are the ones that have not gone anywhere yet, so they sit
            // closest to the field they will be sent from. Without this row the user pressed 插话
            // again and again — the line left the field with nothing on screen to show for it.
            if (pendingTurns.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = STEER_QUEUE_INSET),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    pendingTurns.take(STEER_ROWS_SHOWN).forEach { queued ->
                        // The same row, the same three actions — what changes is what they mean and
                        // what the row says, which `nextTurn` carries.
                        SteerQueueRow(
                            steer = PendingSteer(queued.id, queued.text, queued.imagesBase64),
                            undelivered = false,
                            nextTurn = true,
                            onPromote = { onPromoteTurn(queued.id) },
                            onEdit = {
                                onRestoreSteer(PendingSteer(queued.id, queued.text, queued.imagesBase64))
                                onRetractTurn(queued.id)
                            },
                            onDelete = { onRetractTurn(queued.id) },
                        )
                    }
                    if (pendingTurns.size > STEER_ROWS_SHOWN) {
                        Text(
                            text = "还有 ${pendingTurns.size - STEER_ROWS_SHOWN} 条待发送",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                // 与下面 pendingSteers 块同一个 3dp（用户：「插话进队列是紧贴的，点了立即插话
                // 就有缝隙了」——两种状态的间距必须恒定）。队列行属于它将要发出的那个输入框，
                // 但「紧贴」和「恒贴 3dp」之间选恒定：状态切换时布局不跳。
                Spacer(Modifier.height(3.dp))
            }
            if (pendingSteers.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = STEER_QUEUE_INSET),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    pendingSteers.take(STEER_ROWS_SHOWN).forEach { queued ->
                        SteerQueueRow(
                            steer = queued,
                            // The run is over, so nothing will ever read this line unless the
                            // user sends it again — the row says so.
                            undelivered = !running,
                            onPromote = { onPromoteSteer(queued.id) },
                            onEdit = {
                                // Both halves go back, text and picture — see restoreQueuedSteer.
                                // The line is then retracted, so nothing is sent twice.
                                onRestoreSteer(queued)
                                onRetractSteer(queued.id)
                            },
                            onDelete = { onRetractSteer(queued.id) },
                        )
                    }
                    if (pendingSteers.size > STEER_ROWS_SHOWN) {
                        Text(
                            text = "还有 ${pendingSteers.size - STEER_ROWS_SHOWN} 条排队中",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                // Tight to the pill: a queued line belongs to the field it will be sent from,
                // and a gap here made it look like a floating card of its own.
                Spacer(Modifier.height(3.dp))
            }
            // Opaque surface with one real soft shadow: the drawn concentric rings read
            // as a halo, and 86% alpha let the transcript bleed through the pill.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 8.dp,
                        shape = RoundedCornerShape(24.dp),
                        ambientColor = Color.Black.copy(alpha = 0.25f),
                        spotColor = Color.Black.copy(alpha = 0.25f),
                    ),
            ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                // Light uses the canvas colour; dark steps one surface up, because there
                // `surface` IS the canvas and the pill would vanish into it.
                color = if (LocalDarkTheme.current) scheme.surfaceContainer else scheme.surface,
                shadowElevation = 0.dp,
            ) {
                Column {
                    // Row 1: the field itself, transparent on the capsule — no border,
                    // no container, just text with room to breathe above the controls.
                    BasicTextField(
                        value = input,
                        onValueChange = onInput,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(start = 20.dp, top = 14.dp, end = 20.dp, bottom = 4.dp)
                            .onFocusChanged { focus ->
                                if (focus.isFocused) onFocused()
                            },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 16.sp,
                            color = scheme.onSurface,
                        ),
                        cursorBrush = SolidColor(scheme.primary),
                        maxLines = 5,
                        // Enter sends — and while a run is live it sends the line to that
                        // run instead of starting a second one. 「回车键发送」 turned off makes
                        // the key a newline instead: the field is already multi-line, and the
                        // action button under it is then the only way to submit.
                        keyboardOptions = KeyboardOptions(
                            imeAction = if (settings.enterKeySends) {
                                ImeAction.Send
                            } else {
                                ImeAction.Default
                            },
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                val text = input.trim()
                                // A picture with no caption is still something to send: only an
                                // entirely empty composer does nothing here.
                                if (text.isEmpty() && attachments.isEmpty()) return@KeyboardActions
                                if (running) {
                                    // The attachment rides along: 排队 mode turns this into the next
                                    // turn, 立即 mode into a steer, and either way a picture the user
                                    // attached belongs to the line.
                                    //
                                    // The field is cleared by the **view model**, not here — see
                                    // ChatViewModel.steer, which clears on both accepted paths. This
                                    // used to clear as well, and that second writer is why the text and
                                    // the picture kept coming back: `setDraft`'s defaults come from
                                    // *this composition's* captured draft, so `onInput("")` wrote the old
                                    // uri/name straight back into `_drafts`, and the collector pushed it
                                    // onto the screen a frame later.
                                    //
                                    // A refusal (the run ended between the last frame and this tap)
                                    // falls through to an ordinary send, so the line is never left
                                    // sitting in the box with nothing happening.
                                    withAttachment(attachments, text)
                                        .let { (body, images) -> if (!onSteer(body, images)) onSend() }
                                } else {
                                    onSend()
                                }
                                dismissKeyboard()
                            },
                        ),
                        decorationBox = { inner ->
                            Box {
                                if (input.isEmpty()) {
                                    Text(
                                        text = when {
                                            pendingSteers.isNotEmpty() ->
                                                "还有 ${pendingSteers.size} 条排队中…"

                                            running -> "插一句话…"
                                            else -> "让随行做点什么…"
                                        },
                                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                                        color = scheme.onSurfaceVariant,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    // Row 2: attach, the two flat selectors, mic, action — the controls
                    // live under the text now. 48dp of content, not 44: the buttons carry
                    // a 48dp minimum touch target, and a shorter row squeezed them into
                    // ellipses with the circle clipped to a 40x36 box.
                    Row(
                        modifier = Modifier
                            // 10dp, and the box beside it adds 7dp more (a 36dp box around a 22dp
                            // glyph): the 「+」 sits 17dp from the capsule's edge, which is the
                            // 16–18dp the user asked for. The row's own padding is the only lever
                            // that moves that edge without moving the gap between the two buttons.
                            .padding(start = 10.dp, end = 12.dp, bottom = 8.dp)
                            .height(48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        // Zero: the two buttons' own boxes supply the only gap between their glyphs,
                        // and every dp spent here is a dp the user sees between the icons.
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                    // The anchor's own left edge and the card's width, both measured: the card opens
                    // to the right of this button, and on a window narrower than the button plus
                    // the card it used to hang off the screen's right edge.
                    var plusAnchorLeft by remember { mutableStateOf(0f) }
                    var plusWidth by remember { mutableStateOf(0) }
                    Box(Modifier.onGloballyPositioned { plusAnchorLeft = it.boundsInWindow().left }) {
                        IconButton(
                            onClick = { menuOpen = true },
                            // 36dp around the glyph's original 22dp. What the eye measures is the
                            // glyph's distance to the capsule's edge (the row's 10dp plus this box's
                            // 7dp) and to the glyph beside it (7 + 0 + 8 = 15dp), and both are set by
                            // this one number — so it is chosen once rather than tuned twice: at
                            // 40dp the pair would sit 19dp apart, outside the 14–16dp asked for. The
                            // 「不要浪费空间」 the user pointed at was the 11dp a side that the 44dp
                            // box carried; this one carries 7dp.
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = ImageVector.vectorResource(R.drawable.ic_plus),
                                contentDescription = "添加图片或文件",
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        // The same card as the model sheet: one popup style for the whole
                        // screen, instead of a Material dropdown here and a card there.
                        MenuCard(
                            visible = menuOpen,
                            onDismiss = { menuOpen = false },
                            // Opens upward from the button it belongs to. BottomStart puts the
                            // popup's bottom edge on the anchor's bottom edge, and the anchor is the
                            // button's own 36dp box — so the offset clears that box plus the 8dp of
                            // air the card is meant to leave above it (-8dp alone left the card
                            // sitting on the button).
                            alignment = Alignment.BottomStart,
                            offset = with(density) {
                                IntOffset(
                                    popupNudge(
                                        anchorLeft = plusAnchorLeft,
                                        width = plusWidth,
                                        windowPx = screenWidthPx,
                                        edgePx = popupEdgePx,
                                    ).roundToInt(),
                                    -44.dp.roundToPx(),
                                )
                            },
                            // Same reason as the model sheet: this card hangs off a button of
                            // the composer too, and a focusable popup here would move the
                            // composer out from under it.
                            focusable = false,
                            modifier = Modifier
                                .widthIn(max = popupCap(200.dp, windowWidth))
                                .onSizeChanged { plusWidth = it.width },
                        ) {
                            MenuItemRow(
                                icon = ImageVector.vectorResource(R.drawable.ic_image),
                                label = "图片",
                                onClick = {
                                    menuOpen = false
                                    onPickImage()
                                },
                            )
                            MenuItemRow(
                                icon = ImageVector.vectorResource(R.drawable.ic_camera),
                                label = "拍照",
                                onClick = {
                                    menuOpen = false
                                    onTakePhoto()
                                },
                            )
                            MenuItemRow(
                                icon = ImageVector.vectorResource(R.drawable.ic_file),
                                label = "文件",
                                onClick = {
                                    menuOpen = false
                                    onPickFile()
                                },
                            )
                            // Always listed: the entry disappearing between runs made users
                            // think the feature was gone. With no run in flight the engine
                            // refuses and the toast says why — a visible refusal beats a
                            // missing menu item.
                            MenuItemRow(
                                icon = ImageVector.vectorResource(R.drawable.ic_minimize_2),
                                label = "压缩上下文",
                                onClick = {
                                    menuOpen = false
                                    val asked = onRequestCompress()
                                    if (asked) {
                                        Toast.makeText(ctx, "已请求压缩，本轮结束后生效", Toast.LENGTH_SHORT).show()
                                    } else {
                                        // 没有 run：**真的压一次**，并且状态行会显示「正在压缩上下文」。
                                        // 以前这里只弹一句 toast，什么也没压（用户：「点了没反应」）。
                                        onCompressNow()
                                        Toast.makeText(ctx, "正在压缩上下文…", Toast.LENGTH_SHORT).show()
                                    }
                                },
                            )
                        }
                    }
                    // 工具审批, right of the add button: the same choice the settings page
                    // offers (自动运行 / 每次询问 / 禁止), one tap from the field it governs —
                    // asking for approval is a decision about the question being typed, and
                    // walking to 设置 mid-thought is how the setting stayed on whatever it was.
                    // The icon is the mode itself, so the row says which one is in force without
                    // being opened: a tick when it runs on its own, a shield while it asks, an
                    // alert shield when it refuses.
                    Box(
                        Modifier.onGloballyPositioned {
                            approvalAnchorLeft = it.boundsInWindow().left
                        },
                    ) {
                        IconButton(
                            onClick = { approvalOpen = true },
                            // Same 36dp box and the same glyph size as the 「+」 beside it: the two are
                            // one visual group on the row's left, so they are measured together.
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = ImageVector.vectorResource(approvalIcon(approvalMode)),
                                contentDescription = "工具审批：${approvalMode.label}",
                                tint = when (approvalMode) {
                                    ApprovalMode.DENY -> scheme.error
                                    ApprovalMode.AUTO -> scheme.primary
                                    else -> scheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        MenuCard(
                            visible = approvalOpen,
                            onDismiss = { approvalOpen = false },
                            alignment = Alignment.BottomStart,
                            offset = with(density) {
                                IntOffset(
                                    popupNudge(
                                        anchorLeft = approvalAnchorLeft,
                                        width = approvalWidth,
                                        windowPx = screenWidthPx,
                                        edgePx = popupEdgePx,
                                    ).roundToInt(),
                                    -44.dp.roundToPx(),
                                )
                            },
                            focusable = false,
                            modifier = Modifier
                                .widthIn(max = popupCap(220.dp, windowWidth))
                                .onSizeChanged { approvalWidth = it.width },
                        ) {
                            ApprovalMode.entries.forEach { mode ->
                                MenuItemRow(
                                    icon = ImageVector.vectorResource(approvalIcon(mode)),
                                    label = mode.label,
                                    selected = mode == approvalMode,
                                    onClick = {
                                        approvalOpen = false
                                        onPickApproval(mode)
                                    },
                                )
                            }
                            // The mode is read when a run starts (see ApprovalGate), so saying
                            // so here is the difference between a setting and a surprise.
                            Text(
                                text = "下一次任务生效",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
                            )
                        }
                    }
                    // One selector for both decisions: the model and the reasoning
                    // level live together in the 生成设置 sheet, so the row keeps a
                    // single caption instead of two competing ones.
                    //
                    // The caption is the row's flexible child (weight, not a fixed cap):
                    // capping it at 104dp was what squeezed a long model id down to a single
                    // ellipsis while the rest of the row sat empty, and it could still
                    // overflow a 360dp screen once the ring, the mic and the send button
                    // were all present. Weighted, it takes exactly the space the controls
                    // leave — never more — so the row can neither clip the send button nor
                    // starve the name. The name itself caps at 160dp and ellipsizes after
                    // that, which is the one truncation the user asked for.
                    Box(Modifier.weight(1f)) {
                        // The sheet hangs off the capsule itself, not off a probe at the top
                        // of the stack: anchored there it opened above the whole composer, and
                        // the user asked for it above the model button. Right-aligned inside
                        // the free space so the capsule sits against the ring, as asked.
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                                // The probe and the capsule are siblings here, and that is the whole
                                // of it: as a *wrapper* around them the probe stretched this box —
                                // a Box is as wide as its widest child, and a full-width child makes
                                // it the whole free space — and the capsule inside then sat at that
                                // box's own start (TopStart, the default), i.e. the model selector
                                // at the far left of an empty row with the ring way off to the
                                // right. That is exactly the screenshot the user sent: the
                                // right-alignment was written and had nothing to align.
                                //
                                // Zero-height probe at the capsule's TOP edge: the popup hangs
                                // off it bottom-aligned, so its bottom edge lands 8dp above the
                                // capsule. Hung on the capsule box itself, BottomStart put the
                                // popup's bottom on the capsule's bottom — covering the button
                                // that opened it, which is what the user photographed.
                                Box(Modifier.fillMaxWidth().height(0.dp)) {
                                    GenerationSheet(
                                        settings = settings,
                                        assistantId = assistantId,
                                        conversationProviderId = conversationProviderId,
                                        conversationModel = conversationModel,
                                        conversationReasoningEffort = conversationReasoningEffort,
                                        onChooseReasoning = onChooseReasoning,
                                        visible = sheetOpen,
                                        capsuleRight = capsuleRight,
                                        onPickModel = onPickModel,
                                        onOpenSettings = onOpenSettings,
                                        onDismiss = { sheetOpen = false },
                                    )
                                }
                                // The capsule's own right edge, measured: the sheet hangs off
                                // the button that opened it, and the free space around the
                                // button is wider than the button whenever the model name is
                                // short — right-aligning to the free space left the card that
                                // far to the right of the control it belongs to.
                                Box(
                                    Modifier.onGloballyPositioned {
                                        capsuleRight = it.boundsInWindow().right
                                    },
                                ) {
                                UnifiedSelector(
                                settings = settings,
                                assistantId = assistantId,
                                conversationProviderId = conversationProviderId,
                                conversationModel = conversationModel,
                                conversationReasoningEffort = conversationReasoningEffort,
                                onClick = {
                                    sheetOpen = true
                                    // The sheet is the one place models are looked at, so
                                    // this is where the endpoint's own window numbers are
                                    // worth asking for — once per endpoint per process.
                                    onPrefetchWindows()
                                },
                                )
                                }
                        }
                    }
                    // Left of the mic, where the user asked for it: the mic is the control
                    // reached for mid-thought, and this is the gauge that says whether the
                    // next question still fits in the window.
                    ContextRing(
                        used = contextUsed,
                        estimated = contextEstimated,
                        window = contextWindow,
                        sessionTokens = sessionTokens,
                        sessionEstimate = sessionEstimate,
                        sessionSplit = sessionSplit,
                        sessionTurns = sessionTurns,
                        sessionPerSecond = sessionPerSecond,
                        sessionCachePercent = sessionCachePercent,
                        sessionElapsedMs = sessionElapsedMs,
                        live = stats,
                        breakSplit = contextBreak,
                        onPanelOpen = onContextPanelOpen,
                    )
                    // The ring's own right margin: the row packs its children edge to edge, and the
                    // gauge sat hard against whatever follows it — the mic when 语音输入 is on, the
                    // send button when it is not. Four dp, and nothing else in the row moves: the
                    // model capsule to its left takes the free space either way.
                    Spacer(Modifier.width(4.dp))
                    // 设置里的「语音输入」是唯一的开关，默认关：关着时这里什么都不画，连占位都不留。
                    // 开着也不需要系统识别器了——我们自己的录音链路走 AudioRecord，不会再弹系统面板。
                    if (voiceInput) {
                        IconButton(
                            onClick = onMic,
                            enabled = !running,
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(
                                ImageVector.vectorResource(R.drawable.ic_mic),
                                contentDescription = "语音输入",
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    val hasSomething = input.isNotBlank() || attachments.isNotEmpty()
                    // While a run is going, a field with something in it sends that line to the
                    // run; an empty field stops it. One button, and what it does is what the
                    // field in front of the user says — the separate 插话 button only ever made
                    // the user wonder which of the two they wanted.
                    //
                    // "Something" is text **or** a picture: a screenshot with no caption is still a
                    // turn the user wants to send, and judging by text alone left that case with no
                    // way out at all — the button stayed on 停止 and the attachment could not go
                    // anywhere until the run ended. 停止 is for an empty field, nothing else.
                    val steers = running && hasSomething
                    val sendable = running || hasSomething
                    CircleActionButton(
                        icon = if (running && !steers) {
                            ImageVector.vectorResource(R.drawable.ic_stop_filled)
                        } else {
                            ImageVector.vectorResource(R.drawable.ic_arrow_up)
                        },
                        contentDescription = when {
                            steers -> "插话发送"
                            running -> "停止"
                            else -> "发送"
                        },
                        onClick = {
                            when {
                                // Cleared by the view model when it takes the line, not here — see the
                                // note on the Enter path above. A refusal falls through to a normal
                                // send, so the text cannot be stranded in the field.
                                steers -> {
                                    withAttachment(attachments, input.trim())
                                        .let { (body, images) -> if (!onSteer(body, images)) onSend() }
                                    dismissKeyboard()
                                }
                                running -> onStop()
                                else -> {
                                    onSend()
                                    dismissKeyboard()
                                }
                            }
                        },
                        enabled = sendable,
                        size = 40.dp,
                        // Three states, three colours: a live run offers stop, a field with
                        // something in it turns brand-coloured so the button reads as ready,
                        // and an empty field stays a quiet grey circle.
                        containerColor = if (running || hasSomething) {
                            scheme.primary
                        } else {
                            scheme.surfaceVariant
                        },
                        contentColor = if (running || hasSomething) {
                            scheme.onPrimary
                        } else {
                            scheme.onSurfaceVariant
                        },
                        // Flat: the pill's own lift is the only depth in this corner.
                        shadowElevation = 0.dp,
                        iconSize = 22.dp,
                    )
                    }
                }
            }
        }
    }
            }
}

