package com.suixing.agent.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.suixing.agent.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suixing.agent.agent.ChildRunSummary
import com.suixing.agent.agent.SubagentRecords
import com.suixing.agent.agent.SubagentStatus
import com.suixing.agent.core.Core
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.ChildRunConfig
import com.suixing.agent.data.Roles
import com.suixing.agent.ui.components.SettingsStyle
import com.suixing.agent.ui.components.SuixingDialog
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 查看子智能体：一个**弹窗**，不是第三个页面。
 *
 * 之前这里是 `Dialog + fillMaxSize + Scaffold + PageTopBar`，实际整屏铺开——顶栏、返回箭头
 * 和独立页面一模一样，站在别的页面旁边就成了另一种风格。现在换成和项目其它弹窗同一套外形
 * （`AlertDialogDefaults` 的圆角与容器色），标题栏只放名字、状态和三个动作，正文交给下面
 * 现成的子对话渲染复用。
 */
@Composable
internal fun SubagentRunViewer(
    chatId: String, summaries: List<ChildRunSummary>, selectedId: String?,
    onSelect: (String?) -> Unit, readingState: SaveableStateHolder, onClose: () -> Unit,
) {
    val selected = summaries.firstOrNull { it.id == selectedId } ?: summaries.lastOrNull()
    var switching by remember { mutableStateOf(false) }
    var details by remember(selected?.id) { mutableStateOf(false) }
    val maxHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.82f)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = onClose)
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 520.dp).heightIn(max = maxHeight),
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(52.dp).padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = selected?.name ?: "子智能体",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        selected?.let { child ->
                            Text(
                                text = "子智能体 · ${subagentStatusLabel(child.status)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (child.status.isActive()) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                    if (summaries.size > 1) Box {
                        TextButton(onClick = { switching = true }) { Text("切换") }
                        DropdownMenu(expanded = switching, onDismissRequest = { switching = false }) {
                            summaries.forEach { child ->
                                DropdownMenuItem(
                                    text = { Text("${child.name} · ${subagentStatusLabel(child.status)}") },
                                    onClick = { switching = false; onSelect(child.id) },
                                )
                            }
                        }
                    }
                    if (selected != null) TextButton(onClick = { details = true }) { Text("详情") }
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_x),
                            contentDescription = "关闭",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                if (selected == null) {
                    Text(
                        "当前会话还没有子智能体记录。",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else readingState.SaveableStateProvider("child:${selected.id}") {
                    SubagentConversation(
                        chatId, selected.id, details,
                        onDismissDetails = { details = false },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private val ExpansionSaver = mapSaver<androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>>(
    save = { it.toMap() },
    restore = { values -> mutableStateMapOf<String, Boolean>().apply {
        values.forEach { (key, value) -> put(key, value as Boolean) }
    } },
)

/** Only the selected child subscribes to token updates; reading state belongs to that child. */
@Composable
private fun SubagentConversation(
    chatId: String,
    id: String,
    details: Boolean,
    onDismissDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val record by remember(id) { SubagentRecords.record(id) }.collectAsStateWithLifecycle()
    val approval by remember(id) { Core.gate.pending.map { it[id] }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = Core.gate.pending.value[id])
    val settings by remember { Core.settings.flow.distinctUntilChanged { a, b -> a.sameForChat(b) } }
        .collectAsStateWithLifecycle(initialValue = AppSettings())
    val value = record
    if (value == null) {
        Text("正在读取对话…", Modifier.padding(SettingsStyle.PageMargin))
        return
    }
    val history = remember(IdentityKey(value.messages)) { subagentHistory(value.messages) }
    val rows = remember(value, IdentityKey(history)) { subagentTranscript(value, history) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val atBottom by remember(listState) { derivedStateOf { !listState.canScrollForward } }
    var followLatest by rememberSaveable { mutableStateOf(true) }
    val expanded = rememberSaveable(saver = ExpansionSaver) { mutableStateMapOf<String, Boolean>() }
    val expansion = remember(id) { ReasoningExpansion(id, expanded) }
    val position = remember(listState) { TranscriptPosition(listState) }
    var pressed by remember { mutableStateOf(false) }
    val followNow = rememberUpdatedState {
        followLatest && settings.autoFollow && !pressed && !listState.isScrollInProgress
    }
    SideEffect {
        position.following = { followNow.value() }
        position.reading = { !followLatest && !pressed && !listState.isScrollInProgress }
    }
    val assistant = settings.assistant(value.config.assistantId)
    val bubbleWidth = LocalConfiguration.current.screenWidthDp.dp * settings.bubbleWidthFraction
    LaunchedEffect(listState) {
        // The initial unmeasured list reports canScrollForward=false. It must not overwrite
        // a restored child's saved reading mode before the first layout.
        var scrolled = false
        snapshotFlow { listState.isScrollInProgress }.distinctUntilChanged().collect { scrolling ->
            if (scrolling) {
                scrolled = true
                followLatest = false
            } else if (scrolled) {
                followLatest = !listState.canScrollForward
                scrolled = false
            }
        }
    }
    LaunchedEffect(position) {
        snapshotFlow { Triple(followLatest, pressed, listState.isScrollInProgress) }
            .collect { position.interactionChanged() }
    }
    Column(modifier.fillMaxWidth().fillMaxHeight()) {
        CompositionLocalProvider(LocalChatReadOnly provides true, LocalTranscriptPosition provides position) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().transcriptPosition(position)
                    .pointerInput(id) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            pressed = true
                            try {
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Final)
                                } while (event.changes.any { it.pressed })
                            } finally { pressed = false }
                        }
                    }, state = listState,
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(rows, key = { index, row ->
                    if (row.message.role == Roles.USER || row.message.role == Roles.SYSTEM) row.message.id else "$id:reply:$index"
                }, contentType = { _, row -> if (row.message.role == Roles.USER) "user" else "reply" }) { index, row ->
                    val reply = row.message.role == Roles.ASSISTANT || row.message.role == Roles.TOOL
                    val streaming = reply && index == rows.lastIndex && value.status.isActive()
                    CompositionLocalProvider(LocalReadingRowKey provides "$id:$index") {
                        Column {
                            if (settings.showTimestamps && row.showTimeLabel) {
                                TimeLabel(row.message.timestamp)
                                Spacer(Modifier.height(12.dp))
                            }
                            if (reply) {
                                val parts = remember(IdentityKey(row.parts), settings.showReasoning) {
                                    if (settings.showReasoning) row.parts else row.parts.filterNot { it is Part.Thinking }
                                }
                                SelectableWhenArmed(enabled = !streaming && !expansion.completionDeferred(id)) {
                                AssistantRow(
                                    parts = parts, streaming = streaming, name = value.config.assistantName,
                                    face = assistant?.face.orEmpty(), avatarPath = assistant?.avatarPath.orEmpty(),
                                    runningTool = value.currentTool.takeIf { streaming && it.isNotBlank() },
                                    message = row.message, runId = id, usage = row.usage,
                                    engineLabel = value.config.model, expandTools = settings.expandTools,
                                    expandReasoning = settings.expandReasoning, reasoningMaxHeight = settings.reasoningHeight.dp,
                                    followReasoning = settings.followReasoning, bubbleMaxWidth = bubbleWidth,
                                    reasoningExpansion = expansion, card = settings.assistantCard,
                                    flatPad = settings.flatPaddingDp.dp, fileActivities = row.fileActivities,
                                )
                                }
                            } else MessageRow(
                                row = row, assistantName = value.config.assistantName,
                                showReasoning = settings.showReasoning, expandTools = settings.expandTools,
                                reasoningMaxHeight = settings.reasoningHeight.dp, bubbleMaxWidth = bubbleWidth,
                                contextWindow = 0, reasoningExpansion = expansion,
                                onBranch = { _, _ -> }, onRegenerate = {}, onEdit = {},
                            )
                        }
                    }
                }
            }
        }
        if (value.status.isActive() || approval != null || value.saveError.isNotBlank() || value.statusLine.isNotBlank() || !atBottom) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (value.saveError.isNotBlank()) Text(value.saveError, color = MaterialTheme.colorScheme.error)
                if (value.statusLine.isNotBlank()) Text(value.statusLine, style = MaterialTheme.typography.bodySmall,
                    color = if (value.status == SubagentStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                approval?.let { request ->
                    Text("等待审批：${request.toolName}", style = MaterialTheme.typography.bodyMedium)
                    Row {
                        TextButton(onClick = { Core.gate.resolve(id, true, expectedRequestId = request.id) }) { Text("允许一次") }
                        TextButton(onClick = { Core.gate.resolve(id, false, expectedRequestId = request.id) }) { Text("拒绝") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(subagentStatusLabel(value.status), modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!atBottom) TextButton(onClick = {
                        scope.launch {
                            listState.pinToBottom()
                            followLatest = true
                            position.rebaseAfterNavigation()
                        }
                    }) { Text("最新") }
                    if (value.status.isActive()) TextButton(onClick = { Core.engine.cancelSubagent(chatId, id) }) { Text("停止") }
                }
            }
        }
    }
    if (details) SuixingDialog(
        onDismissRequest = onDismissDetails, title = { Text("对话详情") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(subagentStatusLabel(value.status), style = MaterialTheme.typography.titleMedium)
                if ((value.status == SubagentStatus.FAILED || value.status == SubagentStatus.CANCELLED) && value.result.isNotBlank()) {
                    SelectionContainer { Text(value.result, style = MaterialTheme.typography.bodySmall,
                        color = if (value.status == SubagentStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                SubagentEffectiveConfig(value.config)
                if (value.context.isNotBlank()) {
                    Text("主代理提供的上下文", style = MaterialTheme.typography.titleSmall)
                    SelectionContainer { Text(value.context, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = { dialog -> TextButton(onClick = { dialog.closeThen(onDismissDetails) }) { Text("关闭") } },
    )
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
