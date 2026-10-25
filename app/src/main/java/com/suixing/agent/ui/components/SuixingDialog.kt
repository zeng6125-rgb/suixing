package com.suixing.agent.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

/**
 * 对话框的统一入口 —— **直接转调官方 AlertDialog**。
 *
 * 走过一段弯路：为了做进出场动画，曾经用 `BasicAlertDialog` 自绘卡片、铺满窗口、自画遮罩。
 * 代价是宽度失控（卡片被内部 `fillMaxWidth` 撑到整屏）、阴影与官方观感不一致，用户要求
 * 「恢复官方的」。所以这里退回官方实现，外观（宽度、圆角、投影、系统遮罩）与 M3 完全一致。
 *
 * 保留这层包装只为两件事：
 *  · 调用点不需要各自 import `AlertDialog`；
 *  · [DialogScope] 的签名还在，各处的 `scope.closeThen { ... }` 不用改 —— 它现在就是直接执行，
 *    因为系统对话框本来就没有退场动画可等。
 */
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
    val scope = DialogScope()
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { confirmButton(scope) },
        properties = properties,
        icon = icon,
        title = title,
        text = text,
        dismissButton = dismissButton?.let { { it(scope) } },
    )
}

/** 保留调用点签名：`closeThen` 现在直接执行（官方对话框没有退场动画可等）。 */
internal class DialogScope {
    fun closeThen(after: () -> Unit) = after()
}
