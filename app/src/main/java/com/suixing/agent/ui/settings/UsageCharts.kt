package com.suixing.agent.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suixing.agent.R
import com.suixing.agent.data.DayUsage
import com.suixing.agent.data.ModelUsage
import com.suixing.agent.ui.components.SettingsStyle
import com.suixing.agent.ui.components.actionIconButtonColors
import com.suixing.agent.ui.components.SuixingCard
import java.time.LocalDate
import kotlin.math.ceil

internal fun usageHeatLevel(value: Long, peak: Long): Int =
    if (value <= 0 || peak <= 0) 0 else ceil(value.toDouble() / peak * 4).toInt().coerceIn(1, 4)

internal fun usageHeatWeeks(days: List<DayUsage>): List<List<DayUsage?>> {
    if (days.isEmpty()) return emptyList()
    val leading = LocalDate.parse(days.first().date).dayOfWeek.value - 1
    val cells = List<DayUsage?>(leading) { null } + days
    return (cells + List<DayUsage?>((7 - cells.size % 7) % 7) { null }).chunked(7)
}

@Composable
internal fun UsageHeatmap(days: List<DayUsage>) {
    val scheme = MaterialTheme.colorScheme
    val weeks = remember(days) { usageHeatWeeks(days) }
    val peak = remember(days) { days.maxOfOrNull { it.tokens.total } ?: 0L }
    var selected by rememberSaveable(days.firstOrNull()?.date) { mutableStateOf(days.lastOrNull()?.date.orEmpty()) }
    val current = days.firstOrNull { it.date == selected } ?: days.lastOrNull()
    val lineHeight = MaterialTheme.typography.labelSmall.lineHeight
    val headerHeight = with(LocalDensity.current) { lineHeight.toDp() }.coerceAtLeast(16.dp)
    val palette = listOf(scheme.surfaceContainerHighest, scheme.primary.copy(alpha = .22f),
        scheme.primary.copy(alpha = .45f), scheme.primary.copy(alpha = .7f), scheme.primary)
    SuixingCard {
        Text("活动热力图", style = SettingsStyle.cardTitle)
        Text("近 90 个自然日 · 按已报告 tokens 着色", style = SettingsStyle.description, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 4.dp
            val labelWidth = 18.dp
            // Round once in pixels so every week uses the same square, including the last column.
            val cellSize = with(LocalDensity.current) {
                if (weeks.isEmpty()) 16.dp else
                    ((constraints.maxWidth - labelWidth.roundToPx() - gap.roundToPx() * weeks.size) / weeks.size)
                        .coerceAtLeast(0).toDp()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Spacer(Modifier.height(headerHeight))
                    listOf("一", "", "三", "", "五", "", "日").forEach { label ->
                        Box(Modifier.width(labelWidth).height(cellSize), contentAlignment = Alignment.Center) {
                            Text(label, style = SettingsStyle.badge, color = scheme.onSurfaceVariant)
                        }
                    }
                }
                weeks.forEachIndexed { index, week ->
                    Column(Modifier.width(cellSize), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val first = week.filterNotNull().firstOrNull()?.date?.let(LocalDate::parse)
                        Text(if (first != null && (index == 0 || first.dayOfMonth <= 7)) first.monthValue.toString() else "",
                            modifier = Modifier.height(headerHeight), style = SettingsStyle.badge,
                            color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Clip)
                        week.forEach { day ->
                            val shape = RoundedCornerShape(3.dp)
                            val level = usageHeatLevel(day?.tokens?.total ?: 0, peak)
                            Box(Modifier.size(cellSize)
                                .background(if (day == null) Color.Transparent else palette[level], shape)
                                .then(if (day == null) Modifier else Modifier
                                    .border(if (day.date == selected) 2.dp else 1.dp,
                                        if (day.date == selected) scheme.onSurface else if (day.unreported > 0 && day.reported == 0L)
                                            scheme.primary else Color.Transparent, shape)
                                    .clickable { selected = day.date }
                                    .semantics { contentDescription = "${day.date}，已报告 ${day.tokens.total} tokens，${day.unreported} 次未报告用量" }))
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("少", style = SettingsStyle.badge, color = scheme.onSurfaceVariant)
            palette.forEach { color -> Box(Modifier.padding(start = 4.dp).size(10.dp).background(color, RoundedCornerShape(2.dp))) }
            Text("多", modifier = Modifier.padding(start = 4.dp), style = SettingsStyle.badge, color = scheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("空心：仅未报告", style = SettingsStyle.badge, color = scheme.onSurfaceVariant)
        }
        current?.let { day ->
            UsageDayDetail(day, days, onSelect = { selected = it })
        }
    }
}

@Composable
internal fun UsageDailyChart(days: List<DayUsage>, range: Int) {
    val scheme = MaterialTheme.colorScheme
    val peak = remember(days) { days.maxOfOrNull { it.tokens.total } ?: 0L }
    var selected by rememberSaveable(days.firstOrNull()?.date) { mutableStateOf(days.lastOrNull()?.date.orEmpty()) }
    val current = days.firstOrNull { it.date == selected } ?: days.lastOrNull()
    SuixingCard {
        Text("每日用量", style = SettingsStyle.cardTitle)
        Text("近 $range 天 · 峰值 ${usageExact(peak)} tokens", style = SettingsStyle.description, color = scheme.onSurfaceVariant)
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            UsageLegend("输入", scheme.primary)
            UsageLegend("输出", scheme.tertiary)
        }
        Canvas(Modifier.fillMaxWidth().height(148.dp).padding(top = 12.dp)
            .semantics { contentDescription = "近 $range 天输入输出堆叠条形图；下方可逐日查看精确值" }
            .pointerInput(days) {
                detectTapGestures { tap ->
                    if (days.isNotEmpty() && size.width > 0) {
                        selected = days[(tap.x / size.width * days.size).toInt().coerceIn(days.indices)].date
                    }
                }
            }) {
            for (step in 1..3) drawLine(scheme.outlineVariant.copy(alpha = .5f),
                Offset(0f, size.height * step / 3), Offset(size.width, size.height * step / 3), 1.dp.toPx())
            if (days.isNotEmpty()) {
                val slot = size.width / days.size
                val width = (slot * .68f).coerceAtLeast(1f)
                days.forEachIndexed { index, day ->
                    val height = if (peak == 0L) 0f else (day.tokens.total.toDouble() / peak * size.height).toFloat()
                    val input = if (day.tokens.total == 0L) 0f else (day.tokens.prompt.toDouble() / day.tokens.total * height).toFloat()
                    val x = slot * index + (slot - width) / 2
                    if (height > 0f) {
                        drawRect(scheme.primary, Offset(x, size.height - input), Size(width, input))
                        drawRect(scheme.tertiary, Offset(x, size.height - height), Size(width, height - input))
                    }
                    if (day.date == selected) drawLine(scheme.onSurface.copy(alpha = .35f),
                        Offset(slot * (index + .5f), 0f), Offset(slot * (index + .5f), size.height), 1.dp.toPx())
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(days.firstOrNull()?.date?.drop(5).orEmpty(), style = SettingsStyle.badge, color = scheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(days.lastOrNull()?.date?.drop(5).orEmpty(), style = SettingsStyle.badge, color = scheme.onSurfaceVariant)
        }
        current?.let { UsageDayDetail(it, days, onSelect = { date -> selected = date }) }
    }
}

@Composable
private fun UsageDayDetail(day: DayUsage, days: List<DayUsage>, onSelect: (String) -> Unit) {
    val index = days.indexOfFirst { it.date == day.date }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(day.date, style = SettingsStyle.metadata)
            Text("${usageExact(day.tokens.total)} tokens", style = SettingsStyle.body)
            Text("输入 ${usageExact(day.tokens.prompt)} · 输出 ${usageExact(day.tokens.completion)}",
                style = SettingsStyle.badge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${day.reported + day.unreported} 次响应 · ${day.unreported} 次未报告用量",
                style = SettingsStyle.badge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(enabled = index > 0, onClick = { onSelect(days[index - 1].date) }, colors = actionIconButtonColors()) {
            Icon(ImageVector.vectorResource(R.drawable.ic_chevron_left), "前一天")
        }
        IconButton(enabled = index >= 0 && index < days.lastIndex, onClick = { onSelect(days[index + 1].date) }, colors = actionIconButtonColors()) {
            Icon(ImageVector.vectorResource(R.drawable.ic_chevron_right), "后一天")
        }
    }
}

@Composable
private fun UsageLegend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(2.dp)))
        Text(label, style = SettingsStyle.badge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun UsageModelBar(model: ModelUsage, total: Long, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val fraction = if (total > 0) (model.tokens.total.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
    SuixingCard(onClick = onClick, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(model.model, modifier = Modifier.weight(1f), style = SettingsStyle.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(10.dp))
            Text(compact(model.tokens.total), style = SettingsStyle.metadata)
        }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(5.dp).background(scheme.surfaceContainerHighest, RoundedCornerShape(3.dp))) {
            if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(scheme.primary, RoundedCornerShape(3.dp)))
        }
        Row(Modifier.padding(top = 6.dp)) {
            Text("${usageExact(model.tokens.total)} tokens", modifier = Modifier.weight(1f), style = SettingsStyle.badge,
                color = scheme.onSurfaceVariant)
            Text(if (total > 0) String.format(java.util.Locale.getDefault(), "%.1f%%", fraction * 100) else "—",
                style = SettingsStyle.badge, color = scheme.onSurfaceVariant)
        }
    }
}
