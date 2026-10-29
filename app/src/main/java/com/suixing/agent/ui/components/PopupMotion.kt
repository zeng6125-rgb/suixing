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
        // Opacity lands first so the card is readable immediately; the spatial settle keeps a
        // short, slightly under-damped tail (≈300ms) so the card arrives with some life.
        launch { opacity.animateTo(1f, tween(100)) }
        progress.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 700f))
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
        // The animated layer is the padded box, so its pivot is measured against that box: the
        // anchor's centre relative to the box, not relative to the card inside it.
        val boxWidth = cardSize.width + margin * 2
        val boxHeight = cardSize.height + margin * 2
        onOrigin(TransformOrigin(
            if (boxWidth == 0) 0.5f else ((anchorBounds.center.x - boxPosition.x).toFloat() / boxWidth).coerceIn(0f, 1f),
            if (boxHeight == 0) 0.5f else ((anchorBounds.center.y - boxPosition.y).toFloat() / boxHeight).coerceIn(0f, 1f),
        ))
        return boxPosition
    }
}
