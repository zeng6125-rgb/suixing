package com.suixing.agent.ui.chat

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.suixing.agent.ui.drawer.drawerScrollPriority
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.layout.Layout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.ripple
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.suixing.agent.R
import com.suixing.agent.agent.StreamStats
import com.suixing.agent.ui.chat.GenerationSheet
import com.suixing.agent.ui.components.MenuCard
import com.suixing.agent.ui.components.MENU_EXIT_MS
import com.suixing.agent.ui.components.MenuItemRow
import com.suixing.agent.ui.components.menuRippleClickable
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.Goal
import com.suixing.agent.data.GoalStatus
import com.suixing.agent.data.ProviderConfig
import com.suixing.agent.data.Tps
import com.suixing.agent.data.TODO_IN_PROGRESS
import com.suixing.agent.data.TodoItem
import com.suixing.agent.data.label
import com.suixing.agent.ui.components.CircleActionButton
import com.suixing.agent.ui.theme.LocalDarkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.util.Locale
import androidx.compose.runtime.mutableFloatStateOf

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
/** The margin a popup keeps from the window's own edges. */
internal val POPUP_EDGE = 8.dp

/** 取消追底的手指位移阈值（用户：「取消的最小方法就是往上移动一点就需要取消」）。 */
internal const val CANCEL_FOLLOW_PX = 4f

/**
 * 阅读锚点「还算漂移」的上限（issue #11/#15，设计 A）。
 *
 * 帧回调里每帧对一次账：偏差在这以内 ⇒ 当成内容长高造成的漂移，直接补掉；
 * 超过它 ⇒ 是用户自己滚了，把锚点跟上去而不是把人拽回原处。
 * 200px 大约是一整张卡片的高度，正常的长高不会一帧跳这么多。
 */
internal const val USER_SCROLL_PX = 200

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

/** Position action menus from current layout bounds without publishing coordinates to composition. */
internal class ComposerMenuPositionProvider(
    private val edgePx: Float,
    private val offsetYPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val nudge = popupNudge(anchorBounds.left.toFloat(), popupContentSize.width,
            windowSize.width.toFloat(), edgePx).roundToInt()
        return anchorBounds.topLeft +
            Alignment.BottomStart.align(IntSize.Zero, anchorBounds.size, layoutDirection) -
            Alignment.BottomStart.align(IntSize.Zero, popupContentSize, layoutDirection) +
            IntOffset(if (layoutDirection == LayoutDirection.Ltr) nudge else -nudge, offsetYPx)
    }
}

/**
 * Where the slash-command candidates hang, in window coordinates: [gapPx] above the field's own
 * top edge, inset [insetPx] inside that field so the card reads as belonging to it (the same inset
 * the queue rows use).
 *
 * The field is the anchor, so its width is already inside the window; the two clamps only matter
 * for the vertical one — a tall field (five lines, attachments, queued rows) can leave less room
 * above it than the card needs, and a card that runs off the top of the screen is a card whose
 * first rows cannot be read. It then covers the field instead, which is still usable.
 */
internal fun slashPopupOffset(
    anchor: IntRect,
    windowWidth: Int,
    popupSize: IntSize,
    insetPx: Int,
    gapPx: Int,
): IntOffset {
    val x = (anchor.left + insetPx).coerceIn(
        insetPx,
        (windowWidth - insetPx - popupSize.width).coerceAtLeast(insetPx),
    )
    val y = (anchor.top - popupSize.height - gapPx).coerceAtLeast(insetPx)
    return IntOffset(x, y)
}

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



@Composable
internal fun BlinkingCursor() {
    // **不要用 `rememberInfiniteTransition` + tween**：那会让界面按**屏幕刷新率**持续重绘。
    // 真机实测（0.8.382，一轮在跑、手指不动）：15 秒 3194 帧 ≈ 213 fps、单核 42%
    // —— 长回答/长工具调用时就是持续发热与耗电，而同屏还有悬浮窗在同时动画。
    // 闪烁只需要"两档"，用 480ms 的步进替代：观感一样，重绘从 ~120 次/秒降到 ~2 次/秒。
    val cursorAlpha = remember { mutableFloatStateOf(1f) }
    var visible by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, visible) {
        if (!visible) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(480)
                cursorAlpha.floatValue = if (cursorAlpha.floatValue > 0.5f) 0.15f else 1f
            }
        }
    }
    // 光标画成一条**细线**而不是字符「▍」：那个字形本身有宽度（一个全角块的宽度），
    // 用户反馈「光标太宽」。线宽 1.5dp ≈ 一个字笔画的粗细，高度取正文行高。
    val body = MaterialTheme.typography.bodyMedium
    // 高度跟着字号走（乘 1.4 约等于正文行高），这样系统字体缩放时光标也跟着变；
    // 不直接读 lineHeight，是因为它在个别主题下是 Unspecified。
    val lineHeight = with(LocalDensity.current) {
        (body.fontSize.value * 1.4f).sp.toDp()
    }
    Box(
        modifier = Modifier
            .width(1.5.dp)
            .height(lineHeight)
            .onVisibilityChanged(minFractionVisible = 0f) { visible = it }
            .graphicsLayer { alpha = cursorAlpha.floatValue }
            .background(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(1.dp),
            ),
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
    elapsed: androidx.compose.runtime.State<Long>? = null,
) {
    val clock = elapsed ?: rememberRunElapsed(startedAt, timed)
    val caption = if (!timed) text else "$text，用时 ${formatRunElapsed(clock.value)}"
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
    // The string only changes when the clock crosses the 24h boundary, not on every
    // recomposition of a history row. The formatter's Date/buffer still allocates.
    val text = remember(timestamp) { formatTime(timestamp) }
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        // The label's distance from its bubble is decided by the caller (3dp inside the
        // item's Column); this only tightens the text's own line box, so the glyphs sit
        // closer to the top of their slot than labelSmall's 16sp box would put them.
        style = MaterialTheme.typography.labelSmall.copy(lineHeight = 12.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

private val ComposerPillShape = RoundedCornerShape(24.dp)

@Composable
internal fun Composer(
    input: String,
    /** Slash commands to suggest while the user is typing one: name to one-line description. */
    slashTools: List<Pair<String, String>> = emptyList(),
    /** The goal and execution state owned by this conversation. */
    goal: Goal? = null,
    onClearGoal: () -> Unit = {},
    onResumeGoal: () -> Unit = {},
    onInput: (String) -> Unit,
    /**
     * Takes a queued line back out of the queue and into the composer — its text **and its
     * picture**. A callback of its own rather than `onInput(text)`: the attachment lives in the
     * view model's cache, not in this composable, so only the caller can put both halves back.
     *
     * [nextTurn] says which queue owns [PendingSteer]: a waiting turn or a steer for the run
     * in flight. The caller retracts the matching queue entry **only after** the restore has
     * committed; doing it here in the same click would drop the row if the decode failed
     * (issue #76 F-01).
     */
    onRestoreSteer: (PendingSteer, Boolean) -> Unit,
    running: Boolean,
    settings: AppSettings,
    /** The persona this conversation runs with, not the app-wide active one. */
    assistantId: String,
    /** That persona's display name; the empty-field placeholder follows it. */
    assistantName: String,
    /** The window the context ring divides by; 0 hides the ring (no denominator). */
    contextWindow: Int,
    /** Tokens the newest round filled of that window, and how many of them were cached. */
    contextUsed: Int?,
    /** True when [contextUsed] is this app's own estimate rather than the endpoint's count. */
    contextEstimated: Boolean = false,
    /**
     * 圆环面板三行（系统提示词 / 工具定义 / 对话消息）的当前窗口读数，不依赖 run：空闲时由
     * [onContextPanelOpen] 触发上层计算后从这里进来。
     */
    contextBreak: Triple<Int, Int, Int>? = null,
    /** 面板打开时回调：上层按当前上下文算一次三行。 */
    onContextPanelOpen: () -> Unit = {},
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
    subagentQueue: @Composable () -> Unit = {},
    /** Called once the stack has been placed for this frame: the caller's follow pin rides it. */
) {
    // 每 120ms 重组的只有这一个环（含时间读数），不拖别人。
    val scheme = MaterialTheme.colorScheme
    // 输入正文与占位字共用这一份：字体、行高、字重全部一致，只有颜色不同。
    val baseFieldTextStyle = MaterialTheme.typography.bodyLarge
    // 16/17 reduces the default 17sp body to 16sp and 28sp leading to about 26sp.
    // Scale the current theme values proportionally so app font tiers and system fontScale remain active.
    val fieldTextStyle = remember(baseFieldTextStyle) {
        baseFieldTextStyle.copy(
            fontSize = baseFieldTextStyle.fontSize * (16f / 17f),
            lineHeight = baseFieldTextStyle.lineHeight * (26f / 28f),
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            // 保留首尾行的完整行高，英文输入切到中文 fallback 时不再按字形盒撑高。
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.None,
                mode = LineHeightStyle.Mode.Fixed,
            ),
        )
    }
    // 发送后键盘退避（用户：「抬起输入法发送时，对话框也会挡住」）：不收键盘的话 composer 悬在
    // 键盘上方、把列表尾的状态行盖在后面。发送＝这句话说完了，收键盘让整条栈落回去；「停止」
    // 不收（那一下没有发送任何文字，用户的手还在输入上）。
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val goalFocus = remember { FocusRequester() }
    val latestInput = rememberUpdatedState(input)
    // The field keeps its own selection so an external write (the goal prefix, a restored
    // queued line) can land the caret at the end instead of leaving it before the text.
    var field by remember { mutableStateOf(TextFieldValue(input, TextRange(input.length))) }
    LaunchedEffect(input) {
        if (input != field.text) field = TextFieldValue(input, TextRange(input.length))
    }
    val commandEnd = remember(field.text) { slashTokenEnd(field.text) }
    val commandTint = remember(commandEnd, scheme.primary) {
        FileReferenceTransformation(scheme.primary, commandEnd)
    }
    val fieldStyle = remember(fieldTextStyle, scheme.onSurface) {
        fieldTextStyle.copy(color = scheme.onSurface)
    }
    val placeholderStyle = remember(fieldTextStyle, scheme.onSurfaceVariant) {
        fieldTextStyle.copy(color = scheme.onSurfaceVariant)
    }
    val fieldLines = remember { androidx.compose.runtime.mutableIntStateOf(1) }
    val fieldLineHeightPx = with(LocalDensity.current) { fieldTextStyle.lineHeight.toPx() }
    val fieldTextMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val fieldBaselinePx = remember(fieldTextMeasurer, fieldTextStyle) {
        fieldTextMeasurer.measure("Ag国", style = fieldTextStyle, maxLines = 1).firstBaseline
    }
    val referenceQuery = remember(field.text, field.selection) {
        if (field.selection.collapsed) activeFileReference(field.text, field.selection.start) else null
    }
    val ctx = LocalContext.current
    var referenceResults by remember {
        mutableStateOf<Pair<FileReferenceQuery, List<FileReferenceSuggestion>>?>(null)
    }
    val referenceSuggestions = referenceResults?.takeIf { it.first == referenceQuery }?.second.orEmpty()
    LaunchedEffect(ctx, referenceQuery) {
        referenceResults = null
        val query = referenceQuery ?: return@LaunchedEffect
        val suggestions = withContext(Dispatchers.IO) {
            fileReferenceSuggestions(com.suixing.agent.tools.workspaceDir(ctx), query.query)
        }
        referenceResults = query to suggestions
    }
    // The goal command lives in the add menu, not as a permanent icon: it is occasional
    // configuration and the row's space belongs to typing and sending. Existing text becomes
    // the goal verbatim; an empty field gets the prefix.
    val startGoal: () -> Unit = remember(onInput, goalFocus, latestInput) {
        {
            val trimmed = latestInput.value.trim()
            if (trimmed.isEmpty()) {
                onInput("/goal ")
            } else if (!trimmed.startsWith("/goal")) {
                onInput("/goal " + trimmed)
            }
            goalFocus.requestFocus()
        }
    }
    val dismissKeyboard: () -> Unit = remember(keyboardController, focusManager) {
        {
            keyboardController?.hide()
            focusManager.clearFocus()
        }
    }
    val editGoal: (String) -> Unit = remember(onInput, goalFocus) {
        { text ->
            onInput("/goal $text")
            goalFocus.requestFocus()
        }
    }
    val density = LocalDensity.current
    // The window's own width, which is what a popup has to fit inside. `screenWidthDp` is the
    // window's, not the display's, so a card that fits here also fits a split-screen window.
    val windowWidth = LocalConfiguration.current.screenWidthDp.dp
    val menuPopup = remember(density) {
        with(density) {
            ComposerMenuPositionProvider(edgePx = POPUP_EDGE_AIR.toPx(), offsetYPx = -44.dp.roundToPx())
        }
    }
    var menuOpen by remember { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }
    // The approval mode's own card, anchored to its button like the other two.
    var approvalOpen by remember { mutableStateOf(false) }
    // The two cards this row opens are focus-less (see [MenuCard]'s focusable): a focusable
    // popup window takes the keyboard away from the field, the shell then lays the composer
    // out a keyboard lower, and the card stays anchored to where the composer was — 250dp
    // above the button it belongs to, which is the 偏上 the user photographed. A focus-less
    // window cannot see a tap outside its own bounds and cannot see BACK, so both are caught
    // here. The scrim is a window of its own, composed before the cards: windows stack in
    // composition order, so a scrim composed after them would cover them instead.
    //
    // 纯遮罩：三张卡片都是 [MenuCard]，进出场在卡片自己身上，这层什么都不画、也不加动画修饰符。
    // `keeping` 只做一件事：卡片退场那段让遮罩留在组合里，否则「点卡片外面」会在卡片还看得见的
    // 时候漏到下面的对话流上。
    val anyCardOpen = menuOpen || sheetOpen || approvalOpen
    var keepCardScrim by remember { mutableStateOf(false) }
    LaunchedEffect(anyCardOpen) {
        if (anyCardOpen) keepCardScrim = true else {
            delay(MENU_EXIT_MS)
            keepCardScrim = false
        }
    }
    if (anyCardOpen || keepCardScrim) {
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
    BackHandler(enabled = anyCardOpen || keepCardScrim) {
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
            .padding(horizontal = 16.dp)
            // The air under the pill, named: the list's own padding is this plus the strip
            // above, and it is measured here, inside the padding (see COMPOSER_BOTTOM_PAD).
            .padding(top = 6.dp, bottom = COMPOSER_BOTTOM_PAD)
            .onSizeChanged { size -> onHeightChanged(with(density) { size.height.toDp() }) },
    ) {
        Layout(content = {
            // 「回到最新」按钮**不在这里**（用户报的「往上滑一点点就跳」）：它原来在本列里占
            // 48dp + 12dp，一出现就把 composer 撑高 60dp ⇒ 底部让位跟着变大 ⇒ 整条转录被顶上去
            // 60dp。现在画在 composer 之外的兄弟节点上（见 ChatScreen 里的 jumpToLatest 浮钮），
            // 高度不参与 [onHeightChanged] 的测量。
            // No progress line here any more: the run's caption lives in the transcript,
            // where the answer is going to appear. Pinned above the pill it read as a
            // second, competing status area — and it carried no clock, so the wait looked
            // untimed while the user was staring at it.
            ComposerPendingPanels(
                goal = goal,
                running = running,
                onEditGoal = editGoal,
                onResumeGoal = onResumeGoal,
                onClearGoal = onClearGoal,
                attachments = attachments,
                onRemoveAttachment = onRemoveAttachment,
                todos = todos,
                onOpenTodos = onOpenTodos,
                subagentQueue = subagentQueue,
                pendingTurns = pendingTurns,
                onPromoteTurn = onPromoteTurn,
                onRetractTurn = onRetractTurn,
                pendingSteers = pendingSteers,
                onPromoteSteer = onPromoteSteer,
                onRetractSteer = onRetractSteer,
                onRestoreSteer = onRestoreSteer,
            )
            // Typing `/` names a command: show what can be run instead of expecting the
            // user to remember tool names. Only while the name is still being typed.
            //
            // **候选不在这条竖列里**（用户：「不用特地为它让一块区域，直接画顶层浮在会话上面」）：
            // 排在 Column 里时，敲下 `/` 就把 composer 撑高一截 —— 转录底部让位随之变大，
            // 整条列表跟着往上跳一格。改成 pill 上方的一个独立窗口（见下面 pill 里的 Popup），
            // 输入框和转录都不动，候选只是浮在会话上面。
            val suggestions = remember(slashTools, field.text) {
                slashSuggestions(slashTools, field.text)
            }
            // 窗口里的内容没有父布局可量，卡片宽度只能取 pill 量到的宽度：左右各缩一个
            // STEER_QUEUE_INSET，与排队行同一个内缩。
            var pillWidthPx by remember { mutableIntStateOf(0) }
            val slashInsetPx = with(density) { STEER_QUEUE_INSET.roundToPx() }
            val slashGapPx = with(density) { 6.dp.roundToPx() }
            val slashWidth = with(density) {
                (pillWidthPx - slashInsetPx * 2).coerceAtLeast(0).toDp()
            }
            val slashPopup = remember(slashInsetPx, slashGapPx) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset {
                        // 底边压在 pill 顶上 6dp：排在列里时的那道缝，位置不变。
                        return slashPopupOffset(
                            anchor = anchorBounds,
                            windowWidth = windowSize.width,
                            popupSize = popupContentSize,
                            insetPx = slashInsetPx,
                            gapPx = slashGapPx,
                        )
                    }
                }
            }
            // Opaque surface with one real soft shadow: the drawn concentric rings read
            // as a halo, and 86% alpha let the transcript bleed through the pill.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // 候选卡片的宽度和锚点都来自这里：窗口量不到父布局，只能量 pill。
                    .onSizeChanged { pillWidthPx = it.width }
                    .shadow(
                        elevation = 8.dp,
                        shape = ComposerPillShape,
                        ambientColor = Color.Black.copy(alpha = 0.25f),
                        spotColor = Color.Black.copy(alpha = 0.25f),
                    ),
            ) {
            // 候选浮层：独立窗口，画在会话上面，不占布局 —— 敲 `/` 时输入框和转录都不动。
            // focus-less 与这里其它浮层一致：可获焦的窗口会把键盘从输入框手里抢走。
            SlashSuggestionPopup(
                visible = suggestions.isNotEmpty(),
                items = suggestions,
                onPick = { name ->
                    onInput("/$name ")
                    goalFocus.requestFocus()
                },
                popupPositionProvider = slashPopup,
                width = slashWidth,
            )
            val referenceItems = remember(referenceSuggestions) {
                referenceSuggestions.map {
                    (it.path + if (it.directory) "/" else "") to
                        (if (it.directory) "文件夹 · 点击进入" else it.path)
                }
            }
            SlashSuggestionPopup(
                visible = referenceItems.isNotEmpty() && referenceQuery != null,
                items = referenceItems,
                onPick = { path ->
                    val query = referenceQuery
                    val suggestion = referenceSuggestions.firstOrNull {
                        it.path + (if (it.directory) "/" else "") == path
                    }
                    if (query != null && suggestion != null) {
                        val (text, cursor) = replaceFileReference(field.text, query, suggestion)
                        field = TextFieldValue(text, TextRange(cursor))
                        onInput(text)
                        goalFocus.requestFocus()
                    }
                },
                popupPositionProvider = slashPopup,
                width = slashWidth,
                labelForItem = { fileReferenceLabel(it) },
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = ComposerPillShape,
                // Light uses the canvas colour; dark steps one surface up, because there
                // `surface` IS the canvas and the pill would vanish into it.
                color = if (LocalDarkTheme.current) scheme.surfaceContainer else scheme.surface,
                shadowElevation = 0.dp,
            ) {
                val openAddMenu: () -> Unit = remember {
                    { sheetOpen = false; approvalOpen = false; menuOpen = true }
                }
                val closeAddMenu: () -> Unit = remember { { menuOpen = false } }
                val openApprovalMenu: () -> Unit = remember {
                    { menuOpen = false; sheetOpen = false; approvalOpen = true }
                }
                val closeApprovalMenu: () -> Unit = remember { { approvalOpen = false } }
                val openSheetMenu: () -> Unit = remember(onPrefetchWindows) {
                    {
                        menuOpen = false
                        approvalOpen = false
                        sheetOpen = true
                        onPrefetchWindows()
                    }
                }
                val closeSheetMenu: () -> Unit = remember { { sheetOpen = false } }
                val compactControls = windowWidth < 360.dp || density.fontScale >= 1.3f || settings.fontScale >= 3
                val hasText = input.isNotBlank()
                Layout(content = {
                    // Row 1: the field itself, transparent on the capsule — no border,
                    // no container, just text with room to breathe above the controls.
                    BasicTextField(
                        value = field,
                        onValueChange = { next ->
                            field = next
                            if (next.text != input) onInput(next.text)
                        },
                        visualTransformation = commandTint,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clipToBounds()
                            .focusRequester(goalFocus)
                            .padding(start = 20.dp, top = 14.dp, end = 20.dp, bottom = 4.dp)
                            .composerFieldHeight(fieldLineHeightPx, fieldBaselinePx) { fieldLines.intValue }
                            // Accessible label is independent of the changing placeholder.
                            .semantics { contentDescription = "消息输入框" }
                            .onFocusChanged { focus ->
                                if (focus.isFocused) onFocused()
                            },
                        textStyle = fieldStyle,
                        onTextLayout = { fieldLines.intValue = it.lineCount.coerceIn(1, 5) },
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
                                val text = latestInput.value.trim()
                                val primaryAction = primaryChatAction(
                                    running,
                                    text.isNotEmpty(),
                                    attachments.isNotEmpty(),
                                )
                                // A picture with no caption is still something to send: only an
                                // entirely empty composer does nothing here.
                                if (primaryAction == PrimaryChatAction.DISABLED || primaryAction == PrimaryChatAction.STOP) return@KeyboardActions
                                if (primaryAction == PrimaryChatAction.STEER) {
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
                                if (field.text.isEmpty()) {
                                    Text(
                                        text = when {
                                            pendingSteers.isNotEmpty() ->
                                                "还有 ${pendingSteers.size} 条排队中…"

                                            running -> "插一句话…"
                                            else -> "让${assistantName.ifBlank { "助手" }}做点什么…"
                                        },
                                        style = placeholderStyle,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
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
                    ComposerControlsArea(
                        ctx = ctx,
                        running = running,
                        hasText = hasText,
                        latestInput = latestInput,
                        settings = settings,
                        assistantId = assistantId,
                        contextWindow = contextWindow,
                        contextUsed = contextUsed,
                        contextEstimated = contextEstimated,
                        contextBreak = contextBreak,
                        onContextPanelOpen = onContextPanelOpen,
                        conversationProviderId = conversationProviderId,
                        conversationModel = conversationModel,
                        conversationReasoningEffort = conversationReasoningEffort,
                        onPickModel = onPickModel,
                        onChooseReasoning = onChooseReasoning,
                        attachments = attachments,
                        onPickImage = onPickImage,
                        onTakePhoto = onTakePhoto,
                        onPickFile = onPickFile,
                        onSend = onSend,
                        onStop = onStop,
                        onSteer = onSteer,
                        onRequestCompress = onRequestCompress,
                        onCompressNow = onCompressNow,
                        onMic = onMic,
                        voiceInput = voiceInput,
                        approvalMode = approvalMode,
                        onPickApproval = onPickApproval,
                        onOpenSettings = onOpenSettings,
                        menuPopup = menuPopup,
                        windowWidth = windowWidth,
                        compactControls = compactControls,
                        menuOpen = menuOpen,
                        onOpenMenu = openAddMenu,
                        onDismissMenu = closeAddMenu,
                        approvalOpen = approvalOpen,
                        onOpenApproval = openApprovalMenu,
                        onDismissApproval = closeApprovalMenu,
                        sheetOpen = sheetOpen,
                        onOpenSheet = openSheetMenu,
                        onDismissSheet = closeSheetMenu,
                        startGoal = startGoal,
                        dismissKeyboard = dismissKeyboard,
                    )
                }, measurePolicy = ComposerStackMeasurePolicy)
            }
            }
        }, measurePolicy = ComposerStackMeasurePolicy)
    }
}

@Composable
private fun ComposerPendingPanels(
    goal: Goal?,
    running: Boolean,
    onEditGoal: (String) -> Unit,
    onResumeGoal: () -> Unit,
    onClearGoal: () -> Unit,
    attachments: List<Attachment>,
    onRemoveAttachment: (Int) -> Unit,
    todos: List<TodoItem>,
    onOpenTodos: () -> Unit,
    subagentQueue: @Composable () -> Unit,
    pendingTurns: List<PendingTurn>,
    onPromoteTurn: (String) -> Unit,
    onRetractTurn: (String) -> Unit,
    pendingSteers: List<PendingSteer>,
    onPromoteSteer: (String) -> Unit,
    onRetractSteer: (String) -> Unit,
    onRestoreSteer: (PendingSteer, Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    if (goal != null) {
        GoalChip(
            goal = goal,
            running = running,
            onEdit = { onEditGoal(goal.text) },
            onResume = onResumeGoal.takeIf {
                !running && (goal.status == GoalStatus.PAUSED || goal.status == GoalStatus.BLOCKED)
            },
            onClear = onClearGoal,
            modifier = Modifier.padding(horizontal = STEER_QUEUE_INSET),
        )
        Spacer(Modifier.height(3.dp))
    }
    if (attachments.isNotEmpty()) {
        val attachmentScroll = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = STEER_QUEUE_INSET)
                .drawerScrollPriority { delta ->
                    if (delta < 0f) attachmentScroll.canScrollForward else attachmentScroll.canScrollBackward
                }
                .horizontalScroll(attachmentScroll),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            attachments.forEachIndexed { index, item ->
                AttachmentChip(attachment = item, onRemove = { onRemoveAttachment(index) })
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (todos.isNotEmpty()) {
        val board = remember(todos) { todoBoard(todos) }
        val rows = board.first
        val done = board.second
        val current = rows.firstOrNull { it.status == TODO_IN_PROGRESS } ?: rows.firstOrNull()
        if (current != null) {
            Box(Modifier.padding(horizontal = STEER_QUEUE_INSET)) {
                TodoQueueRow(
                    item = current,
                    tally = "$done/${todos.size}",
                    onClick = onOpenTodos,
                )
            }
        }
        Spacer(Modifier.height(3.dp))
    }
    subagentQueue()
    if (pendingTurns.isNotEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = STEER_QUEUE_INSET),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            pendingTurns.take(STEER_ROWS_SHOWN).forEach { queued ->
                SteerQueueRow(
                    steer = PendingSteer(queued.id, queued.text, queued.imagesBase64),
                    undelivered = false,
                    nextTurn = true,
                    onPromote = { onPromoteTurn(queued.id) },
                    onEdit = {
                        onRestoreSteer(
                            PendingSteer(queued.id, queued.text, queued.imagesBase64),
                            true,
                        )
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
                    undelivered = !running,
                    onPromote = { onPromoteSteer(queued.id) },
                    onEdit = { onRestoreSteer(queued, false) },
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
        Spacer(Modifier.height(3.dp))
    }
}

@Composable
private fun ComposerControlsArea(
    ctx: android.content.Context,
    running: Boolean,
    hasText: Boolean,
    latestInput: androidx.compose.runtime.State<String>,
    settings: AppSettings,
    assistantId: String,
    contextWindow: Int,
    contextUsed: Int?,
    contextEstimated: Boolean,
    contextBreak: Triple<Int, Int, Int>?,
    onContextPanelOpen: () -> Unit,
    conversationProviderId: String?,
    conversationModel: String?,
    conversationReasoningEffort: String?,
    onPickModel: (ProviderConfig, String) -> Unit,
    onChooseReasoning: (String) -> Unit,
    attachments: List<Attachment>,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickFile: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onSteer: (String, List<String>) -> Boolean,
    onRequestCompress: () -> Boolean,
    onCompressNow: () -> Unit,
    onMic: () -> Unit,
    voiceInput: Boolean,
    approvalMode: ApprovalMode,
    onPickApproval: (ApprovalMode) -> Unit,
    onOpenSettings: () -> Unit,
    menuPopup: ComposerMenuPositionProvider,
    windowWidth: Dp,
    compactControls: Boolean,
    menuOpen: Boolean,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    approvalOpen: Boolean,
    onOpenApproval: () -> Unit,
    onDismissApproval: () -> Unit,
    sheetOpen: Boolean,
    onOpenSheet: () -> Unit,
    onDismissSheet: () -> Unit,
    startGoal: () -> Unit,
    dismissKeyboard: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val leadingControls: @Composable () -> Unit = {
        Box {
            IconButton(
                onClick = onOpenMenu,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = com.suixing.agent.ui.components.suixingIcon(R.drawable.ic_plus),
                    contentDescription = "添加图片或文件",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(
                        if (com.suixing.agent.ui.theme.LocalUseMiuix.current) 18.dp else 22.dp,
                    ),
                )
            }
            MenuCard(
                visible = menuOpen,
                scrim = false,
                onDismiss = onDismissMenu,
                alignment = Alignment.BottomStart,
                offset = IntOffset.Zero,
                popupPositionProvider = menuPopup,
                focusable = false,
                modifier = Modifier.widthIn(max = popupCap(200.dp, windowWidth)),
                compactMotion = true,
            ) {
                MenuItemRow(
                    icon = com.suixing.agent.ui.components.suixingIcon(R.drawable.ic_image),
                    label = "图片",
                    onClick = {
                        onDismissMenu()
                        onPickImage()
                    },
                )
                MenuItemRow(
                    icon = com.suixing.agent.ui.components.suixingIcon(R.drawable.ic_camera),
                    label = "拍照",
                    onClick = {
                        onDismissMenu()
                        onTakePhoto()
                    },
                )
                MenuItemRow(
                    icon = com.suixing.agent.ui.components.suixingIcon(R.drawable.ic_file),
                    label = "文件",
                    onClick = {
                        onDismissMenu()
                        onPickFile()
                    },
                )
                MenuItemRow(
                    icon = com.suixing.agent.ui.components.suixingIcon(R.drawable.ic_compass),
                    label = "目标指令",
                    onClick = {
                        onDismissMenu()
                        startGoal()
                    },
                )
                MenuItemRow(
                    icon = com.suixing.agent.ui.components.suixingIcon(R.drawable.ic_minimize_2),
                    label = "压缩上下文",
                    onClick = {
                        onDismissMenu()
                        val asked = onRequestCompress()
                        if (asked) {
                            Toast.makeText(ctx, "已请求压缩，本轮结束后生效", Toast.LENGTH_SHORT).show()
                        } else {
                            onCompressNow()
                            Toast.makeText(ctx, "正在压缩上下文…", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }
        Box {
            IconButton(
                onClick = onOpenApproval,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = com.suixing.agent.ui.components.suixingIcon(approvalIcon(approvalMode)),
                    contentDescription = "当前会话工具审批：${approvalMode.label}",
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
                scrim = false,
                onDismiss = onDismissApproval,
                alignment = Alignment.BottomStart,
                offset = IntOffset.Zero,
                popupPositionProvider = menuPopup,
                focusable = false,
                modifier = Modifier.widthIn(max = popupCap(220.dp, windowWidth)),
                compactMotion = true,
            ) {
                ApprovalMode.entries.forEach { mode ->
                    MenuItemRow(
                        icon = com.suixing.agent.ui.components.suixingIcon(approvalIcon(mode)),
                        label = mode.label,
                        selected = mode == approvalMode,
                        onClick = {
                            onDismissApproval()
                            onPickApproval(mode)
                        },
                    )
                }
                Text(
                    text = "仅当前会话 · 下一次任务生效",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
                )
            }
        }
    }
    if (compactControls) {
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 12.dp).height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { leadingControls() }
    }
    Row(
        modifier = Modifier
            .padding(start = 10.dp, end = 12.dp, bottom = 8.dp)
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (!compactControls) leadingControls()
        Box(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Box {
                    GenerationSheet(
                        settings = settings,
                        assistantId = assistantId,
                        conversationProviderId = conversationProviderId,
                        conversationModel = conversationModel,
                        conversationReasoningEffort = conversationReasoningEffort,
                        onChooseReasoning = onChooseReasoning,
                        visible = sheetOpen,
                        scrim = false,
                        onPickModel = onPickModel,
                        onOpenSettings = onOpenSettings,
                        onDismiss = onDismissSheet,
                    )
                    UnifiedSelector(
                        settings = settings,
                        assistantId = assistantId,
                        conversationProviderId = conversationProviderId,
                        conversationModel = conversationModel,
                        conversationReasoningEffort = conversationReasoningEffort,
                        onClick = onOpenSheet,
                    )
                }
            }
        }
        ContextRing(
            used = contextUsed,
            estimated = contextEstimated,
            window = contextWindow,
            breakSplit = contextBreak,
            onPanelOpen = onContextPanelOpen,
        )
        Spacer(Modifier.width(4.dp))
        if (voiceInput) {
            IconButton(
                onClick = onMic,
                enabled = !running,
                modifier = Modifier.size(30.dp),
            ) {
                Icon(
                    com.suixing.agent.ui.components.suixingIcon(R.drawable.ic_mic),
                    contentDescription = if (running) "语音输入，正在生成时不可录音" else "语音输入",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(
                        if (com.suixing.agent.ui.theme.LocalUseMiuix.current) 20.dp else 18.dp,
                    ),
                )
            }
        }
        val primaryAction = primaryChatAction(running, hasText, attachments.isNotEmpty())
        val hasSomething = hasText || attachments.isNotEmpty()
        val steers = primaryAction == PrimaryChatAction.STEER
        val stopping = primaryAction == PrimaryChatAction.STOP
        val sendable = primaryAction != PrimaryChatAction.DISABLED
        CircleActionButton(
            icon = if (stopping) {
                com.suixing.agent.ui.components.suixingIcon(R.drawable.ic_stop_filled)
            } else {
                com.suixing.agent.ui.components.suixingVector(
                    R.drawable.ic_arrow_up,
                    com.suixing.agent.ui.components.MiuixExtraIcons.Send,
                )
            },
            contentDescription = when {
                steers -> "插话发送"
                running -> "停止"
                else -> "发送"
            },
            onClick = {
                when (primaryAction) {
                    PrimaryChatAction.STEER -> {
                        withAttachment(attachments, latestInput.value.trim())
                            .let { (body, images) -> if (!onSteer(body, images)) onSend() }
                        dismissKeyboard()
                    }
                    PrimaryChatAction.STOP -> onStop()
                    PrimaryChatAction.DISABLED -> Unit
                    PrimaryChatAction.SEND -> {
                        onSend()
                        dismissKeyboard()
                    }
                }
            },
            enabled = sendable,
            size = 40.dp,
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
            shadowElevation = 0.dp,
            iconSize = if (stopping) {
                if (com.suixing.agent.ui.theme.LocalUseMiuix.current) 20.dp else 18.dp
            } else {
                22.dp
            },
        )
    }
}

/** A compact control strip; the assistant reply carries the goal's outcome. */
@Composable
private fun GoalChip(
    goal: Goal,
    running: Boolean,
    onEdit: () -> Unit,
    onResume: (() -> Unit)?,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val status = when (goal.status) {
        GoalStatus.ACTIVE -> if (goal.wakeAt > 0L) "等待继续" else "进行中"
        GoalStatus.PAUSED -> "已暂停"
        GoalStatus.BLOCKED -> "待处理"
        GoalStatus.COMPLETED -> if (running) "正在总结" else "已完成"
    }
    Surface(
        modifier = modifier.fillMaxWidth().height(36.dp),
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = 10.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = com.suixing.agent.ui.components.suixingIcon(R.drawable.ic_compass),
                contentDescription = null,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$status · ${goal.text}",
                style = MaterialTheme.typography.labelMedium,
                color = if (goal.status == GoalStatus.ACTIVE) scheme.primary else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onResume != null) {
                Text("继续", color = scheme.primary, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable(onClick = onResume).padding(horizontal = 8.dp, vertical = 10.dp))
            }
            QueueAction(icon = R.drawable.ic_square_pen, label = "编辑目标", onClick = onEdit)
            QueueAction(icon = R.drawable.ic_x, label = "清除目标", onClick = onClear)
        }
    }
}

// The card opens from its bottom anchor without scaling text or moving the composer.
