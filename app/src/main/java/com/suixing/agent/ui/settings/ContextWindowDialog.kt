package com.suixing.agent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.suixing.agent.core.Core
import kotlinx.coroutines.launch

/**
 * One model's context window, asked for in k because that is how the number is written
 * everywhere else (128k, not 131072), and answered in tokens because that is the unit the
 * engine counts in. Empty means 不限.
 *
 * The page uses this dialog and keeps the result in its draft; the chat's ring uses
 * [ContextWindowEditor], which writes straight through. Both go through
 * [withContextWindow], so the key a window is filed under is decided in one place.
 */
@Composable
internal fun ContextWindowDialog(
    model: String,
    current: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(if (current > 0) (current / 1000).toString() else "") }
    val suggested = suggestedContextWindow(model)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("上下文窗口") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "这个模型一次能读多少 token。填了它，输入框左边的圆环才会按占用比例" +
                        "显示，并在快满时变红；留空＝不限（圆环不显示比例）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { value -> text = value.filter { it.isDigit() }.take(4) },
                    label = { Text("k tokens") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (suggested != null) {
                    // 按模型名猜的，只做提示：用户点了才进输入框，不点就什么都不写。
                    Text(
                        text = "建议 ${suggested / 1000}k（按模型名猜的，可改）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(onClick = { text = (suggested / 1000).toString() }) {
                        Text("用建议值")
                    }
                }
                Text(
                    text = "模型：${model.ifBlank { "未选择" }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.toIntOrNull()?.times(1000) ?: 0) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * The chat ring's 「去设置」: the same dialog, writing straight through. The chat keeps no
 * draft, so there is nothing to hand back — the ring redraws from the settings flow.
 */
@Composable
internal fun ContextWindowEditor(model: String, current: Int, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    ContextWindowDialog(model = model, current = current, onDismiss = onDismiss) { tokens ->
        onDismiss()
        scope.launch { setContextWindow(model, tokens) }
    }
}

/** Writes [model]'s window straight through, for callers with no draft of their own. */
internal suspend fun setContextWindow(model: String, tokens: Int) {
    if (model.isBlank()) return
    Core.settings.update { current ->
        current.copy(modelContexts = current.modelContexts.withContextWindow(model, tokens))
    }
}
