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
 * 投影：**轻**、**单层**（用户：「阴影我不需要很重的，全部改轻」「阴影不要有偏移」，
 * 后来又报「有分层」「刚出来被截一点」，0.8.553 上又报「没有阴影了」）。
 *
 * 机制（0.8.553 实测踩到的坑）：Android 的 **ambient 阴影还要再乘主题的
 * `ambientShadowAlpha`**，而该主题属性的默认值只有 **0.039**（AOSP：`core/res/res/values/
 * themes.xml:457` 指向 `core/res/res/values/dimens.xml:756` 的 0.039；spot 对应 0.19，
 * 见 dimens.xml:757）。所以只开 ambient 等于没有阴影 —— 哪怕给的是 18% 的黑。
 * 真正看得见的那层一直是 `spotColor`。反过来，ambient+spot 同时开着，
 * 两层模糊半径/偏移不同，叠在一起就是用户说的「分层」。
 *
 * 结论：**只留 spot 一层**。浓度与 elevation 沿用用户认可过的 18% / 10dp；
 * `ambientColor` 置透明，`clip = false` 让阴影画到节点边界之外（给 24dp 余量）。
 */
internal fun Modifier.popupCardSurface(color: Color): Modifier = this
    .shadow(
        elevation = 10.dp,
        shape = PopupCardShape,
        clip = false,
        ambientColor = Color.Transparent,
        spotColor = Color.Black.copy(alpha = 0.18f),
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
