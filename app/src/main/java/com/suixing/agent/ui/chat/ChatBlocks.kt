
package com.suixing.agent.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suixing.agent.ui.components.INLINE_ICON_BOX
import com.suixing.agent.ui.components.InlineIconAction
import com.suixing.agent.R
import com.suixing.agent.agent.StreamStats
import com.suixing.agent.data.ContextCheckpoint
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.ui.chat.markdown.MarkdownText
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.flow.collectLatest

/**
 * 折叠块的展开箭头（过程卡 / 思考块 / 单个工具共用）。
 *
 * 两件事一次做完（用户：「回复卡片中的箭头icon要对齐大小和位置，同时折叠展开icon要有动效」）：
 *  · **对齐**：三处原来各写一份，尺寸是 18 / 18 / **16**dp，而且有的直接贴在行末、有的夹在
 *    文字后面，位置随内容浮动。现在统一成「24dp 盒里居中、图标 16dp」—— 与 [ProcessCard]
 *    左侧那枚图标（24dp 盒 + 16dp 图标）同一基准，所以左右两端对得齐。
 *  · **动效**：原来靠 `if (expanded) chevron_up else chevron_down` 直接换资源，是硬切。
 *    现在只用一个 chevron_down，按状态旋转 0↔180°，180ms 缓动。
 */
@Composable
internal fun ExpandChevron(expanded: Boolean) {
    val rotation = animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "expandChevron",
    )
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.ic_chevron_down),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = rotation.value },
        )
    }
}


@Composable
internal fun BranchArrow(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    boxWidth: Dp = INLINE_ICON_BOX,
) = InlineIconAction(
    icon = icon,
    label = label,
    onClick = onClick,
    enabled = enabled,
    boxWidth = boxWidth,
)

/**
 * How wide a process block's own name may get before it starts yielding.
 *
 * A name is short by nature — 「思考过程」 is four characters, `run_shell` nine — so it is never
 * given a share of the row to fill: it takes what it needs and hands the rest to the status and
 * the preview beside it. The cap only stops a long MCP name from eating the whole header.
 */
internal val PROCESS_TITLE_MAX = 160.dp

/**
 * TranscriptPosition 的手势保护基准；动画本身按内容高度选时长。
 *
 * 180 → 220（用户：「慢一点点，不要太生硬即可」）：180ms 对整块卡片来说收得太急，末尾像"啪"地
 * 停住；不能改这个值，因为 [TranscriptPosition] 用它设置手势保护窗口。RevealHeight 以它为收起
 * 时长曲线的缩放基准，但每次动画仍按 dp 高度选时长，短收起可以短于 220ms。
 */
internal const val PROCESS_EXPAND_MS = 220

private val REVEAL_DURATION_STOPS = listOf(
    0f to 130,
    200f to 180,
    480f to 360,
    900f to 550,
    1200f to 680,
)
private val COLLAPSE_DURATION_STOPS = listOf(
    0f to 110,
    200f to 150,
    480f to 300,
    900f to 470,
    1200f to 580,
)
private const val COLLAPSE_BASE_SCALE_MS = 220f
private const val MIN_REVEAL_TRANSITION_MS = 80

/**
 * Full-span duration is based on logical content height, so equal dp heights animate equally
 * across display densities. The five stops keep the response continuous from tiny summaries to
 * very tall ones, with a plateau for content beyond 1200dp.
 */
internal fun revealDurationMsForHeight(heightDp: Float): Int {
    return durationMsForHeight(REVEAL_DURATION_STOPS, heightDp)
}

private fun durationMsForHeight(stops: List<Pair<Float, Int>>, heightDp: Float): Int {
    if (heightDp == Float.POSITIVE_INFINITY) return stops.last().second
    val height = if (heightDp.isNaN()) 0f else heightDp.coerceAtLeast(0f)
    val first = stops.first()
    if (height <= first.first) return first.second
    for (index in 1 until stops.size) {
        val (endHeight, endDuration) = stops[index]
        if (height <= endHeight) {
            val (startHeight, startDuration) = stops[index - 1]
            val fraction = (height - startHeight) / (endHeight - startHeight)
            return interpolateDurationMs(startDuration, endDuration, fraction)
        }
    }
    return stops.last().second
}

private fun interpolateDurationMs(startMs: Int, endMs: Int, fraction: Float): Int =
    startMs + ((endMs - startMs) * fraction.coerceIn(0f, 1f)).roundToInt()

/** Convert the measured pixel height using the same density that will choose motion timing. */
internal fun revealHeightDpForDuration(heightPx: Int, density: Float): Float =
    if (density > 0f) heightPx / density else 0f

/**
 * Scale the dp-based collapse schedule with the established 220ms compatibility baseline. The
 * shortest close is intentionally below that guard value, while taller cards get more travel time.
 */
internal fun scaledCollapseDurationMs(baseMs: Int, heightDp: Float): Int {
    if (baseMs <= 0) return 0
    val scheduleScale = baseMs / COLLAPSE_BASE_SCALE_MS
    return (durationMsForHeight(COLLAPSE_DURATION_STOPS, heightDp) * scheduleScale).roundToInt()
}

/**
 * A direction change continues from the current fraction. Scale the full-span duration to the
 * remaining distance, with a small floor so a near-complete reversal does not flick shut.
 */
internal fun revealTransitionDurationMs(
    fullDurationMs: Int,
    currentFraction: Float,
    targetFraction: Float,
): Int {
    if (fullDurationMs <= 0) return 0
    val current = if (currentFraction.isNaN()) 0f else currentFraction.coerceIn(0f, 1f)
    val target = if (targetFraction.isNaN()) current else targetFraction.coerceIn(0f, 1f)
    val remaining = kotlin.math.abs(target - current)
    if (remaining == 0f) return 0
    return (fullDurationMs * remaining).roundToInt()
        .coerceAtLeast(MIN_REVEAL_TRANSITION_MS)
        .coerceAtMost(fullDurationMs)
}

/** Only an opening from a fully closed reveal needs to invalidate its cached measurement. */
internal fun revealNeedsFreshMeasurement(
    visible: Boolean,
    wasExpanding: Boolean,
    currentFraction: Float,
): Boolean = visible && !wasExpanding && currentFraction <= 0f

private const val REVEAL_ALPHA_RAMP = 2.5f

/** The ramp rises with fraction, while the carried floor prevents a reverse from fading backward. */
internal fun revealAlphaForFraction(
    fraction: Float,
    alphaFloor: Float,
): Float {
    val safeFraction = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
    val safeFloor = if (alphaFloor.isNaN()) 0f else alphaFloor.coerceIn(0f, 1f)
    val expansionAlpha = (safeFraction * REVEAL_ALPHA_RAMP).coerceIn(0f, 1f)
    return maxOf(safeFloor, expansionAlpha)
}

/** The carried opacity is the actual alpha at the hand-off, except a fresh open starts its fade at 0. */
internal fun revealAlphaFloorAfterDirectionChange(
    wasExpanding: Boolean,
    willExpand: Boolean,
    currentFraction: Float,
    alphaFloor: Float,
): Float {
    val currentAlpha = revealAlphaForFraction(currentFraction, alphaFloor)
    return if (willExpand && !wasExpanding && currentFraction <= 0f) 0f else currentAlpha
}

private const val REVEAL_REVERSAL_VELOCITY_EPSILON = 0.001f
private const val CRITICAL_SPRING_ONE_PERCENT_SETTLE_FACTOR = 6.6f

/** Keep spring completion below a quarter physical pixel, with a safe threshold for empty content. */
internal fun revealFractionVisibilityThreshold(heightPx: Int): Float =
    if (heightPx > 0) minOf(0.01f, 0.25f / heightPx) else 0.01f

/**
 * Use a critically damped spring only when reversing active motion. Keep only velocity that points
 * toward the new target, capped at omega * distance; this makes the critical trajectory monotonic
 * and bounded by its start and target. The spring threshold is pixel based, and stiffness is
 * adjusted for that threshold so the nominal settle time stays near the remaining-travel duration.
 * Ordinary transitions retain their exact height-based tweens.
 */
internal data class RevealInterruptionPolicy(
    val initialVelocity: Float,
    val stiffness: Float,
    val visibilityThreshold: Float,
)

internal fun revealInterruptionPolicy(
    velocity: Float,
    currentFraction: Float,
    targetFraction: Float,
    transitionDurationMs: Int,
    heightPx: Int,
): RevealInterruptionPolicy? {
    if (
        transitionDurationMs <= 0 ||
        !velocity.isFinite() ||
        abs(velocity) <= REVEAL_REVERSAL_VELOCITY_EPSILON
    ) {
        return null
    }
    val start = if (currentFraction.isNaN()) 0f else currentFraction.coerceIn(0f, 1f)
    val target = if (targetFraction.isNaN()) start else targetFraction.coerceIn(0f, 1f)
    val distance = abs(target - start)
    if (distance == 0f) return null

    val direction = if (target > start) 1f else -1f
    val visibilityThreshold = revealFractionVisibilityThreshold(heightPx)
    if (visibilityThreshold >= distance) return null
    val relativeThreshold = visibilityThreshold / distance
    val settleFactor = criticalSpringSettleFactor(relativeThreshold)
    val angularFrequency = settleFactor /
        (transitionDurationMs / 1000f)
    val velocityTowardTarget = (velocity * direction)
        .coerceAtLeast(0f)
        .coerceAtMost(angularFrequency * distance)
    return RevealInterruptionPolicy(
        initialVelocity = direction * velocityTowardTarget,
        stiffness = angularFrequency * angularFrequency,
        visibilityThreshold = visibilityThreshold,
    )
}

/** Solve `(1 + x) * exp(-x) = relativeThreshold` for the critically damped zero-velocity tail. */
private fun criticalSpringSettleFactor(relativeThreshold: Float): Float {
    if (relativeThreshold >= 1f) return CRITICAL_SPRING_ONE_PERCENT_SETTLE_FACTOR
    val threshold = relativeThreshold.coerceAtLeast(1e-9f).toDouble()
    var low = 0.0
    var high = 32.0
    repeat(28) {
        val midpoint = (low + high) / 2.0
        val remaining = (1.0 + midpoint) * exp(-midpoint)
        if (remaining > threshold) low = midpoint else high = midpoint
    }
    return high.toFloat()
}

/**
 * 短块保留原曲线，随高度平滑混合到大块的对称曲线；展开和收起都用它，避免高卡片在
 * 收起时因进度前置而一下子消失。两条组成曲线单调且不越界，按高度混合后也不会回弹。
 */
internal val REVEAL_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private val LONG_REVEAL_EASING = CubicBezierEasing(0.4f, 0f, 0.6f, 1f)
private const val SHORT_REVEAL_EASING_MAX_DP = 160f
private const val LONG_REVEAL_EASING_MIN_DP = 480f

/**
 * Keep the established curve for short blocks, then blend smoothly to a symmetric curve for long reveals.
 * Both component curves are monotonic; interpolating their outputs preserves monotonic progress.
 */
internal fun revealEasingForHeight(heightDp: Float): Easing {
    val safeHeightDp = if (heightDp.isNaN()) 0f else heightDp
    val blend = ((safeHeightDp - SHORT_REVEAL_EASING_MAX_DP) /
        (LONG_REVEAL_EASING_MIN_DP - SHORT_REVEAL_EASING_MAX_DP)).coerceIn(0f, 1f)
    if (blend <= 0f) return REVEAL_EASING
    if (blend >= 1f) return LONG_REVEAL_EASING
    return Easing { fraction ->
        val shortProgress = REVEAL_EASING.transform(fraction)
        val longProgress = LONG_REVEAL_EASING.transform(fraction)
        shortProgress + (longProgress - shortProgress) * blend
    }
}

/**
 * 主转录的滚动状态：折叠块展开/收起时，若**不在底部**，要按高度增量反向补偿，
 * 才能让块顶边钉住、内容向下展开（见 [RevealHeight]）。
 */
internal val LocalChatListState = compositionLocalOf<androidx.compose.foundation.lazy.LazyListState?> { null }


/**
 * 外观 → 打字机效果（默认开）。只落在**两个**流式渲染点上：正文的 Markdown 与思考块。
 * 用 composition local 而不是逐层传参，是因为这两点离设置有好几层，而参数要穿过折叠卡片、
 * 工具块、轮次渲染器。
 */
internal val LocalTypewriter = compositionLocalOf { true }

/**
 * 卡片级可点块的按下反馈：**整块背景压暗**，不用涟漪。
 *
 * M3 的 `ripple()` 是 bounded 的：半径＝触摸点到本节点**最远角**的距离。放在一个又高又宽的卡片上，
 * 它画出来就是一个大圆（被圆角裁掉一半仍是个圆），与「这一块被按下了」的预期完全不是一回事 ——
 * 用户看到的就是「为什么是个圆形」。这里换成整块 8% 的 onSurface 叠色，形状与可见圆角一致，
 * 松手即恢复。
 *
 * 只给**卡片级**的可点块用（工具行、折叠卡片的表头、检查点卡）；单行小按钮（图标按钮、菜单行、
 * 26dp 的圆形动作）保留 M3 原生涟漪，那才是涟漪本来该有的样子。
 *
 * 没有复用 `suixingClickable`：它的两个重载都把 `indication = ripple()` 写死了，没有留出
 * 「不要涟漪」的入口。
 */
@Composable
internal fun Modifier.blockPress(shape: Shape, expanded: Boolean? = null, label: String? = null, onClick: () -> Unit): Modifier {
    val position = LocalTranscriptPosition.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    return this
        .semantics(mergeDescendants = true) {
            if (expanded != null) stateDescription = if (expanded) "已展开" else "已折叠"
        }
        .clip(shape)
        .background(
            if (pressed) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            } else {
                Color.Transparent
            },
        )
        .clickable(
            interactionSource = interaction,
            indication = null,
            role = androidx.compose.ui.semantics.Role.Button,
            onClickLabel = label?.let { (if (expanded == true) "折叠" else "展开") + it },
            onClick = {
                position?.pauseForUserChange()
                onClick()
            },
        )
}

/** Keep the accepted 36dp row at normal sizes; grow only when scaled text needs it. */
@Composable
private fun processHeaderHeight(): Dp {
    val style = MaterialTheme.typography.labelMedium
    val required = with(LocalDensity.current) { style.lineHeight.toDp() } + 12.dp
    return if (required <= 36.dp) 36.dp else maxOf(48.dp, required)
}


/** One monotonic height animation; the transcript owns scrolling and anchoring. */
@Composable
private fun RevealHeight(
    visible: Boolean,
    // Scales the dp-based collapse schedule and preserves the established caller contract.
    durationMs: Int,
    content: @Composable () -> Unit,
) {
    val fraction = remember { androidx.compose.animation.core.Animatable(if (visible) 1f else 0f) }
    // Direction and alpha floor are written only when the target changes. The animated fraction
    // remains a draw-phase read, so frames do not recompose the content.
    var expanding by remember { androidx.compose.runtime.mutableStateOf(visible) }
    var alphaFloor by remember { mutableFloatStateOf(0f) }
    val position = LocalTranscriptPosition.current
    val density = LocalDensity.current
    val latestVisible = rememberUpdatedState(visible)
    val latestPosition = rememberUpdatedState(position)
    val latestDensity = rememberUpdatedState(density.density)
    // Animatable resets its own velocity when its coroutine is cancelled. Keep the last sampled
    // velocity outside snapshot state so the reversal policy can project it onto the new target.
    val lastVelocity = remember { floatArrayOf(0f) }
    // 高度在下面的 `Layout` 里才量得到，所以展开先等本次测量，再按该次 toggle 的高度启动动画。
    // durationHeight 用 -1 表示尚未测量，0 仍是合法的空内容高度；测量值只影响后续 toggle，
    // 不作为 LaunchedEffect key，因此流式内容长高时不会取消并重启当前动画。
    var contentHeight by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    /**
     * 用来算时长的**粗粒度**高度（issue #59 B1），-1 表示当前展开尚未测量。
     *
     * 高度仅在首次测量或变化超过 max(48px, 1/8) 时更新。它不再是动画 effect 的 key，
     * 本次 toggle 启动时捕获一次时长，流式期间不会重启动画。
     */
    var durationHeight by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    LaunchedEffect(durationMs) {
        snapshotFlow { latestVisible.value }.collectLatest { targetVisible ->
            if (!targetVisible && fraction.value == 0f) return@collectLatest

            val wasExpanding = expanding
            val currentFraction = fraction.value
            val startingVelocity = lastVelocity[0]
            val needsFreshMeasurement = revealNeedsFreshMeasurement(
                visible = targetVisible,
                wasExpanding = wasExpanding,
                currentFraction = currentFraction,
            )
            alphaFloor = revealAlphaFloorAfterDirectionChange(
                wasExpanding = wasExpanding,
                willExpand = targetVisible,
                currentFraction = currentFraction,
                alphaFloor = alphaFloor,
            )
            expanding = targetVisible
            val currentPosition = latestPosition.value
            val currentDensity = latestDensity.value
            val tracked = currentPosition?.beginRevealTransition() == true
            try {
                if (needsFreshMeasurement) {
                    durationHeight = -1
                }
                val heightForDuration = snapshotFlow { durationHeight }.first { it >= 0 }
                val heightDp = revealHeightDpForDuration(heightForDuration, currentDensity)
                val targetFraction = if (targetVisible) 1f else 0f
                val fullDuration = if (targetVisible) revealDurationMsForHeight(heightDp)
                                   else scaledCollapseDurationMs(durationMs, heightDp)
                val transitionDuration = revealTransitionDurationMs(
                    fullDurationMs = fullDuration,
                    currentFraction = fraction.value,
                    targetFraction = targetFraction,
                )
                val interruption = revealInterruptionPolicy(
                    velocity = startingVelocity,
                    currentFraction = fraction.value,
                    targetFraction = targetFraction,
                    transitionDurationMs = transitionDuration,
                    heightPx = contentHeight,
                )
                val animationSpec: AnimationSpec<Float> = interruption?.let { policy ->
                    spring<Float>(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = policy.stiffness,
                        visibilityThreshold = policy.visibilityThreshold,
                    )
                } ?: tween(
                    transitionDuration,
                    easing = revealEasingForHeight(heightDp),
                )
                fraction.animateTo(
                    targetValue = targetFraction,
                    animationSpec = animationSpec,
                    initialVelocity = interruption?.initialVelocity ?: 0f,
                ) {
                    lastVelocity[0] = velocity
                }
                lastVelocity[0] = 0f
            } finally {
                if (tracked) currentPosition.endRevealTransition()
            }
        }
    }
    val mounted by remember(visible) { derivedStateOf { visible || fraction.value > 0f } }
    if (mounted) Layout(
        // Fresh expansion fades in by 40%. A reversal carries its current alpha across the
        // direction switch, so changing the target cannot jump between the ramp and full opacity.
        content = {
            Box(Modifier.graphicsLayer {
                alpha = revealAlphaForFraction(fraction.value, alphaFloor)
            }) { content() }
        },
        modifier = Modifier.fillMaxWidth().clipToBounds(),
    ) { measurables, constraints ->
        val child = measurables.first().measure(constraints.copy(minHeight = 0))
        // 实测高度写回（只在变化时写，避免每帧触发重组）。
        if (contentHeight != child.height) contentHeight = child.height
        // 时长用的那份只在显著变化时更新：差得少就沿用当前时长，别把动画重启成"换挡"。
        val delta = kotlin.math.abs(contentHeight - durationHeight)
        if (durationHeight < 0 || delta > kotlin.math.max(48, durationHeight / 8)) {
            durationHeight = contentHeight
        }
        val height = (child.height * fraction.value).roundToInt().coerceIn(0, child.height)
        layout(child.width, height) { child.place(0, 0) }
    }
}

/**
 * The chrome every folded process block shares: thinking, a tool call and the turn's own
 * summary all draw through this, so a reply that thinks, calls, thinks again reads as one
 * repeating shape instead of three unrelated widgets stacked in a column.
 *
 * The header *is* the summary line — there is no second row above it — which is what
 * keeps a turn that ran ten tools the height of one row.
 */
@Composable
internal fun ProcessCard(
    icon: ImageVector,
    title: String,
    /** 外层留白/宽度：压缩卡要按助手卡片的左右留白收进来，其余卡片用默认。 */
    modifier: Modifier = Modifier,
    expanded: Boolean,
    onToggle: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    /** First line of the folded content, shown after the meta while the card is closed. */
    preview: String? = null,
    /** Drawn right after [preview]: the streaming cursor for a folded think. */
    previewTail: (@Composable () -> Unit)? = null,
    /** The turn summary owns its seam with the flat tail; other cards keep their usual air. */
    contentBottomPadding: Dp = 6.dp,
    /** false = 没有可展开内容：不响应点击、不画箭头（卡片仍显示，用来报用时）。 */
    expandable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // **表头样式只有一个来源**：标题（已压缩上下文 · 03:44）与预览（68 条消息 → 摘要）
    // 必须是同一个字号 —— 两处各写一遍 `labelMedium` 时已经漂移过一次
    // （预览曾用 metaStyle()＝labelMedium×12/13，比标题小一档）。
    val headerStyle = MaterialTheme.typography.labelMedium
    // *折叠条切换后视口不动** —— 不在这里做任何事：这一项变高变矮会把上方内容推走，而 ChatScreen
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(processHeaderHeight())
                // 整块按下反馈（见 blockPress）：这一行是折叠条的入口，涟漪是一颗小圆角矩形。
                .then(
                    if (expandable) Modifier.blockPress(ProcessHeaderShape, expanded, title) { onToggle() } else Modifier,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
                // One slot for all three card families: the icon sits centred in a
                // 24dp box at 16dp, so Psychology, Build and Insights line up exactly
                // instead of each header carrying its own size and tint.
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    // 折叠块统一：标题一律 labelMedium（原来这里是 labelLarge×13/14）。三处
                    // （思考块 / 工具行 / 本轮用量）的表头现在同一行高、同一字号、同一内边距。
                    style = headerStyle,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // The name takes what it needs, not half the row. `weight(1f, fill = false)`
                    // used to hand it a share of the width and keep it even when the name was
                    // short: the leftover sat right after 「思考过程」 as a band of nothing, and a
                    // name as short as `run_shell` could come back cut in half inside it. With a
                    // preview beside it the name yields at [PROCESS_TITLE_MAX]; without one it
                    // still holds the row, which is what lets the summary card's long header
                    // ellipsize instead of the chip after it.
                    // 标题吃满剩余宽度 ⇒ 箭头贴行最右（有 preview 的卡片才收缩）。
                    modifier = if (preview != null) {
                        Modifier.widthIn(max = PROCESS_TITLE_MAX)
                    } else {
                        // **`fill = false`**（用户：「大总结条右侧折叠常驻icon改为贴内容右侧摆放」）：
                        // `weight(1f)` 会把标题撑满剩余宽度，箭头因此被顶到卡片右边缘；
                        // `fill = false` 只给它「可用份额」的上限、实际宽度按内容算，箭头就紧跟在
                        // 标题后面。标题过长时仍受份额与 `maxLines`/省略号约束，不会溢出。
                        //
                        // 只有**大总结条**走这个分支（`preview == null` 的 ProcessCard 调用点只有它）；
                        // 思考过程 / 工具 / 压缩卡都传了 preview，走上面那支，位置不变。
                        Modifier.weight(1f, fill = false)
                    },
                )
                if (trailing != null) {
                    // The title and the status are two different facts; without this gap
                    // they read as one word — 「run_shell1 个失败」.
                    Spacer(Modifier.width(8.dp))
                    trailing()
                }
                if (preview != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = preview,
                        // **与标题同一个变量**（用户：68 条消息 → 摘要 这个字体要和标题一致）。
                        // 原来这里用 metaStyle()＝labelMedium×12/13，比标题小一档。
                        style = headerStyle,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // The row's one flexible slot: it eats whatever the name, the status and
                        // the chevron leave, so the chevron stays on the card's right edge and
                        // nothing is left over behind it. It used to be `fill = false` as well,
                        // which is what left that leftover band sitting after the chevron.
                        modifier = Modifier.weight(1f),
                    )
                    previewTail?.invoke()
                }
                if (expandable) ExpandChevron(expanded)
            }
            // 展开体：高度动画由 [RevealHeight] 驱动（滚动交给底锚，不再逐帧同步列表）。
            // 前两版都没成：只用 expandVertically + 追底＝两套逐帧动画，谁先谁后不确定，总会
            // 露出一帧位移；干脆不做高度动画＝不闪，但"啪"地出现，生硬（用户：「还是会闪一下
            // ……动画也生硬」）。这一版只做高度：逐帧把进度写进 progress，卡片底边由底锚
            // 钉住，长高只向上。
            RevealHeight(visible = expanded, durationMs = PROCESS_EXPAND_MS) {
                Column {
                    // Header and content are one card; the hairline is their only seam.
                    if (expandable) HorizontalDivider(color = scheme.outlineVariant)
                    // The grid the expanded rows share: 12dp of side air, 8dp above and
                    // below, and every row inside lines its own column up to this edge.
                    Column(
                        // 水平 0dp（用户：「展开时原样展开」）：运行时那些块是顶到卡片左右边缘的，
                        // 12dp 内边距让展开后的内容整体变窄 —— 就是「又窄又短」的那一半。
                        // **上方 0dp**（用户：「折叠条分割线下方还有一处空白，不能去掉吗」）：
                        // 那 8dp 就是分割线下面那道空白，所有折叠块共用这一处，所以在这里一次去掉。
                        // 下方仍留 8dp：展开内容与卡片下沿之间需要一点收尾空气。
                        Modifier.padding(bottom = contentBottomPadding),
                        content = content,
                    )
                }
            }
        }
}

/**
 * 12sp for the trailing status of a [ProcessCard].
 *
 * Expressed off labelMedium rather than as a bare 12.sp so the user's 字号 still applies:
 * the theme scales every slot, and a hard size would opt this text out of it.
 */
/** 折叠条表头统一圆角：文件级常量，避免每次组合分配。 */
private val ProcessHeaderShape = RoundedCornerShape(8.dp)
@Composable
internal fun metaStyle(): TextStyle {
    val base = MaterialTheme.typography.labelMedium
    return remember(base) { base.copy(fontSize = base.fontSize * (12f / 13f)) }
}

/** The trailing status of a [ProcessCard]: a short phrase, never a sentence. */
@Composable
internal fun ProcessStatus(text: String, tone: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = text,
        style = metaStyle(),
        color = tone,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The spinner both process blocks show while they are the one being waited on. */
@Composable
internal fun ProcessSpinner(tone: Color) {
    CircularProgressIndicator(
        modifier = Modifier.size(13.dp),
        strokeWidth = 2.dp,
        color = tone,
    )
}

/**
 * 一条过程留痕（「自动重试 1 次 · 服务器限流（429）」）。
 *
 * 刻意只做**一行小字 + 图标**，不是 [ProcessCard]：它没有可展开的内容，也不该看起来像一次
 * 工具调用。但它必须留在转录里 —— 这一轮是成功的，用户回头要能看出"这次慢是因为重试过"，
 * 而不是只看到一张说这轮失败了的红卡（用户：「重试成功要在过程态中留痕……整轮成功就不要有
 * 错误提示卡片」）。
 *
 * **[modifier] 由调用方给，块自己不带任何上下留白。** 两条渲染路径的间距来源不一样：流式那条
 * 每个 part 之间已经有 6dp 的 Spacer，转录那条没有、靠各 part 自己的内边距。写死在这里，两边的
 * 上下边距必然有一边对不上（用户截的就是这个：重试行贴着上面、离下面远）。
 */
@Composable
internal fun TraceBlock(note: String, modifier: Modifier = Modifier) {
    // **与折叠条同一套栅格**（用户：「你把它改成和思考块 工具块这种不行吗？这样的嵌入 icon
    // 标题什么都没有对齐」）：36dp 行高、图标居中在 24dp 槽里（16dp）、8dp 之后接标题，
    // 标题用 labelMedium —— 与 ProcessCard 的表头逐项相同，所以思考块 / 工具块 / 重试留痕
    // 三者的图标和文字左边线在一条竖线上。
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(processHeaderHeight()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Icon(
                // `material-icons-extended` 的最后一个非必要用途（issue #57 P1-b）：
                // 仓库现成的 Lucide `ic_rotate_cw` 就是同一个图形，去掉那 36MB 依赖。
                painter = painterResource(R.drawable.ic_rotate_cw),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = note,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 运行中的一次上下文压缩：画成过程块，位置就是它发生的那一刻。
 *
 * 与转录里那张 [CheckpointCard] 是同一件事的两种形态 —— 那张按"被折叠的最后一条消息"定位，
 * 负责回看；这一块属于正在跑的这轮，所以出现在「思考 → 工具 → 压缩 → 继续」的顺序里。
 * 不再往气泡下面塞一行提示（用户：「不需要这种在卡片下的提示」）。
 */
@Composable
internal fun CompressionBlock(
    checkpoint: ContextCheckpoint,
    expansion: ReasoningExpansion,
    blockId: String,
) {
    val expanded = expansion.stored(blockId) ?: false
    ProcessCard(
        icon = ImageVector.vectorResource(R.drawable.ic_minimize_2),
        title = "已压缩上下文",
        expanded = expanded,
        onToggle = { expansion.set(blockId, !expanded) },
        preview = "${checkpoint.messages} 条消息 → 摘要 · 压缩前约 ${shortTokens(checkpoint.promptTokens)}",
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .verticalScroll(remember(expansion, blockId) { expansion.viewport(blockId).scrollState }),
        ) {
            // 同样按 Markdown 渲染（摘要里有粗体与有序列表）
            MarkdownText(
                text = checkpoint.summary,
                style = thinkingStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Which thinking blocks the user has opened or closed, for the conversation on screen.
 *
 * A block's state cannot live inside the block: the live think and the stored one it turns
 * into are two different call sites, so a `remember` there started from scratch the moment
 * the reply was saved (and with 思考过程默认展开 on, that meant a think never folded). The
 * conversation is part of every key, so two chats never share a block's switch.
 */
@Stable
internal class ReasoningExpansion(
    private val conversationId: String,
    private val entries: MutableMap<String, Boolean>,
) {
    // Live and stored bubbles are different composition groups. Protect this run's
    // viewports through hand-over, plus 16 recent historical viewports. An arbitrary
    // cap on the active run would lose the first positions when 17+ blocks remount.
    // Entries contain state only, never a job, composable, or Activity.
    private val viewports = LinkedHashMap<String, ReasoningViewport>(16, 0.75f, true)
    private var protectedPrefix: String? = null
    private var historicalViewports = 0
    /** The screen supplies only interaction/visibility, never the engine's running state. */
    var holdCompletion: (String) -> Boolean = { false }
    private var settledPresentationRun: String? = null

    fun protectRun(runId: String) {
        val prefix = "$runId:"
        if (protectedPrefix == prefix) return
        protectedPrefix = prefix
        settledPresentationRun = null
        historicalViewports = viewports.keys.count { !isProtected(it) }
        trimHistoricalViewports()
    }

    fun viewport(blockId: String): ReasoningViewport {
        viewports[blockId]?.let { return it }
        val state = ReasoningViewport()
        viewports[blockId] = state
        if (!isProtected(blockId)) historicalViewports++
        trimHistoricalViewports()
        return state
    }

    private fun isProtected(blockId: String): Boolean = protectedPrefix?.let { blockId.startsWith(it) } == true

    private fun trimHistoricalViewports() {
        if (historicalViewports <= 16) return
        val oldest = viewports.entries.iterator()
        while (historicalViewports > 16 && oldest.hasNext()) {
            if (!isProtected(oldest.next().key)) {
                oldest.remove()
                historicalViewports--
            }
        }
    }

    /** Null while this block has no opinion of its own. */
    fun stored(blockId: String): Boolean? = entries["$conversationId:$blockId"]

    fun set(blockId: String, expanded: Boolean) {
        entries["$conversationId:$blockId"] = expanded
    }

    fun completionDeferred(runId: String): Boolean =
        protectedPrefix == "$runId:" && settledPresentationRun != runId && holdCompletion(runId)

    /** Commit only the expansion actually presented, after the reader's gesture has ended. */
    fun settleSummary(runId: String, expanded: Boolean, deferred: Boolean) {
        if (deferred) return
        if (protectedPrefix == "$runId:") settledPresentationRun = runId
        if (stored(summarySlot(runId)) == null) set(summarySlot(runId), expanded)
    }

    fun hasOpenFold(parts: List<Part>, runId: String,
                    slots: List<String?> = processFoldSlots(parts, runId)): Boolean = parts.indices.any { index ->
        val slot = slots[index] ?: return@any false
        if (stored(slot) == true) return@any true
        val tool = parts[index] as? Part.Tools ?: return@any false
        stored(slot) != false && tool.calls.indices.any { stored("$slot:call:$it") == true }
    }

    fun summaryExpanded(parts: List<Part>, runId: String, streaming: Boolean,
                        slots: List<String?> = processFoldSlots(parts, runId),
                        deferred: Boolean = completionDeferred(runId)): Boolean =
        stored(summarySlot(runId)) ?: (streaming || deferred || hasOpenFold(parts, runId, slots))


}

@Stable
internal class ReasoningViewport {
    private val tableChildren = HashMap<Int, ReasoningViewport>()
    fun table(index: Int): ReasoningViewport = tableChildren.getOrPut(index) { ReasoningViewport() }
    val prose = TextRevealState()
    private var liveMarkdown: com.suixing.agent.ui.chat.markdown.LiveBlocks? = null
    fun growingMarkdown(): com.suixing.agent.ui.chat.markdown.LiveBlocks =
        liveMarkdown ?: com.suixing.agent.ui.chat.markdown.LiveBlocks().also { liveMarkdown = it }
    fun releaseMarkdown() { liveMarkdown = null }
    val listState by lazy { LazyListState() }
    val scrollState by lazy { ScrollState(0) }
    var follow by mutableStateOf(true)
}

internal fun summarySlot(runId: String): String = "$runId:summary"

/** Last prose and anything that arrived after it stay in source order outside the process card. */
internal fun processTailStart(parts: List<Part>): Int =
    parts.indexOfLast { it is Part.Text }.takeIf { it >= 0 } ?: parts.size

/** One pass preserves raw-run think ordinals and the preceding tool-call count. */
internal fun processFoldSlots(parts: List<Part>, runId: String): List<String?> {
    val thinks = HashMap<String, Int>()
    var calls = 0
    return parts.map { part ->
        when (part) {
            is Part.Thinking -> {
                val ordinal = thinks[part.runId] ?: 0
                thinks[part.runId] = ordinal + 1
                "${part.runId.ifEmpty { runId }}:$ordinal"
            }
            is Part.Tools -> "$runId:tools:$calls".also { calls += part.calls.size }
            is Part.Compress -> "$runId:compression:${part.checkpoint.id}"
            else -> null
        }
    }
}

internal fun processFoldSlot(parts: List<Part>, index: Int, runId: String): String? = when (val part = parts[index]) {
    is Part.Thinking -> thinkSlot(parts, index, runId)
    is Part.Tools -> {
        var before = 0
        for (i in 0 until index) before += (parts[i] as? Part.Tools)?.calls?.size ?: 0
        "$runId:tools:$before"
    }
    is Part.Compress -> "$runId:compression:${part.checkpoint.id}"
    else -> null
}

/**
 * Which slot a thinking block occupies in the screen's table of open blocks.
 *
 * The key is the run that produced the think plus how many thinks that run had already
 * produced — not the composable's position, and not the message id, because neither of
 * those is shared by the live block and the stored message that replaces it. The run id is:
 * the engine stamps it on the message it saves, so the same think answers to the same key
 * on both sides of the hand-over. [fallback] covers a message stored before runs were keyed.
 */
internal fun thinkSlot(parts: List<Part>, index: Int, fallback: String): String {
    val part = parts.getOrNull(index) as? Part.Thinking ?: return index.toString()
    // 一次遍历数「同一 run 在这一段之前已经有几段思考」：原来 `parts.take(index)` 每次都分配
    // 一个中间 list —— 卡片里每段思考调一次，展开体里每段再调一次，纯属白分配。
    var before = 0
    for (i in 0 until index) {
        val earlier = parts[i]
        if (earlier is Part.Thinking && earlier.runId == part.runId) before++
    }
    return "${part.runId.ifEmpty { fallback }}:$before"
}

/**
 * The model's thinking, folded into the reply's own card.
 *
 * It is drawn in its own [LazyColumn] so a 10k-character chain of thought only ever
 * composes the handful of lines on screen — laying the whole string out as one [Text] is
 * what makes a long think stutter. Whether it is open is not remembered here: it is a row
 * of the screen's [ReasoningExpansion] table, so the choice outlives this composable and
 * survives the hand-over from the live row to the stored message.
 */
@Composable
internal fun ReasoningBlock(
    text: String,
    streaming: Boolean,
    /** The run can still be live after this individual think stopped growing. */
    runStreaming: Boolean = streaming,
    /** Preserve an untouched live block's shape while its completed run is being touched. */
    preserveLiveExpansion: Boolean = false,
    /** This block's slot in the screen's table: see [thinkSlot]. */
    blockId: String,
    expansion: ReasoningExpansion,
    reasoningTokens: Int = 0,
    elapsedMs: Long = 0,
    /**
     * 外观 → 历史思考块默认展开 ([AppSettings.expandReasoning]): what a block does when the
     * user has never touched its header. A block that is still arriving is always open — it is
     * the reason to run a thinking model, and watching it arrive is the point. One that has
     * settled opens only if this is on: with it off, the transcript stays a transcript, and the
     * header tap is the way into any think the reader wants in full.
     */
    expandReasoning: Boolean = true,
    /**
     * Inside an expanded summary card the block is a row of the card's grid, not a
     * card of its own: no surface, no second header height — just the icon slot, the
     * title and the chain, lined up with the tool rows beside it.
     */
    nested: Boolean = false,
    /** How tall the chain's inner scroll may grow; the appearance page owns it. */
    maxHeight: Dp = 180.dp,
    /**
     * 外观 → 思考块跟随最新: whether the chain's own scroll chases its tail while the think
     * streams. A parameter rather than a composition local because this is the one value the
     * nested rows already carry [maxHeight] for, and a second hidden channel for a second
     * boolean is how two sources of the same truth start to disagree. Defaults to true, which
     * is what the block did before the switch existed.
     */
    followReasoning: Boolean = true,
) {
    // Open unless the user closed it. Both halves of the report are answered here: a block used
    // to fold itself the moment its answer landed, and — past 200 lines — to draw only its first
    // 200 with a 「展开全部 N 行」 button, so the chain being read was replaced by a control the
    // moment it grew. The chain is now drawn whole (its height cap below is the bound, not its
    // length), and the only thing that closes a block is a tap on its own header — recorded in
    // the expansion table, which outlives the hand-over from the live row to the stored message.
    //
    // What a block nobody has touched does is the one thing the appearance setting still decides:
    // a think that is still arriving is open (watching it arrive is why the model was asked to
    // think), and one that has settled follows 历史思考块默认展开. Off, a finished think folds to
    // its header — the reader's tap is the way in, and the table remembers it per conversation.
    val expanded = expansion.stored(blockId) ?: (streaming || preserveLiveExpansion || (!runStreaming && expandReasoning))
    // **增量切行**（见 [IncrementalLines]）：文本是逐字变长的，`text.split('\n')` 每一拍都在
    // 全文上重做一次切分 + 分配一个新列表。切分器记住上一次的文本与结果，只切新增那一段。
    val shown = rememberTypedText(
        text = text,
        enabled = streaming,
        startAtZero = true,
        retained = expansion.viewport(blockId).prose,
    )
    val splitter = remember { IncrementalLines() }
    val lines = remember(shown) { splitter.linesFor(shown) }
    // **remember 真正的依赖**（原来每帧一个 buildList）：`meta` 只由这两个数决定。
    val meta = remember(reasoningTokens, elapsedMs) {
        buildList<String> {
            if (reasoningTokens > 0) add("$reasoningTokens tokens")
            if (elapsedMs > 0) add(formatDuration(elapsedMs))
        }
    }
    // Source order is also visual order. A trailing blank line owns the cursor
    // immediately after a newline, rather than moving it back onto the preceding line.
    val cursorAt = if (streaming) lines.lastIndex else -1
    val firstLine = remember(lines) { lines.firstOrNull { it.isNotBlank() }?.trim().orEmpty() }
    // The chain itself, shared by both shapes: a think with no text at all (the
    // endpoint reported time but no chain) must not render as an empty slab, and a
    // monster chain stays bounded at 320dp of lazy list that follows its tail while
    // the think streams.
    val chain: @Composable () -> Unit = {
        if (lines.none { it.isNotBlank() }) {
            if (!streaming && text.isBlank()) Text(
                text = "（本段无思考文本）",
                style = thinkingStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // Top-anchored lines keep an already-read wrapped line still while its tail grows.
            val viewport = remember(expansion, blockId) { expansion.viewport(blockId) }
            val inner = viewport.listState
            val lineCount by rememberUpdatedState(lines.size)
            // A completed user scroll away from the bottom stops following; reaching
            // the bottom resumes it. Content growth alone must not disable following.
            var innerWasScrolling by remember { mutableStateOf(false) }
            LaunchedEffect(inner) {
                inner.interactionSource.interactions.collect { interaction ->
                    if (interaction is DragInteraction.Start) viewport.follow = false
                }
            }
            LaunchedEffect(inner) {
                snapshotFlow { Triple(inner.isScrollInProgress, inner.canScrollForward, inner.layoutInfo.totalItemsCount > 0) }
                    .collect { (scrolling, canForward, measured) ->
                        if (!measured) return@collect
                        if (!canForward && !scrolling) {
                            viewport.follow = true
                        } else if (innerWasScrolling && !scrolling && canForward) {
                            // 一次滚动结束在非底部 ⇒ 用户拖上去的（程序化贴底只会停在底部）。
                            viewport.follow = false
                        }
                        innerWasScrolling = scrolling
                    }
            }
            // Follow only while opted in. The ordinary top anchor itself keeps a
            // reader still when a wrapped line or later lines grow below them.
            LaunchedEffect(inner, expanded, followReasoning) {
                if (!expanded || !followReasoning) return@LaunchedEffect
                snapshotFlow {
                    inner.canScrollForward to viewport.follow
                }.collect { (offBottom, follow) ->
                    if (follow && offBottom && !inner.isScrollInProgress) inner.scrollToItem(lineCount)
                }
            }
            LazyColumn(
                state = inner,
                reverseLayout = false,
                modifier = Modifier
                    .fillMaxWidth()
                    // Configurable in 外观: a chain of thought is context, and the
                    // answer has to stay on screen while it is being read.
                    .heightIn(max = maxHeight),
                // 首行上边距放在**列表内部**（contentPadding），不是容器上：容器内边距在滚动区
                // 外面，滑到哪它都留在那儿 —— 用户说的「列表滑下去那个空白条还在」就是它。
                // contentPadding 属于可滚动内容，第一行滚上去时这段空白一起滚走。
                contentPadding = PaddingValues(top = BLOCK_CONTENT_TOP),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(
                    count = lines.size,
                    // Appending a logical line never changes an existing line's identity.
                    key = { index -> index },
                    contentType = { "reasoning-line" },
                ) { index ->
                    val line = lines[index]
                    if (line.isBlank()) {
                        // **最新行是空行时，光标就画在这里**：它才是「已显示文本的末尾」
                        // （刚换行、下一个字还没被打字机放出来）。原来是 `Spacer`，光标于是
                        // 跳回上一行末尾 —— 见 cursorAt 上面的说明。
                        if (streaming && index == cursorAt) {
                            // 与有字那行同一个光标实现（内联绘制），空串也会画出竖条。
                            MarkdownText(
                                text = "",
                                style = thinkingStyle(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                cursor = true,
                            )
                        } else if (index > 0) {
                            // A leading blank line takes no extra space below the header.
                            Spacer(Modifier.height(4.dp))
                        }
                    } else {
                        // 思考链每一行都可选中复制（用户：「确实所有情况都要可复制」）——
                        // 由长按武装制满足：不按时零选区成本，长按那一刻容器就位。
                        SelectableWhenArmed {
                        MarkdownText(
                            text = line,
                            // 13sp with roomier leading than bodySmall's: a long chain of
                            // thought is read, not skimmed.
                            style = thinkingStyle(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // 不再额外加上边距：thinkingStyle 是 13sp 字号配 20sp 行高
                            // （ChatBlocks.thinkingStyle），首行上方已经有约 3.5dp 的半行距，
                            // 再叠 padding 就成了用户看到的那点「空白」。
                            modifier = Modifier,
                            // **光标直接拼在这一行的末字符后面**（用户：「根本没有跟在思考链
                            // 的后面」）：以前是一个独立的 `BlinkingCursor()` 跟在 item 后面，
                            // 它自成一行、位置和文字对不上。现在作为 AnnotatedString 的尾段，
                            // 换行和对齐都跟着正文走。
                            // The current final logical line owns the streaming cursor.
                            cursor = streaming && index == cursorAt,
                        )
                        }
                    }
                    // 光标已内联进上一段 MarkdownText（见 cursor 参数），这里不再另起一行。
                }
                // A zero-height terminal item provides exact bottom following even when
                // the final logical line wraps taller than this capped viewport.
                item(key = "reasoning-end", contentType = "reasoning-end") { Spacer(Modifier.height(0.dp)) }
            }
        }
    }
    val toggle = {
        // Written to the table, not to a local: the choice has to survive the hand-over
        // from the live block to the stored one.
        // 贴底这件事不在这里做：底锚下卡片长高天然向上、底边不动，这里再滚一次就会滚两倍。
        expansion.set(blockId, !expanded)
    }
    val status: @Composable () -> Unit = {
        // The gap in front of the first item belongs to the caller: [ProcessCard] already puts
        // 8dp between the name and this trailing slot, so a second one here doubled it to 16dp —
        // the band of nothing the user pointed at after 「思考过程」. The nested row below has no
        // ProcessCard and adds its own.
        // Item by item with a real gap: joining them into one " · " string is what made
        // "256 tokens · 25.1s" read as a single glued-together word at 13sp.
        meta.forEachIndexed { index, item ->
            if (index > 0) Spacer(Modifier.width(7.dp))
            ProcessStatus(item)
        }
        if (streaming) {
            // 用户口径三次叠加：先「运行中的 icon 左右间距不一致」⇒ 放进方槽居中；
            // 再「左右边距小一点」⇒ 7dp → 4dp；再「再小一点」⇒ 4dp → 2dp。
            // **这一处故意不跟 meta 项的 7dp 走**：spinner 是图标不是文字，和「256 tokens」读成一组太松。
            Spacer(Modifier.width(2.dp))
            Box(
                modifier = Modifier.size(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                ProcessSpinner(MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(2.dp))
        }
    }
    if (nested) {
        // **平铺的折叠块**（用户：「思考块就是折叠块的啊，展开还是虚拟列表啊」+「要的就是平铺」）：
        // 外层不再套卡片底（见 ProcessCard），这一块自己是一条折叠条 —— 表头（图标 + 思考过程 +
        // 段信息 + 箭头）+ 展开后仍是虚拟列表（chain 里的 LazyColumn，逐行虚拟化、高度有上限）。
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(processHeaderHeight())
                    // 整块按下反馈（见 blockPress）：平铺后这一行就是折叠条本体，涟漪是小圆角矩形。
                    .blockPress(MaterialTheme.shapes.small, expanded, "思考过程", onClick = toggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_brain),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "思考过程",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta.isNotEmpty()) Spacer(Modifier.width(8.dp))
                status()
                if (!expanded && firstLine.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = firstLine,
                        style = metaStyle(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // The row's one flexible slot, exactly as in [ProcessCard]: the preview
                        // eats the leftover so the chevron sits on the right edge.
                        modifier = Modifier.weight(1f),
                    )
                }
                ExpandChevron(expanded)
            }
            // 和第一层（ProcessCard）走同一条路径（用户：「第一层折叠能自适应，多个工具展开
            // 就全都往上展」）：`AnimatedVisibility(expandVertically)` 只改高度、不碰列表，
            // 底锚下长出来的那一截只能往上顶；RevealHeight 每帧把增长量反向补进列表，块顶边
            // 才不动。淡入淡出随之取消 —— 第一层本来就没有，两层的观感才一致。
            RevealHeight(visible = expanded, durationMs = PROCESS_EXPAND_MS) {
                // 虚拟列表照旧（用户：「展开还是虚拟列表啊」）：chain 内部就是 LazyColumn，
                // 逐行虚拟化 + heightIn(max = 外观里的思考块高度) 上限。
                // top 归 0：首行边距已经放进 chain 内部列表的 contentPadding（会随滚动消失），
                // 这里再留一层就是固定不动的空白条。
                Column(Modifier.padding(start = 44.dp, top = 0.dp)) { chain() }
            }
        }
    } else {
        ProcessCard(
            icon = ImageVector.vectorResource(R.drawable.ic_brain),
            title = "思考过程",
            expanded = expanded,
            onToggle = toggle,
            preview = firstLine.takeIf { it.isNotEmpty() },
            // **折叠条不再挂光标**（用户截图里同一屏出现两个光标）：展开体里已经有那个
            // 跟在末字符后面的光标了，折叠条这个纯属重复。
            previewTail = null,
            trailing = status,
        ) {
            // 思考块**不加**首行间距：正文自带行高，再加一条就是用户看到的那道空白
            // （用户：「思考块分割线下面那个空白」）。工具块仍用 BLOCK_CONTENT_TOP ——
            // 那边是等宽命令/输出，字符本身贴得紧，需要一点 padding。
            chain()
        }
    }
}

/**
 * 折叠块展开后，**内容第一行**与折叠条分割线之间的间距 —— 所有块共用这一个值。
 *
 * 为什么必须是一个常量：思考块（13sp/20sp 行高）、工具块的命令（12sp 等宽）、「输出」行
 * 原本各自用自己的内边距或行高凑间距，于是三个块展开后首行上方的空隙互不相同
 * （用户：「所有块基本的东西都要保持一致」）。现在统一取它。
 */
internal val BLOCK_CONTENT_TOP = 6.dp

/** 13sp / 20sp leading, scaled off bodySmall so 字号 still applies to both. */
// 两个补偿是纯配置对象，提为常量（原来每次调用新建 3 个对象）。
private val NoFontPadStyle = PlatformTextStyle(includeFontPadding = false)
private val FirstLineTopTrim = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.FirstLineTop,
)

@Composable
internal fun thinkingStyle(): TextStyle {
    val body = MaterialTheme.typography.bodySmall
    return remember(body) {
        body.copy(
        lineHeight = body.fontSize * (20f / 13f),
        // 两个补偿缺一不可：
        //  · includeFontPadding = false 去掉**字体自带**的上下 padding；
        //  · Trim.FirstLineTop 去掉**首行**分摊到的那半截行距（20sp 行高配 13sp 字号，
        //    首行上方原本还有约 3.5dp）。
        // 少了第二条，思考块的首行空隙就是 BLOCK_CONTENT_TOP + 3.5dp，而工具块只有
        // BLOCK_CONTENT_TOP —— 仍然是「不一致」，只是数值换了。
        platformStyle = NoFontPadStyle,
            lineHeightStyle = FirstLineTopTrim,
        )
    }
}

/**
 * Every tool call of one turn behind one [ProcessCard]: the header is the summary line,
 * so ten calls cost one row until the user asks for more.
 *
 * Two levels on purpose. The header opens the list of calls; a single call opens its own
 * arguments and output. Arguments are the noisy part, so they stay behind the second tap
 * even once the list is open.
 */
@Composable
internal fun ToolBundle(
    tools: List<ToolLine>,
    expansion: ReasoningExpansion,
    blockId: String,
    runningTool: String? = null,
    startExpanded: Boolean = false,
) {
    val expanded = expansion.stored(blockId) ?: startExpanded
    // **每个工具独立展开**（issue #20）：原来是单个 `String?`，展开 B 会覆盖 A ——
    // 想同时对照「第 1 个的输入」和「第 3 个的输出」就得来回点。用 Set 记住所有打开的，
    // 展开新的不影响已开的。
    // 代价（issue #20 自己提的）：多个 `RevealHeight` 会同时跑动画，位置补偿的负载上升，
    // 与 #13 是同一处风险 —— 若实测位置跳动更明显，再考虑限制「最多同时开 N 个」。
    // **摘要只依赖 tools 本身**：count/标题/预览 remember 住，折叠条常驻，
    // 原来每帧都重算一遍（审计 2§A14）。
    // R4：这个 count 也是同一类 —— 它只随 tools 变，而这一行在每次重组都会被求值一遍。
    // **键要包 IdentityKey**：`tools: List<ToolLine>` 里挂着 `StoredMessage.toolOutput`
    // 与 `imagesBase64`（截图 base64），裸 List 当键会逐字符深比较这些内容。
    // 本仓库已有 6 处这么用，这里漏了。重算本体只是 count + 取首行，O(calls)。
    val failures = remember(IdentityKey(tools)) { tools.count { it.done && !it.ok } }
    val (title, preview) = remember(IdentityKey(tools)) {
        // One call is its own name; several are a count, because the names are what the
        // expanded list is for.
        val t = if (tools.size == 1) {
            tools.first().name.ifBlank { "调用 1 个工具" }
        } else {
            "调用 ${tools.size} 个工具"
        }
        // Folded, the header still says what the call is doing: the first argument line of
        // the newest call (a shell command, a path), which is the row's own content anyway.
        val p = tools.lastOrNull()?.let { toolArgumentPreview(it.name, it.args) }
        t to p
    }
    ProcessCard(
        icon = ImageVector.vectorResource(R.drawable.ic_wrench),
        title = title,
        expanded = expanded,
        onToggle = { expansion.set(blockId, !expanded) },
        preview = preview,
        trailing = {
            when {
                // The name of the tool being waited on is the only useful thing the header
                // can say while it is waiting.
                runningTool != null -> {
                    ProcessStatus(runningTool, MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    ProcessSpinner(MaterialTheme.colorScheme.primary)
                }

                failures > 0 -> ProcessStatus(
                    text = "$failures 个失败",
                    tone = MaterialTheme.colorScheme.error,
                )

                // **「完成」用中性灰**（用户：「折叠后的多个工具的标题颜色还是黑的呀？」）：
                // 上一轮只改了单个工具（ToolDetail）的 tone，漏了「多个工具」这个折叠条的
                // 状态字 —— 它还挂着 primary（深绿 #1F6B4C），与大总结条、思考过程、以及
                // 展开后那些成功的工具行都不一致。仍在跑（runningTool）与失败（failures）保留颜色。
                else -> ProcessStatus("完成", MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    ) {
        // One call: the header already IS that call's row, so expanding shows its
        // detail straight away — an inner row repeating the header's own name is
        // what drew one call as two.
        if (tools.size == 1) {
            ToolDetail(
                line = tools.first(),
                listState = remember(expansion, blockId) { expansion.viewport("$blockId:call:0:output").listState },
            )
        } else {
            // **这里不用虚拟列表，也不用高度上限**（用户：3 个工具条里不该有虚拟列表，
            // 具体工具里面设虚拟列表即可，和单个工具一样）。
            // 原来是 `LazyColumn(heightIn(max = 320.dp))`：代价有两个 ——
            //  ① 工具条被 320dp 截断，第 2、3 个工具看不全，只能在条内滑动；
            //  ② 条内一滑，容器里的内容和主列表的滚动就脱节，接着折叠会让位置跳一下。
            // 改成普通 Column 后：工具条高度 == 内容高度（不被截断、不能滑动），
            // 里面的块长高多少，容器就长高多少 ⇒ 补偿和第一层用同一套算法。
            // 具体的工具内容仍然是虚拟列表（ToolDetail 自己的 180dp 上限，没动）。
            //
            // 不再加 8dp 行距：多工具里每个工具点开后的样子必须和「单个工具」一致，
            // 多一层间距就是多一层它没有的边距。
            Column(Modifier.fillMaxWidth()) {
                tools.forEachIndexed { index, tool ->
                    // 每个工具**各自**点开（用户：「不是点击多个工具马上平铺各个工具」），
                    // 但彼此**不互斥**：展开第二个不会收起第一个（issue #20）。
                    // 展开后的内容与单个工具同一套（ToolRow 内部不再加缩进）。
                    val key = "$blockId:call:$index"
                    key(key) {
                        ToolRow(
                            line = tool,
                            listState = remember(expansion, key) { expansion.viewport("$key:output").listState },
                            // 只有这一轮还在跑才转圈；历史 done=false 是中断遗留。
                            running = runningTool != null,
                            expanded = expansion.stored(key) ?: false,
                            onToggle = {
                                expansion.set(key, !(expansion.stored(key) ?: false))
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * What a call actually did: its arguments, then its output behind a copy button, then
 * any screenshot it took. Shared by the two-level row and the single-call bundle, so
 * a live call and a settled one show the same thing.
 */
@Composable
internal fun ToolDetail(line: ToolLine, maxHeight: Dp = 180.dp, listState: LazyListState? = null) {
    val scheme = MaterialTheme.colorScheme
    val args = line.args.takeIf { it.isNotBlank() && it != "{}" }
    val output = line.message?.toolOutput?.takeIf { it.isNotBlank() }
    // 行表在这里算（`LazyColumn` 的 content lambda **不是** `@Composable`，里面不能用 remember）。
    // 不 remember 的话，工具块在流式期间每个发布节拍重组都会重跑一次 lines()。
    val rows = remember(output) { output?.lines().orEmpty() }
    // 工具输出的「复制」：写剪贴板 + Toast（用户：「复制后给个提示」）。
    val copyOutput = rememberCopyToClipboard()
    // 与思考块同一套形态：**虚拟列表 + 高度上限**，内容比上限短时按真实高度渲染。
    // 两处刻意的差别（用户要求）：
    //  · 命令**不截断**（原来 maxLines = 2 + Ellipsis，长命令根本读不全）；
    //  · 输出不再用「固定 132dp 的滚动 Text」，而是逐行进入虚拟列表，长输出不会一次性排版。
    // 命令与输出都可选中复制（用户：「确实所有情况都要可复制」）——同上，武装制。
    SelectableWhenArmed {
    // 12sp 等宽正文样式：`MaterialTheme.typography` 是 @Composable 读取，**不能**写在
    // remember 的 lambda 里（编译器报 “@Composable invocations can only happen from …”）。
    // 先取出 typography，再 remember 派生值；下面逐行复用它。
    val bodySmall = MaterialTheme.typography.bodySmall
    val monoStyle = remember(bodySmall) { bodySmall.copy(fontSize = 12.sp) }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxHeight), state = listState ?: rememberLazyListState()) {
        if (args != null) {
            item(key = "args") {
                Text(
                    text = args,
                    style = monoStyle,
                    fontFamily = FontFamily.Monospace,
                    color = scheme.onSurfaceVariant,
                    // 上 6dp：卡片内容区顶部现在是 0，命令直接贴着折叠条分割线太挤
                    // （用户：「输入那个指令就相当于贴边了」）。只给 args 自己留，不给整块内容区留 ——
                    // 这样「只有输出」的卡片上方仍然是干净的 2dp。
                    modifier = Modifier.fillMaxWidth().padding(top = BLOCK_CONTENT_TOP),
                )
            }
        }
        if (output != null) {
            if (args != null) {
                // 输入（命令/参数）与输出之间要有一条分割线：两段都是等宽小字，没有这条线
                // 读起来是连续的一大段，分不清哪是让它做的、哪是它返回的。
                item(key = "divider") {
                    // 只留上方 6dp：下方再留就成了「分割线与「输出」之间 6+8=14dp」的空白
                    // （用户看出来的「这一行离分割线的间距很大」）。
                    HorizontalDivider(
                        color = scheme.outlineVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            item(key = "output-head") {
                // 垂直居中对齐：28dp 的复制按钮和「输出」两字的视觉中线必须重合，用 Top 对齐时
                // 图标会显得比文字高一截（用户看出来的）。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // 卡片内容区上方已经是 0dp，所以这里固定 2dp：有输入时距分割线 2dp、
                    // 只有输出时距卡片分割线也是 2dp —— 两种卡片看起来一样。
                    modifier = Modifier.padding(top = BLOCK_CONTENT_TOP),
                ) {
                    if (!line.ok) {
                        // A failed call's output is the evidence; the bar marks it as such.
                        Box(
                            Modifier
                                .width(3.dp)
                                .heightIn(min = 16.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(scheme.error),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    // 左边标出「输出」：复制按钮在右边，左侧空着时那一行看不出是什么区段
                    // （用户：「显得左侧很空，可以在左侧写上输出二字」）。
                    Text(
                        text = "输出",
                        style = metaStyle(),
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { copyOutput(output) },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_copy),
                            contentDescription = "复制输出",
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            // 逐行进虚拟列表：行数再多也只排版可见的那些。
            items(rows.size, key = { index -> "out-$index" }, contentType = { "tool-output" }) { index ->
                Text(
                    text = rows[index],
                    style = monoStyle,
                    fontFamily = FontFamily.Monospace,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = if (line.ok) 0.dp else 11.dp),
                )
            }
        }
    }
    }
    val message = line.message
    if (message != null && message.imageCount > 0) message.allImages.forEach { ImageBlock(it) }
}

/**
 * 工具类型 → 图标（任务7）：按工具名关键词归类，让搜索/终端/文件在卡片里一眼可分。
 * 命中不了的一律 [R.drawable.ic_wrench] —— 通用工具保持原样。
 * 顺序敏感：更具体的词放前面（如 `web_fetch` 先命中网页组）。
 */
internal fun toolIconRes(name: String): Int {
    val n = name.lowercase()
    return when {
        // 网页与搜索：web_search / web_fetch / browse / open_url / 插件搜索…
        listOf("web", "search", "browse", "fetch", "url", "http").any { it in n } ->
            R.drawable.ic_globe
        // 终端与命令：run_shell / bash / exec…
        listOf("shell", "bash", "command", "terminal", "exec").any { it in n } ->
            R.drawable.ic_terminal
        // 文件写入与编辑：edit_file / write / patch
        listOf("edit", "write", "patch").any { it in n } ->
            R.drawable.ic_file_code
        // 文件读取与列目录：read_file / list_dir
        listOf("file", "read", "dir", "doc").any { it in n } ->
            R.drawable.ic_file_text
        else -> R.drawable.ic_wrench
    }
}

@Composable
internal fun ToolRow(
    line: ToolLine,
    expanded: Boolean,
    onToggle: () -> Unit,
    /** 这一轮是否还在跑：历史里被中断落盘的 `done=false` 行不是在跑，不能永久转圈。 */
    running: Boolean = false,
    listState: LazyListState? = null,
) {
    // Lowercasing the tool name + four keyword lists is pure work; a scroll or a live
    // state change must not redo it for the same row.
    val icon = remember(line.name) { toolIconRes(line.name) }
    val tone = when {
        !line.done -> MaterialTheme.colorScheme.primary
        // **成功用中性灰**（用户：「工具卡 成功改成 灰的对齐上面两个」）：
        // 大总结条与思考过程的表头都是 onSurfaceVariant，成功的工具没理由更显眼；
        // 仍在跑（primary）和失败（error）保留颜色，那两种情况才需要一眼看出来。
        line.ok -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }
    Column(
        Modifier
            .fillMaxWidth()
            // 整块按下反馈（见 blockPress）：这一列**展开后包含整段参数与输出**，是有多高就多高
            // 的节点 —— 涟漪的半径按「到最远角的距离」算，于是画出来正是一个大圆，也就是用户
            // 截图里那个「为什么是个圆形」。
            .blockPress(MaterialTheme.shapes.small, expanded, line.name, onClick = onToggle)
            // 折叠块统一：水平 12dp 内边距与另外两处表头一致；行高加在**表头那一行**上（这一列
            // 展开后还有内容，给 Column 定高会把详情裁掉）。
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(processHeaderHeight()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Same 24dp slot the thinking rows use, so both icon columns share one
            // left edge inside the expanded card.
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (line.done) {
                    Icon(
                        imageVector = ImageVector.vectorResource(icon),
                        contentDescription = null,
                        tint = tone,
                        modifier = Modifier.size(16.dp),
                    )
                } else if (running) {
                    // A call in flight is a spinner, not a dot: the row has to say "still
                    // working" while the model waits on it.
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = tone,
                    )
                } else {
                    // 历史遗留的 done=false（run 被杀后落盘）：静态类型图标，不再转。
                    // 不定式进度圈按屏幕刷新率重绘，常驻卡片挂着它 = 读历史一直烧帧。
                    Icon(
                        imageVector = ImageVector.vectorResource(icon),
                        contentDescription = null,
                        tint = tone,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = line.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // **标题也要上色**（用户：「调用xx个工具下的次折叠条的工具的标题还是黑色的」）：
                // 这里原来没传 color，于是回落到 `LocalContentColor`（= onSurface #161D19，近黑）。
                // 同一行的图标和状态字早已用 `tone`，只有标题漏了 —— 补上后三个元素同一色系：
                // 成功 = onSurfaceVariant（中性灰，与大总结条/思考过程同色）、运行中 = primary、失败 = error。
                color = tone,
                // The name is short and fixed — it takes what it needs and yields the rest to
                // the argument preview. Holding a half-row share cut it in the middle instead:
                // `run_shell` came back as 「run_sh…」, which is the fragment the user read as
                // 「sh」 sitting in the header.
                modifier = Modifier.widthIn(max = PROCESS_TITLE_MAX),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = when {
                    !line.done -> "运行中…"
                    line.elapsedMs > 0 -> (if (line.ok) "✓ " else "✗ ") + formatDuration(line.elapsedMs)
                    line.ok -> "✓"
                    else -> "✗"
                },
                style = metaStyle(),
                color = tone,
                maxLines = 1,
                softWrap = false,
            )
            // What the call is doing, on the row itself: a folded tool row that only
            // says 「完成」 is exactly what the user called 「执行什么内容也没有」.
            // R4：`line.args` 是这一行唯一的相关输入；逐帧 lineSequence + firstOrNull 只为
            // 了拿它的第一行，remember 住（外层 ToolDetail 也是一个常驻分支）。
            val firstArg = remember(line.name, line.args) {
                toolArgumentPreview(line.name, line.args)
            }
            if (firstArg != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = firstArg,
                    style = metaStyle(),
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            ExpandChevron(expanded)
        }
        // 同 ReasoningBlock：和第一层一样走 RevealHeight，展开时块顶边不动、内容往下长。
        RevealHeight(visible = expanded, durationMs = PROCESS_EXPAND_MS) {
            // 与「单个工具」同一套边距：这里**不加** 44dp 缩进 —— 那是多工具独有的、用户
            // 明确指出的多余边距（「要像单个工具一样没有边距」）。
            Column(Modifier.fillMaxWidth()) {
                ToolDetail(line = line, listState = listState)
            }
        }
    }
}
/**
 * The turn's own summary: how much it thought, how many tools it ran, and what it cost,
 * folded into the same card family as the blocks above it.
 *
 * It replaced a bare line of small print under the reply, which read as a footnote rather
 * than as the answer's own record — and it is where the per-segment numbers live, so the
 * thinking and tool blocks above stay one line each.
 */
@Composable
internal fun TurnSummary(
    parts: List<Part>,
    usage: Usage?,
    live: StreamStats?,
    contextWindow: Int,
    reasoningMaxHeight: Dp = 180.dp,
    /** 外观 → 思考块跟随最新; [ReasoningBlock.followReasoning]. */
    followReasoning: Boolean = true,
    /** 外观 → 历史思考块默认展开; passed on to each think in this card. */
    expandReasoning: Boolean = true,
    /** 外观 → 工具块默认展开; 与运行时同一套（[ToolBundle.startExpanded]）。 */
    expandTools: Boolean = false,
    /**
     * The run's own note — the compression line, when there was one. It belongs in this
     * header rather than only in the status line: the status line is gone the moment the run
     * ends, and "the context was folded here" is a fact about this turn. It rides inside the
     * card, so it folds away with the rest of it.
     */
    note: String = "",
    /** The message these blocks belong to; part of every block's key in the table. */
    messageId: String = "",
    reasoningExpansion: ReasoningExpansion,
    runId: String = messageId,
    foldSlots: List<String?>? = null,
    streaming: Boolean = false,
    runningTool: String? = null,
    // **删掉 `preserveExpanded`（本轮审计）**：它从这一层一路转传到折叠块，但全仓没有任何
    // 读取点（`grep preserveExpanded` 只剩声明与转传）。而调用处传的是
    // `!scroll.stickToBottom && !scroll.restoring` —— 那是在**每个 item 的组合域里**读两个
    // 滚动状态 ⇒ 它们一翻（用户拖动跨过 8px 阈值、或回底协程置位），所有已组合的行全部失效。
    // 值没人用，代价却是整片重组，因此整条链删除。
    runningElapsed: androidx.compose.runtime.State<Long>? = null,
    runElapsedMs: Long = 0L,
    onEdit: (StoredMessage) -> Unit = {},
) {
    // Metadata only: establish protection before the child viewport lookups compose.
    // Keep it through completion; the next live run releases it into bounded history.
    if (streaming) reasoningExpansion.protectRun(runId)
    val slots = foldSlots ?: remember(IdentityKey(parts), runId) { processFoldSlots(parts, runId) }
    val deferredCompletion by remember(reasoningExpansion, runId) {
        derivedStateOf { reasoningExpansion.completionDeferred(runId) }
    }
    val expanded = reasoningExpansion.summaryExpanded(parts, runId, streaming, slots, deferredCompletion)
    SideEffect {
        if (!streaming) {
            // Latch the hand-over once. Closing an inner block later must not close
            // the whole process card around the reader as a side effect. A finger down or
            // an active scroll postpones only this default layout hand-over, not completion.
            reasoningExpansion.settleSummary(runId, expanded, deferredCompletion)
        }
    }
    // **只数数量，不建 List**（issue #56 R21）：下面只用到段数与调用数，
    // 而 `filterIsInstance` + `flatMap` 每次各建一个集合；流式期 `parts` 每 16ms 换实例，
    // 等于每秒 60 次纯分配。一次遍历数出两个数就够。
    val partCounts = remember(IdentityKey(parts)) {
        var thinking = 0
        var toolCalls = 0
        for (part in parts) {
            when (part) {
                is Part.Thinking -> thinking++
                is Part.Tools -> toolCalls += part.calls.size
                else -> Unit
            }
        }
        thinking to toolCalls
    }
    val thoughtCount = partCounts.first
    val callCount = partCounts.second
    val settled = usage?.takeIf { it.known }
    // The prompt size of the newest round is what is actually sitting in the window now.
    // While a run streams, the engine's copy of the endpoint's last report is fresher than
    // anything already stored, so it wins.
    val context = live?.lastPromptTokens?.takeIf { it > 0 } ?: usage?.prompt ?: 0
    // A newly composed/recreated summary must not restart the run's clock.
    val elapsedMs = if (streaming) runningElapsed?.value ?: 0L
        else runElapsedMs.takeIf { it > 0L } ?: live?.elapsedMs ?: settled?.elapsedMs ?: 0L
    // 标题串只在**影响它的四个量**变了才重算（每次重组都 buildList 一遍是纯浪费）。
    val headline = remember(streaming, elapsedMs, thoughtCount, callCount) {
        buildList {
            if (streaming || elapsedMs > 0) add("用时 ${formatRunElapsed(elapsedMs)}")
            if (thoughtCount > 0) add("思考 $thoughtCount 段")
            if (callCount > 0) add("工具 $callCount 个")
        }
    }
    // While a run streams the header shows the numbers that move; a settled reply shows
    // the ones the endpoint reported.
    // The numbers ride inside the title rather than in their own slot, so they shorten
    // with it and the chip after them is never the thing that gets squeezed.
    // 标题串只在输入变化时重算（每次重组 3 个 buildList + joinToString 是纯浪费）。
    val header = remember(headline, note, streaming) {
        buildList {
        add(if (headline.isEmpty()) if (streaming) "正在回复" else "本轮用量" else "本轮：${headline.joinToString(" · ")}")
        // After the numbers, before nothing else: the compression line is a fact about this
        // turn, so it rides in the same title and folds away with the card.
        if (note.isNotBlank()) add(note)
        }.joinToString(" · ")
    }
    // 尾段起点只取决于 parts —— 记住它，展开判定与下面的内容用**同一个**值
    // （原来这里又算了一遍 `processTailStart(parts)`）。
    val tailStart = remember(IdentityKey(parts)) { processTailStart(parts) }
    ProcessCard(
        icon = ImageVector.vectorResource(R.drawable.ic_chart_column),
        title = header,
        expandable = parts.isNotEmpty() && tailStart > 0,
        expanded = expanded,
        contentBottomPadding = 0.dp,
        onToggle = { reasoningExpansion.set(summarySlot(runId), !expanded) },
        // The elapsed title is the run indicator; no summary spinner (user preference).
    ) {
        // Expanding means the run as it happened: every piece in arrival order —
        // thinking blocks (foldable, full text), tool rows, every stretch of prose
        // including the mid-run transitions, steer bubbles — the same sequence the
        // live bubble draws. Usage details stay below the final prose.
        // The prefix and flat tail form one visual sequence.
        // **第一个折叠条上方不再留内边距**（用户：「完全去掉看看」）：分割线下面直接就是它，
        // 中间的空隙只剩 `spacedBy` 管的「子项之间」，而它对第一个不生效。
        // 原来这里是 `padding(top = if (tailStart > 0) 6.dp else 0.dp)` —— 给「新正文把旧尾段
        // 挪到这儿」的情况留一点空气；现在整块不加，那种情况自然也不会冒出来。
        // **块之间不留空隙**（用户要求 0dp）：思考块与工具块自己带内边距，
        // 再叠 6dp 会让展开后的过程区读成一串松散的分段。
        Column(
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // The answer itself is already drawn by the bubble below this card (the settled
            // path keeps the last stretch of prose). Leaving a second copy in here is what
            // made one reply read as a stack of the same text; the mid-run transitions — the
            // model's "let me check" before a tool — stay, because the bubble does not show
            // those and the card is their only record.
            // R5：`indexOfLast` 只随 parts 变（列表元素是数据类，新块诞生/追加即 key 变），
            // 折叠卡展开时却每帧都在整条 parts 上重扫一遍。
            parts.forEachIndexed { index, part ->
                // Guarded before key(index), never inside it: a non-local return out of a key
                // group leaves the group unclosed and Compose aborts with "Start/end
                // imbalance" — the crash this file has already paid for once.
                if (index >= tailStart) return@forEachIndexed
                key(index) {
                    when (part) {
                        is Part.Thinking -> ReasoningBlock(
                            text = part.text,
                            streaming = streaming && index == parts.lastIndex,
                            runStreaming = streaming || deferredCompletion,
                            preserveLiveExpansion = deferredCompletion && index == parts.lastIndex,
                            // One message can hold several thinks (think → tool → think);
                            // the slot is the run plus the think's place inside it.
                            blockId = slots[index]!!,
                            expansion = reasoningExpansion,
                            reasoningTokens = part.tokens,
                            elapsedMs = part.elapsedMs,
                            // A think from an earlier launch is a settled one, so it opens only if
                            // 历史思考块默认展开 is on; a tap on its header decides it per
                            // conversation either way (the expansion table is keyed by chat).
                            expandReasoning = expandReasoning,
                            // *原样展开**（用户：「折叠条只把最后那段最终文本之外的收起，展开时原样
                            nested = false,
                            maxHeight = reasoningMaxHeight,
                            followReasoning = followReasoning,
                        )

                        is Part.Tools -> {
                            // **与运行时同一套渲染**（用户：「展开时原样展开，不对 UI 做任何更改」）：
                            // 这里原来每个调用单独一行，运行时是合并成「调用 N 个工具」的 ToolBundle。
                            ToolBundle(
                                tools = part.calls,
                                expansion = reasoningExpansion,
                                blockId = slots[index]!!,
                                runningTool = runningTool.takeIf { streaming && index == parts.lastIndex },
                                startExpanded = expandTools,
                            )
                        }

                        // Prose keeps the card's own left edge: it is read as the answer's
                        // body, not as a row of the grid, and 2dp of vertical air plus the
                        // column's 8dp keeps it off its neighbours.
                        // This prose already appeared in the flat tail. Reparenting
                        // it here must never replay its typewriter from an empty string.
                        is Part.Text -> AssistantProse(part, streaming, deferSettlement = deferredCompletion,
                            readingKey = "$runId:text:$index",
                            viewport = reasoningExpansion.viewport("$runId:text:$index"))

                        // Earlier steering remains at its actual position in the process.
                        is Part.Steer -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            UserBubble(message = part.message, onEdit = onEdit)
                        }

                        // 重试留痕：与思考块/工具块同一套栅格（见 TraceBlock），留白不再自己加。
                        is Part.Trace -> TraceBlock(part.note)

                        // 压缩块：位置就是它发生的地方（思考 → 工具 → 压缩 → 继续）。
                        is Part.Compress -> CompressionBlock(part.checkpoint, reasoningExpansion,
                            slots[index]!!)
                    }
                }
            }
        }
    }
}


/** One line inside [TurnSummary] is gone with the segment rows; the expanded card now
 * draws full thinking blocks and two-level tool rows instead. */

