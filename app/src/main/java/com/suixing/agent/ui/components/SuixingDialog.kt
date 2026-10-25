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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/**
 * 全站统一的对话框：外形与 Material 3 的 `AlertDialog` 一致，但**有进退场**。
 *
 * 为什么要自建：`AlertDialog` 的窗口是直接出现的，没有任何入场动画 —— 按一下按钮，一整块卡片
 * 「啪」地贴上来；关闭时同样瞬间消失。用户的原话是「出现和消失得太生硬」。
 *
 * 动效取值（与仓库既有口径一致）：
 * - 入场 180ms `LinearOutSlowInEasing`：透明度 0→1、缩放 0.94→1、上浮 12dp→0；
 * - 退场 130ms（比入场快，符合 Material 的「进慢出快」）；
 * - **先播完退场再执行回调**：所以按钮必须走 [DialogScope.closeThen]，否则业务侧会立刻把
 *   对话框从组合里摘掉，用户看不到退场。
 *
 * 视觉参数照抄 M3：`AlertDialogDefaults.shape` / `containerColor` / `TonalElevation`，
 * 最小宽 280dp、最大 560dp，标题与正文的 24dp 边距、按钮行 8dp 间距 —— 换壳不改样式。
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
    content: (@Composable () -> Unit)? = null,
) {
    val progress = remember { Animatable(0f) }
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    val coroutines = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // **等一帧再开始动画**：`BasicAlertDialog` 会新建一个窗口，第一帧要完成窗口创建 +
        // 整个对话框内容的组合与测量（模型列表、多行输入这些都不便宜）。动画若从这一帧起跑，
        // 就等于把最重的一帧算进 180ms 里 —— 用户看到的就是「弹窗有时会顿一下」。
        // 那一帧本来 alpha=0、什么都看不见，白等一帧不影响观感，却能把首帧成本移出动画窗口。
        withFrameNanos { }
        progress.animateTo(1f, tween(DIALOG_IN_MS, easing = LinearOutSlowInEasing))
    }

    // 关闭：先播退场，播完再通知业务侧。`closing` 是闸门 —— 连点两下不会跑两遍退场。
    val scope = remember { DialogScope() }
    scope.closeThen = { after ->
        if (!closing) {
            closing = true
            coroutines.launch {
                progress.animateTo(0f, tween(DIALOG_OUT_MS))
                after()
            }
        }
    }

    BasicAlertDialog(
        onDismissRequest = { scope.closeThen { latestDismiss() } },
        properties = properties,
    ) {
        Surface(
            modifier = Modifier
                .sizeIn(minWidth = 280.dp, maxWidth = 560.dp)
                .graphicsLayer {
                    val p = progress.value
                    alpha = p
                    val scale = 0.94f + 0.06f * p
                    scaleX = scale
                    scaleY = scale
                    translationY = (1f - p) * 12.dp.toPx()
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
                    // 有标题时正文上边距 16dp，没有时与标题同高（24dp）——与 M3 一致。
                    val top = if (title == null) 24.dp else 16.dp
                    Box(Modifier.padding(start = 24.dp, end = 24.dp, top = top)) { it() }
                }
                content?.invoke()
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

/** 让按钮能"先播完退场再执行回调"，而不是业务侧直接把对话框摘掉。 */
internal class DialogScope {
    internal var closeThen: ((() -> Unit) -> Unit)? = null

    /** 关闭对话框，动画播完后再跑 [after]。 */
    fun closeThen(after: () -> Unit) {
        val close = closeThen
        if (close != null) close(after) else after()
    }
}

/** 退场比入场快（Material 的「进慢出快」）。 */
private const val DIALOG_IN_MS = 180
private const val DIALOG_OUT_MS = 130
