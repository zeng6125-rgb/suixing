package com.suixing.agent.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * 小浮层的**唯一**卡片样式。
 *
 * 用户的要求是「全部小浮层动画 + 卡片样式统一」。原来每个浮层各写一份 `shadow + clip +
 * background`：`MenuCard` 是 20dp 圆角，上下文圆环/会话统计却是 16dp —— 并排看就是两种质感。
 *
 * 收敛到一处之后，圆角、投影、底色只有这一个版本；想改样式只改这里。
 */
internal val PopupCardShape = RoundedCornerShape(20.dp)

/**
 * 投影：**轻**、**不带偏移**（用户：「阴影我不需要很重的，全部改轻，同时阴影不要有偏移，
 * 要有质感的阴影」）。
 *
 * 做法：`ambientColor` 与 `spotColor` 同色同浓度 ⇒ 只有环境光没有方向性偏移，看着是"浮起
 * 一层纸"而不是"贴在某一边"。elevation 从 8dp 降到 3dp，配合 18% 的黑 ⇒ 轻。
 *
 * 用 `ambientColor`/`spotColor` 显式给色，而不是靠 elevation 推算：Material 的默认阴影色
 * 偏灰且随 elevation 加重，用户看到的"重"就是它。
 */
internal fun Modifier.popupCardSurface(color: Color): Modifier = this
    .shadow(
        // 扩散加大（用户：「阴影扩散再大一点，现在感觉聚在一起没有质感」）：elevation 决定
        // 模糊半径，从 3dp 提到 10dp 光晕摊得更开；浓度同时从 18% 降到 10%，否则摊开之后
        // 会发黑发脏。ambient 与 spot 同色 ⇒ 没有方向性偏移。
        elevation = 10.dp,
        shape = PopupCardShape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = 0.18f),
        spotColor = Color.Transparent,
    )
    .clip(PopupCardShape)
    .background(color)

/** How long a popup card's exit animation runs before the popup is taken down. */
internal const val MENU_EXIT_MS = 140L

/** Menus leave faster than they enter; the outer touch shield outlives this exit. */
internal const val POPUP_ANIM_MS = 110

internal val PopupShadowMargin = 24.dp

/** Read animation frames in the layer, keeping content measurement and popup anchors stable. */
internal fun Modifier.popupMotion(state: PopupMotionState, origin: () -> TransformOrigin = { TransformOrigin.Center }): Modifier =
    graphicsLayer {
        val p = state.progress.value
        // Group opacity composites the card **and its shadow** into one offscreen buffer sized to
        // this node, so the caller must hand this modifier a node that already reserves the
        // shadow margin (MenuCard's [PopupShadowMargin] box). Outsizes cannot rescue a card-sized
        // layer: the shadow is drawn by the node's own shadow modifier, inside this buffer.
        // ModulateAlpha is not an option either — it would show the shadow through the card.
        clip = false
        alpha = state.opacity.value
        scaleX = 0.94f + 0.06f * p
        scaleY = scaleX
        transformOrigin = origin()
        // Grow out of the triggering control: a menu opening upward starts lower, one opening
        // downward starts higher, so the card reads as coming from the button that summoned it.
        translationY = (1f - p) * 8.dp.toPx() * (transformOrigin.pivotFractionY * 2f - 1f)
    }
