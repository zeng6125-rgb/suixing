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
import androidx.compose.ui.input.nestedscroll.nestedScroll
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

/** The composer's status line: plain 13sp text with no container of its own. */
@Composable
internal fun statusLineStyle(): TextStyle = MaterialTheme.typography.labelMedium

/**
 * 思考强度 beside the model chip: the same levels as the settings slider (REASONING_LEVELS),
 * written
 * through on selection. It lands on the provider the active assistant is bound to when
 * that provider owns its sampling knobs, and on the global setting otherwise — the same
 * precedence [AppSettings.forAssistant] resolves with.
 */
/**
 * The one sheet both composer captions open: which model answers and how hard it
 * thinks are one decision, so they share one surface — model name and level badge on
 * top, the six-step slider in the middle, the provider's enabled models as radio rows
 * below. Every change writes through at once; there is no confirm button to forget.
 *
 * It hangs off the composer's zero-height anchor box (see `ComposerStack`, the `Box` at the
 * top of the stack): the sheet opens upward from there and its bottom edge lands 8dp above
 * that box, so it never covers the field it belongs to. Horizontally its right edge lines up
 * with the composer's content edge, and it is nudged back inside the screen when that would
 * hang it off an edge. Right-aligned because centring it on the capsule put the sheet's mass
 * left of the ring, which the user read as left-aligned.
 */
/** How long a popup card's exit animation runs before the popup is taken down. */
internal const val MENU_EXIT_MS = 140L

/**
 * The app's one popup card: rounded, shadowed, and animated in and out.
 *
 * Every menu on this screen wears it — the 生成设置 sheet and the 「+」 attachment menu — so
 * they read as one control instead of as two unrelated leftovers. The caller keeps passing
 * [visible]; this keeps the popup composed until the exit animation has played, because a
 * popup taken down the moment it is dismissed has no time to animate out.
 */
@Composable
internal fun MenuCard(
    visible: Boolean,
    onDismiss: () -> Unit,
    alignment: Alignment,
    offset: IntOffset,
    modifier: Modifier = Modifier,
    /**
     * Whether the popup's own window takes focus.
     *
     * False by default, and every caller in this file passes it explicitly. That default is the
     * lesson of the 「点圆环把输入法关了」 report: a focusable popup window takes the keyboard away
     * from the field, the shell then drops the whole composer by the keyboard's height, and a card
     * anchored 8dp above a button of that row is left floating a keyboard above it — the card the
     * user photographed as 偏上. Only a card whose own subject *is* the keyboard (a field inside
     * it) should ask for `true`; everything hanging off this screen's bars is focus-less, which
     * means it can see neither a tap outside itself nor BACK, so both live with the caller: see the
     * scrim and BackHandler in [Composer], the ring's card, and the metric card's call site.
     */
    focusable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var keepMounted by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            keepMounted = true
        } else {
            delay(MENU_EXIT_MS)
            keepMounted = false
        }
    }
    // A plain return from this composable, before any group is opened: while the card is
    // closed there is no popup at all, and nothing to intercept a touch with.
    if (!keepMounted) return
    Popup(
        alignment = alignment,
        offset = offset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = focusable),
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.92f),
            exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.92f),
        ) {
            Column(
                modifier = modifier
                    .shadow(
                        elevation = 8.dp,
                        shape = RoundedCornerShape(20.dp),
                        ambientColor = Color.Black.copy(alpha = 0.25f),
                        spotColor = Color.Black.copy(alpha = 0.25f),
                    )
                    .clip(RoundedCornerShape(20.dp))
                    .background(scheme.surface)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                content = content,
            )
        }
    }
}

/** The margin a popup keeps from the window's own edges. */
internal val POPUP_EDGE = 8.dp

/** 取消追底的手指位移阈值（用户：「取消的最小方法就是往上移动一点就需要取消」）。 */
internal const val CANCEL_FOLLOW_PX = 4f

/** 弹窗离屏幕边缘的**呼吸感**（用户：「弹窗位置不要贴屏幕边缘，留点呼吸感」）。 */
internal val POPUP_EDGE_AIR = 16.dp

/** 圆环面板离屏幕右边缘的距离（实测面板停在 63dp 处后定的值，见 ContextRing 里的说明）。 */
internal val RING_POPUP_EDGE = 12.dp

/**
 * A popup's width cap: the width it wants, never wider than the window it opens in.
 *
 * [windowWidth] is `screenWidthDp`, the window's own width — in split screen the display's width
 * would hand a card a size the window does not have. A card wider than its window is a card whose
 * right half is off the screen: the model sheet's 340dp needs a 356dp window to fit.
 */
internal fun popupCap(nominal: Dp, windowWidth: Dp): Dp = minOf(nominal, windowWidth - POPUP_EDGE_AIR * 2)

/**
 * How far a popup anchored at [anchorLeft] has to move to stay inside the window.
 *
 * The card is [width] wide and opens to the right of its anchor, so only its left edge can be
 * moved; [edgePx] of air is kept on both sides. The same rule the model sheet's own nudge uses,
 * shared here so the card hung off the row's left end obeys it too.
 */
internal fun popupNudge(anchorLeft: Float, width: Int, windowPx: Float, edgePx: Float): Float {
    if (width <= 0 || anchorLeft == 0f) return 0f
    val left = anchorLeft.coerceIn(edgePx, (windowPx - edgePx - width).coerceAtLeast(edgePx))
    return left - anchorLeft
}

/**
 * [popupNudge] for a card centred on its anchor instead of left-aligned to it — the ring's own
 * detail card, which is centred on a ring that sits at the row's right end.
 */
internal fun popupNudgeCentred(anchorCentre: Float, width: Int, windowPx: Float, edgePx: Float): Float {
    if (width <= 0 || anchorCentre == 0f) return 0f
    val desired = anchorCentre - width / 2f
    val left = desired.coerceIn(edgePx, (windowPx - edgePx - width).coerceAtLeast(edgePx))
    return left - desired
}

/** One row of a popup menu: icon, label, and the whole row as the target. */
@Composable
internal fun MenuItemRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    /** Ticked on the right when this row is the one in force; the caller says which. */
    selected: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            )
            .padding(vertical = 9.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_check),
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * The thinking-strength slider: a gradient-filled track with a round handle, drawn here
 * rather than taken from Material.
 *
 * The stock slider's hairline rail and its flanking icons were the look the user called
 * old-fashioned; this one carries the app's own colours and nothing else. The value is a
 * level index, so a drag snaps to the nearest step as it goes.
 */
@Composable
internal fun LevelSlider(levelIndex: Int, levels: Int, onChange: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val last = (levels - 1).coerceAtLeast(1)
    var width by remember { mutableStateOf(0) }
    val fraction = (levelIndex.toFloat() / last).coerceIn(0f, 1f)
    val pick: (Float) -> Unit = { x ->
        if (width > 0) {
            val index = (x / width * last).roundToInt().coerceIn(0, last)
            if (index != levelIndex) onChange(index)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .onSizeChanged { width = it.width }
            .pointerInput(levels) { detectTapGestures { spot -> pick(spot.x) } }
            .pointerInput(levels) {
                detectHorizontalDragGestures { change, _ -> pick(change.position.x) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxWidth().height(32.dp)) {
            val trackHeight = 8.dp.toPx()
            val radius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
            val top = (size.height - trackHeight) / 2f
            drawRoundRect(
                color = scheme.surfaceVariant,
                topLeft = Offset(0f, top),
                size = Size(size.width, trackHeight),
                cornerRadius = radius,
            )
            // Filled up to the handle, in a brand gradient: a flat colour read as a
            // progress bar rather than as a control that can be moved.
            val filled = (size.width * fraction).coerceAtLeast(trackHeight)
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    listOf(scheme.primary.copy(alpha = 0.55f), scheme.primary),
                ),
                topLeft = Offset(0f, top),
                size = Size(filled, trackHeight),
                cornerRadius = radius,
            )
            val handle = 11.dp.toPx()
            val centre = Offset(
                x = (size.width * fraction).coerceIn(handle, (size.width - handle).coerceAtLeast(handle)),
                y = size.height / 2f,
            )
            // A surface ring inside the handle, so it stays visible where it overlaps the
            // filled part of the rail.
            drawCircle(color = scheme.surface, radius = handle, center = centre)
            drawCircle(color = scheme.primary, radius = handle - 2.dp.toPx(), center = centre)
        }
    }
}

@Composable
internal fun GenerationSheet(
    settings: AppSettings,
    /** The persona the conversation runs with: the sheet must name the endpoint a run
     *  will actually reach, and the app-wide active persona is a different one. */
    assistantId: String?,
    /** This conversation's own pick, or null while it follows the persona and defaults. */
    conversationProviderId: String?,
    conversationModel: String?,
    /** The conversation's own thinking level, if it ever picked one. */
    conversationReasoningEffort: String?,
    /** Writes a level onto this conversation alone; see the view model's chooseReasoning. */
    onChooseReasoning: (String) -> Unit,
    /** The model button's centre, in window pixels; 0 until the composer has measured it. */
    /** Whether the card is open. It animates in and out on this, so it stays composed. */
    visible: Boolean,
    /**
     * The model capsule's right edge in window pixels, or 0 before the composer has measured
     * it. The sheet is right-aligned to this edge rather than to the free space around the
     * capsule: the free space is wider than the button whenever the model name is short, and
     * the card then sat that far to the right of the control it belongs to.
     */
    capsuleRight: Float,
    onPickModel: (ProviderConfig, String) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val assistant = assistantId?.let { settings.assistant(it) } ?: settings.activeAssistant()
    // One chain for the whole screen — this conversation's pick, then the persona's pins,
    // then the app-wide default — so the header, the tick and the request all agree.
    val choice = settings.choiceFor(conversationProviderId, conversationModel, assistant)
    // The endpoint the choice lands on; a conversation that never picked falls through to
    // the base url the app is talking to, and a provider with anything enabled is the last
    // witness. Null only when there is genuinely nothing configured.
    val provider = settings.providers.firstOrNull { it.id == choice.providerId }
        ?: settings.providers.firstOrNull {
            it.baseUrl.trimEnd('/') == choice.baseUrl.trimEnd('/')
        }
        ?: settings.providers.firstOrNull { it.enabled.isNotEmpty() }
    val wire = reasoningWire(settings, provider, conversationReasoningEffort)
    val levelIndex = REASONING_LEVELS.indexOfFirst { it.first == wire }.coerceAtLeast(0)
    // Every endpoint with ticked models can offer one: this picker is the only place to
    // switch endpoints without a detour through settings, so it lists them all instead
    // of only the endpoint the assistant happens to point at. Inside one endpoint the
    // ids split by upstream vendor (an aggregating proxy prefixes them).
    val offers = remember(settings.providers, choice.model, choice.providerId) {
        modelOffers(settings.providers, choice.model, provider)
    }
    val density = LocalDensity.current
    // The anchor's own left edge and the sheet's own width, both measured: the first is
    // where the popup's alignment starts counting from, the second is what decides how far
    // a centred sheet would hang off an edge.
    var anchorLeft by remember { mutableStateOf(0f) }
    var anchorRight by remember { mutableStateOf(0f) }
    var sheetWidth by remember { mutableStateOf(0) }
    val windowWidth = LocalConfiguration.current.screenWidthDp.dp
    val screenPx = with(density) { windowWidth.toPx() }
    val edge = with(density) { POPUP_EDGE_AIR.toPx() }
    // Right-aligned to the capsule that opened it, not centred: centred put the sheet's mass to
    // the left of the ring, which read as left-aligned. Falls back to the free space's own right
    // edge until the capsule has been measured — the two agree whenever the name is long enough
    // to fill the row, which is the case the old anchor was written for.
    val nudge = if (sheetWidth <= 0 || anchorLeft == 0f) {
        0f
    } else {
        val width = sheetWidth.toFloat()
        val right = if (capsuleRight > 0f) capsuleRight else anchorRight
        val left = (right - width)
            .coerceIn(edge, (screenPx - edge - width).coerceAtLeast(edge))
        left - anchorLeft
    }
    // Zero-height, full-width probe inside the composer's anchor box: it reads that box's
    // edges, which are the origin the popup's own alignment is measured from.
    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                anchorLeft = it.boundsInWindow().left
                anchorRight = it.boundsInWindow().right
            },
    )
    // Which page the sheet is on: the light one — the model that will answer and how hard it
    // thinks — or the full list of models. The list became a page of its own because the user
    // asked for the first page to stay small.
    var picking by remember { mutableStateOf(false) }
    // 关掉就回第一页（用户：「选了模型进列表页，关掉再开还停在列表页，要点返回才到思考强度页」）。
    // 这张卡关着也保持 composed（为了进出动画），页状态不会随卸载重置，所以显式在 visible 落
    // false 时清掉。只清页、不清 [folded]：折叠是用户对着列表做的选择，重开还在更顺手。
    LaunchedEffect(visible) {
        if (!visible) picking = false
    }
    // Providers whose list is folded away. The one answering this conversation starts open:
    // the list is what the page is for, and folding everything would hide it.
    val folded = remember { mutableStateMapOf<String, Boolean>() }
    val modelLabel = provider?.displayModel(choice.model)
        ?.takeIf { it != choice.model }
        ?: choice.model.substringAfter('/').ifBlank { "选模型" }
    MenuCard(
        // Anchored to the composer's own top edge, not a sheet from the screen's bottom:
        // the picker is a control of the row that opened it. Start-aligned plus [nudge] is
        // how the card ends up centred on the model button.
        visible = visible,
        onDismiss = onDismiss,
        alignment = Alignment.BottomStart,
        offset = with(density) { IntOffset(nudge.roundToInt(), -8.dp.roundToPx()) },
        // Focus-less: opening this card must not take the keyboard off the field, or the
        // composer falls by the keyboard's height under a card anchored to where it used to
        // be. See [MenuCard]'s focusable and the scrim in [Composer].
        focusable = false,
        // One width per page. Page one is one model name and one slider, and 340dp is 94% of a
        // 360dp screen — at that width the card read as a full-width panel rather than as
        // something hanging off the button that opened it, so page one keeps 300dp (content
        // 264dp after MenuCard's own 18dp padding either side). The list page is the one that
        // needs the room: its provider and vendor headings are single names the user is reading
        // to find a model, and 264dp of content cut the long ones at the card's edge. The 340dp
        // that first fixed this is back, but only while the list is up.
        modifier = Modifier
            .widthIn(max = popupCap(if (picking) 340.dp else 300.dp, windowWidth))
            .onSizeChanged { sheetWidth = it.width },
    ) {
        if (!picking) {
            // Page one: one big name to tap, one slider to drag, nothing else.
            //
            // Hugs its own content instead of the card's cap. The only control here that
            // could have decided the width is the slider, and a slider is as wide as what it
            // slides on — so a fillMaxWidth slider pinned the card to its maximum, which is
            // what made a card holding two short lines look like a panel. widthIn is written
            // first on purpose: the intrinsic size is then coerced into the floor and the cap,
            // so a short model id gives a narrow card and a long one is still cut off at the
            // cap rather than at the screen's edge.
            Column(
                modifier = Modifier
                    .widthIn(min = 200.dp, max = 260.dp)
                    .width(IntrinsicSize.Max),
            ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(),
                    ) { picking = true }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = modelLabel,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_chevron_right),
                    contentDescription = "选择模型",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "思考强度",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Surface(shape = CircleShape, color = scheme.primaryContainer) {
                    Text(
                        text = REASONING_LEVELS[levelIndex].second,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            LevelSlider(levelIndex = levelIndex, levels = REASONING_LEVELS.size) { index ->
                // The level belongs to this conversation, like the model pick beside it:
                // writing it to the app-wide default here is what re-timed every other chat.
                onChooseReasoning(REASONING_LEVELS[index].first)
            }
            }
        } else {
            // Page two: every endpoint's models, the one that answers this conversation first
            // and already open.
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { picking = false }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_arrow_left),
                        contentDescription = "返回",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "选择模型",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                )
            }
            Spacer(Modifier.height(6.dp))
            // No endpoint has anything ticked: the picker has nothing to offer, so the
            // way out is the settings page. Asking the offers list would hide that way
            // out as soon as the active model is the only thing left in it.
            if (settings.providers.none { it.enabled.isNotEmpty() }) {
                Text(
                    text = "还没有启用任何模型。",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onOpenSettings, shape = RoundedCornerShape(12.dp)) {
                    Text("去设置里拉取")
                }
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    offers.forEach { (owner, ids) ->
                        // Folded per endpoint, with the one answering this conversation left
                        // open: the page exists to show models, and a column of folded
                        // headers would hide the very thing it is for.
                        val open = !(folded[owner.id] ?: (owner.id != choice.providerId))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(),
                                ) { folded[owner.id] = open }
                                // 4dp of side air, not none: this row clips itself to
                                // shapes.small for its ripple, and a label sitting flush against
                                // that edge had the descender of a name like "qwen" cut by the
                                // corner arc — worse the rounder the 外观 corner setting is. The
                                // vendor rows below are inset by the same 4dp.
                                .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = owner.shortLabel,
                                // bodyMedium, the size the model rows themselves are set in:
                                // two levels of heading over the rows read as a list of three
                                // unrelated sizes when they were smaller than what they head.
                                // Weight and colour are what separate the levels now — SemiBold
                                // in the accent colour here, onSurfaceVariant on the vendor
                                // rows below.
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = scheme.primary,
                                // Two lines and an ellipsis, not "it wraps by itself": a
                                // heading here is usually a host or a `vendor/model` pair, one
                                // ASCII run with no break opportunity inside it, and such a run
                                // cannot wrap — it was cut at the card's edge with nothing to
                                // say it had been. Two lines let a real name through; the
                                // ellipsis is the honest signal for the ones that still do not
                                // fit. `fill = false` stays, so the chevron keeps the right edge
                                // either way.
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Icon(
                                imageVector = if (open) {
                                    ImageVector.vectorResource(R.drawable.ic_chevron_up)
                                } else {
                                    ImageVector.vectorResource(R.drawable.ic_chevron_down)
                                },
                                contentDescription = if (open) "折叠" else "展开",
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        // A folded endpoint simply has nothing to draw; an empty list keeps
                        // the block below identical for both states. The type is written out
                        // because `emptyList()` on its own gives the compiler nothing to
                        // infer from — `groupModels` returns (group, ids) pairs.
                        val groups: List<Pair<String, List<String>>> =
                            if (open) groupModels(ids) else emptyList()
                        groups.forEach { (group, groupIds) ->
                            if (groups.size > 1) {
                                Text(
                                    text = group,
                                    // Same size class as the provider titles above and as the
                                    // model rows below: one size for the whole list, with the
                                    // colour and the weight telling the levels apart. Two lines
                                    // and an ellipsis for the same reason as the provider title
                                    // above: a vendor id is one unbreakable ASCII run, so with no
                                    // cap it was cut at the card's edge with no sign of it, and
                                    // the user reported exactly that.
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                                )
                            }
                            groupIds.forEach { id ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.small)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = ripple(),
                                        ) { onPickModel(owner, id) }
                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        // Ticked on the resolved choice, never on the raw
                                        // settings: a conversation that picked its own model
                                        // shows that one, and one that never picked shows
                                        // whatever the persona and the default resolve to.
                                        selected = owner.id == choice.providerId &&
                                            id == choice.model,
                                        onClick = null,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = owner.displayModel(id),
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * What the dictation card is doing.
 *
 * The card used to be a single `listening` flag, which is why a recognizer that refused the
 * app closed the card without a word: there was no state in which a failure could be shown.
 */
internal sealed interface VoicePhase {
    /** Between startListening and onReadyForSpeech: the recognizer is warming up. */
    object Preparing : VoicePhase

    object Listening : VoicePhase

    /** Speech ended; the recognizer is turning it into text, and the mic is closed. */
    object Thinking : VoicePhase

    /** It failed. [message] says why in plain words, [code] keeps the raw code for retry. */
    data class Failed(val message: String, val code: Int) : VoicePhase
}

/**
 * The recognizer's error codes in words a user can act on.
 *
 * Kept even though the in-app recognizer is gone: the system-activity fallback still reports
 * codes through this table when the ROM's own screen answers with one.
 */
/** What the next question carries along: an image for the model, or a file on disk. */

/** 附件与正文的合流（用户：「文件的注入不生效，随对话发出就消失了」）。 */
internal fun withAttachment(attachments: List<Attachment>, text: String): Pair<String, List<String>> {
    val paths = attachments.mapNotNull { it.filePath }
    val body = if (paths.isEmpty()) {
        text
    } else {
        val note = paths.joinToString("\n") { "［附件文件］$it" }
        if (text.isBlank()) note else "${text.trimEnd()}\n\n$note"
    }
    return body to attachments.mapNotNull { it.base64.takeIf { b -> b.isNotEmpty() } }
}

internal enum class AttachKind { IMAGE, FILE }

/**
 * The context window as a ring, left of the mic.
 *
 * A gauge rather than a number: 22dp of arc is all the room this row has, and the figures are
 * one tap away. The arc turns red at 80% of the window — where the engine starts folding the
 * conversation and where the next question may no longer fit — so the colour carries the
 * warning and the card carries the detail. With no window configured (0 = unlimited) the ring
 * is still drawn, as a neutral track with no arc, and the card says so and offers the way to
 * settings: the user asked for a gauge, and "this model has no window set" is exactly the kind
 * of thing a gauge should be able to tell them. No denominator is invented — a made-up
 * fraction would be decoration pretending to be data.
 *
 * The touch target is 26dp, not the 48dp the primary controls use: this row also carries the
 * model caption, the mic and the send button, and at 48dp the caption would be too narrow to
 * read on a 360dp-wide screen. The arc is 22dp of that, which is what leaves room for the
 * percentage in the middle: 22dp less the 2dp stroke either side is 18dp of clear space, and
 * three digits at 7sp measure about 11.7dp by 8dp.
 */
@Composable
internal fun ContextRing(
    used: Int,
    /** True when [used] is this app's own estimate; the card says 「约」 when it is. */
    estimated: Boolean = false,
    window: Int,
    /** Everything this conversation has ever spent, prompt + completion. */
    sessionTokens: Int,
    /** The text-based floor for the cumulative row, when nothing in the session was priced. */
    sessionEstimate: Int = 0,
    /** That spend in the endpoint's own three parts. */
    sessionSplit: SessionSplit,
    /** How many questions that tally was built from, so 「累计」 cannot read as 「本轮」. */
    sessionTurns: Int,
    sessionPerSecond: Double,
    sessionCachePercent: Double,
    sessionElapsedMs: Long,
    live: StreamStats?,
    /**
     * 面板三行（系统提示词 / 工具定义 / 对话消息）的**当前窗口**读数，不依赖 run：run 中优先用
     * [live] 的实测三块，空闲用这个（打开面板时由 [onPanelOpen] 触发上层按当前上下文算）。
     */
    breakSplit: Triple<Int, Int, Int>? = null,
    /** 打开面板时回调：让上层按当前上下文算一次三行（见 ChatViewModel.refreshContextBreakdown）。 */
    onPanelOpen: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val known = window > 0
    val fraction = if (known) (used.toFloat() / window).coerceIn(0f, 1f) else 0f
    val tone = if (fraction >= 0.8f) scheme.error else scheme.primary
    val track = scheme.surfaceVariant
    // 布局取证要用（见 layoutDiag）。
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    // A focus-less popup does not receive BACK either: without this the press falls through to the
    // activity and finishes it. Same pair the composer's own cards use.
    BackHandler(enabled = open) { open = false }
    val density = LocalDensity.current
    val ringPopupMarginPx = with(density) { RING_POPUP_EDGE.toPx() }
    // The number in the middle, as a whole percent: the arc is a shape and the number is the
    // figure behind it. A window that was never set has no percentage to show (0 = unlimited,
    // not 0%), and 0% is a ring with no arc to read it against.
    val percent = if (known) (fraction * 100).roundToInt() else 0
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = {
                    open = true
                    // 打开即按当前上下文算一次三行（不依赖 run）：空闲时也有得显示。
                    onPanelOpen()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val stroke = 2.dp.toPx()
            val ringSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(stroke / 2f, stroke / 2f)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = ringSize,
                style = Stroke(width = stroke),
            )
            if (known && fraction > 0f) {
                drawArc(
                    color = tone,
                    // From twelve o'clock, clockwise: how a gauge is read.
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = ringSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        // 9sp, two steps up from the 7sp the arc's clear space was first sized for: Bold at 7sp
        // was read as "could be a little bigger" twice, the second time with a figure. Three
        // digits at 9sp are about 15dp of the 18dp of clear space inside the arc, so the
        // longest reading still fits. Bold, not Medium: at this size the user read the Medium
        // weight as thin, and the ring is the only place these digits appear — the extra weight
        // costs nothing elsewhere.
        if (percent > 0) {
            Text(
                text = percent.toString(),
                fontSize = 9.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.Bold,
                color = tone,
                maxLines = 1,
            )
        }
        // The card's anchor: a zero-height box at the ring's top edge, so the popup opens
        // upward from the ring instead of covering the row it belongs to.
        Box(
            Modifier
                .align(Alignment.TopCenter),
        ) {
            // A focusable window takes the keyboard away from the field below — the user asked why
            // tapping the ring closes the input method, and that is the whole of the answer: the
            // popup grabs focus, the IME goes, and the composer is then laid out a keyboard lower
            // while this card stays where it was. The card is focus-less instead, which means its
            // window can see neither a tap outside itself nor BACK; both are caught here, the way
            // the composer's own cards do it (see [MenuCard]'s `focusable`). The scrim is a window
            // of its own, composed before the card: windows stack in composition order, so a scrim
            // composed after it would cover it instead.
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
                    // *显式定位**（不再用 alignment）：真机截图量到面板落在屏幕**左侧**
                    popupPositionProvider = remember(ringPopupMarginPx) {
                        object : PopupPositionProvider {
                            override fun calculatePosition(
                                anchorBounds: IntRect,
                                windowSize: IntSize,
                                layoutDirection: LayoutDirection,
                                popupContentSize: IntSize,
                            ): IntOffset {
                                val margin = ringPopupMarginPx.toInt()
                                val x = (windowSize.width - margin - popupContentSize.width)
                                    .coerceAtLeast(margin)
                                val y = (anchorBounds.top - popupContentSize.height - 8)
                                    .coerceAtLeast(0)
                                return IntOffset(x, y)
                            }
                        }
                    },
                    onDismissRequest = { open = false },
                    properties = PopupProperties(focusable = false),
                ) {
                    Box(
                        Modifier
                            // A focus-less window only receives what lands inside it, and drawing
                            // its own background is not the same as consuming a touch: without
                            // this the tap on the card would fall through to the transcript the
                            // card is floating over.
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {},
                    ) {
                        ContextPanelCard(
                            used = used,
                            window = window,
                            // 实测优先（run 中），空闲用上层按当前上下文算出来的那份。
                            split = live?.let {
                                Triple(it.breakSystem, it.breakTools, it.breakHistory)
                            } ?: breakSplit,
                        )
                    }
                }
            }
        }
    }
}

/** The ring's detail: the numbers a 22dp arc cannot show. */
@Composable
internal fun ContextRingCard(
    used: Int,
    /** True when [used] is this app's own estimate rather than a count the endpoint sent. */
    estimated: Boolean = false,
    window: Int,
    sessionTokens: Int,
    /** The text-based floor for the cumulative row, when nothing in the session was priced. */
    sessionEstimate: Int = 0,
    sessionSplit: SessionSplit,
    sessionTurns: Int,
    sessionPerSecond: Double,
    sessionCachePercent: Double,
    sessionElapsedMs: Long,
    live: StreamStats?,
) {
    val scheme = MaterialTheme.colorScheme
    val known = window > 0
    val percent = if (known) used * 100 / window else 0
    Column(
        modifier = Modifier
            // 264dp, not 240: the value on the 「上下文占用」 line is a sentence of its own
            // (「12.3k / 200k（6%）」, or the 窗口未设置 form) and the old cap ellipsized it.
            // Still inside a 360dp screen's own margin, and still narrower than the
            // transcript's full width. Capped against the window, so a split-screen window
            // gets a card that fits it rather than one that hangs off its edge.
            .widthIn(max = popupCap(264.dp, LocalConfiguration.current.screenWidthDp.dp))
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = Color.Black.copy(alpha = 0.25f),
                spotColor = Color.Black.copy(alpha = 0.25f),
            )
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (used <= 0) {
            // A brand-new conversation has nothing to report yet, and saying so is not the same
            // as saying there is no data: 「暂无数据」 on a chat the user has not typed into yet
            // reads as a fault. Everything else here has been estimated by now, so this branch
            // means an empty transcript and nothing else.
            Text(
                text = if (sessionTurns <= 0) "新会话 · 还没有用量" else "暂无数据",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        } else {
            // Same intrinsic-width trick as the metric cards: the numbers share one right edge
            // without the card asking for the screen's width.
            Column(Modifier.width(IntrinsicSize.Max).widthIn(min = 208.dp)) {
            RingDetail(
                "上下文占用",
                if (known) {
                    // 「约」 only when the number is this app's own estimate: the endpoint's own
                    // count is a fact and reads as one, and an estimate presented as a fact is
                    // the one thing this card must not do.
                    "${if (estimated) "约 " else ""}${shortTokens(used)} / ${shortTokens(window)}（$percent%）"
                } else {
                    // No invented denominator, and no editor either: where the window is
                    // filled in is the settings page, which also fills it from the endpoint's
                    // own context_window when the user has not said otherwise. The ring only
                    // reports.
                    "${if (estimated) "约 " else ""}${shortTokens(used)} · 窗口未设置 · 到设置里填"
                },
            )
            // The conversation's own hit rate, not the newest round's: the user asked for
            // 「总缓存命中率」 and a label of exactly 「缓存命中」. SessionStats.cachePercent is
            // the whole session's cached tokens over its whole prompt, so it is the total this
            // line is for; the newest round's own ratio was the wrong number under the wrong
            // name. It sits here, above the speed and the clock, because it is a reading of the
            // context the line above it is about.
            // Drawn even when it reads 0%: the line used to be skipped whenever the ratio came
            // out at zero, and the user — who had asked for this reading by name — read the
            // missing row as the reading having been dropped. A priced session with no cache
            // hits yet is a 0%, not a gap.
            // 两位小数：整百分比在几千 token 的会话里几乎不动，四舍五入之后看不出缓存到底在不在
            // 起作用。数值本身不取整（SessionStats.cachePercent 是 Double），格式只在这里决定。
            RingDetail(
                "缓存命中",
                remember(sessionCachePercent) {
                    String.format(Locale.getDefault(), "%.2f%%", sessionCachePercent)
                },
            )
            // The rows below are in three groups, in this order: 上下文 (the two above) →
            // 本会话累计 → 速度与时间. They used to be stacked in the order the features landed,
            // which put 「输出速度」 above the total and the two per-round lines at the very bottom
            // — the user asked for the ordering to make sense. The grouping is carried by the
            // names (「累计…」) rather than by a divider or a heading: every row is already a
            // label/value pair, so a separator would add chrome and answer nothing.
            //
            // The per-round pair that used to sit here (「本轮输入」/「本轮输出」) is gone: the user
            // asked for it off, and it was the only consumer of the round's own two figures — the
            // arc's number and these totals come from other sources, so nothing else went with it.
            if (sessionTokens > 0) {
                // 本会话累计: the whole conversation's spend, not the context on screen. Every
                // round re-sends the context, so this is the number a bill would be built from —
                // and the same tally the usage page shows. Labelled 「累计」 rather than 「总量」
                // because it counts each round's re-send, which is why it can dwarf the
                // 「上下文占用」 line above it.
                RingDetail("累计用量", "${fullTokens(sessionTokens.toLong())} tok · $sessionTurns 轮")
                // The split under the total, by the names the user asked for: what the cache
                // could not answer, what it did, and what came back. All three are the whole
                // conversation's, like the total above them.
                RingDetail("未缓存输入", "${fullTokens(sessionSplit.uncached)} tok")
                RingDetail("缓存读取", "${fullTokens(sessionSplit.cached)} tok")
                RingDetail("输出", "${fullTokens(sessionSplit.output)} tok")
            } else if (sessionEstimate > 0) {
                // Nothing was ever priced — a gateway that sends no usage block, or rows written
                // before the counts existed — so the total is the same text-based floor the line
                // above uses, times the turns that re-sent it. 「约」 says what it is, and the
                // split stays out rather than being invented: the cache halves cannot be guessed
                // from a character count, and a made-up 「缓存读取」 is worse than a missing one.
                RingDetail(
                    "累计用量",
                    "约 ${fullTokens(sessionEstimate.toLong())} tok · $sessionTurns 轮",
                )
            }
            // 速度与时间 last: they are about the work, not about the window or the bill.
            if (sessionPerSecond > 0) {
                RingDetail(
                    "输出速度",
                    remember(sessionPerSecond) {
                        "${Tps.format(sessionPerSecond)} tok/s"
                    },
                )
            }
            if (sessionElapsedMs > 0) {
                RingDetail(
                    "总用时",
                    remember(sessionElapsedMs) { formatRunElapsed(sessionElapsedMs) },
                )
            }
            }
        }
    }
}

/**
 * The ring's popup content, restyled to the panel the user handed over as a screenshot:
 * a title row (「上下文已用 N%」 with the 「~used / window」 figure right-aligned), a progress
 * bar, then the live request's three shares as dotted rows with their own proportion bars.
 * The old detail card (占用/缓存/累计/速度/时间) did not go away — it moved to the title
 * capsule's entry ([TopBarContextEntry]); this panel is what the composer's ring opens now.
 */
@Composable
internal fun ContextPanelCard(used: Int, window: Int, split: Triple<Int, Int, Int>?) {
    val scheme = MaterialTheme.colorScheme
    val known = window > 0
    val percent = if (known) used * 100 / window else 0
    Column(
        modifier = Modifier
            .widthIn(max = popupCap(264.dp, LocalConfiguration.current.screenWidthDp.dp))
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = Color.Black.copy(alpha = 0.25f),
                spotColor = Color.Black.copy(alpha = 0.25f),
            )
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (known) "上下文已用 $percent%" else "上下文用量",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurface,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (known) {
                    "~${panelTokens(used)} / ${panelTokens(window)}"
                } else {
                    "~${panelTokens(used)}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
        if (known) {
            LinearProgressIndicator(
                progress = { (percent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // 三行**常驻**（用户：「系统提示词就是当前窗口的 tokens 和占比，不是单个 run 的」）：
        // 数值来自实测（run 中）或按当前上下文的估算（空闲，见 refreshContextBreakdown），
        // 所以打开面板就有；进度条基准是**上下文窗口**（三行占比之和 = 顶部那条的比例）。
        split?.let { (system, tools, history) ->
            val parts = listOf(
                Triple("系统提示词", system, scheme.primary),
                Triple("工具定义", tools, scheme.secondary),
                Triple("对话消息", history, scheme.tertiary),
            )
            Spacer(Modifier.height(4.dp))
            parts.forEach { (label, value, tone) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(tone))
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        // *fill = true**（用户：「进度条没有对齐」）：fill=false 时标签列只占自己
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "~${panelTokens(value)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        // 定宽（用户：「进度条，长度对齐」）：值列宽窄不一会把后面的小条挤得参差；
                        // 定宽后三行的条同起点同长度，右端与顶部进度条对齐。
                        modifier = Modifier.width(44.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(scheme.surfaceContainerHighest),
                    ) {
                        // 基准 = 上下文窗口（用户：「进度条是以上下文窗口为准啊」）：每段条长 = 该段
                        // ÷ 窗口，于是三行加起来就是顶部那条的总占比。窗口未设置（0）时退回三块之和，
                        // 至少还能看构成。
                        val denominator = if (window > 0) window else parts.sumOf { it.second }
                        val fraction = if (denominator > 0) {
                            value.toFloat() / denominator
                        } else {
                            0f
                        }
                        Box(
                            Modifier
                                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(tone),
                        )
                    }
                }
            }
        }
    }
}

/** 「6.9K / 23.9K / 150K / 1M」: one decimal under 100 of a unit, whole above, unit letter capped. */
internal fun panelTokens(v: Int): String {
    val k = v / 1000.0
    val (n, unit) = if (k >= 1000.0) k / 1000.0 to "M" else k to "K"
    val s = String.format(java.util.Locale.US, "%.1f", n)
    return (if (s.endsWith(".0")) s.dropLast(2) else s) + unit
}

