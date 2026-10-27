package com.suixing.agent.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The values the OpenAI-compatible `reasoning_effort` field accepts, wire value to
 * 档位名. The settings slider and the chat's 思考 pill menu read the same list, so the
 * two can never disagree about what a level is called.
 */
val REASONING_LEVELS: List<Pair<String, String>> = listOf(
    // **七档全英文**（用户 2026-10-23：「默认 / minimal / 低 / 中 / 高 / xhigh / max
    // 先对应七档全英吧」）。左列是 wire 值（发给接口的），右列是界面显示名。
    "" to "Default",
    "minimal" to "Minimal",
    "low" to "Low",
    "medium" to "Medium",
    "high" to "High",
    "xhigh" to "XHigh",
    // 用户要的 max 档（2026-10-13）：wire 值就是 "max"。与 xhigh 同待遇——仅部分模型/网关
    // 支持，不支持的忽略（设置页 hint 已说明）。
    "max" to "Max",
)

@Composable
fun EmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    orbSize: Dp = 128.dp,
    content: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SuixingOrb(size = orbSize)
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (content != null) {
            Spacer(Modifier.height(22.dp))
            content()
        }
    }
}
