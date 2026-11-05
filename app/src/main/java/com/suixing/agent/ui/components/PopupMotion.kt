package com.suixing.agent.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Independent opacity keeps the card readable while its small spatial motion settles. */
internal class PopupMotionState {
    val progress = Animatable(0f)
    val opacity = Animatable(0f)

    suspend fun show() = coroutineScope {
        // Make text readable first, then settle with a short, restrained spatial tail.
        launch { opacity.animateTo(1f, tween(100)) }
        progress.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 850f))
    }

    suspend fun hide(durationMs: Int = POPUP_ANIM_MS) = coroutineScope {
        launch { opacity.animateTo(0f, tween(durationMs.coerceAtMost(100))) }
        progress.animateTo(0f, tween(durationMs, easing = FastOutLinearInEasing))
    }
}

/** Keep existing callers' card geometry while reserving real window space for its shadow. */
internal class ShadowPopupPositionProvider(
    private val margin: Int,
    private val delegate: PopupPositionProvider?,
    private val alignment: Alignment,
    private val offset: IntOffset,
    private val onOrigin: (TransformOrigin) -> Unit = {},
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val cardSize = IntSize((popupContentSize.width - margin * 2).coerceAtLeast(0),
            (popupContentSize.height - margin * 2).coerceAtLeast(0))
        val position = delegate?.calculatePosition(anchorBounds, windowSize, layoutDirection, cardSize)
            ?: (anchorBounds.topLeft + alignment.align(IntSize.Zero, anchorBounds.size, layoutDirection) -
                alignment.align(IntSize.Zero, cardSize, layoutDirection) +
                IntOffset(if (layoutDirection == LayoutDirection.Ltr) offset.x else -offset.x, offset.y))
        val boxPosition = position - IntOffset(margin, margin)
        // **夹回窗口内**（issue #56 R30）：窗口是「卡片 + 每边 margin」，`clippingEnabled=false`
        // 时超出屏幕的部分会被测量压扁。原来只有 `ModelSelectors` 自己算 margin 规避，
        // 其余 7 个调用点都吃默认值。定位阶段已经同时拿到 `windowSize` 与 `popupContentSize`，
        // 在这里统一夹一次，调用方不必各算各的。
        // **只在装不下时才夹**（issue #57 R30 的落点）：定位结果允许为负、允许贴边 ——
        // 那是弹窗相对屏幕的正常摆放（`PopupShadowPositionTest` 钉着居中的算法）。
        // 真正会出问题的是**整块比窗口还大**：`clippingEnabled=false` 的窗口会被测量约束
        // 压扁。这种情况把左上角贴到 0，让内容拿到最大可用空间。
        val boxSize = IntSize(cardSize.width + margin * 2, cardSize.height + margin * 2)
        val tooBig = boxSize.width > windowSize.width || boxSize.height > windowSize.height
        val clampedPosition = if (tooBig) IntOffset(0, 0) else boxPosition
        // The animated layer is the padded box, so its pivot is measured against that box: the
        // anchor's centre relative to the box, not relative to the card inside it.
        val boxWidth = boxSize.width
        val boxHeight = boxSize.height
        onOrigin(TransformOrigin(
            if (boxWidth == 0) 0.5f else ((anchorBounds.center.x - clampedPosition.x).toFloat() / boxWidth).coerceIn(0f, 1f),
            if (boxHeight == 0) 0.5f else ((anchorBounds.center.y - clampedPosition.y).toFloat() / boxHeight).coerceIn(0f, 1f),
        ))
        return clampedPosition
    }
}
