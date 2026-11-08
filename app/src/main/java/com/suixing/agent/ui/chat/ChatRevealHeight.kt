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

/** 展开/收起的实测高度驱动：测量、动画规格与滚动补偿。 */

@Composable
internal fun RevealHeight(
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
