package com.suixing.agent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suixing.agent.capability.CapabilityState
import com.suixing.agent.core.Core
import com.suixing.agent.mcp.MCP_MAX_RECONNECT_ATTEMPTS
import com.suixing.agent.mcp.McpManager
import com.suixing.agent.mcp.McpServerState
import com.suixing.agent.mcp.McpStatus
import com.suixing.agent.skills.Skill
import com.suixing.agent.tools.TIER_ACCESSIBILITY
import com.suixing.agent.tools.TIER_NONE
import com.suixing.agent.tools.TIER_ROOT
import com.suixing.agent.tools.TIER_SHELL
import com.suixing.agent.ui.components.EmptyState
import com.suixing.agent.ui.components.PageTopBar
import com.suixing.agent.ui.components.SegmentedPill
import com.suixing.agent.ui.components.StatusPill
import com.suixing.agent.ui.components.SuixingCard
import com.suixing.agent.ui.theme.Tone

/** The tab strip's three faces, in the order the pills are drawn. */
private val TABS = listOf("工具", "Skill", "MCP")

private const val MCP_GROUP = "MCP（外部）"
private const val OTHER_GROUP = "其它"

/**
 * The ToolGroup each registered tool name came from, read off the bundles in
 * `com.suixing.agent.tools`.
 *
 * The registry does not carry that fact: [com.suixing.agent.tools.ToolRegistry.add] wraps every
 * spec in an anonymous [com.suixing.agent.tools.Tool] that keeps only the spec and the group's
 * tier (tools/Tool.kt:153-162), so "which bundle registered this" cannot be asked at runtime.
 * The rows below are still read live from [Core.tools] — this table only decides which bucket a
 * name falls into, and a name that is not listed lands in [OTHER_GROUP] instead of vanishing.
 *
 * One bundle per entry, in the order the groups should be shown:
 * FileTools, ShellTools, UiTools, SystemTools (which is where `memory_*` is registered,
 * SystemTools.kt:77-88), JobTools, TodoTools, ScheduleTools, SkillTools, WebTools, VoiceTools.
 */
private val TOOL_GROUPS: List<Pair<String, List<String>>> = listOf(
    "文件" to listOf(
        "read_file", "write_file", "list_dir", "make_dir", "stat_path",
        "move_path", "delete_path", "find_files", "search_in_files",
    ),
    "Shell" to listOf("run_shell", "app_action"),
    "界面操作" to listOf(
        "ui_dump", "ui_screenshot", "ui_tap", "ui_long_press", "ui_swipe", "ui_click_text",
        "ui_type", "ui_type_into", "ui_key", "ui_current_app", "ui_wait",
    ),
    "系统" to listOf(
        "device_info", "list_apps", "open_app", "open_url", "clipboard_read", "clipboard_write",
        "memory_write", "memory_read",
    ),
    "后台任务" to listOf("job_start", "job_list", "job_output", "job_kill"),
    "任务清单" to listOf("todo_write", "todo_read"),
    "定时提醒" to listOf("schedule_create", "schedule_list", "schedule_update", "schedule_delete"),
    "技能" to listOf("skill_list", "skill_read", "skill_save"),
    "网络" to listOf("web_fetch", "web_download", "web_search"),
    "语音" to listOf("speak", "stop_speaking"),
)

private val GROUP_BY_NAME: Map<String, String> = buildMap {
    TOOL_GROUPS.forEach { (group, names) -> names.forEach { put(it, group) } }
}

private fun groupOf(name: String): String = when {
    name.startsWith(McpManager.MCP_PREFIX) -> MCP_GROUP
    else -> GROUP_BY_NAME[name] ?: OTHER_GROUP
}

/** Keeps the drawn order the same as [TOOL_GROUPS]; MCP and 其它 come last. */
private fun groupRank(group: String): Int = when (group) {
    MCP_GROUP -> TOOL_GROUPS.size
    OTHER_GROUP -> TOOL_GROUPS.size + 1
    else -> TOOL_GROUPS.indexOfFirst { it.first == group }.coerceAtLeast(0)
}

/**
 * 工具 / Skill / MCP in one place: what the agent can actually reach right now.
 *
 * Read-only by design. There is no global "disable this tool" switch to reuse — the only
 * disable list in the app is per assistant ([com.suixing.agent.data.Assistant.disabledTools],
 * applied at ChatViewModel.kt:1031), and inventing a second, global one here would give two
 * sources of truth for the same question. Editing MCP servers and importing skills stay on
 * their own screens, which is where the writing already lives.
 */
@Composable
fun ToolsScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        PageTopBar(title = "工具", subtitle = "工具、Skill 与 MCP 的全景", onBack = onBack)
        Column(Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))
            SegmentedPill(options = TABS, selectedIndex = tab, onSelect = { tab = it })
        }
        Spacer(Modifier.height(4.dp))
        when (tab) {
            0 -> ToolsTab()
            1 -> SkillsTab()
            else -> McpTab(onOpen)
        }
    }
}

@Composable
private fun ToolsTab() {
    // MCP tools appear and disappear with their server, so the snapshot is re-taken whenever
    // the MCP state changes; the builtin half never changes after Core.init.
    val servers by Core.mcp.servers.collectAsState()
    val capabilities by Core.capabilities.state.collectAsState()
    val tools = remember(servers) { Core.tools.all() }
    var query by rememberSaveable { mutableStateOf("") }

    val filtered = remember(tools, query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            tools
        } else {
            tools.filter { it.spec.name.contains(needle, ignoreCase = true) }
        }
    }
    val grouped = remember(filtered) {
        filtered.groupBy { groupOf(it.spec.name) }
            .toList()
            .sortedBy { (group, _) -> groupRank(group) }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("搜索工具名") },
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "已启用 ${tools.size} / 共 ${tools.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (query.isNotBlank()) {
                    Text(
                        text = "命中 ${filtered.size} 个",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = "只读总览：全局没有禁用开关，要按助手关掉某些工具，去助手的编辑页。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
        }
        if (grouped.isEmpty()) {
            EmptyState(title = "没有匹配的工具", subtitle = "换个名字再搜一次。")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                grouped.forEach { (group, groupTools) ->
                    item(key = "group-$group") { GroupHeader(group, groupTools.size) }
                    items(groupTools, key = { it.spec.name }) { tool ->
                        ToolRow(
                            name = tool.spec.name,
                            description = tool.spec.description,
                            requirement = requirementOf(tool.tier, capabilities),
                        )
                    }
                }
            }
        }
    }
}

/** The privilege a tool needs, with whether it is granted right now; null when it needs none. */
private fun requirementOf(tier: String, capabilities: CapabilityState): Pair<String, Boolean>? =
    when (tier) {
        TIER_ACCESSIBILITY -> "需要无障碍" to capabilities.accessibility
        TIER_SHELL -> "需要 Shizuku" to capabilities.shellReady
        TIER_ROOT -> "需要 root" to capabilities.rootReady
        TIER_NONE -> null
        else -> tier to true
    }

@Composable
private fun GroupHeader(name: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(
            text = "$count 个",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToolRow(name: String, description: String, requirement: Pair<String, Boolean>?) {
    SuixingCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (requirement != null) {
                Spacer(Modifier.width(10.dp))
                StatusPill(
                    text = requirement.first,
                    tone = if (requirement.second) Tone.OK else Tone.WARN,
                )
            }
        }
        if (description.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SkillsTab() {
    var skills by remember { mutableStateOf<List<Skill>?>(null) }
    LaunchedEffect(Unit) { skills = Core.skills.list() }
    val loaded = skills

    when {
        loaded == null -> Hint("正在读取技能目录…")
        loaded.isEmpty() -> EmptyState(
            title = "还没有技能",
            subtitle = "把带 SKILL.md 的文件夹放进 ${Core.skills.root.absolutePath}，" +
                "或从「扩展 → 技能」导入。",
        )

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(loaded, key = { it.id }) { skill -> SkillRow(skill) }
        }
    }
}

@Composable
private fun SkillRow(skill: Skill) {
    SuixingCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = skill.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!skill.enabled) {
                Spacer(Modifier.width(10.dp))
                StatusPill(text = "已停用", tone = Tone.OFF)
            }
        }
        if (skill.description.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = skill.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = skill.path,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun McpTab(onOpen: (String) -> Unit) {
    val servers by Core.mcp.servers.collectAsState()
    Column(Modifier.fillMaxSize()) {
        if (servers.isEmpty()) {
            EmptyState(
                title = "还没有 MCP 服务器",
                subtitle = "在「扩展 → MCP」里添加一个，连上后它提供的工具会出现在「工具」标签页。",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                // weight, not fillMaxSize: the list shares the column with the footer row below.
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(servers, key = { it.config.name }) { state -> McpRow(state) }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "本页只读，连接与编辑仍在扩展页",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            TextButton(onClick = { onOpen("extension?tab=0") }) { Text("去管理") }
        }
    }
}

@Composable
private fun McpRow(state: McpServerState) {
    val (label, tone) = statusOf(state)
    SuixingCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.config.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (state.config.enabled) "已启用" else "已停用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(10.dp))
            StatusPill(text = label, tone = tone)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "工具 ${state.toolCount} 个" +
                if (state.serverName.isBlank()) "" else " · ${state.serverName}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.status == McpStatus.FAILED && !state.error.isNullOrBlank()) {
            Text(
                text = state.error.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Same wording as the extension page's list (ExtensionScreen.kt:512-517), read-only here. */
private fun statusOf(state: McpServerState): Pair<String, Tone> = when (state.status) {
    McpStatus.READY -> "已连接" to Tone.OK
    McpStatus.CONNECTING -> "连接中" to Tone.INFO
    McpStatus.RECONNECTING -> "重连中 ${state.attempt}/$MCP_MAX_RECONNECT_ATTEMPTS" to Tone.INFO
    McpStatus.FAILED -> "连接失败" to Tone.WARN
    McpStatus.DISCONNECTED -> (if (state.config.enabled) "未连接" else "已停用") to Tone.OFF
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
    )
}
