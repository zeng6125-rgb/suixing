package com.suixing.agent.ui.settings

import com.suixing.agent.ui.components.SettingsStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import com.suixing.agent.ui.components.SuixingDialog
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
 * **存储与往返都用完整值**：窗口内部按 tokens 存（settings 的 `modelContexts`），输入框的显示
 * （[kiloText]）与解析（[kiloToTokens]）都走完整精度——整千写成整数（`128000` → `128`），其余
 * 按需保留小数位（`1500` → `1.5`、`131072` → `131.072`），写回的还是原来那个 token 数。
 * 这条链上没有第二次取整：整数除法会把 `1500` 读成 `1`，用户点「确定」就把窗口改成 `1000`，
 * 那 500 是他从没同意过的值。
 *
 * 超过上限（`9999k` ＝ `9999000` token）或不是数字时不写回，只把输入框标红——绝不替用户截断：
 * 静默截断会把粘贴进来的 `131072` 变成 `1310k`。
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
    // 键是 current：对话框不离开组合时（同一个模型上重开、或设置被别处改掉）输入框必须跟着走，
    // 否则它留着旧值，而点「确定」写回去的就是那个旧值。
    var text by remember(current) { mutableStateOf(kiloText(current)) }
    // 非法输入只是看得见地标红并停住写入，绝不替用户把值改小：静默截断会把 131072 变成 1310k。
    val invalid = text.isNotBlank() && parseWindowTokens(text) == null
    // 类型显式写出来：这个 lambda 只是 if 的一个分支，指望参数类型反向推断它在部分编译器
    // 版本上会塌成「Cannot infer type / @Composable invocations can only happen…」。
    val supporting: (@Composable () -> Unit)? = if (invalid) {
        { Text("最多 9999k（＝9999000 token）；单位可写 k / m") }
    } else {
        null
    }
    val suggested = suggestedContextWindow(model)
    SuixingDialog(
        onDismissRequest = onDismiss,
        title = { Text("上下文窗口") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "这个模型一次能读多少 token。填了它，输入框左边的圆环才会按占用比例" +
                        "显示，并在快满时变红；留空＝不限（圆环不显示比例）。",
                    style = SettingsStyle.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    textStyle = SettingsStyle.fieldText,
                    value = text,
                    // 放行字母 k/m（大小写）：单位是这次要支持的输入（用户点名）。其余字符仍滤掉，
                    // 否则一个逗号或全角数字会静默变成「非法」而不是「没打进去」。
                    onValueChange = { value ->
                        text = value.filter { it.isDigit() || it == '.' || it == 'k' || it == 'K' || it == 'm' || it == 'M' }
                    },
                    label = { Text("tokens，可带单位 k / m（如 128k、1m；留空 = 不限）") },
                    singleLine = true,
                    // Decimal 而不是 Number：输入框接受小数点（见上面的 filter），数字键盘
                    // 打不出「.」的话，1.5k 这种值就只有建议按钮写得进去。
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    isError = invalid,
                    supportingText = supporting,
                )
                if (suggested != null) {
                    // 按模型名猜的，只做提示：用户点了才进输入框，不点就什么都不写。
                    // 提示与按钮都走 kiloText（完整值）：`suggested / 1000` 是这条链上的第二次
                    // 取整——131072 会先被说成「131k」，再写进去 131000。
                    Text(
                        text = "建议 ${kiloText(suggested)}（按模型名猜的，可改）",
                        style = SettingsStyle.description,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(onClick = { text = kiloText(suggested) }) {
                        Text("用建议值")
                    }
                }
                Text(
                    text = "模型：${model.ifBlank { "未选择" }}",
                    style = SettingsStyle.badge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { scope ->
            // 非法时不写回：这个按钮会一直显示，点了却没有反应比不显示更让人困惑，所以
            // 只在合法时启用。
            TextButton(
                onClick = { scope.closeThen { onConfirm(parseWindowTokens(text) ?: 0) } },
                enabled = !invalid,
            ) { Text("确定") }
        },
        dismissButton = { scope -> TextButton(onClick = { scope.closeThen(onDismiss) }) { Text("取消") } },
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

/**
 * [current] tokens as the box shows it: **always with an explicit `k`** (128000 → "128k",
 * 1500 → "1.5k", 131072 → "131.072k"). 后缀必须显式：[parseWindowTokens] 里裸数字＝tokens，
 * 若这里还写 "128"，用户原样点「确定」就把 128000 静默改成 128 —— 一个他从没同意过的值。
 * 整数除法会把 1500 读成 "1"，所以非整千走小数。
 */
private fun kiloText(current: Int): String =
    if (current <= 0) "" else if (current % 1000 == 0) "${current / 1000}k" else "${current / 1000.0}k"

/**
 * [text] as tokens, or null when it is not a number the box can write back.
 *
 * 单位（用户：「上下文输入要支持 k/K、m/M」）：裸数字＝tokens；`k`/`K`＝×1000；`m`/`M`＝×1000000。
 * 后缀大小写不敏感、前面可以有空格。**裸数字的语义从「k」改成「tokens」**：输入框既然收单位，
 * 就不该再让「128」偷偷等于 128000 —— label/placeholder 都写明了单位，歧义由显式后缀消掉。
 * 上限与旧的一致（9999000 token），超过或负数返回 null（输入框标红、不写回）。
 */
internal fun parseWindowTokens(text: String): Int? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val multiplier = when (trimmed.last()) {
        'k', 'K' -> 1_000L
        'm', 'M' -> 1_000_000L
        else -> if (trimmed.last().isDigit()) 1L else return null
    }
    val numberPart = if (multiplier == 1L) trimmed else trimmed.dropLast(1).trimEnd()
    val value = numberPart.toDoubleOrNull() ?: return null
    if (value < 0) return null
    val tokens = value * multiplier
    if (tokens > 9_999_000) return null
    // round 而不是 toInt()：`1.001 * 1000` 在 IEEE-754 里是 1000.9999999999999，截断会把
    // 1001 个 token 写回成 1000（实测）—— 写回去的值必须与显示的完全一致。
    return Math.round(tokens).toInt()
}
