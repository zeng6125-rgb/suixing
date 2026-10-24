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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.StateFlow
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

/**
 * The context entry on the title capsule's right end (user: 「原圆环的内容移至标题胶囊右侧，
 * 可以换个 icon」): a bar-chart glyph, and a popup that opens **downward** — the bar sits at the
 * screen's top, so the ring's upward-opening card would leave the window here. Its content is
 * the ring's original detail card, unchanged.
 */
@Composable
internal fun TopBarContextEntry(
    used: Int,
    estimated: Boolean,
    window: Int,
    sessionTokens: Int,
    sessionEstimate: Int,
    sessionSplit: SessionSplit,
    sessionTurns: Int,
    sessionPerSecond: Double,
    sessionCachePercent: Double,
    sessionElapsedMs: Long,
    statsFlow: StateFlow<StreamStats?>,
) {
    val live by statsFlow.collectAsState()
    var open by remember { mutableStateOf(false) }
    BackHandler(enabled = open) { open = false }
    var anchorCentreX by remember { mutableStateOf(0f) }
    var cardWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val screenPx = with(density) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    val edgePx = with(density) { POPUP_EDGE_AIR.toPx() }
    Box(
        modifier = Modifier
            .size(28.dp)
            .onGloballyPositioned { anchorCentreX = it.boundsInWindow().center.x }
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = { open = true },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_chart_column),
            contentDescription = "上下文用量明细",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (open) {
            Popup(properties = PopupProperties(focusable = false)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { open = false },
                )
            }
            Popup(
                alignment = Alignment.TopCenter,
                offset = with(density) {
                    IntOffset(
                        popupNudgeCentred(
                            anchorCentre = anchorCentreX,
                            width = cardWidth,
                            windowPx = screenPx,
                            edgePx = edgePx,
                        ).roundToInt(),
                        // Below the glyph, clear of the capsule: the icon box is 28dp tall.
                        28.dp.roundToPx() + 8.dp.roundToPx(),
                    )
                },
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = false),
            ) {
                Box(
                    Modifier
                        .onSizeChanged { cardWidth = it.width }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {},
                ) {
                    ContextRingCard(
                        used = used,
                        estimated = estimated,
                        window = window,
                        sessionTokens = sessionTokens,
                        sessionEstimate = sessionEstimate,
                        sessionSplit = sessionSplit,
                        sessionTurns = sessionTurns,
                        sessionPerSecond = sessionPerSecond,
                        sessionCachePercent = sessionCachePercent,
                        sessionElapsedMs = sessionElapsedMs,
                        live = live,
                    )
                }
            }
        }
    }
}

/**
 * One label/value line of the ring's and the metrics' cards.
 *
 * The value is right-aligned on a shared column rather than set beside the label: a stack of
 * 「输入 83.6k / 缓存读取 82.4k / 未缓存输入 1.2k」 reads as a table when the
 * numbers line up on one edge, and as prose when each number starts wherever its label happened
 * to end. The card itself is given a floor width so that column has room to exist.
 */
@Composable
internal fun RingDetail(label: String, value: String) {
    Row(
        // Fills its parent, and the parent is sized to the widest row (see the cards): that is
        // what lines the numbers up. It deliberately does not ask for the screen's width — the
        // first attempt did, and a popup that should hug its content grew to the full screen.
        // 1.5dp top and bottom is the line spacing the user asked for: two rows meet with 3dp
        // between them, and a card's own 12-14dp of padding swallows the outer two halves.
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.5.dp),
        // Centred, and it has to be said: the value is a size smaller than its label, so a
        // Top alignment would hang the number off the label's cap line.
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            // A size under the label: the numbers are the detail of the line, and at the
            // label's own size they read as a second heading beside it. SemiBold, so that a
            // size down is still the heavier of the two: the number is what the line is for,
            // and the label carries it in the regular weight.
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            // A shade under the label's own tone: the numbers are the detail, and at full
            // onSurface they read heavier than the labels they belong to.
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * 12.3k / 950 / 1.2M — the short form the context readouts use.
 *
 * Past a million the k form stops being readable (a long conversation's own tally runs to
 * hundreds of millions of tokens, and 「143798.2k」 says nothing); M is the unit a person
 * actually reads.
 */
internal fun shortTokens(tokens: Int): String = when {
    tokens < 1000 -> "$tokens"
    tokens < 1_000_000 -> String.format(Locale.US, "%.1fk", tokens / 1000.0)
    else -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
}

/**
 * The whole number, thousands separated: 「8,502,144 tok」.
 *
 * [shortTokens] answers "how big is this" at a glance, which is right for a window or a meter.
 * The three rows of the ring's split are read against a bill instead, so they are given in
 * full — a rounded figure cannot be checked against anything.
 */
internal fun fullTokens(tokens: Long): String = String.format(Locale.US, "%,d", tokens)

/** The three parts of a conversation's spend, as the endpoint itself reports them. */
data class SessionSplit(val uncached: Long, val cached: Long, val output: Long)

/**
 * Sums [SessionSplit] off the stored usage rows, the same rows [SessionStats] divides its hit
 * rate by, so the parts and the rate are two readings of one measurement.
 */
internal fun sessionSplitOf(messages: List<StoredMessage>): SessionSplit = SessionSplit(
    uncached = messages.sumOf { it.promptTokens.toLong() - it.cachedTokens.toLong() }.coerceAtLeast(0L),
    cached = messages.sumOf { it.cachedTokens.toLong() },
    output = messages.sumOf { it.completionTokens.toLong() },
)

/**
 * The messages a compression record covers, or null when this transcript no longer holds
 * both ends of the span — a branch switch, or a conversation trimmed by hand.
 */
internal fun checkpointSpan(
    messages: List<StoredMessage>,
    checkpoint: ContextCheckpoint,
): List<StoredMessage>? {
    val from = messages.indexOfFirst { it.id == checkpoint.fromMessageId }
    val to = messages.indexOfFirst { it.id == checkpoint.toMessageId }
    if (from < 0 || to < from) return null
    return messages.subList(from, to + 1)
}

/**
 * One compression the engine made, drawn where it happened.
 *
 * A separator, not a bubble: it is not something either side said, it is a note about the
 * transcript itself. Folded it is a single line; opened it shows the summary the model
 * produced — bounded, because a summary can be long — and, on request, the messages it
 * replaced. Those originals are still on disk, which is the whole point of keeping a record:
 * the conversation is never actually lost, only folded.
 */
@Composable
internal fun CheckpointCard(checkpoint: ContextCheckpoint, originals: List<StoredMessage>) {
    val scheme = MaterialTheme.colorScheme
    var expanded by rememberSaveable { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }
    // *和思考块/工具块同一个折叠壳**（用户：「不要直勾勾显示在卡片下方，像工具块思考块一样
    ProcessCard(
        icon = ImageVector.vectorResource(R.drawable.ic_minimize_2),
        title = "已压缩上下文 · ${formatTime(checkpoint.at)}",
        expanded = expanded,
        onToggle = { expanded = !expanded },
        preview = "${originals.size} 条消息 → 摘要",
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = checkpoint.summary,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurface,
            )
        }
        Text(
            // 同表头口径：条数用转录计数（[originals]），与「查看原文」展开看到的一致；
            // checkpoint.messages 是 wire 计数，不展示。
            text = "压缩前约 ${shortTokens(checkpoint.promptTokens)} tokens · " +
                "${originals.size} 条消息 · ${formatTime(checkpoint.at)}",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
        TextButton(
            onClick = { showOriginal = !showOriginal },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                text = if (showOriginal) "收起原文" else "查看原文",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (showOriginal) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "被压缩的原文（${originals.size} 条）",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = scheme.onSurfaceVariant,
                )
                originals.forEach { original ->
                    Column {
                        Text(
                            text = "${if (original.role == Roles.USER) "我" else "随行"} · " +
                                formatTime(original.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                        Text(
                            text = original.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurface,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One queued line, above the composer.
 *
 * A shallow rounded bar rather than a bubble or a card: it is a line waiting its turn, and it
 * is inset from the pill (see [STEER_QUEUE_INSET]) so it reads as belonging to the field
 * below it without being part of it. The three things the user can still do to it are icons
 * on the right, in the order they are likely to want them. Only the newest three are drawn
 * (see [STEER_ROWS_SHOWN]) — a queue long enough to push the field off screen would be worse
 * than the count that replaces it.
 */
@Composable
internal fun SteerQueueRow(
    steer: PendingSteer,
    /** The run is over: this line will never be read unless the user sends it again. */
    undelivered: Boolean,
    /**
     * 排队 mode: this line is waiting to be the **next turn**, not an interruption of the one in
     * flight. The row is otherwise identical — same three actions — but it has to say which of the
     * two it is, or 「立即插话」 reads as a promise the queue does not make.
     */
    nextTurn: Boolean = false,
    onPromote: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 10.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A clock, not a speech mark: the line is waiting, and that is the whole point of
            // the bar.
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_clock),
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(8.dp))
            // A line that is only a picture has no text to show, and an empty bar leaves the user
            // unable to tell what is waiting — so it says what it is.
            val label = steer.text.ifBlank { if (steer.imagesBase64.isNotEmpty()) "（图片）" else steer.text }
            // The picture itself, at the size this 36dp bar can hold. Decoded here rather than
            // through ImageBlock: that one is built for a bubble (its own size plus the full-screen
            // preview dialog), so it would be a second layout path, not a reuse. 多附件只画第一张
            // 缩略图 + 计数，一排 24dp 的格子装不下也不该装下整列。
            val firstImage = steer.imagesBase64.firstOrNull()
            var thumb by remember(firstImage) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(firstImage) {
                thumb = firstImage?.let { decodeAttachment(it, target = THUMB_PX)?.asImageBitmap() }
            }
            // Tapping the thumbnail opens the picture at full size. The decode happens on the tap,
            // not in the row: the 24dp copy above is downsampled to [THUMB_PX] and blowing it up
            // would show exactly the blur the user is trying to avoid, so the base64 is decoded
            // again — on IO — at a size worth looking at.
            val rowScope = rememberCoroutineScope()
            val rowContext = LocalContext.current
            var fullPicture by remember(firstImage) { mutableStateOf<ImageBitmap?>(null) }
            thumb?.let { picture ->
                Image(
                    bitmap = picture,
                    contentDescription = "查看图片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(),
                        ) {
                            val base64 = firstImage ?: return@clickable
                            rowScope.launch {
                                val decoded = decodeAttachment(base64, target = FULL_PREVIEW_PX)
                                if (decoded == null) {
                                    Toast.makeText(rowContext, "这张图片打不开", Toast.LENGTH_SHORT).show()
                                } else {
                                    fullPicture = decoded.asImageBitmap()
                                }
                            }
                        },
                )
                Spacer(Modifier.width(8.dp))
            }
            fullPicture?.let { picture ->
                ImagePreviewDialog(
                    bitmap = picture,
                    contentDescription = "排队中的图片",
                    keepKeyboard = true,
                ) { fullPicture = null }
            }
            Text(
                text = if (undelivered) "$label（未送达）" else label,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 排队 rows say what they are: the line has not reached the model at all, so calling
            // it an interruption would be a lie, and 「立即插入」 is the deliberate way to make it
            // one. An undelivered steer has no round left to interrupt, so its action starts a new
            // turn instead and says so.
            QueueAction(
                icon = R.drawable.ic_arrow_up,
                label = when {
                    nextTurn -> "立即插入"
                    undelivered -> "发送"
                    else -> "立即插话"
                },
                onClick = onPromote,
            )
            QueueAction(icon = R.drawable.ic_square_pen, label = "编辑", onClick = onEdit)
            QueueAction(icon = R.drawable.ic_trash, label = "删除", onClick = onDelete)
        }
    }
}

/**
 * One line of the agent's board, above the composer.
 *
 * The same body as [SteerQueueRow] on purpose — 36dp, the same radius and container — because
 * the two stacks sit one above the other and a different shape would read as a different app's
 * card. What differs is the content: the glyph is the item's status (a tick once it is done,
 * the list glyph while it is still ahead), the row being worked on is tinted with the brand
 * colour, and a finished line is dimmed rather than removed mid-drag of the eye.
 *
 * [tally] is 「已完成/总数」, drawn on the board's first row alone.
 */
@Composable
internal fun TodoQueueRow(
    item: TodoItem,
    tally: String?,
    /** 点这一行看完整清单；null 时不可点（只作展示用）。 */
    onClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val done = item.status == TODO_COMPLETED
    val active = item.status == TODO_IN_PROGRESS
    val ink = when {
        active -> scheme.primary
        done -> scheme.onSurfaceVariant.copy(alpha = 0.6f)
        else -> scheme.onSurfaceVariant
    }
    // Surface 的 onClick 重载：可点时它自己带 ripple 与语义，不用另加 clickable。
    val shape = RoundedCornerShape(14.dp)
    val modifier = Modifier
        .fillMaxWidth()
        .height(36.dp)
    val body: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 10.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(
                    if (done) R.drawable.ic_circle_check else R.drawable.ic_list_checks,
                ),
                contentDescription = null,
                tint = if (active) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = item.subject,
                style = MaterialTheme.typography.labelMedium,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (tally != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = tally,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
    if (onClick == null) {
        Surface(shape = shape, color = scheme.surfaceContainerHigh, modifier = modifier) { body() }
    } else {
        // 可点：整行就是「看完整清单」的入口，形状/颜色/高度与不可点时完全一致。
        Surface(
            onClick = onClick,
            shape = shape,
            color = scheme.surfaceContainerHigh,
            modifier = modifier,
        ) { body() }
    }
}

/**
 * 卡片底部的一行原因：中断、上游截断、失败。
 *
 * 属于这一轮的回答，所以跟着卡片走 —— 用户看到「连接中断」时，它就该长在那条半截回答的下面，
 * 而不是屏幕顶上一条与内容无关的浮层。样式用卡片里既有的一档小字（labelSmall）加错误色，
 * 不再新造一种提示形态。
 */
@Composable
internal fun RunReasonLine(reason: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 6.dp, end = 4.dp),
        // 垂直居中而不是顶对齐：顶对齐 + 图标自己再 padding(top = 1dp) 会把 14dp 的图标
        // 压成 12dp 并整条偏上 —— 用户看到的「错误提示和 icon 没有对齐」。
        // 居中之后图标、文字行、右侧复制按钮在同一条中线上。
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.ic_circle_alert),
            contentDescription = null,
            tint = scheme.error,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = reason,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.error,
            modifier = Modifier.weight(1f),
        )
        // 错误提醒可复制（用户：「错误提醒怎么还不能复制」）：排查要的就是这段原文。
        val clip = LocalClipboardManager.current
        IconButton(
            onClick = { clip.setText(AnnotatedString(reason)) },
            modifier = Modifier.size(24.dp),
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_copy),
                contentDescription = "复制错误提醒",
                tint = scheme.error,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/**
 * 失败回执：run 最终失败时 VM 落盘的一条 system 消息画成的红色小卡。
 *
 * 与 [RunReasonLine] 的分工：那条是**进行中/刚结束**时挂在最后一张卡片里的小红字（会随横幅
 * 计时消失）；这一条是**留在会话记录里**的那一份 —— 用户原话「如果最终确实是失败了，需要把
 * 失败的提示类似气泡那样作为回复」。全宽、可换行、不截断，重开会话还在。
 */
@Composable
internal fun FailureReceiptRow(text: String) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = scheme.errorContainer.copy(alpha = 0.5f),
    ) {
        // 图标要与**正文第一行的中线**对齐（用户：「我要你让icon和正文第一行对齐」）。
        // 图标 14dp、正文行高比它高 ⇒ 直接顶对齐会让图标整体偏上；写死一个上边距也只是
        // 让两枚图标互相对齐（上一版就错在这），跟第一行的中线还是差一截。
        // 这里按行高算：图标中心 = 行高的一半 = 第一行的中线。
        val bodySmall = MaterialTheme.typography.bodySmall
        val density = LocalDensity.current
        val lineHeight = with(density) { bodySmall.lineHeight.toDp() }
        // **对准字形的墨迹中心，不是行框中心**（用户：「视觉上没有对齐中心啊」）。
        // 行框（lineHeight）比 em 框高，中文字形只占 em 框的 ~86% 且坐在基线上 ⇒
        // 墨迹中心比行框中心低约 0.06em。真机截图量过（12sp 正文）：差 ~1.5px ≈ 0.9dp，
        // 两枚图标彼此是齐的（都是 51.0），只是都比第一行高。
        // 用 em 的比例算（而不是写死 dp），字体缩放时依然对得上。
        val inkShift = with(density) { (bodySmall.fontSize.value * 0.06f).sp.toDp() }
        val iconTop = ((lineHeight - 14.dp) / 2 + inkShift).coerceAtLeast(0.dp)
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_circle_alert),
                contentDescription = null,
                tint = scheme.error,
                modifier = Modifier.size(14.dp).padding(top = iconTop),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            // 同 [RunReasonLine]：回执也要能复制（排查要原文）。
            //
            // **图标与左边那枚同高**（用户：「这两个icon要跟第一行垂直居中啊，你这样都没有对齐」）：
            // IconButton 会把图标在 26dp 的方框里**居中**，于是它比左边的告警图标低了 6dp ——
            // 文字换行成两行时，两枚图标更不在同一条中线上。改成 26dp 的可点区域 + 顶部对齐，
            // 图标自己补 1dp 上边距（与左边那枚一致，正文第一行的中线就在那儿）。
            val clip = LocalClipboardManager.current
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clickable { clip.setText(AnnotatedString(text)) },
                contentAlignment = Alignment.TopCenter,
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_copy),
                    contentDescription = "复制错误提醒",
                    tint = scheme.error,
                    // 同一个 iconTop ⇒ 与左边那枚完全同高，且都在第一行的中线上。
                    modifier = Modifier.size(14.dp).padding(top = iconTop),
                )
            }
        }
    }
}

/** 完整任务清单：输入框上方那一行点开就是它。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TodoSheet(todos: List<TodoItem>, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val done = todos.count { it.status == TODO_COMPLETED }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = scheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text("任务清单", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                text = "已完成 $done/${todos.size}",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(todos) { item -> TodoSheetRow(item) }
            }
        }
    }
}

/**
 * 文件路径菜单（LINK-CLICK-SPEC §3.2 一期）：点消息里的文件路径弹出来。
 *
 * 一期只有两项，且都是「永远可用」的：复制路径、让随行读取。二期才加 FileProvider 的
 * 「文件管理器打开 / 分享」——spec 说得很明白，ACTION_VIEW 对 mime 与已装 app 依赖太大
 * （`.md` 多半没有对应 app），所以把复制与让模型读这两个不会断的放前面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PathActionSheet(
    path: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onAskModel: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = scheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            // 路径本体：等宽、可换行、不截断——用户要核对的就是这串。
            Text(
                text = path,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = scheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            MenuItemRow(
                icon = ImageVector.vectorResource(R.drawable.ic_copy),
                label = "复制路径",
                onClick = onCopy,
            )
            MenuItemRow(
                icon = ImageVector.vectorResource(R.drawable.ic_bot),
                label = "让随行读取并总结",
                onClick = onAskModel,
            )
        }
    }
}

/** 清单里的一行：状态标记 + 事项；完成的划掉一点、进行中的用品牌色。 */
@Composable
internal fun TodoSheetRow(item: TodoItem) {
    val scheme = MaterialTheme.colorScheme
    val done = item.status == TODO_COMPLETED
    val active = item.status == TODO_IN_PROGRESS
    val ink = when {
        active -> scheme.primary
        done -> scheme.onSurfaceVariant.copy(alpha = 0.6f)
        else -> scheme.onSurface
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(
                when {
                    done -> R.drawable.ic_circle_check
                    // 没有专门的「进行中」图标：箭头朝右＝正在往前走，比再画一个圆点更不含糊。
                    active -> R.drawable.ic_chevron_right
                    else -> R.drawable.ic_list_checks
                },
            ),
            contentDescription = null,
            tint = when {
                active -> scheme.primary
                done -> scheme.onSurfaceVariant.copy(alpha = 0.6f)
                else -> scheme.onSurfaceVariant
            },
            modifier = Modifier.size(16.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = item.subject,
            style = MaterialTheme.typography.bodyMedium,
            color = ink,
            textDecoration = if (done) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The board rows worth drawing: the items still open, in the order the model wrote them, and —
 * once everything is done — the last few that finished.
 *
 * Finished items leave the row as they finish, which is what "创建、完成、消失" describes: the
 * plan on screen is what is *left*, and the tally is what says how far along the whole board is.
 * Showing the whole list instead would push the one item being worked on off the bottom of a
 * three-row strip as soon as a plan grew past it.
 */

/**
 * One icon action of a queued row.
 *
 * The touch target is the 40dp node the ripple and the click live on, with the glyph drawn
 * inside it: a 16dp icon with a 16dp hit area is a control the user misses half the time.
 * [label] is the accessibility name and the thing to grep for.
 */
@Composable
internal fun QueueAction(icon: Int, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(icon),
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** The chip above the pill: a thumbnail, the name, and a way out of it. */
@Composable
internal fun AttachmentChip(attachment: Attachment, onRemove: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // The same inset the queue rows and the board above use: the strip and the rows under it are
    // one column of things belonging to the field. 多附件后内缩交给外面那一排 Row（每张 chip 自带
    // 会在一排里叠出参差的间距）。
    Row {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = scheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
        ) {
            Row(
                modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val preview = attachment.preview
                var full by remember { mutableStateOf(false) }
                // The picture and its name are one tap target; 移除 stays its own button beside
                // them. Making the whole chip clickable would put "preview" and "delete" on the
                // same 26dp of glass, which is how a user deletes what they meant to look at.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = if (preview != null) {
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(),
                                onClick = { full = true },
                            )
                    } else {
                        Modifier
                    },
                ) {
                if (preview != null) {
                    Image(
                        bitmap = preview,
                        contentDescription = "查看图片",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                } else {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_file),
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = attachment.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 190.dp),
                )
                }
                // The attachment's own bitmap, not a scaled-down copy: this is the picture the user
                // picked, at the size it was stored, so opening it shows what will be sent.
                if (full && preview != null) {
                    ImagePreviewDialog(
                        bitmap = preview,
                        contentDescription = attachment.label,
                        keepKeyboard = true,
                    ) { full = false }
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(26.dp)) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_x),
                        contentDescription = "移除",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}

/**
 * What the ring draws, and whether the number is the endpoint's own or this app's estimate.
 *
 * Four sources, in one order, and the order is the whole point:
 *
 * 1. [liveReported] — the count the endpoint itself sent for the round in flight. The only
 *    honest measure of 「上下文用了多少」 (see `StreamStats.lastPromptTokens`).
 * 2. [newestStored] — the newest round in the transcript that carries a count. This is what the
 *    ring reads on a conversation that was just opened, and between the start of a round and
 *    the endpoint's usage block at its end.
 * 3. [liveEstimate] — the run's own character-based estimate of the wire, used while a round is
 *    going and before its usage block lands.
 * 4. [transcriptEstimate] — the transcript's own size, for a conversation that is *not* running
 *    and whose stored rounds carry no counts at all: a gateway that ignores
 *    `stream_options.include_usage` (see LlmClient's own note), or rows written before the counts
 *    existed. Without it the card was empty in exactly that case — every line zero, 「暂无数据」
 *    on a conversation with plenty of text in it — which is the report this answers.
 *
 * The last two are estimates, so [estimated] is set and the card writes 「约」: a proportion is not
 * a price, but a proportion that says 6% beats a blank.
 */

/**
 * The glyph for an approval mode, from the drawables the app already ships.
 *
 * A tick when tools run on their own, a person-with-shield while every call is put to the user,
 * an alert shield when they are refused. The icon is the state, which is what makes the row's
 * button readable without opening it.
 */
internal fun approvalIcon(mode: ApprovalMode): Int = when (mode) {
    ApprovalMode.AUTO -> R.drawable.ic_circle_check
    ApprovalMode.ASK -> R.drawable.ic_shield_user
    ApprovalMode.DENY -> R.drawable.ic_shield_alert
}

/** The reading for the four sources above; 0 means there is nothing to draw at all. */

/** True while the last item is on screen — the state that lets a reply auto-scroll. */
internal fun LazyListState.atEnd(slack: Int = 24): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return true
    return last.index >= info.totalItemsCount - 1 &&
        last.offset + last.size <= info.viewportEndOffset + slack
}

/**
 * 抬手后守护窗还开多久。选择手柄在抬手那一刻才出现、框架的 bringIntoView 跟着落在抬手后，
 * 400ms 够盖住它那次程序滚动，又短到不会跟用户随后的正常滚动打架。
 */
internal val LONG_PRESS_SETTLE_MS = 400L

/**
 * The air the composer leaves under its pill, in dp.
 *
 * Named because two places have to agree on it: the composer's own bottom padding, and the room
 * the transcript leaves for the stack (the caller's stackFootprint). They were a literal 12.dp
 * in one of them and a measured number in the other, which is how the two drifted a frame apart.
 */
internal val COMPOSER_BOTTOM_PAD = 12.dp

/**
 * The size of a viewport change that is a jump rather than a frame of an animation.
 *
 * The platform animates the IME inset frame by frame where it can — deltas of a few pixels, and
 * the list follows them one for one, which is what makes the motion match the keyboard. A ROM
 * that hands the whole inset over at once produces one delta of several hundred pixels instead,
 * and that is the one worth animating.
 */
internal const val KEYBOARD_JUMP_PX = 40f

/** Matches the platform's own keyboard animation, so the two read as one motion. */
internal const val KEYBOARD_ANIM_MS = 220

/** How many queued lines are drawn before the rest collapse into one count. */
internal const val STEER_ROWS_SHOWN = 3

/**
 * The draft's `attachmentUri` for a picture taken back out of the queue by 「修改」.
 *
 * A marker, not a uri: the picture lives in the view model's attachment cache and nowhere else, so
 * this exists only to make the composer re-read that cache (its `remember` is keyed on the draft's
 * uri and name). After a restart the cache is empty and the chip is simply absent, which is the
 * honest outcome — the alternative is a draft pointing at a uri that cannot be read.
 */
internal const val QUEUED_ATTACHMENT_URI = "queued://steer-image"

/** What the restored picture is called in the composer's chip. */
internal const val QUEUED_IMAGE_LABEL = "图片"

/** Target width of a queue row's thumbnail, in pixels: the row draws it at 24dp. */
internal const val THUMB_PX = 96

/**
 * Target width for a picture opened from a queue row, in pixels.
 *
 * Larger than any phone's screen is tall, so nothing is lost on the way in, while still leaving the
 * decoder a bound: a 12-megapixel photo decoded at full size is ~48 MB and an OOM on a cheap device.
 */
internal const val FULL_PREVIEW_PX = 2160

/**
 * How many board rows are drawn above the queue — the same three as the queue, for the same
 * reason: this stack sits over the field the user is typing into, and a long plan must not
 * push that field off the screen. The tally covers the items that do not fit.
 */

internal const val ATTACHMENT_MAX_WIDTH = 1280

internal fun readAttachment(context: Context, uri: Uri, kind: AttachKind): Attachment? = when (kind) {
    AttachKind.IMAGE -> decodeImage(context, uri)
    AttachKind.FILE -> importFile(context, uri)
}

internal fun decodeImage(context: Context, uri: Uri): Attachment? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

    var scale = 1
    while (bounds.outWidth / scale > ATTACHMENT_MAX_WIDTH || bounds.outHeight / scale > ATTACHMENT_MAX_WIDTH) {
        scale *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = scale }
    val bitmap = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: return@runCatching null
    bitmapAttachment(bitmap, displayName(context, uri) ?: "图片")
}.getOrNull()

/** Camera and gallery both land here: one JPEG, small enough to send every turn. */
internal fun bitmapAttachment(bitmap: Bitmap, label: String = "照片"): Attachment {
    val scaled = if (bitmap.width > ATTACHMENT_MAX_WIDTH) {
        val ratio = ATTACHMENT_MAX_WIDTH.toFloat() / bitmap.width
        Bitmap.createScaledBitmap(
            bitmap,
            ATTACHMENT_MAX_WIDTH,
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    } else {
        bitmap
    }
    val bytes = java.io.ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 82, bytes)
    return Attachment(
        base64 = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP),
        label = label,
        preview = scaled.asImageBitmap(),
    )
}

/**
 * Decodes stored base64 back into a bitmap, off the main thread.
 *
 * Used by the queue row's 24dp thumbnail and by 「修改」, which puts the picture back into the
 * composer: both hold bytes, not a uri, so neither can go through [readAttachment]. Downsampled to
 * about [target] pixels wide, because the callers draw it small and the full JPEG would cost
 * megabytes per row. Null when the bytes are not an image, which every caller treats as "no
 * picture" rather than as an error worth showing.
 */
internal suspend fun decodeAttachment(base64: String, target: Int = 512): Bitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return@runCatching null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= target) sample *= 2
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }.getOrNull()
    }

/**
 * A picked file is copied into the app's own folder, because the agent reads absolute
 * paths and a `content://` handle means nothing to it. The path goes into the question.
 */
internal fun importFile(context: Context, uri: Uri): Attachment? = runCatching {
    val name = displayName(context, uri) ?: "文件"
    val inbox = File(context.getExternalFilesDir(null) ?: context.filesDir, "inbox").apply { mkdirs() }
    val target = File(inbox, name)
    context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    } ?: return@runCatching null
    Attachment(base64 = "", label = target.absolutePath, filePath = target.absolutePath)
}.getOrNull()

internal fun displayName(context: Context, uri: Uri): String? {
    if (uri.scheme == "file") return uri.lastPathSegment
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
}

/**
 * Rewrites a message. A question can also be re-asked from the edited text, which
 * throws away everything the model said afterwards — those answers were written for
 * a prompt that no longer exists.
 */
@Composable
internal fun EditDialog(
    message: StoredMessage,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onResend: ((String) -> Unit)?,
) {
    var draft by remember(message.id) { mutableStateOf(message.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (message.role == Roles.USER) "修改这条消息" else "修改这条回复") },
        text = {
            Column {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp, max = 300.dp),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                if (message.role == Roles.USER) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "「重新发送」会丢掉这条之后的所有内容，让模型重新回答。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (onResend != null) {
                TextButton(
                    onClick = { onResend(draft) },
                    enabled = draft.isNotBlank(),
                ) { Text("重新发送") }
            } else {
                TextButton(onClick = { onSave(draft) }, enabled = draft.isNotBlank()) { Text("保存") }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onResend != null) {
                    TextButton(onClick = { onSave(draft) }, enabled = draft.isNotBlank()) {
                        Text("只保存")
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/**
 * The one full-screen picture viewer: a bubble's screenshot, the attachment chip above the field and
 * a queued line's thumbnail all open this.
 *
 * Shared rather than copied because it is not merely a Dialog: the black scrim, the pinch-and-pan
 * transform and tap-anywhere-to-close are the behaviour the user already knows from the transcript,
 * and a second implementation would drift from it. A `Dialog` and not a focusable overlay, so it
 * cannot take the keyboard away from the field — the same rule the composer's own cards follow.
 */
@Composable
internal fun ImagePreviewDialog(
    bitmap: ImageBitmap,
    contentDescription: String,
    /**
     * Draw it without taking window focus, for the two callers that sit right above the field the
     * user is typing in — the attachment chip and the queued rows. A focusable window takes the
     * keyboard away, and the composer would jump under the user's hands for a tap meant to look at a
     * picture. The transcript's own screenshots keep the ordinary dialog.
     */
    keepKeyboard: Boolean = false,
    onDismiss: () -> Unit,
) {
    // Reading a shot needs the whole screen and a pinch.
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformable = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 4f)
        offset = if (scale > 1f) offset + pan else Offset.Zero
    }
    val viewer: @Composable () -> Unit = {
        // 手势返回也要关（用户：「预览图片的时候，手势返回为什么不会关闭图片」）。keepKeyboard 那条
        BackHandler(onBack = onDismiss)
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.9f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = contentDescription,
                modifier = Modifier
                    .fillMaxSize()
                    .transformable(transformable)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
            )
        }
    }
    if (keepKeyboard) {
        // Focus-less, like the composer's own cards: it cannot see BACK, which is why the scrim
        // above closes on a tap anywhere — the same trade the model sheet makes.
        Popup(properties = PopupProperties(focusable = false)) { viewer() }
    } else {
        Dialog(onDismissRequest = onDismiss) { viewer() }
    }
}

/**
 * 已解码截图的**进程级**小缓存（4 张）。
 *
 * 为什么是进程级而不是 `remember`：`remember` 随组合销毁 —— 列表项滚出视口就丢，
 * 滚回来就要重新解码一张 1080p（几十毫秒）。用户"滑上滑下很慢"有一部分就是它。
 */
// 16 张：滚动一屏常见 2~4 张截图，4 张一进一出等于每次都重新解码（跳变+耗电）。
private val decodedImages = DecodedImageCache(capacity = 16)

@Composable
internal fun ImageBlock(base64: String) {
    // Decoding a 1080p screenshot costs tens of milliseconds; doing it inside
    // composition stalls the frame and can ANR while the list scrolls.
    var bitmap by remember(base64) { mutableStateOf<ImageBitmap?>(null) }
    var full by remember { mutableStateOf(false) }
    LaunchedEffect(base64) {
        val key = decodedImages.keyOf(base64)
        // 命中缓存直接显示：滚出视口再滚回来时不必重新解码（issue #38 方向 7）。
        (decodedImages.get(key) as? ImageBitmap)?.let {
            bitmap = it
            return@LaunchedEffect
        }
        bitmap = withContext(Dispatchers.Default) {
            runCatching {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                // **只解析尺寸，先算降采样倍率**：显示上限 240dp（约 720px），
                // 直接解 1080p 原图既慢又占内存，而这一张图最终只会显示成那么高。
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val sample = ImageDecode.sampleSizeFor(
                    width = bounds.outWidth,
                    height = bounds.outHeight,
                    maxHeight = 720,
                )
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
            }.getOrNull()
        }
        bitmap?.let { decodedImages.put(key, it) }
    }
    // **解码期间的占位**（滚动跳变根因之一）：解码完成前这里原来什么都不画，
    // 行高 0 → 解码完长到 ≤240dp，整列被顶一下。截图几乎都是竖长图（Fit 后顶满
    // 240dp 上限），用 240dp 占位绝大多数情况下高度零变化；横图有小幅差值可接受。
    Spacer(Modifier.height(6.dp))
    val shape = RoundedCornerShape(12.dp)
    if (bitmap == null) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
    } else {
        val decoded = bitmap
        if (decoded != null) {
            Image(
                bitmap = decoded,
                contentDescription = "工具截图",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .clip(shape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(),
                        onClick = { full = true },
                    ),
            )
            if (full) ImagePreviewDialog(decoded, contentDescription = "工具截图") { full = false }
        }
    }
}


/**
 * 行内时间戳：`HH:mm`，超过一天则 `M月d日 HH:mm`。
 *
 * **格式化器按线程复用**（性能优化，输出逐字节不变）：`SimpleDateFormat` 的构造很贵（解析 pattern、
 * 建符号表），而这段代码原来**每次调用都 new 一个**，被消息时间戳与导航条标签反复调用。
 * 用 `ThreadLocal` 而不是共享单例，是因为 `SimpleDateFormat` 本身不是线程安全的。
 */
private val timeFormatShort = ThreadLocal.withInitial {
    SimpleDateFormat("HH:mm", Locale.getDefault())
}
private val timeFormatWithDate = ThreadLocal.withInitial {
    SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())
}

internal fun formatTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val withDate = now - timestamp >= 24 * 60 * 60 * 1000L
    val fmt = (if (withDate) timeFormatWithDate else timeFormatShort).get()!!
    return fmt.format(Date(timestamp))
}

internal fun formatDuration(millis: Long): String =
    if (millis < 1000) "${millis}ms" else String.format(Locale.getDefault(), "%.1fs", millis / 1000.0)

/** 12 秒 / 1 分 03 秒 — how the run caption counts, never more than two units. */
internal fun formatRunElapsed(millis: Long): String {
    val seconds = millis / 1000
    return if (seconds < 60) {
        "$seconds 秒"
    } else {
        "${seconds / 60} 分 ${(seconds % 60).toString().padStart(2, '0')} 秒"
    }
}

