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

/** 揭示动画的时长档位：高度 → 毫秒。 */
internal val REVEAL_DURATION_STOPS = listOf(
    0f to 130,
    200f to 180,
    480f to 360,
    900f to 550,
    1200f to 680,
)
internal val COLLAPSE_DURATION_STOPS = listOf(
    0f to 110,
    200f to 150,
    480f to 300,
    900f to 470,
    1200f to 580,
)
internal const val COLLAPSE_BASE_SCALE_MS = 220f
internal const val MIN_REVEAL_TRANSITION_MS = 80

/**
 * 折叠/展开的时长、缓动与打断策略：纯算术，只吃高度与阈值，不碰组合状态。
 * 从 ChatBlocks 搬出来：它与卡片绘制无关。
 */

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
