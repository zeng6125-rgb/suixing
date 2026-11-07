package com.suixing.agent.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suixing.agent.R
import com.suixing.agent.agent.SubagentRecords
import com.suixing.agent.agent.SubagentStatus
import com.suixing.agent.core.Core
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.SubagentLaunchOptions
import com.suixing.agent.data.SubagentProfile
import com.suixing.agent.ui.components.CompactOutlinedTextField
import com.suixing.agent.ui.components.SettingsStyle
import com.suixing.agent.ui.components.SuixingDialog
import com.suixing.agent.ui.settings.SubagentConfigEditor
import com.suixing.agent.ui.settings.subagentProfileValidation
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The composer observes summaries and availability, never the children's text deltas. */
@Composable
internal fun SubagentStatusAction(chatId: String) {
    val tasks by remember(chatId) { SubagentRecords.summaries(chatId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val canCreate by remember(chatId) {
        Core.engine.subagentAvailabilityFlow.map { chatId in it }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = Core.engine.canCreateSubagent(chatId))
    LaunchedEffect(chatId) { SubagentRecords.load(chatId) }
    var open by remember(chatId) { mutableStateOf(false) }
    var creating by remember(chatId) { mutableStateOf(false) }
    var selectedId by remember(chatId) { mutableStateOf<String?>(null) }
    val readingState = rememberSaveableStateHolder()
    if (tasks.isEmpty() && !canCreate) return
    val completed = tasks.count { it.status == SubagentStatus.COMPLETED }
    IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) {
        BadgedBox(badge = { if (tasks.isNotEmpty()) Badge { Text(tasks.size.toString()) } }) {
            Icon(ImageVector.vectorResource(R.drawable.ic_list_checks),
                "子任务：$completed/${tasks.size} 已完成，可创建和查看",
                Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
    if (open) SubagentRunViewer(
        chatId = chatId, summaries = tasks, selectedId = selectedId, onSelect = { selectedId = it },
        canCreate = canCreate, readingState = readingState, onCreate = { creating = true }, onClose = { open = false },
    )
    if (creating) SubagentCreateDialog(
        chatId = chatId, canCreate = canCreate, onDismiss = { creating = false },
        onCreated = { id -> creating = false; selectedId = id; open = true },
    )
}

@Composable
private fun SubagentCreateDialog(chatId: String, canCreate: Boolean, onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    val parentRun by remember(chatId) { Core.engine.runsFlow.map { it[chatId] }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = Core.engine.runsFlow.value[chatId])
    val settings by remember {
        Core.settings.flow.map<AppSettings, AppSettings?> { it }
    }.collectAsStateWithLifecycle(initialValue = null)
    val stored = settings
    if (stored == null) {
        SuixingDialog(onDismissRequest = onDismiss, title = { Text("创建子任务") }, text = { Text("正在读取配置…") },
            confirmButton = { dialog -> TextButton(onClick = { dialog.closeThen(onDismiss) }) { Text("关闭") } })
        return
    }
    var task by remember { mutableStateOf("") }
    var context by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var draft by remember {
        mutableStateOf(stored.subagentProfiles.firstOrNull { it.id == stored.defaultSubagentProfileId }
            ?: SubagentProfile(id = "", name = "本次配置", model = stored.subagentModel))
    }
    var menu by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val parentTools = remember(chatId, parentRun, canCreate) {
        if (!canCreate) emptyList() else runCatching {
            Core.engine.previewSubagent(chatId, SubagentLaunchOptions(
                draftProfile = SubagentProfile(id = "", name = "父任务范围"),
            )).allowedTools
        }.getOrDefault(emptyList())
    }
    val options = remember(draft) { SubagentLaunchOptions(profileId = draft.id, draftProfile = draft) }
    val validation = remember(draft, stored, parentTools) { subagentProfileValidation(draft, stored, parentTools) }
    val preview = remember(chatId, parentRun, options, canCreate) {
        if (!canCreate) null else runCatching { Core.engine.previewSubagent(chatId, options) }
    }
    SuixingDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = !closing, dismissOnClickOutside = !closing),
        title = { Text("创建子任务") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(SettingsStyle.FieldGap)) {
                CompactOutlinedTextField(value = task, onValueChange = { if (!closing) { task = it; error = "" } },
                    enabled = !closing, label = { Text("任务与期望结果") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                CompactOutlinedTextField(value = name, onValueChange = { if (!closing) { name = it.take(64); error = "" } },
                    enabled = !closing, label = { Text("任务名称（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                CompactOutlinedTextField(value = context, onValueChange = { if (!closing) { context = it; error = "" } },
                    enabled = !closing, label = { Text("相关上下文（可选）") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Box {
                    OutlinedButton(enabled = !closing, onClick = { menu = true }) { Text("模板：${draft.name}") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("本次配置（跟随父任务）") }, onClick = {
                            draft = SubagentProfile(id = "", name = "本次配置"); menu = false; error = ""
                        })
                        stored.subagentProfiles.forEach { profile ->
                            DropdownMenuItem(text = { Text(profile.name) }, onClick = { draft = profile; menu = false; error = "" })
                        }
                    }
                }
                if (!closing) SubagentConfigEditor(profile = draft, onChange = { draft = it; error = "" }, settings = stored, toolNames = parentTools)
                Text("仅对这个子任务生效；查看或切换不会更改父会话的助手。", style = SettingsStyle.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                preview?.getOrNull()?.let { effective ->
                    Text("本次实际配置", style = SettingsStyle.cardTitle)
                    SubagentEffectiveConfig(effective)
                    Text("权限和工具已经按父任务的授权范围收紧。", style = SettingsStyle.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val problem = error.ifBlank { validation ?: preview?.exceptionOrNull()?.message.orEmpty() }
                if (problem.isNotBlank()) Text(problem, style = SettingsStyle.description, color = MaterialTheme.colorScheme.error)
                if (!canCreate) Text("父任务已结束，无法再创建子任务。", style = SettingsStyle.description,
                    color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { dialog ->
            TextButton(enabled = !closing && canCreate && task.isNotBlank() && validation == null && preview?.isSuccess == true,
                onClick = confirm@{
                    if (closing) return@confirm
                    val taskSnapshot = task
                    val contextSnapshot = context
                    val nameSnapshot = name
                    val optionsSnapshot = options
                    try {
                        val child = Core.engine.createSubagent(chatId, taskSnapshot, contextSnapshot, nameSnapshot, optionsSnapshot)
                        closing = true
                        dialog.closeThen { onCreated(child.id) }
                    } catch (failure: Exception) { error = failure.message ?: "子任务创建失败" }
                }) { Text("创建") }
        },
        dismissButton = { dialog -> TextButton(enabled = !closing, onClick = {
            if (!closing) { closing = true; dialog.closeThen(onDismiss) }
        }) { Text("取消") } },
    )
}
