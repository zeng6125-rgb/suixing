package com.suixing.agent.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.suixing.agent.core.Core
import com.suixing.agent.data.PerfRecord
import com.suixing.agent.data.PerfSummary

/**
 * 设置 → 性能：最近每一轮请求的实测记录（有界 200 条、落盘，见 [com.suixing.agent.data.PerfMonitor]）。
 *
 * **只显示真的测到的数字**：首字延迟、缓存命中、请求体大小这三项在当前采集点还没有来源，
 * 所以它们显示为「—」而不是 0 —— 0 会被读成"测得是零"，那是另一种撒谎。
 */
@Composable
internal fun PerfScreen(onBack: () -> Unit) {
    // 一次进入读一次：这是诊断页，不需要实时刷新；退出再进就是最新数据。
    val summary = remember { Core.perf.summary(limit = 20) }
    val records = remember { Core.perf.recent(limit = 20) }

    // 必须自己铺底色：其他设置页是 Scaffold(containerColor = background)，这里只有一个 Column
    // 时会直接透出 NavHost 的底（用户看到的「成透明的了」）。状态栏也要自己让开。
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            Spacer(Modifier.width(4.dp))
            Text("性能", style = MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item(key = "summary") {
                Column(Modifier.padding(vertical = 12.dp)) {
                    Text("最近 ${summary.count} 轮", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    MetricRow("首字延迟（均值）", dashIfZero(summary.avgTtftMs))
                    MetricRow("整轮耗时（均值）", dashIfZero(summary.avgTotalMs))
                    MetricRow("缓存命中（均值）", if (summary.avgCachePercent <= 0) "—" else "${summary.avgCachePercent}%")
                    MetricRow(
                        "输出速度（均值）",
                        if (summary.avgTokensPerSecond <= 0.0) "—" else "%.1f tok/s".format(summary.avgTokensPerSecond),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (records.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "还没有记录。跑一轮对话后再回来，这里会出现那一轮的实测数字。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            } else {
                items(records, key = { it.at.toString() + it.conversationId }) { record ->
                    RecordRow(record)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

/** 0 在这里的含义是「没有采集到」，不是「测得为零」。 */
private fun dashIfZero(value: Long): String = if (value <= 0) "—" else "${value} ms"

@Composable
private fun MetricRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(150.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun RecordRow(record: PerfRecord) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = clock(record.at),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = if (record.totalMs > 0) "${record.totalMs} ms" else "—",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (record.tokensPerSecond > 0.0) {
                Text(
                    "%.1f tok/s".format(record.tokensPerSecond),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = buildString {
                append("prompt ")
                append(if (record.promptTokens > 0) record.promptTokens.toString() else "—")
                append(" · completion ")
                append(if (record.completionTokens > 0) record.completionTokens.toString() else "—")
                if (record.cachePercent > 0) append(" · 缓存 ${record.cachePercent}%")
                if (record.toolRounds > 0) append(" · 工具 ${record.toolRounds} 轮")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun clock(at: Long): String {
    if (at <= 0) return "—"
    val format = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    return format.format(java.util.Date(at))
}

/** 供页面之外（例如诊断日志）复用同一份摘要形状。 */
internal fun PerfSummary.isEmpty(): Boolean = count <= 0
