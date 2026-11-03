package com.suixing.agent.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.suixing.agent.ui.PageMotion
/**
 * 主窗口里的全屏覆盖层：进出场与导航页使用同一套 [PageMotion]。
 *
 * 退场期间 [content] 仍然在组合里（AnimatedVisibility 的语义），所以宿主要把「最后一次的
 * 内容」留到退场结束（见各宿主的 lastId 写法），避免退出过程中变成空白页。
 */
@Composable
fun FullScreenOverlay(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(
            animationSpec = PageMotion.spec(entering = true),
        ) { fullWidth -> fullWidth },
        exit = slideOutHorizontally(
            animationSpec = PageMotion.spec(entering = false),
        ) { fullWidth -> fullWidth },
    ) {
        content()
    }
}
