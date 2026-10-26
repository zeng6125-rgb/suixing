package com.suixing.agent.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
/**
 * 主窗口里的全屏覆盖层：进出场与导航页同一套动画（[NAV_ENTER_MS]/[NAV_EXIT_MS]）。
 *
 * 退场期间 [content] 仍然在组合里（AnimatedVisibility 的语义），所以宿主要把「最后一次的
 * 内容」留到退场结束（见各宿主的 lastId 写法），否则淡出的那 200ms 会是一片空白。
 */
@Composable
fun FullScreenOverlay(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(
            animationSpec = tween(durationMillis = NAV_ENTER_MS, easing = FastOutSlowInEasing),
        ) { fullWidth -> fullWidth / NAV_SHIFT_DIVISOR },
        exit = fadeOut(tween(NAV_EXIT_MS, easing = FastOutSlowInEasing)),
    ) {
        content()
    }
}
