package com.suixing.agent.ui.chat

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString

/**
 * 「复制」按钮的统一入口：写剪贴板 + 一条 Toast。
 *
 * 用户：「点击复制 icon 的按钮，复制后，给用户来个 toast 提示」—— 复制是**没有视觉反馈**的动作，
 * 点完屏幕毫无变化，用户分不清是没点上还是复制了。Toast 是最轻的确认方式（不占布局、不需要状态）。
 *
 * 用 `applicationContext`：`MessageActions` 这类地方没有 Activity，长按后立刻切走也不该因为
 * Context 失效而吞掉提示。
 */
@Composable
internal fun rememberCopyToClipboard(): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    return remember(clipboard, context) {
        { text ->
            clipboard.setText(AnnotatedString(text))
            Toast.makeText(context.applicationContext, "已复制", Toast.LENGTH_SHORT).show()
        }
    }
}
