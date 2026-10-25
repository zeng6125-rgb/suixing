package com.suixing.agent.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * 小浮层（`Popup`）的统一进出场。
 *
 * 为什么需要：模型列表、上下文用量这些浮层原来写成 `if (open) { Popup(...) }` —— 一挂就整块
 * 出现、一撤就整块消失。用户的原话是「出现得很生硬」「关掉的阴影不连续」（`Popup` 的窗口由
 * 系统直接创建/销毁，本身没有任何过渡）。
 *
 * 用法：
 * ```kotlin
 * val motion = rememberPopupMotion()
 * LaunchedEffect(open) { if (open) motion.enter() else motion.exit() }
 * if (open || motion.keeping) {
 *     Popup(...) { Box(motion.modifier()) { 内容 } }
 * }
 * ```
 * 关键点是 [keeping]：退场动画期间浮层**必须仍在组合里**，否则用户看不到退场。
 *
 * 入场 150ms（`LinearOutSlowInEasing`，与全站同一条曲线）、退场 140ms（与 MenuCard 的 MENU_EXIT_MS 对齐，避免遮罩比卡片早消失）（进慢出快）；
 * 缩放 0.94→1、上浮 8dp、透明度 0→1 —— 小浮层比对话框更轻，幅度也更小。
 */
@Stable
internal class PopupMotion {
    /** 是否仍需要留在组合里（入场中或退场中）。 */
    var keeping by mutableStateOf(false)
        private set

    private val animation = Animatable(0f)

    suspend fun enter() {
        keeping = true
        animation.animateTo(1f, tween(POPUP_IN_MS, easing = LinearOutSlowInEasing))
    }

    suspend fun exit() {
        if (!keeping) return
        animation.animateTo(0f, tween(POPUP_OUT_MS))
        keeping = false
    }

    /** 挂到浮层内容的根节点：值只在绘制期读，不触发重组。 */
    fun modifier(): Modifier = Modifier.graphicsLayer {
        val p = animation.value
        alpha = p
        val scale = 0.94f + 0.06f * p
        scaleX = scale
        scaleY = scale
        translationY = (1f - p) * 8.dp.toPx()
    }
}

@Composable
internal fun rememberPopupMotion(): PopupMotion = remember { PopupMotion() }

private const val POPUP_IN_MS = 150
private const val POPUP_OUT_MS = 140
