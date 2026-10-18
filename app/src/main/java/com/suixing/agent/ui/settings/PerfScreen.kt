package com.suixing.agent.ui.settings

import android.view.Choreographer
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.suixing.agent.core.Core
import com.suixing.agent.data.LiveMetrics
import com.suixing.agent.data.LiveSampler
import com.suixing.agent.data.PerfHealth
import com.suixing.agent.data.PerfRecord
import com.suixing.agent.data.PerfSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * 设置 → 性能：三段。
 *
 * 1. **设备实时** —— CPU（本进程/整机）、GPU、帧率、内存、线程，进页面每秒采一次，离开就停。
 * 2. **会话健康** —— 最近若干轮的 p50/p90、抖动、缓存命中、无 usage 回报与慢轮计数。
 * 3. **每轮明细** —— 有界 200 条、落盘，跨重启可看（见 [com.suixing.agent.data.PerfMonitor]）。
 *
 * **只显示真的测到的数字**：GPU 利用率在 Android 上没有公开 API、部分机型的 sysfs 也不给读，
 * 所以它显示为「—」而不是 0 —— 0 会被读成"测得是零"，那是另一种撒谎。
 */
@Composable
internal fun PerfScreen(onBack: () -> Unit) {
    // 一次进入读一次：这是诊断页，不需要实时刷新；退出再进就是最新数据。
    val summary = remember { Core.perf.summary(limit = 20) }
    val records = remember { Core.perf.recent(limit = 20) }
    val health = remember(records) { PerfHealth.of(records) }

    // 设备实时：采样器自己记着上一帧的读数，所以第一秒的 CPU 是「—」（没有可比的前一次）。
    val sampler = remember { LiveSampler() }
    var live by remember { mutableStateOf<LiveMetrics?>(null) }
    LaunchedEffect(sampler) {
        while (true) {
            live = sampler.sample()
            delay(1000)
        }
    }
    val frame = rememberFrameStat()

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
                    MetricRow("缓存命中（均值）", cacheText(summary.avgCachePercent))
                    MetricRow(
                        "输出速度（均值）",
                        if (summary.avgTokensPerSecond <= 0.0) "—" else "%.1f tok/s".format(summary.avgTokensPerSecond),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item(key = "live") {
                // 委派属性（by remember）不能智能转换，先落到局部变量；顺带保证这一帧读的是同一份采样。
                val metrics = live
                Column(Modifier.padding(vertical = 12.dp)) {
                    Text("设备实时", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    MetricRow("CPU（本进程）", percentText(metrics?.processCpuPercent))
                    MetricRow("CPU（整机）", percentText(metrics?.systemCpuPercent))
                    MetricRow("GPU", gpuText(metrics))
                    MetricRow("帧率", frame?.let { "${it.fps} fps · p90 ${it.p90Ms} ms" } ?: "采样中…")
                    MetricRow("内存（PSS）", metrics?.pssMb?.let { "$it MB" } ?: "—")
                    val heap = metrics?.javaHeapMb
                    val heapMax = metrics?.javaHeapMaxMb
                    MetricRow("Java 堆", if (heap != null && heapMax != null) "$heap / $heapMax MB" else "—")
                    MetricRow("线程", metrics?.threads?.toString() ?: "—")
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item(key = "health") {
                Column(Modifier.padding(vertical = 12.dp)) {
                    Text("会话健康（最近 ${health.runs} 轮）", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    MetricRow("首字延迟 p50 / p90", pairText(health.ttftP50, health.ttftP90))
                    MetricRow("整轮耗时 p50 / p90", pairText(health.totalP50, health.totalP90))
                    MetricRow("抖动（p90/p50）", if (health.jitter <= 0.0) "—" else "%.2f×".format(health.jitter))
                    MetricRow("缓存命中（均值）", cacheText(health.cachePercent))
                    MetricRow(
                        "输出速度（均值）",
                        if (health.tokensPerSecond <= 0.0) "—" else "%.1f tok/s".format(health.tokensPerSecond),
                    )
                    MetricRow("无 usage 回报", "${health.noUsageRuns} 轮")
                    MetricRow("慢轮（>${PerfHealth.SLOW_MS / 1000}s）", "${health.slowRuns} 轮")
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
private fun dashIfZero(value: Long): String = if (value <= 0) "—" else "$value ms"

/**
 * 缓存命中率：两位小数，与聊天页标题里的「缓存命中」同一口径。
 * 0 同样是「没采到」（端点没报 usage），画成「—」而不是 0.00%。
 */
private fun cacheText(percent: Double): String = if (percent <= 0.0) "—" else "%.2f%%".format(percent)

private fun percentText(value: Int?): String = if (value == null) "—" else "$value%"

private fun pairText(p50: Long, p90: Long): String =
    if (p50 <= 0 && p90 <= 0) "—" else "${dashIfZero(p50)} / ${dashIfZero(p90)}"

/**
 * GPU 这一行要把"没有这个数"和"这个数很小"分开：利用率读不到时只说频率，
 * 频率也读不到就说该机型不提供，绝不画 0%。
 */
private fun gpuText(live: LiveMetrics?): String {
    val busy = live?.gpuPercent
    val mhz = live?.gpuFreqMhz
    return when {
        busy != null && mhz != null -> "$busy% · $mhz MHz"
        busy != null -> "$busy%"
        mhz != null -> "$mhz MHz · 利用率不提供"
        else -> "— · 该机型不提供"
    }
}

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

/** 帧间隔统计：fps 取最近 60 帧均值，p90 取最近秩。 */
private data class FrameStat(val fps: Int, val p90Ms: Int)

/**
 * 本页可见期间的帧率。逐帧 `postFrameCallback` 会让 Choreographer 一直出帧 ——
 * 这正是"帧率"要测的东西，页面不可见时协程随组合销毁，回调自然停。
 */
@Composable
private fun rememberFrameStat(): FrameStat? {
    var stat by remember { mutableStateOf<FrameStat?>(null) }
    LaunchedEffect(Unit) {
        val choreographer = Choreographer.getInstance()
        val intervals = ArrayDeque<Long>()
        var last = 0L
        while (true) {
            val now = suspendCancellableCoroutine<Long> { cont ->
                choreographer.postFrameCallback { cont.resume(it) }
            }
            if (last > 0L) {
                intervals.addLast((now - last).coerceAtLeast(0L))
                while (intervals.size > 60) intervals.removeFirst()
                if (intervals.size >= 20) {
                    val mean = intervals.average()
                    val sorted = intervals.sorted()
                    stat = FrameStat(
                        fps = if (mean > 0.0) (1_000_000_000.0 / mean).roundToInt() else 0,
                        p90Ms = (sorted[(sorted.size * 9) / 10] / 1_000_000L).toInt(),
                    )
                }
            }
            last = now
        }
    }
    return stat
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
                if (record.cachePercent > 0) {
                    append(" · 缓存 ").append("%.2f%%".format(record.cachePercent))
                }
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
