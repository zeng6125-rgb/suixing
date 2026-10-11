package com.suixing.agent.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suixing.agent.R
import com.suixing.agent.capability.CapabilityState
import com.suixing.agent.core.Core
import com.suixing.agent.data.McpServerConfig
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
import com.suixing.agent.ui.components.SoftIcon
import com.suixing.agent.ui.components.StatusPill
import com.suixing.agent.ui.components.SuixingCard
import com.suixing.agent.ui.extension.SkillViewDialog
import com.suixing.agent.ui.extension.SkillsSection
import com.suixing.agent.ui.theme.LocalDarkTheme
import com.suixing.agent.ui.theme.MonoStyle
import com.suixing.agent.ui.theme.Tone
import com.suixing.agent.ui.theme.color
import kotlinx.coroutines.launch

/** The tab strip's three faces, in the order the pills are drawn. */
/**
 * 工具 / 技能 / MCP / 插件，按「现在就能用的能力」排到「以后可装卸的扩展」。
 *
 * 插件排最后：它是将来承载其它能力的壳，形态还没定，把一个空标签放在第一眼的位置只会让
 * 页面看起来是坏的。标签名统一用中文，MCP 是专有名词所以保留（原来写作 "Skill"）。
 */
private val TABS = listOf("工具", "技能", "MCP", "插件")

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
 * 工具 / Skill / MCP in one place: what the agent can actually reach right now — and the
 * one place where the two runtime extensions are managed.
 *
 * 工具 一栏仍然是只读总览：全局没有「禁用某个工具」的开关，唯一的禁用名单是按助手存的
 * ([com.suixing.agent.data.Assistant.disabledTools]，在 ChatViewModel.kt:1031 生效)，
 * 在这里再发明一个全局名单会让同一个问题有两个出处。Skill 与 MCP 两栏则是可写的：
 * 导入、启停、增删改都收在这一页，扩展页不再提供第二份入口。
 */
@Composable
fun ToolsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(0) }

    var mcpEditorOpen by remember { mutableStateOf(false) }
    var mcpEditing by remember { mutableStateOf<McpServerConfig?>(null) }
    var mcpDeleting by remember { mutableStateOf<McpServerConfig?>(null) }

    var skillViewing by remember { mutableStateOf<Skill?>(null) }
    var skillDeleting by remember { mutableStateOf<Skill?>(null) }
    var skillImportMenu by remember { mutableStateOf(false) }
    var skillUrlImport by remember { mutableStateOf(false) }
    var skillRefresh by remember { mutableIntStateOf(0) }

    fun notify(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val count = runCatching { Core.skills.importTree(uri) }.getOrElse { error ->
                snackbar.showSnackbar("导入失败：${error.message ?: error::class.java.simpleName}")
                return@launch
            }
            skillRefresh++
            snackbar.showSnackbar(
                if (count == 0) {
                    "没找到技能：需要含 SKILL.md 的文件夹，或一个 .md 文件"
                } else {
                    // Says the switch is off before the user goes looking for the skill in the
                    // model's catalogue: they will not find it there until they open it.
                    "已导入 $count 个技能，默认关闭，打开开关后生效"
                },
            )
        }
    }

    // A single SKILL.md shared straight out of a chat or a download has no folder to
    // pick, so it gets its own launcher; it lands as a single-file skill.
    val fileImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val skill = runCatching { Core.skills.importFile(uri) }.getOrElse { error ->
                snackbar.showSnackbar("导入失败：${error.message ?: error::class.java.simpleName}")
                return@launch
            }
            if (skill == null) {
                snackbar.showSnackbar("只能导入 .md 文件")
                return@launch
            }
            skillRefresh++
            snackbar.showSnackbar("已导入技能「${skill.name}」，默认关闭，打开开关后生效")
        }
    }

    // 根节点必须自己铺不透明底：转场里进页是**不淡入**的（见 SuixingApp 顶部那段），这一页
    // 若透出外壳的底，进页的那一帧就会看到上一页的残影。
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            PageTopBar(
                title = "工具",
                subtitle = when (tab) {
                    0 -> "模型能调用的全部工具"
                    1 -> "技能给模型加知识"
                    2 -> "MCP 给模型加工具"
                    else -> "插件：随行的一切能力都以插件形式提供"
                },
                onBack = onBack,
                actions = {
                    // 工具页与插件页没有可刷新的东西：工具那栏跟着 MCP 状态与能力状态自己变，
                    // 插件页现在只有一个说明（还没有可装卸的东西）。
                    if (tab == 1 || tab == 2) {
                        IconButton(
                            onClick = {
                                if (tab == 1) {
                                    skillRefresh++
                                } else {
                                    scope.launch {
                                        Core.mcp.load()
                                        notify("已重新加载 MCP 配置")
                                    }
                                }
                            },
                        ) {
                            Icon(ImageVector.vectorResource(R.drawable.ic_rotate_cw), contentDescription = "刷新", modifier = Modifier.size(20.dp))
                        }
                        Box {
                            IconButton(
                                onClick = {
                                    if (tab == 1) {
                                        skillImportMenu = true
                                    } else {
                                        mcpEditing = null
                                        mcpEditorOpen = true
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = if (tab == 1) {
                                        ImageVector.vectorResource(R.drawable.ic_download)
                                    } else {
                                        ImageVector.vectorResource(R.drawable.ic_plus)
                                    },
                                    contentDescription = if (tab == 1) "导入技能" else "添加服务器",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            // A folder and a lone SKILL.md need different pickers, so the
                            // import button offers the choice instead of guessing.
                            DropdownMenu(
                                expanded = skillImportMenu,
                                onDismissRequest = { skillImportMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("选文件夹（批量）") },
                                    leadingIcon = {
                                        Icon(ImageVector.vectorResource(R.drawable.ic_folder_open), null, modifier = Modifier.size(20.dp))
                                    },
                                    onClick = {
                                        skillImportMenu = false
                                        importer.launch(null)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("选 .md 文件") },
                                    leadingIcon = {
                                        Icon(ImageVector.vectorResource(R.drawable.ic_file_text), null, modifier = Modifier.size(20.dp))
                                    },
                                    onClick = {
                                        skillImportMenu = false
                                        fileImporter.launch(arrayOf("text/markdown", "text/plain", "*/*"))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("从链接导入") },
                                    leadingIcon = {
                                        Icon(ImageVector.vectorResource(R.drawable.ic_download), null, modifier = Modifier.size(20.dp))
                                    },
                                    onClick = {
                                        skillImportMenu = false
                                        skillUrlImport = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
            // 这里不再加 12dp：PageTopBar 自己以 Spacer(12.dp) 收尾（Common.kt:532），
            // 本页把 PageTopBar 放在 Column 里，那一份是生效的，再加就是 24dp。
            Column(Modifier.padding(horizontal = 16.dp)) {
                SegmentedPill(options = TABS, selectedIndex = tab, onSelect = { tab = it })
            }
            Spacer(Modifier.height(4.dp))
            when (tab) {
                0 -> ToolsTab()
                1 -> SkillsSection(
                    refresh = skillRefresh,
                    onView = { skillViewing = it },
                    onDelete = { skillDeleting = it },
                    onToggle = { skill, enabled ->
                        scope.launch {
                            Core.skills.setEnabled(skill.id, enabled)
                            skillRefresh++
                            notify(if (enabled) "已启用 ${skill.name}" else "已停用 ${skill.name}")
                        }
                    },
                )

                2 -> McpSection(
                    onEdit = {
                        mcpEditing = it
                        mcpEditorOpen = true
                    },
                    onDelete = { mcpDeleting = it },
                    onNotify = ::notify,
                )

                else -> PluginsTab(onNotify = ::notify)
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }

    if (mcpEditorOpen) {
        McpEditorDialog(
            initial = mcpEditing,
            onDismiss = { mcpEditorOpen = false },
            onSave = { config ->
                mcpEditorOpen = false
                scope.launch {
                    // 落盘失败（磁盘满/权限）以前会把协程炸掉，而对话框在 launch 之前就关了：
                    // 用户既看不到「已保存」也看不到失败原因，只能对着没生效的列表猜。
                    runCatching {
                        Core.mcp.upsert(config)
                        if (config.enabled) Core.mcp.connect(config.name)
                    }
                        .onSuccess { notify("已保存 ${config.name}") }
                        .onFailure { notify("保存失败：${it.message ?: "未知错误"}") }
                }
            },
        )
    }

    mcpDeleting?.let { target ->
        AlertDialog(
            onDismissRequest = { mcpDeleting = null },
            title = { Text("删除服务器") },
            text = { Text("确定要删除「${target.name}」吗？它的连接会被断开。") },
            confirmButton = {
                TextButton(onClick = {
                    mcpDeleting = null
                    scope.launch {
                        runCatching { Core.mcp.remove(target.name) }
                            .onSuccess { notify("已删除 ${target.name}") }
                            .onFailure { notify("删除失败：${it.message ?: "未知错误"}") }
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { mcpDeleting = null }) { Text("取消") }
            },
        )
    }

    skillViewing?.let { skill ->
        SkillViewDialog(skill = skill, onDismiss = { skillViewing = null })
    }

    skillDeleting?.let { target ->
        AlertDialog(
            onDismissRequest = { skillDeleting = null },
            title = { Text("删除技能") },
            text = { Text("确定要删除「${target.name}」吗？删除后模型就读不到它了。") },
            confirmButton = {
                TextButton(onClick = {
                    skillDeleting = null
                    scope.launch {
                        // delete 写 state 文件时会抛 IOException：不接住就是协程炸掉 + 没有提示，
                        // 用户以为删掉了（列表也刷新了），其实磁盘上还在。
                        runCatching { Core.skills.delete(target.id) }
                            .onSuccess {
                                skillRefresh++
                                notify("已删除 ${target.name}")
                            }
                            .onFailure { notify("删除失败：${it.message ?: "未知错误"}") }
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { skillDeleting = null }) { Text("取消") }
            },
        )
    }

    if (skillUrlImport) {
        SkillUrlImportDialog(
            onDismiss = { skillUrlImport = false },
            onImport = { url ->
                skillUrlImport = false
                scope.launch {
                    val skill = runCatching { Core.skills.importUrl(url) }.getOrElse { error ->
                        snackbar.showSnackbar("导入失败：${error.message ?: error::class.java.simpleName}")
                        return@launch
                    }
                    skillRefresh++
                    snackbar.showSnackbar("已下载技能「${skill.name}」，默认关闭，打开开关后生效")
                }
            },
        )
    }
}

/**
 * 「从链接导入」: one URL in, one skill out.
 *
 * The dialog checks only the scheme, which is the same check the import itself makes, so a
 * mistyped address is answered here instead of after a download. Everything else — the
 * ceilings, the folder name, the switched-off default — belongs to `SkillStore.importUrl`.
 */
@Composable
private fun SkillUrlImportDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    val usable = url.trim().startsWith("http://") || url.trim().startsWith("https://")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从链接导入技能") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "填一个 SKILL.md 的地址（http 或 https）。导入后技能默认关闭，" +
                        "看过内容再打开开关，它才会进入模型的技能目录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    textStyle = MaterialTheme.typography.bodyMedium,
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("技能文件地址") },
                    placeholder = { Text("https://example.com/SKILL.md") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                )
                if (url.isNotBlank() && !usable) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "链接必须以 http:// 或 https:// 开头",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(url.trim()) }, enabled = usable) { Text("导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
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
                textStyle = MaterialTheme.typography.bodyMedium,
                value = query,
                onValueChange = { query = it },
                // 不要给这个框写死高度：OutlinedTextField 的内容（浮动标签 + 文字行盒 + 上下留白）
                // 有自己的最小高度，把外层约束压到 44dp 之后放不下，字的下缘（带降部的汉字/字母）
                // 就被裁掉 —— 0.8.76 收紧密度时压的就是这一处，用户看到的截断即它。
                // 密度靠下面那些行内边距与间距拿，不靠裁这一个唯一有输入文字的控件。
                // 字号与最小高度按设置页的统一规格：bodyMedium(14sp) + heightIn(min = 52.dp)。
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                singleLine = true,
                label = { Text("搜索工具名") },
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // 内置工具是全局的、不可修改的，所以「已启用 X / 共 X」这种计数没有意义
                    // （两个数永远相等）：这里只报一共有多少个。
                    text = "共 ${tools.size} 个工具",
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
                text = "全局工具默认不可修改：这里只能看名称、用途和它需要的权限。" +
                    "要按助手关掉某些工具，去那个助手的编辑页。",
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
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 72.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
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

/**
 * 「插件」 tab: the idea, and now the packages themselves.
 *
 * The user's framing is DSH's: everything the app can do is something you can put in and take out.
 * The intro card explains that; [PluginsSection] below it is the actual store — installed packages,
 * their level and risk, and a white-box view of what each would do. M1 installs and inspects only:
 * nothing here reaches the tool table or the prompt yet, which is why the detail view shows raw
 * templates rather than a friendly summary.
 */
@Composable
private fun PluginsTab(onNotify: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { PluginsIntroCard() }
        // The store draws its own empty state (the same words this tab used to show), so the list and
        // the "nothing here yet" case stay in one place instead of being decided twice.
        item { PluginsSection(onNotify = onNotify) }
    }
}

@Composable
private fun PluginsIntroCard() {
    SuixingCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SoftIcon(ImageVector.vectorResource(R.drawable.ic_cpu), size = 34.dp, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(12.dp))
            Text("什么是插件", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "插件是可以装卸的扩展：随行的每一项能力，将来都以插件的形式提供——" +
                "装一个就多一份本事，卸掉就干净地消失，不用等应用更新。",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "现在还没有可安装的插件，这一页先放着，等形态定下来再填。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The privilege a tool needs, with whether it is granted right now; null when it needs none.
 *
 * [TIER_ROOT] is never emitted by the registry: every group in `com.suixing.agent.tools` takes the
 * [TIER_NONE] default except ShellTools ([TIER_SHELL]) and UiTools ([TIER_ACCESSIBILITY]), and
 * root is a *runtime* escalation inside those two (ShellTools.kt:128, UiTools.kt:219), not a tier
 * a tool is registered under. The arm is kept because [TIER_ROOT] is a real registry value.
 */
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
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
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
    SuixingCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp)) {
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
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
@Composable
private fun McpSection(
    onEdit: (McpServerConfig) -> Unit,
    onDelete: (McpServerConfig) -> Unit,
    onNotify: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val servers by Core.mcp.servers.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { McpIntroCard() }

        if (servers.isEmpty()) {
            item {
                Text(
                    text = "还没有配置服务器。点右上角「+」，填一条启动命令（stdio）或一个地址（http）即可。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(servers, key = { it.config.name }) { state ->
            ServerCard(
                state = state,
                onToggleEnabled = { enabled ->
                    scope.launch {
                        Core.mcp.setEnabled(state.config.name, enabled)
                        onNotify(if (enabled) "已启用 ${state.config.name}" else "已停用 ${state.config.name}")
                    }
                },
                onConnect = {
                    scope.launch {
                        Core.mcp.connect(state.config.name)
                        onNotify("已连接 ${state.config.name}")
                    }
                },
                onReconnect = {
                    // Manual retry from FAILED/RECONNECTING: reconnect() resets the
                    // backoff counter and starts a fresh attempt immediately.
                    scope.launch {
                        Core.mcp.reconnect(state.config.name)
                        onNotify("正在重连 ${state.config.name}")
                    }
                },
                onDisconnect = {
                    scope.launch {
                        Core.mcp.disconnect(state.config.name)
                        onNotify("已断开 ${state.config.name}")
                    }
                },
                onEdit = { onEdit(state.config) },
                onDelete = { onDelete(state.config) },
            )
        }
    }
}

@Composable
private fun McpIntroCard() {
    SuixingCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SoftIcon(ImageVector.vectorResource(R.drawable.ic_info), size = 34.dp, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(12.dp))
            Text("什么是 MCP", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "MCP（Model Context Protocol）是给模型外挂工具的标准协议。接上服务器后，" +
                "它提供的每个工具都会以 mcp__服务器名__工具名 出现在模型可调用的工具列表里，" +
                "和内置工具一样受审批闸门管控。",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "stdio：填一条启动命令，随行会把它当子进程跑，用管道收发 JSON-RPC。" +
                "手机上的进程默认以应用身份运行，读不到 Termux 私有目录；" +
                "装了 Magisk/KernelSU 的话可以用 su -c \"...\" 前缀借 root 跑。\n" +
                "http：填服务器地址（如 http://127.0.0.1:3000/mcp），适合跑在 Termux、电脑或公网的服务。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ServerCard(
    state: McpServerState,
    onToggleEnabled: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val dark = LocalDarkTheme.current
    val tone = when (state.status) {
        McpStatus.READY -> Tone.OK
        McpStatus.CONNECTING -> Tone.INFO
        McpStatus.RECONNECTING -> Tone.INFO
        McpStatus.FAILED -> Tone.WARN
        McpStatus.DISCONNECTED -> Tone.OFF
    }
    SuixingCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SoftIcon(ImageVector.vectorResource(R.drawable.ic_server), tint = tone.color(dark))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.config.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.statusLine(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = state.config.enabled, onCheckedChange = onToggleEnabled)
        }

        val target = if (state.config.isStdio) state.config.command else state.config.url
        if (target.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Text(
                    text = target,
                    modifier = Modifier.fillMaxWidth().padding(6.dp),
                    style = MonoStyle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(
                text = if (state.config.isStdio) "stdio" else "http",
                tone = Tone.INFO,
            )
            Spacer(Modifier.weight(1f))
            when (state.status) {
                McpStatus.READY -> {
                    TextButton(onClick = onDisconnect) { Text("断开") }
                    TextButton(onClick = onConnect) { Text("刷新工具") }
                }

                // A dropped server keeps retrying on its own; 重连 lets the user
                // reset the backoff and try again right now instead of waiting.
                McpStatus.FAILED, McpStatus.RECONNECTING -> {
                    TextButton(onClick = onReconnect) { Text("重连") }
                }

                McpStatus.CONNECTING -> {
                    TextButton(onClick = onConnect, enabled = false) { Text("连接中…") }
                }

                McpStatus.DISCONNECTED -> {
                    TextButton(onClick = onConnect) { Text("连接") }
                }
            }
            IconButton(onClick = onEdit) {
                Icon(ImageVector.vectorResource(R.drawable.ic_square_pen), contentDescription = "编辑", modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(ImageVector.vectorResource(R.drawable.ic_trash), contentDescription = "删除", modifier = Modifier.size(20.dp))
            }
        }
    }
}

private fun McpServerState.statusLine(): String = when (status) {
    McpStatus.DISCONNECTED -> if (config.enabled) "未连接" else "已停用"
    McpStatus.CONNECTING -> "正在连接…"
    McpStatus.RECONNECTING -> "重连中（第 $attempt/$MCP_MAX_RECONNECT_ATTEMPTS 次）…"
    McpStatus.READY -> "已就绪 · $toolCount 个工具${if (serverName.isBlank()) "" else " · $serverName"}"
    McpStatus.FAILED -> "连接失败：${error.orEmpty()}"
}

@Composable
private fun McpEditorDialog(
    initial: McpServerConfig?,
    onDismiss: () -> Unit,
    onSave: (McpServerConfig) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var transport by remember { mutableStateOf(initial?.transport ?: McpServerConfig.TRANSPORT_STDIO) }
    var command by remember { mutableStateOf(initial?.command.orEmpty()) }
    var url by remember { mutableStateOf(initial?.url.orEmpty()) }
    var env by remember { mutableStateOf(initial?.env.orEmpty()) }
    var headers by remember { mutableStateOf(initial?.headers.orEmpty()) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    val stdio = transport != McpServerConfig.TRANSPORT_HTTP
    val nameOk = name.isNotBlank()
    val targetOk = if (stdio) command.isNotBlank() else url.isNotBlank()

    fun current() = McpServerConfig(
        name = name.trim(),
        transport = transport,
        command = command.trim(),
        url = url.trim(),
        env = env,
        headers = headers,
        enabled = enabled,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加 MCP 服务器" else "编辑 ${initial.name}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                SegmentedPill(
                    options = listOf("stdio", "http"),
                    selectedIndex = if (stdio) 0 else 1,
                    onSelect = { index ->
                        transport = if (index == 0) {
                            McpServerConfig.TRANSPORT_STDIO
                        } else {
                            McpServerConfig.TRANSPORT_HTTP
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    textStyle = MaterialTheme.typography.bodyMedium,
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（会进工具名，建议用英文）") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                )
                Spacer(Modifier.height(10.dp))

                if (stdio) {
                    OutlinedTextField(
                        textStyle = MaterialTheme.typography.bodyMedium,
                        value = command,
                        onValueChange = { command = it },
                        label = { Text("启动命令") },
                        placeholder = { Text("python /sdcard/mcp/server.py") },
                        minLines = 1,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        textStyle = MaterialTheme.typography.bodyMedium,
                        value = env,
                        onValueChange = { env = it },
                        label = { Text("环境变量（每行 KEY=VALUE，可空）") },
                        minLines = 1,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        textStyle = MaterialTheme.typography.bodyMedium,
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("服务器地址") },
                        placeholder = { Text("http://127.0.0.1:3000/mcp") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        textStyle = MaterialTheme.typography.bodyMedium,
                        value = headers,
                        onValueChange = { headers = it },
                        label = { Text("请求头（每行 Name: value，可空）") },
                        minLines = 1,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                    Spacer(Modifier.width(10.dp))
                    Text("启用（启动时自动连接）", style = MaterialTheme.typography.bodyMedium)
                }

                result?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("连接成功")) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(current()) },
                enabled = nameOk && targetOk,
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        testing = true
                        result = null
                        val config = current()
                        scope.launch {
                            result = Core.mcp.test(config)
                            testing = false
                        }
                    },
                    enabled = !testing && nameOk && targetOk,
                ) { Text(if (testing) "测试中…" else "测试") }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}