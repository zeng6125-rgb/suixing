package com.suixing.agent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suixing.agent.R
import com.suixing.agent.core.Core
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.SubagentModelSelection
import com.suixing.agent.data.SubagentProfile
import com.suixing.agent.data.label
import com.suixing.agent.data.resolve
import com.suixing.agent.plugins.PluginRuntime
import com.suixing.agent.tools.ToolSpec
import com.suixing.agent.ui.components.CompactOutlinedTextField
import com.suixing.agent.ui.components.ActionMenu
import com.suixing.agent.ui.components.ActionMenuItem
import com.suixing.agent.ui.components.actionIconButtonColors
import com.suixing.agent.ui.components.RowChevron
import com.suixing.agent.ui.components.SegmentedPill
import com.suixing.agent.ui.components.SettingRow
import com.suixing.agent.ui.components.SettingsStyle
import com.suixing.agent.ui.components.SettingsSwitch
import com.suixing.agent.ui.components.SuixingCard
import com.suixing.agent.ui.components.SuixingDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

/** Includes connected MCP and enabled plugin tools without doing catalog IO during composition. */
@Composable
internal fun rememberSubagentToolNames(): List<String>? {
    val revision by Core.tools.catalogRevision.collectAsStateWithLifecycle()
    val names by produceState<List<String>?>(initialValue = null, key1 = revision) {
        value = withContext(Dispatchers.IO) {
            val builtin = Core.tools.all().map { it.spec }
            val plugins = PluginRuntime.toolSpecs(PluginRuntime.enabledPlugins(), builtin.mapTo(HashSet()) { it.name })
            (builtin + plugins).map { it.name }.distinct().sorted()
        }
    }
    return names
}

@Composable
internal fun SubagentProfilesCard(settings: AppSettings) {
    val scope = rememberCoroutineScope()
    val tools = rememberSubagentToolNames()
    var editing by remember { mutableStateOf<SubagentProfile?>(null) }
    var deleting by remember { mutableStateOf<SubagentProfile?>(null) }
    var defaultMenu by remember { mutableStateOf(false) }
    var defaultAnchorWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val default = settings.subagentProfiles.firstOrNull { it.id == settings.defaultSubagentProfileId }
    SuixingCard(contentPadding = PaddingValues(vertical = SettingsStyle.CardPadding)) {
        Text("配置模板", style = SettingsStyle.cardTitle, modifier = Modifier.padding(horizontal = SettingsStyle.CardPadding))
        Text("创建子任务时可选模板，再单独调整助手、模型、工具和预算；修改模板不影响已经启动的任务。",
            style = SettingsStyle.description, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = SettingsStyle.CardPadding))
        Box(Modifier.fillMaxWidth().onSizeChanged { defaultAnchorWidth = it.width }) {
            SettingRow(title = "默认模板", subtitle = default?.name ?: "跟随父任务与子任务模型设置",
                onClick = { defaultMenu = true }, control = { RowChevron() },
                contentPadding = PaddingValues(horizontal = SettingsStyle.CardPadding))
            ActionMenu(expanded = defaultMenu, onDismissRequest = { defaultMenu = false },
                anchorWidth = with(density) { defaultAnchorWidth.toDp() }) {
                ActionMenuItem(text = { Text("跟随父任务") }, selected = default == null, onClick = {
                    defaultMenu = false
                    scope.persist { it.copy(defaultSubagentProfileId = "") }
                })
                settings.subagentProfiles.forEach { profile ->
                    ActionMenuItem(text = { Text(profile.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        selected = profile.id == default?.id, onClick = {
                        defaultMenu = false
                        scope.persist { current ->
                            if (current.subagentProfiles.any { it.id == profile.id }) current.copy(defaultSubagentProfileId = profile.id)
                            else current
                        }
                    })
                }
            }
        }
        settings.subagentProfiles.forEach { profile ->
            var menu by remember(profile.id) { mutableStateOf(false) }
            SettingRow(title = profile.name + if (profile.id == default?.id) " · 默认" else "",
                subtitle = subagentProfileSummary(profile, settings), onClick = { editing = profile },
                contentPadding = PaddingValues(horizontal = SettingsStyle.CardPadding),
                control = {
                    Box {
                        IconButton(onClick = { menu = true }, colors = actionIconButtonColors()) {
                            Icon(ImageVector.vectorResource(R.drawable.ic_more_vertical), "${profile.name}的操作", Modifier.size(18.dp))
                        }
                        ActionMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            ActionMenuItem(text = { Text("编辑") }, onClick = { menu = false; editing = profile })
                            ActionMenuItem(text = { Text("复制") }, onClick = {
                                menu = false
                                editing = profile.copy(id = newProfileId(), name = "${profile.name}副本")
                            })
                            ActionMenuItem(text = { Text("设为默认") }, onClick = {
                                menu = false
                                scope.persist { current ->
                                    if (current.subagentProfiles.any { it.id == profile.id }) current.copy(defaultSubagentProfileId = profile.id)
                                    else current
                                }
                            })
                            ActionMenuItem(text = { Text("删除") }, onClick = { menu = false; deleting = profile })
                        }
                    }
                })
        }
        if (settings.subagentProfiles.isEmpty()) Text("还没有模板，可从新建模板开始。", style = SettingsStyle.description,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = SettingsStyle.CardPadding).padding(top = SettingsStyle.FieldGap))
        TextButton(onClick = { editing = SubagentProfile(id = newProfileId(), name = "新模板") },
            modifier = Modifier.padding(horizontal = SettingsStyle.CardPadding)) { Text("新建模板") }
    }
    editing?.let { original ->
        var draft by remember(original.id) { mutableStateOf(original) }
        var saving by remember(original.id) { mutableStateOf(false) }
        var error by remember(original.id) { mutableStateOf("") }
        SuixingDialog(
            onDismissRequest = { if (!saving) editing = null },
            properties = DialogProperties(dismissOnBackPress = !saving, dismissOnClickOutside = !saving),
            title = { Text(if (settings.subagentProfiles.any { it.id == original.id }) "编辑子任务模板" else "新建子任务模板") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(SettingsStyle.FieldGap)) {
                    CompactOutlinedTextField(value = draft.name, onValueChange = { draft = draft.copy(name = it) },
                        label = { Text("模板名称") }, enabled = !saving, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (tools == null) Text("正在读取工具目录…", style = SettingsStyle.description)
                    else SubagentConfigEditor(draft, { if (!saving) draft = it }, settings, tools)
                    if (error.isNotBlank()) Text(error, style = SettingsStyle.description, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { dialog ->
                TextButton(enabled = !saving && tools != null, onClick = {
                    if (!saving) {
                        val validation = if (draft.name.isBlank()) "请输入模板名称。" else subagentProfileValidation(draft, settings, tools.orEmpty())
                        if (validation != null) error = validation
                        else {
                            val saved = draft.copy(name = draft.name.trim())
                            saving = true
                            scope.launch {
                                try {
                                    Core.settings.update { current ->
                                        val entries = current.subagentProfiles
                                        current.copy(subagentProfiles = if (entries.any { it.id == saved.id }) {
                                            entries.map { if (it.id == saved.id) saved else it }
                                        } else entries + saved)
                                    }
                                    dialog.closeThen { editing = null }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { error = failure.message ?: "模板保存失败。"; saving = false }
                            }
                        }
                    }
                }) { Text(if (saving) "保存中…" else "保存") }
            },
            dismissButton = { dialog -> TextButton(enabled = !saving, onClick = { dialog.closeThen { editing = null } }) { Text("取消") } },
        )
    }
    deleting?.let { profile ->
        SuixingDialog(onDismissRequest = { deleting = null }, title = { Text("删除模板？") },
            text = { Text("删除“${profile.name}”不会改变已创建的子任务或它们的记录。") },
            confirmButton = { dialog -> TextButton(onClick = { dialog.closeThen {
                deleting = null
                scope.persist { current -> current.copy(
                    subagentProfiles = current.subagentProfiles.filterNot { it.id == profile.id },
                    defaultSubagentProfileId = current.defaultSubagentProfileId.takeUnless { it == profile.id }.orEmpty(),
                ) }
            } }) { Text("删除") } },
            dismissButton = { dialog -> TextButton(onClick = { dialog.closeThen { deleting = null } }) { Text("取消") } },
        )
    }
}

private fun newProfileId(): String = "profile_${UUID.randomUUID()}"

internal fun subagentProfileSummary(profile: SubagentProfile, settings: AppSettings): String {
    val assistant = settings.assistants.firstOrNull { it.id == profile.assistantId }?.label ?: "跟随父助手"
    val model = profile.model?.model ?: "继承模型"
    val tools = profile.allowedTools?.let { if (it.isEmpty()) "仅文字" else "${it.size}个工具" } ?: "继承工具"
    return "$assistant · $model · ${profile.approvalMode?.label ?: "继承审批"} · $tools"
}

internal fun subagentProfileValidation(profile: SubagentProfile, settings: AppSettings, toolNames: List<String>): String? = when {
    profile.assistantId.isNotBlank() && settings.assistants.none { it.id == profile.assistantId } -> "指定助手已不可用，请重新选择。"
    profile.model != null && profile.model.resolve(settings.providers) == null -> "请从已启用的供应商中选择有效模型。"
    profile.maxToolRounds != null && profile.maxToolRounds < 0 -> "轮数不能小于0。"
    profile.timeoutSeconds !in 10..600 -> "时长请输入10到600秒。"
    profile.allowedActions.any { (name, _) -> profile.allowedTools != null && name !in profile.allowedTools } -> "动作限制必须属于已选择的工具。"
    else -> null
}

/** Shared by template editing and per-task creation; callers own scrolling and confirmation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubagentConfigEditor(
    profile: SubagentProfile,
    onChange: (SubagentProfile) -> Unit,
    settings: AppSettings,
    toolNames: List<String>,
) {
    val providers = remember(settings.providers) {
        settings.providers.filter { it.baseUrl.isNotBlank() && it.enabled.any(String::isNotBlank) }
    }
    var providerMenu by remember(profile.id) { mutableStateOf(false) }
    var modelMenu by remember(profile.id, profile.model?.providerId) { mutableStateOf(false) }
    var toolsExpanded by remember(profile.id) { mutableStateOf(false) }
    var actionsExpanded by remember(profile.id) { mutableStateOf(profile.allowedActions.isNotEmpty()) }
    var query by remember(profile.id) { mutableStateOf("") }
    var timeoutText by remember(profile.id) { mutableStateOf(profile.timeoutSeconds.toString()) }
    var roundText by remember(profile.id, profile.maxToolRounds == null, profile.maxToolRounds == 0) {
        mutableStateOf(profile.maxToolRounds?.toString().orEmpty())
    }
    LaunchedEffect(profile.timeoutSeconds) {
        if ((timeoutText.toIntOrNull() ?: 0) != profile.timeoutSeconds) timeoutText = profile.timeoutSeconds.toString()
    }
    LaunchedEffect(profile.maxToolRounds) {
        if (profile.maxToolRounds != null && (roundText.toIntOrNull() ?: -1) != profile.maxToolRounds) {
            roundText = profile.maxToolRounds.toString()
        }
    }
    val toolActions by produceState<Map<String, List<String>>>(initialValue = emptyMap(), key1 = toolNames) {
        value = withContext(Dispatchers.IO) {
            val builtin = Core.tools.all().map { it.spec }
            val plugins = PluginRuntime.toolSpecs(PluginRuntime.enabledPlugins(), builtin.mapTo(HashSet()) { it.name })
            val selected = toolNames.toSet()
            (builtin + plugins).filter { it.name in selected }.mapNotNull { spec ->
                spec.actionValues().takeIf { it.isNotEmpty() }?.let { spec.name to it }
            }.toMap()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(SettingsStyle.FieldGap)) {
        ProfileChoiceRow("助手", profile.assistantId, settings.assistants.firstOrNull { it.id == profile.assistantId }?.label ?: "跟随父助手",
            listOf("" to "跟随父助手") + settings.assistants.map { it.id to it.label }) { id -> onChange(profile.copy(assistantId = id)) }
        Text("只改变子任务使用的助手，不切换父会话助手。", style = SettingsStyle.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("模型", style = SettingsStyle.metadata)
        SegmentedPill(options = listOf("继承", "指定模型"), selectedIndex = if (profile.model == null) 0 else 1,
            onSelect = { onChange(profile.copy(model = if (it == 0) null else profile.model ?: SubagentModelSelection("", ""))) })
        profile.model?.let { selection ->
            val provider = providers.firstOrNull { it.id == selection.providerId }
            ProviderDropdown(providers, provider, true, providerMenu, { providerMenu = it },
                onSelect = { onChange(profile.copy(model = SubagentModelSelection(it.id, ""))) })
            EnabledModelDropdown(provider, selection.model, "选择模型", "仅用于此子任务配置", true,
                modelMenu, { modelMenu = it }, onPick = { id ->
                    provider?.let { onChange(profile.copy(model = SubagentModelSelection(it.id, id))) }
                })
            if (providers.isEmpty()) Text("请先在模型设置中启用模型。", style = SettingsStyle.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val modeOptions = listOf(null, ApprovalMode.ASK, ApprovalMode.AUTO, ApprovalMode.DENY)
        Text("工具审批", style = SettingsStyle.metadata)
        SegmentedPill(options = listOf("继承", "询问", "自动", "禁止"), selectedIndex = modeOptions.indexOf(profile.approvalMode),
            onSelect = { onChange(profile.copy(approvalMode = modeOptions[it])) })
        Text("实际权限取父任务和本配置的交集，审批只能更严格。", style = SettingsStyle.description,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("工具范围", style = SettingsStyle.metadata)
        SegmentedPill(options = listOf("继承父工具", "自选工具"), selectedIndex = if (profile.allowedTools == null) 0 else 1,
            onSelect = { index ->
                val chosen = if (index == 0) null else profile.allowedTools ?: emptyList()
                onChange(profile.copy(allowedTools = chosen,
                    allowedActions = if (chosen == null) profile.allowedActions else profile.allowedActions.filterKeys { it in chosen }))
            })
        if (profile.allowedTools != null) {
            TextButton(onClick = { toolsExpanded = !toolsExpanded }) {
                Text(if (toolsExpanded) "收起工具列表" else "选择工具 · ${profile.allowedTools.size}个")
            }
            if (profile.allowedTools.isEmpty()) Text("未选择工具时，子任务只生成文字。", style = SettingsStyle.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (toolsExpanded) {
                CompactOutlinedTextField(query, { query = it }, label = { Text("搜索工具名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val options = remember(toolNames, profile.allowedTools, query) {
                    (toolNames + profile.allowedTools).distinct().filter { it.contains(query.trim(), true) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(SettingsStyle.FieldGap)) {
                    TextButton(onClick = { onChange(profile.copy(allowedTools = (profile.allowedTools + options).distinct())) }) { Text("全选当前列表") }
                    TextButton(onClick = { onChange(profile.copy(allowedTools = emptyList(), allowedActions = emptyMap())) }) { Text("清空选择") }
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 230.dp)) {
                    items(options, key = { it }, contentType = { "tool-choice" }) { name ->
                        val checked = name in profile.allowedTools
                        fun setSelected(selected: Boolean) {
                            val chosen = if (selected) (profile.allowedTools + name).distinct() else profile.allowedTools - name
                            onChange(profile.copy(allowedTools = chosen,
                                allowedActions = profile.allowedActions.filterKeys { it in chosen }))
                        }
                        SettingRow(title = name, titleMaxLines = 2,
                            subtitle = if (name in toolNames) Core.tools.groupOf(name).ifBlank { if (name.startsWith("mcp_")) "MCP" else "插件或扩展" }
                                else "当前未连接或不可用",
                            onClick = { setSelected(!checked) }, control = { Checkbox(checked, { setSelected(it) }) })
                    }
                }
            }
        }
        val actionOptions = remember(toolNames, toolActions, profile.allowedTools) {
            toolNames.filter { profile.allowedTools == null || it in profile.allowedTools }.mapNotNull { name ->
                toolActions[name]?.let { name to it }
            }
        }
        if (actionOptions.isNotEmpty()) TextButton(onClick = { actionsExpanded = !actionsExpanded }) {
            Text(if (actionsExpanded) "收起动作限制" else "动作限制 · ${profile.allowedActions.size}个工具")
        }
        if (actionsExpanded) actionOptions.forEach { (name, actions) ->
            val restricted = name in profile.allowedActions
            SettingRow(title = "$name 的动作", subtitle = if (restricted) "仅允许所选动作" else "允许此工具的所有动作",
                control = { SettingsSwitch(restricted, { selected ->
                    onChange(profile.copy(allowedActions = if (selected) profile.allowedActions + (name to emptyList()) else profile.allowedActions - name))
                }) })
            if (restricted) FlowRow(horizontalArrangement = Arrangement.spacedBy(SettingsStyle.FieldGap)) {
                actions.forEach { action ->
                    val checked = action in profile.allowedActions[name].orEmpty()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked, { selected ->
                            val values = if (selected) (profile.allowedActions[name].orEmpty() + action).distinct() else profile.allowedActions[name].orEmpty() - action
                            onChange(profile.copy(allowedActions = profile.allowedActions + (name to values)))
                        })
                        Text(actionLabel(name, action), style = SettingsStyle.description)
                    }
                }
            }
        }
        Text("轮数预算", style = SettingsStyle.metadata)
        val roundMode = when (profile.maxToolRounds) { null -> 0; 0 -> 1; else -> 2 }
        SegmentedPill(options = listOf("继承", "不限制", "指定"), selectedIndex = roundMode, onSelect = { index ->
            onChange(profile.copy(maxToolRounds = when (index) { 0 -> null; 1 -> 0; else -> profile.maxToolRounds?.takeIf { it > 0 } ?: 20 }))
        })
        if (roundMode == 2) CompactOutlinedTextField(roundText, { next ->
            if (next.all(Char::isDigit)) { roundText = next; onChange(profile.copy(maxToolRounds = next.toIntOrNull() ?: -1)) }
        }, label = { Text("最多工具轮数") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        CompactOutlinedTextField(timeoutText, { next ->
            if (next.all(Char::isDigit)) { timeoutText = next; onChange(profile.copy(timeoutSeconds = next.toIntOrNull() ?: 0)) }
        }, label = { Text("最长运行时间（10–600秒）") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        CompactOutlinedTextField(profile.extraInstructions, { onChange(profile.copy(extraInstructions = it)) },
            label = { Text("补充要求（可选）") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
    }
}

private fun ToolSpec.actionValues(): List<String> {
    val properties = parameters["properties"] as? JsonObject ?: return emptyList()
    val action = properties["action"] as? JsonObject ?: return emptyList()
    return (action["enum"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
}

private fun actionLabel(tool: String, action: String): String = if (tool == "ui_virtual_screen") when (action) {
    "status" -> "查询状态"; "open" -> "开启"; "preview" -> "预览"; "close" -> "关闭"; "screenshot" -> "截图"; "open_app" -> "打开应用"; else -> action
} else action

@Composable
private fun ProfileChoiceRow(title: String, selectedId: String, selected: String, choices: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var menu by remember(title) { mutableStateOf(false) }
    var anchorWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    Box(Modifier.fillMaxWidth().onSizeChanged { anchorWidth = it.width }) {
        SettingRow(title = title, subtitle = selected, onClick = { menu = true }, control = { RowChevron() })
        ActionMenu(expanded = menu, onDismissRequest = { menu = false },
            anchorWidth = with(density) { anchorWidth.toDp() }, modifier = Modifier.heightIn(max = 300.dp)) {
            choices.forEach { (id, label) ->
                ActionMenuItem(text = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) }, selected = selectedId == id,
                    onClick = { menu = false; onPick(id) })
            }
        }
    }
}
