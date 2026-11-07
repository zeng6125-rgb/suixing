package com.suixing.agent.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suixing.agent.agent.ChildRunSummary
import com.suixing.agent.agent.SubagentRecords
import com.suixing.agent.agent.SubagentStatus
import com.suixing.agent.core.Core
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.ChildRunConfig
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.ui.components.PageTopBar
import com.suixing.agent.ui.components.SettingsStyle
import com.suixing.agent.ui.components.SuixingCard
import com.suixing.agent.ui.chat.markdown.MarkdownText
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private data class SubagentUsage(val input: Long, val output: Long, val reasoning: Long, val cached: Long)

/** Viewing a child is independent from the parent chat and never changes its assistant or run. */
@Composable
internal fun SubagentRunViewer(
    chatId: String,
    summaries: List<ChildRunSummary>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    canCreate: Boolean,
    readingState: SaveableStateHolder,
    onCreate: () -> Unit,
    onClose: () -> Unit,
) {
    val selected = summaries.firstOrNull { it.id == selectedId }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { PageTopBar(title = selected?.name ?: "子任务", onBack = onClose) },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsStyle.PageMargin),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "overview") {
                        FilterChip(selected = selectedId == null, onClick = { onSelect(null) }, label = { Text("总览") })
                    }
                    items(summaries, key = { it.id }) { task ->
                        FilterChip(selected = selectedId == task.id, onClick = { onSelect(task.id) }, label = { Text(task.name) })
                    }
                }
                if (selectedId == null) {
                    readingState.SaveableStateProvider("overview") {
                        SubagentOverview(chatId, summaries, canCreate, onCreate, onSelect)
                    }
                } else {
                    readingState.SaveableStateProvider("child:$selectedId") {
                        SubagentRecordPage(chatId, selectedId)
                    }
                }
            }
        }
    }
}

@Composable
private fun SubagentOverview(
    chatId: String,
    summaries: List<ChildRunSummary>,
    canCreate: Boolean,
    onCreate: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = rememberLazyListState(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(SettingsStyle.PageMargin),
        verticalArrangement = Arrangement.spacedBy(SettingsStyle.SectionGap),
    ) {
        item(key = "controls") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCreate, enabled = canCreate) { Text("创建子任务") }
            }
            if (!canCreate) Text("父任务未运行时，可查看已保留的子任务记录。", style = SettingsStyle.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (summaries.isEmpty()) item(key = "empty") {
            Text("还没有子任务。创建时可指定助手、模型、权限与工具。", style = SettingsStyle.body)
        }
        items(summaries, key = { it.id }, contentType = { "child-summary" }) { task ->
            SuixingCard(onClick = { onSelect(task.id) }) {
                Text(task.name, style = SettingsStyle.cardTitle)
                Text(subagentStatusLabel(task.status), style = SettingsStyle.description,
                    color = if (task.status == SubagentStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${task.config.assistantName} · ${task.config.model}", style = SettingsStyle.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onSelect(task.id) }) { Text("查看完整过程") }
                    if (task.status.isActive()) TextButton(onClick = { Core.engine.cancelSubagent(chatId, task.id) }) { Text("取消") }
                }
            }
        }
    }
}

/** This is the only per-token record subscription; hidden children update no Compose tree. */
@Composable
private fun SubagentRecordPage(chatId: String, id: String) {
    val record by remember(id) { SubagentRecords.record(id) }.collectAsStateWithLifecycle()
    val approval by remember(id) { Core.gate.pending.map { it[id] }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = Core.gate.pending.value[id])
    val value = record
    if (value == null) {
        Text("正在读取子任务记录…", modifier = Modifier.padding(SettingsStyle.PageMargin), style = SettingsStyle.body)
        return
    }
    val usage = remember(value.messages) {
        if (value.messages.none { it.usageKnown }) null else SubagentUsage(
            value.messages.sumOf { it.promptTokens.toLong() },
            value.messages.sumOf { it.completionTokens.toLong() },
            value.messages.sumOf { it.reasoningTokens.toLong() },
            value.messages.sumOf { it.cachedTokens.toLong() },
        )
    }
    // Live text/reasoning publishes arrive on a throttled cadence while the message list usually
    // stays unchanged. Cache this transcript-wide predicate with the same list identity so a long
    // child history is not scanned on every live update.
    val hasAssistantText = remember(value.messages) {
        value.messages.any { it.role == Roles.ASSISTANT && it.text.isNotBlank() }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = rememberLazyListState(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(SettingsStyle.PageMargin),
        verticalArrangement = Arrangement.spacedBy(SettingsStyle.SectionGap),
    ) {
        item(key = "config") {
            SuixingCard {
                Text(subagentStatusLabel(value.status), style = SettingsStyle.cardTitle)
                SubagentEffectiveConfig(value.config)
                Text(usage?.let { "已报告用量：输入 ${it.input} · 输出 ${it.output} · 推理 ${it.reasoning} · 缓存 ${it.cached}" } ?: "用量：—",
                    style = SettingsStyle.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (value.saveError.isNotBlank()) Text(value.saveError, style = SettingsStyle.description,
                    color = MaterialTheme.colorScheme.error)
                if (value.statusLine.isNotBlank()) Text(value.statusLine, style = SettingsStyle.description,
                    color = if (value.status == SubagentStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                value.result.takeIf {
                    (value.status == SubagentStatus.FAILED || value.status == SubagentStatus.CANCELLED) &&
                        it.isNotBlank() && it.trim() != value.statusLine.trim() &&
                        (value.status != SubagentStatus.CANCELLED || it.trim() != "子任务已取消")
                }?.let { detail ->
                    SelectionContainer {
                        Text(detail, style = SettingsStyle.description,
                            color = if (value.status == SubagentStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                approval?.let { request ->
                    Text("等待审批：${request.toolName}", style = SettingsStyle.body)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            Core.gate.resolve(id, true, expectedRequestId = request.id)
                        }) { Text("允许一次") }
                        TextButton(onClick = {
                            Core.gate.resolve(id, false, expectedRequestId = request.id)
                        }) { Text("拒绝") }
                    }
                }
                if (value.status.isActive()) TextButton(onClick = { Core.engine.cancelSubagent(chatId, id) }) { Text("取消此子任务") }
            }
        }
        items(value.messages, key = { it.id }, contentType = { it.role }) { message ->
            SubagentStoredMessage(message)
        }
        if (value.liveReasoning.isNotBlank()) item(key = "live-reasoning") {
            SubagentReasoning(value.liveReasoning, streaming = value.status.isActive())
        }
        if (value.liveText.isNotBlank()) item(key = "live-text") {
            SelectionContainer { MarkdownText(value.liveText, settled = !value.status.isActive()) }
        }
        if (value.currentTool.isNotBlank()) item(key = "current-tool") {
            SuixingCard {
                Text("执行工具：${value.currentTool}", style = SettingsStyle.body)
                ToolDetail(ToolLine(value.currentTool, value.currentToolArgs, done = false, ok = true, elapsedMs = 0), maxHeight = 320.dp)
            }
        }
        if (value.status == SubagentStatus.COMPLETED && value.result.isNotBlank() && !hasAssistantText) {
            item(key = "result") { SelectionContainer { MarkdownText(value.result) } }
        }
    }
}

@Composable
internal fun SubagentEffectiveConfig(config: ChildRunConfig) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Text("模板：${config.profileName}", style = SettingsStyle.description)
    Text("助手：${config.assistantName}", style = SettingsStyle.description)
    Text("模型：${config.model}", style = SettingsStyle.description)
    Text("审批：${subagentApprovalLabel(config.approvalMode)} · 工具 ${config.allowedTools.size} 个", style = SettingsStyle.description)
    Text("预算：${if (config.maxToolRounds == 0) "不限轮数" else "${config.maxToolRounds} 轮"}，${config.timeoutSeconds} 秒",
        style = SettingsStyle.description)
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起工具范围" else "查看工具范围") }
    if (expanded) SelectionContainer {
        Column {
            Text(config.allowedTools.joinToString("、").ifBlank { "没有获准的工具" }, style = SettingsStyle.description)
            config.allowedActions.forEach { (tool, actions) ->
                Text("$tool：${actions.joinToString("、")}", style = SettingsStyle.description)
            }
        }
    }
}

@Composable
private fun SubagentStoredMessage(message: StoredMessage) {
    SuixingCard {
        Text(when (message.role) { Roles.USER -> "任务输入"; Roles.TOOL -> "工具：${message.toolName.orEmpty()}"; else -> "助手" },
            style = SettingsStyle.metadata, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (message.role == Roles.TOOL) {
            ToolDetail(ToolLine.of(message), maxHeight = 360.dp)
        } else {
            message.reasoning?.takeIf { it.isNotBlank() }?.let { SubagentReasoning(it, streaming = false) }
            if (message.text.isNotBlank()) SelectionContainer { MarkdownText(message.text) }
            message.toolCalls.forEach { call ->
                Text("请求工具：${call.name}", style = SettingsStyle.description)
                ToolDetail(ToolLine(call.name, call.arguments, done = false, ok = true, elapsedMs = 0), maxHeight = 240.dp)
            }
            if (message.imageCount > 0) message.allImages.forEach { ImageBlock(it) }
        }
        message.traceNote?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = SettingsStyle.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (message.fileActivities.isNotEmpty()) {
            val files = remember(message.fileActivities) { fileActivitiesFor(emptyList(), listOf(message)) }
            ChatFileActivity(files)
        }
    }
}

@Composable
private fun SubagentReasoning(text: String, streaming: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    Column {
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起思考" else "查看思考") }
        if (expanded) SelectionContainer { MarkdownText(text, settled = !streaming, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

internal fun SubagentStatus.isActive(): Boolean = this == SubagentStatus.QUEUED || this == SubagentStatus.RUNNING
internal fun subagentStatusLabel(status: SubagentStatus): String = when (status) {
    SubagentStatus.QUEUED -> "排队中"
    SubagentStatus.RUNNING -> "执行中"
    SubagentStatus.COMPLETED -> "已完成"
    SubagentStatus.FAILED -> "失败"
    SubagentStatus.CANCELLED -> "已取消"
}
internal fun subagentApprovalLabel(mode: ApprovalMode): String = when (mode) {
    ApprovalMode.ASK -> "每次询问"
    ApprovalMode.AUTO -> "自动"
    ApprovalMode.DENY -> "拒绝"
}
