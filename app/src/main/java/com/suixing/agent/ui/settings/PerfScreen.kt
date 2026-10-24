package com.suixing.agent.ui.settings

import android.view.Choreographer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.suixing.agent.core.Core
import com.suixing.agent.data.LiveMetrics
import com.suixing.agent.data.LiveSampler
import com.suixing.agent.data.PerfHealth
import com.suixing.agent.data.PerfRecord
import com.suixing.agent.data.PerfSummary
import com.suixing.agent.ui.components.PageTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

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
private val NL_PERF = 10.toChar().toString()

@Composable
internal fun PerfScreen(onBack: () -> Unit) {
    // 进页面读一次，退出再进就是最新数据 —— 但**读盘与算分位数都不在主线程**：那是几十 KB 的
    // JSON 加一次排序，放在主线程会让刚进页面的那一帧掉帧，而这页恰恰是拿来量掉帧的。
    var summary by remember { mutableStateOf(PerfSummary()) }
    var records by remember { mutableStateOf<List<PerfRecord>>(emptyList()) }
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            Core.perf.summary(limit = 20) to Core.perf.recent(limit = 20)
        }
        summary = loaded.first
        records = loaded.second
    }
    val health = remember(records) { PerfHealth.of(records) }

    // 设备实时：采样器自己记着上一帧的读数，所以第一秒的 CPU 是「—」（没有可比的前一次）。
    // **采样在 IO 线程**：一秒里要读 /proc/stat、/proc/self/status、PSS 与几个 sysfs 节点，全是
    // 阻塞 IO，放主线程就是每秒卡一下。
    // **页面不在前台就停**（[ProcessLifecycleOwner]）：协程随组合销毁只覆盖"离开这一页"，
    // 覆盖不到"按了 home 键"—— 那之后还每秒读一遍 /proc 就纯属浪费电。
    val sampler = remember { LiveSampler() }
    var live by remember { mutableStateOf<LiveMetrics?>(null) }
    LaunchedEffect(sampler) {
        val owner = ProcessLifecycleOwner.get()
        while (true) {
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                val next = withContext(Dispatchers.IO) { sampler.sample() }
                live = next
            }
            delay(1000)
        }
    }
    val frame = rememberFrameStat()

    // #17 定位用：把 `pin.log` 的尾部搬进界面。
    //
    // 为什么放在这里而不是只写文件：这台机器 MIUI 把 `adb logcat` 限死、release 包 `run-as`
    // 也不可用 ⇒ 之前只能靠 root 的 `adb shell su -c cat` 才能读到。放进性能页之后，
    // 复现一次、点「复制」就能贴出来，验证不再依赖电脑。
    var pinLog by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        pinLog = withContext(Dispatchers.IO) {
            runCatching {
                val ctx = Core.appContext
                // **带上版本**：这份痕迹会被贴到对话里，没有版本号就无法确认是哪个构建产生的
                // （同一台机器上装过很多个 0.8.3xx）。
                val version = runCatching {
                    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
                }.getOrNull().orEmpty()
                val file = java.io.File(ctx.filesDir, "pin.log")
                val body = if (!file.isFile) "" else file.readLines().takeLast(30).joinToString(NL_PERF)
                if (body.isEmpty()) "" else "version=$version" + NL_PERF + body
            }.getOrDefault("")
        }
    }
    val clipboard = LocalClipboardManager.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { PageTopBar(title = "性能", onBack = onBack) },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
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
                    // PSS 的间隔读常量（见 LiveSampler.PSS_INTERVAL_SECONDS）：标签写死"5 秒"，
                    // 而间隔以后一改就变成一句假话。
                    MetricRow(
                        "内存（PSS · ${LiveSampler.PSS_INTERVAL_SECONDS} 秒一采）",
                        metrics?.pssMb?.let { "$it MB" } ?: "—",
                    )
                    val heap = metrics?.javaHeapMb
                    val heapMax = metrics?.javaHeapMaxMb
                    MetricRow("Java 堆", if (heap != null && heapMax != null) "$heap / $heapMax MB" else "—")
                    MetricRow("线程", metrics?.threads?.toString() ?: "—")
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item(key = "pinlog") {
                Column(Modifier.padding(vertical = 12.dp)) {
                    Text("流式上翻痕迹（最近 30 行）", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = pinLog.ifBlank {
                            "（还没有痕迹：发一条长消息、流式中往上滑一下就会生成）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (pinLog.isNotBlank()) {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(pinLog)) }) {
                            Text("复制")
                        }
                    }
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
        val owner = ProcessLifecycleOwner.get()
        while (true) {
            // **采一秒、停一秒**：逐帧 postFrameCallback 会让 Choreographer 一直出帧，页面开着就
            // 等于自己把机器焐热、也把上面那两行 CPU 读数抬上去 —— 这页要量的正是这两样。
            // 一秒窗口足够算 fps 与 p90；页面不在前台时整轮跳过。
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                intervals.clear()
                val until = System.nanoTime() + 1_000_000_000L
                var last = 0L
                while (System.nanoTime() < until) {
                    val now = suspendCancellableCoroutine<Long> { cont ->
                        choreographer.postFrameCallback { cont.resume(it) }
                    }
                    if (last > 0L) {
                        intervals.addLast((now - last).coerceAtLeast(0L))
                        while (intervals.size > 60) intervals.removeFirst()
                    }
                    last = now
                }
                if (intervals.size >= 10) {
                    val mean = intervals.average()
                    val sorted = intervals.sorted()
                    stat = FrameStat(
                        fps = if (mean > 0.0) (1_000_000_000.0 / mean).roundToInt() else 0,
                        p90Ms = (sorted[(sorted.size * 9) / 10] / 1_000_000L).toInt(),
                    )
                }
            }
            delay(1000)
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

// SimpleDateFormat 的构造很贵（解析 pattern），而这里是**列表每一行**都在调。
// 非线程安全 ⇒ ThreadLocal 各持一个；输出与「每次新建」逐字节相同。
private val CLOCK_FORMAT = object : ThreadLocal<java.text.SimpleDateFormat>() {
    override fun initialValue() =
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
}

private fun clock(at: Long): String {
    if (at <= 0) return "—"
    return CLOCK_FORMAT.get()!!.format(java.util.Date(at))
}

/** 供页面之外（例如诊断日志）复用同一份摘要形状。 */
internal fun PerfSummary.isEmpty(): Boolean = count <= 0
