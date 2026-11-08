package com.suixing.agent.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import com.suixing.agent.R
import com.suixing.agent.core.Core
import com.suixing.agent.data.*
import com.suixing.agent.ui.components.PageTopBar
import com.suixing.agent.ui.components.PageTopBarAction
import com.suixing.agent.ui.components.SegmentedPill
import com.suixing.agent.ui.components.SettingsStyle
import com.suixing.agent.ui.components.SuixingCard
import com.suixing.agent.ui.components.SuixingDialog
import com.suixing.agent.ui.components.settingsPagePadding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.LocalDate

private data class UsageViewData(
    val snapshot: UsageSnapshot, val windows: Map<Int, UsageWindow>,
    val heatmap: UsageWindow, val all: UsageBucket,
)

private const val COLLAPSED_MODEL_COUNT = 5

@Composable
fun UsageScreen(onBack: () -> Unit) {
    var data by remember { mutableStateOf<UsageViewData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var range by rememberSaveable { mutableIntStateOf(30) }
    var modelsExpanded by rememberSaveable(range) { mutableStateOf(false) }
    var inspectedModel by remember { mutableStateOf<ModelUsage?>(null) }
    LaunchedEffect(reload) {
        loading = true
        error = null
        try {
            val fresh = Core.usage.refresh()
            data = withContext(Dispatchers.Default) {
                val today = LocalDate.now()
                UsageViewData(fresh, listOf(7, 30, 90).associateWith { usageWindow(fresh, it, today) },
                    usageWindow(fresh, 90, today), fresh.coverage)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "用量读取失败，请重试。" }
        finally { loading = false }
    }
    val scheme = MaterialTheme.colorScheme
    Scaffold(
        containerColor = scheme.background, contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { PageTopBar(title = "用量", onBack = onBack, actions = {
            PageTopBarAction(
                icon = ImageVector.vectorResource(R.drawable.ic_rotate_cw),
                contentDescription = "刷新用量",
                onClick = { reload++ },
                enabled = !loading,
            )
        }) },
    ) { padding ->
        val current = data
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding), contentPadding = settingsPagePadding(),
            verticalArrangement = Arrangement.spacedBy(SettingsStyle.SectionGap),
        ) {
            if (error != null) item(key = "error") {
                SuixingCard {
                    Text(if (current == null) "无法读取用量" else "刷新失败，当前显示上次结果",
                        style = SettingsStyle.cardTitle, color = scheme.error)
                    Text(error.orEmpty(), style = SettingsStyle.description)
                    TextButton(onClick = { reload++ }, enabled = !loading) { Text("重试") }
                }
            }
            if (current == null) {
                if (loading) item(key = "loading") {
                    Row(Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("正在汇总用量…", style = SettingsStyle.body)
                    }
                }
            } else {
                val window = current.windows.getValue(range)
                if (current.snapshot.issues.isNotEmpty()) item(key = "partial") {
                    Text("部分记录未能刷新，统计可能不完整。可点右上角刷新重试。",
                        color = scheme.error, style = SettingsStyle.description)
                }
                item(key = "range") {
                    SegmentedPill(listOf("近 7 天", "近 30 天", "近 90 天"),
                        listOf(7, 30, 90).indexOf(range), { range = listOf(7, 30, 90)[it] })
                }
                item(key = "summary") {
                    UsageSummary(window, range)
                }
                item(key = "heatmap") {
                    UsageHeatmap(current.heatmap.days)
                }
                item(key = "daily-chart") {
                    UsageDailyChart(window.days, range)
                }
                item(key = "models-title") {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("模型用量", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text("近 ${range} 天", style = SettingsStyle.metadata, color = scheme.onSurfaceVariant)
                    }
                }
                if (window.models.isEmpty()) item(key = "empty-models") {
                    Text("这段时间没有已记录的模型响应。", style = SettingsStyle.description,
                        color = scheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                }
                val visibleModels = if (modelsExpanded) window.models else window.models.take(COLLAPSED_MODEL_COUNT)
                items(visibleModels, key = { "model:${it.model}" }, contentType = { "model-usage" }) { model ->
                    UsageModelBar(model, window.usage.tokens.total, onClick = { inspectedModel = model })
                }
                if (window.models.size > COLLAPSED_MODEL_COUNT) item(key = "models-toggle") {
                    TextButton(onClick = { modelsExpanded = !modelsExpanded }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (modelsExpanded) "收起" else "展开全部 ${window.models.size} 个模型")
                        Spacer(Modifier.width(4.dp))
                        Icon(ImageVector.vectorResource(if (modelsExpanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down),
                            contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
                item(key = "all-time") {
                    SuixingCard {
                        Text("全部保留记录", style = SettingsStyle.cardTitle)
                        Text("${usageExact(current.all.tokens.total)} tokens · ${usageExact(current.all.requests)} 次已记录响应",
                            style = SettingsStyle.body)
                        val children = current.snapshot.chats.keys.count { it.startsWith("child/") }
                        Text("包含 ${current.snapshot.chats.size - children} 个主会话、${children} 个子对话。删除对话后相应统计会移除。",
                            style = SettingsStyle.description, color = scheme.onSurfaceVariant)
                        Text("按已保存的模型响应累计输入与输出；缓存、推理单独展示，不重复加进总量。未保存或未上报的用量无法还原，计费以服务商账单为准。",
                            style = SettingsStyle.description, color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
    inspectedModel?.let { model ->
        SuixingDialog(onDismissRequest = { inspectedModel = null }, title = { Text(model.model) },
            text = { UsageBreakdown(model.tokens, model.reported, model.unreported) },
            confirmButton = { dialog -> TextButton(onClick = { dialog.closeThen { inspectedModel = null } }) { Text("关闭") } })
    }
}

@Composable
private fun UsageSummary(window: UsageWindow, range: Int) {
    val usage = window.usage
    SuixingCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("近 ${range} 天已报告用量", style = SettingsStyle.metadata, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("${window.days.count { it.reported + it.unreported > 0 }} 天有活动", style = SettingsStyle.metadata)
        }
        Text(compact(usage.tokens.total), style = MaterialTheme.typography.headlineLarge)
        Text("${usageExact(usage.tokens.total)} tokens", style = SettingsStyle.description,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        val titles = remember { listOf("输入", "输出", "已记录响应") }
        val values = remember(usage) { listOf(compact(usage.tokens.prompt), compact(usage.tokens.completion), compact(usage.requests)) }
        StatCards(titles, values)
        if (usage.unreported > 0) Text("其中 ${usageExact(usage.unreported)} 次响应未报告用量，以上 tokens 仅为已报告部分。",
            style = SettingsStyle.description, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp))
        UsageBreakdown(usage.tokens, usage.reported, usage.unreported, compact = true)
    }
}

@Composable
internal fun UsageBreakdown(tokens: TokenTally, reported: Long, unreported: Long, compact: Boolean = false) {
    val labels = listOf("输入", "输出", "缓存输入（单列）", "推理（单列）", "已报告用量", "未报告用量")
    val values = remember(tokens, reported, unreported) {
        listOf(tokens.prompt, tokens.completion, tokens.cached, tokens.reasoning, reported, unreported).map { usageExact(it) }
    }
    Column(Modifier.fillMaxWidth().padding(top = if (compact) 10.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { index, label ->
            Row(Modifier.fillMaxWidth()) {
                Text(label, modifier = Modifier.weight(1f), style = SettingsStyle.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(values[index], style = SettingsStyle.description)
            }
        }
    }
}

internal fun usageExact(value: Long): String = NumberFormat.getIntegerInstance().format(value)
/** 数值字号的下限：再小就不如让用户去别处看完整数字，但三张卡的数值都必须单行放下。 */
private const val MIN_STAT_VALUE_SP = 13f

/**
 * 三张指标卡：**等宽、等高、同一个字号**。
 *
 * 上一版把三张卡各自交给 [StatCard]，谁的字放不下谁就自己折行——「256.5M」在 24sp 下比卡片
 * 内宽还宽（360dp 屏上内宽约 72dp，这个串要 80dp），于是它被拆成「256.5」+「M」两行：第一张
 * 卡因此比旁边两张高出一截，看起来"大小各不相同、不和谐"。
 *
 * 这里把两件事一次性定下来：① 字号由**三张卡共用一个**，取"最长的那条数值也能单行放下"的
 * 那一档（谁都不许比谁小）；② 卡片内边距收窄到 12dp，让设计字号在常见屏宽下本来就放得下，
 * 缩小只在大字体/窄屏时才发生。
 */
@Composable
private fun StatCards(titles: List<String>, values: List<String>) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val base = MaterialTheme.typography.titleLarge
    BoxWithConstraints {
        // 卡片内宽 = 行宽 − 两个 8dp 间距，三等分，再扣掉卡片自己的左右 12dp 内边距。
        val inner = (maxWidth - 16.dp) / 3 - 24.dp
        val room = with(density) { inner.toPx() }
        // key 里带上 `base` 与 `measurer`：字号/字重/字体/字距/语言区域任何一项变了，同一个字的
        // 宽度就变 —— 只按 inner+values 记会把上一档字号下选出的结果当成新答案（字号档变化后
        // 字号陈旧的那个 bug）。TextStyle 覆写了 equals（逐字段比较，它自己就区分
        // hasSameLayoutAffectingAttributes），density 不在 TextStyle 里，但 rememberTextMeasurer
        // 会随 density/字体解析器重建，所以 measurer 也进 key。
        val fontSize = remember(inner, values, base, measurer) {
            // 档位序列与旧循环逐档递减完全一致：每档在上一档基础上减 0.5sp。不用
            // `k * 0.5` 一次相减 —— 浮点累加的结果才与旧循环逐位相同。
            fun sizeAt(step: Int): TextUnit {
                var size = base.fontSize
                repeat(step) { size = (size.value - 0.5f).sp }
                return size
            }

            fun tooWide(size: TextUnit): Boolean =
                values.any { measurer.measure(it, style = base.copy(fontSize = size)).size.width > room }

            // 旧循环量到「手上这一档已经越过下限」就不再量、直接拿它当答案，先把这条界算出来。
            var floorStep = 0
            var cursor = base.fontSize
            while (cursor.value > MIN_STAT_VALUE_SP) {
                cursor = (cursor.value - 0.5f).sp
                floorStep++
            }
            if (floorStep == 0) return@remember base.fontSize

            // ① 基准档：三个值各量一次。放得下就交差 —— 旧循环也是这一遍就返回。
            val widths = values.map { measurer.measure(it, style = base).size.width }
            if (widths.all { it <= room }) return@remember base.fontSize

            // ② 宽度与字号近似成正比：拿最宽的那条算出要缩几档，一次跳到目标字号；不再从
            //    base 起一档档往下量（最坏 22 档 × 3 个值 = 66 次完整段落测量，全在主线程）。
            val widest = widths.max().toFloat()
            val target = base.fontSize.value * (room / widest)
            var step = ((base.fontSize.value - target) / 0.5f).toInt().coerceIn(1, floorStep)

            // ③ 跳到的那一档实测校验一次，之后只在实测结果与估算不符时挪 1~2 档：
            //    跳过头（这一档还宽裕）就往回收，跳不够（仍超宽）就继续退。两个方向都是实测，
            //    所以停下来的那一档与逐档递减找到的必然是同一档（宽度随字号单调不减）。
            if (tooWide(sizeAt(step))) {
                while (step < floorStep) {
                    step++
                    if (!tooWide(sizeAt(step))) break
                }
            } else {
                while (step > 1 && !tooWide(sizeAt(step - 1))) step--
            }
            sizeAt(step)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            titles.forEachIndexed { index, title ->
                StatCard(title, values[index], base.copy(fontSize = fontSize), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    valueStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
            Text(
                text = title,
                style = SettingsStyle.badge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // 标签也必须单行：它一折行，这一张卡就比旁边两张高，三张卡又回到"不齐"。
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = valueStyle,
                // 数值永远单行：断行会把「256.5M」拆成「256.5」+「M」。字号已由 [StatCards]
                // 统一挑好，这里的 Ellipsis 只是极端字体缩放下的兜底，正常情况下用不到。
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** `1234567` → `1.2M`; `12345` → `12.3k`; small numbers stay as they are. */
internal fun compact(value: Long): String = when {
    value >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", value / 1_000.0)
    else -> "$value"
}
