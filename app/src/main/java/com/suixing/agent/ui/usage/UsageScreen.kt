package com.suixing.agent.ui.usage

import com.suixing.agent.ui.components.SettingsStyle
import com.suixing.agent.ui.components.SettingSectionTitle
import com.suixing.agent.ui.components.settingsPagePadding
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suixing.agent.core.Core
import com.suixing.agent.data.DayUsage
import com.suixing.agent.data.ModelUsage
import com.suixing.agent.data.TokenTally
import com.suixing.agent.data.byDayOf
import com.suixing.agent.data.byModelOf
import com.suixing.agent.ui.components.PageTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where the tokens went: three headline cards, a 30-day bar chart, per-model subtotals.
 *
 * [Core.usage] is the process-wide store; [com.suixing.agent.data.UsageStore.refresh] is
 * watermark-incremental, so opening the page is a safe place to fold in whatever the
 * chats have grown since the last look.
 */
@Composable
fun UsageScreen(onBack: () -> Unit) {
    val store = Core.usage
    var days by remember { mutableStateOf<List<DayUsage>>(emptyList()) }
    var models by remember { mutableStateOf<List<ModelUsage>>(emptyList()) }
    var totals by remember { mutableStateOf(TokenTally()) }
    var longestTurn by remember { mutableStateOf(0L) }
    var conversations by remember { mutableStateOf(0) }
    // 首帧 days/models 还是空表：没有这一位就会先说「还没有用量记录」再冒数据。
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // **失败不能静默**（issue #41）：原来没有 catch，refresh() 一抛异常就永远
        // `loaded = false`，页面停在"还没有用量记录"且没有任何线索。
        val (snapshot, byDay, byModel) = withContext(Dispatchers.IO) {
            val refreshed = runCatching { store.refresh() }
                .onFailure {
                    if (it is CancellationException) throw it
                    android.util.Log.w("suixing-usage", "refresh failed", it)
                }
                .getOrDefault(com.suixing.agent.data.UsageSnapshot())
            // 日期/模型投影都要排序完整累计表，随后才截取最近30天。与刷新一起在后台
            // 计算，并固定到同一份返回快照，避免转场中主线程排序和跨快照读数混用。
            Triple(refreshed, byDayOf(refreshed, 30), byModelOf(refreshed))
        }
        days = byDay
        models = byModel
        totals = snapshot.totals
        val chats = Core.chats.conversations.value
        conversations = chats.size
        // One run — one engine pass — is what one bubble on the chat screen reports, and it
        // is the unit this card is named after. Context is cumulative, so only the run's
        // newest round knows the real prompt size: the prompt is not summed, the output is
        // (the same reading as ChatScreen's Usage.of). Rounds written before branching carry
        // no run id, and each of those is its own group — matching the chat bubble's merge
        // guard (ChatScreen.kt:1148, `run.isNotEmpty()`). Folding them into one BASE_RUN
        // group instead would add every old reply in a chat up as though it were one run.
        // Every chat's every message is walked here — the heaviest step on this page, so it
        // stays off the main thread like the store refresh above.
        longestTurn = withContext(Dispatchers.IO) {
            // 列表只带 meta（#30），单轮数据从 sessionTotals 拿（meta 里缓存，backfill 才读盘）。
            chats.maxOfOrNull { chat ->
                Core.chats.sessionTotals(chat.id)?.longestTurnTokens ?: 0L
            } ?: 0L
        }
        loaded = true
    }
    val scheme = MaterialTheme.colorScheme
    Scaffold(
        containerColor = scheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { PageTopBar(title = "用量", onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = settingsPagePadding(),
            verticalArrangement = Arrangement.spacedBy(SettingsStyle.SectionGap),
        ) {
            item {
                // 三个标题是常量；三个数值各要一次 String.format（`compact`）。都记在 item 的
                // remember 里，滚动/重组不再逐次重排格式化 —— 输出的字符串一个字节都没变。
                val statTitles = remember { listOf("累计 tokens", "会话数", "最长单轮") }
                val statValues = remember(totals.total, conversations, longestTurn) {
                    listOf(compact(totals.total), "$conversations", compact(longestTurn))
                }
                StatCards(titles = statTitles, values = statValues)
            }
            // #41 真问题 5：三个地方各显示一个数，用户没法知道口径差在哪 —— 这里一行说明
            // 「为什么用量页的数字看起来比网关后台大」：prompt 是按**每次请求**全量累加的，
            // 一轮工具循环 = 十几次请求，而缓存命中的部分网关按约 1/10 计费。
            item {
                Text(
                    text = "tokens 按每次请求全量累加（一轮工具循环 ≈ 十几次请求）；" +
                        "缓存命中的部分网关按约 1/10 计费 —— 这里的数字和账单金额不是一回事。",
                    style = SettingsStyle.badge,
                    color = scheme.onSurfaceVariant,
                )
            }
            item {
                SectionTitle("近 30 天")
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = scheme.surfaceContainer,
                    // 与 SuixingCard / 任务行 / 定时页同一张卡：shapes.large + 1dp outlineVariant。
                    border = BorderStroke(1.dp, scheme.outlineVariant),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (loaded && days.isEmpty()) {
                            Text(
                                text = "还没有用量记录",
                                style = SettingsStyle.description,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        // 峰值与每一行的数值字符串只跟 days 走：remember 一次，别跟着重组重算
                        // （compact 里是一次 String.format）。
                        val peak = remember(days) { days.maxOfOrNull { it.tokens.total } ?: 0L }
                        val dayLabels = remember(days) { days.map { compact(it.tokens.total) } }
                        days.forEachIndexed { index, day ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    // drop 而不是 substring(5)：date 是磁盘缓存 usage.json 的 map key，
                                    // 解码时不校验格式，短于 6 字符的 key 会让 substring 在组合期抛。
                                    text = day.date.drop(5),
                                    style = SettingsStyle.badge,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier.width(44.dp),
                                )
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .heightIn(min = 10.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth(fraction = if (peak > 0) (day.tokens.total.toFloat() / peak).coerceIn(0.02f, 1f) else 0.02f)
                                            .height(10.dp)
                                            .background(scheme.primary, RoundedCornerShape(5.dp)),
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = dayLabels[index],
                                    style = SettingsStyle.badge,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier.width(52.dp),
                                )
                            }
                        }
                    }
                }
            }
            item {
                SectionTitle("按模型")
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = scheme.surfaceContainer,
                    border = BorderStroke(1.dp, scheme.outlineVariant),
                ) {
                    val modelTotals = remember(models) { models.map { compact(it.tokens.total) } }
                    if (models.size > 12) {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 480.dp),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            itemsIndexed(models, key = { _, model -> model.model }, contentType = { _, _ -> "model-usage" }) { index, model ->
                                ModelUsageRow(model, modelTotals[index])
                            }
                        }
                    } else {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (loaded && models.isEmpty()) {
                                Text("还没有用量记录", style = SettingsStyle.description, color = scheme.onSurfaceVariant)
                            }
                            models.forEachIndexed { index, model -> ModelUsageRow(model, modelTotals[index]) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelUsageRow(model: ModelUsage, total: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(model.model, style = SettingsStyle.body, modifier = Modifier.weight(1f))
        Text(total, style = SettingsStyle.metadata, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionTitle(text: String) {
    SettingSectionTitle(text)
}

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
private fun compact(value: Long): String = when {
    value >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", value / 1_000.0)
    else -> "$value"
}
