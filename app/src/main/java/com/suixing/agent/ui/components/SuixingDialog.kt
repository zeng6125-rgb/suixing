package com.suixing.agent.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/**
 * 对话框统一入口：**官方尺寸与遮罩 + 自己控制的进出场**。
 *
 * 关键在 [DialogProperties] 用**默认值**（`usePlatformDefaultWidth = true`）：窗口宽度、外边距、
 * 系统遮罩都还是官方的 —— 之前为了自绘遮罩把窗口改成铺满，结果卡片被内容撑到整屏宽，用户看到
 * 的就是「对话框贴着屏幕」。这里不再碰窗口，只在官方卡片内部做 alpha/scale 动画。
 *
 * 进场 140ms、退场 140ms（与全站小浮层同一套时长）；退场**先播完再 dismiss**，所以按钮走
 * [DialogScope.closeThen]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SuixingDialog(
    onDismissRequest: () -> Unit,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
    confirmButton: @Composable (DialogScope) -> Unit = {},
    dismissButton: (@Composable (DialogScope) -> Unit)? = null,
) {
    val progress = remember { Animatable(0f) }
    val coroutines = rememberCoroutineScope()
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    val scope = remember {
        DialogScope { after ->
            coroutines.launch {
                progress.animateTo(0f, tween(DIALOG_ANIM_MS))
                after()
            }
        }
    }

    LaunchedEffect(Unit) {
        // 等一帧：窗口创建 + 首帧组合很重，别把这段时间算进动画里。
        withFrameNanos { }
        progress.animateTo(1f, tween(DIALOG_ANIM_MS, easing = LinearOutSlowInEasing))
    }

    BasicAlertDialog(
        onDismissRequest = { scope.closeThen { latestDismiss() } },
        properties = properties,
    ) {
        Surface(
            modifier = Modifier.graphicsLayer {
                val p = progress.value
                alpha = p
                val scale = 0.94f + 0.06f * p
                scaleX = scale
                scaleY = scale
            },
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column {
                icon?.let {
                    Box(
                        Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) { it() }
                }
                title?.let {
                    val top = if (icon == null) 24.dp else 16.dp
                    Box(Modifier.padding(start = 24.dp, end = 24.dp, top = top)) { it() }
                }
                text?.let {
                    val top = if (title == null) 24.dp else 16.dp
                    Box(Modifier.padding(start = 24.dp, end = 24.dp, top = top)) { it() }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    dismissButton?.invoke(scope)
                    confirmButton(scope)
                }
            }
        }
    }
}

/** 让按钮能「先播完退场再关」。 */
internal class DialogScope(
    private var onClose: ((() -> Unit) -> Unit)? = null,
) {
    fun closeThen(after: () -> Unit) {
        val close = onClose
        if (close != null) close(after) else after()
    }
}

private const val DIALOG_ANIM_MS = 140
