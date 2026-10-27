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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.suixing.agent.agent.COMPRESS_CONTEXT_SPEC
import com.suixing.agent.capability.CapabilityState
import com.suixing.agent.core.Core
import com.suixing.agent.data.McpServerConfig
import com.suixing.agent.mcp.McpManager
import com.suixing.agent.skills.Skill
import com.suixing.agent.tools.TIER_ACCESSIBILITY
import com.suixing.agent.tools.TIER_NONE
import com.suixing.agent.tools.TIER_ROOT
import com.suixing.agent.tools.TIER_SHELL
import com.suixing.agent.tools.virtualTool
import com.suixing.agent.ui.extension.SkillUrlImportDialog
import com.suixing.agent.ui.components.EmptyState
import com.suixing.agent.ui.components.PageTopBar
import com.suixing.agent.ui.components.SegmentedPill
import com.suixing.agent.ui.components.StatusPill
import com.suixing.agent.ui.components.SuixingCard
import com.suixing.agent.ui.components.SuixingDialog
import com.suixing.agent.ui.components.navBarContentPadding
import com.suixing.agent.ui.extension.SkillViewDialog
import com.suixing.agent.ui.extension.SkillsSection
import com.suixing.agent.ui.theme.Tone
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
 * 分组由**工具包自己声明**（[com.suixing.agent.tools.ToolGroup.group]，经
 * [com.suixing.agent.tools.ToolRegistry.groupOf] 取用）：这一页不再维护「工具名 → 分组」的表，
 * 加一个工具、删一个工具，分组和组头的人数都自动跟着变。
 * MCP 前缀归到 [MCP_GROUP]，没声明分组的归到 [OTHER_GROUP]，两组都排在最后。
 */
private fun groupOf(name: String): String = when {
    name.startsWith(McpManager.MCP_PREFIX) -> MCP_GROUP
    else -> Core.tools.groupOf(name).ifBlank { OTHER_GROUP }
}

/** 组的显示顺序＝工具包的注册顺序；MCP 与「其它」垫底。 */
// `order` 由调用方 hoist 一次传进来：原来这个函数在**比较器里**被反复调用，
// 每次 `Core.tools.groupOrder()` 都要 `filter + distinct` 建两个 list。
private fun groupRank(order: List<String>, group: String): Int {
    val declared = order.indexOf(group)
    return when {
        declared >= 0 -> declared
        group == MCP_GROUP -> order.size
        else -> order.size + 1
    }
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
        Column(Modifier.fillMaxSize()) {
            PageTopBar(
                title = "工具",
                // 副标题（每个 tab 一句说明）去掉：顶栏不带副标题，而当前 tab 由下面的
                // SegmentedPill 高亮着，说明文字不是必需信息。
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

                else -> PluginsSection(onNotify = ::notify)
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
        SuixingDialog(
            onDismissRequest = { mcpDeleting = null },
            title = { Text("删除服务器") },
            text = { Text("确定要删除「${target.name}」吗？它的连接会被断开。") },
            confirmButton = { dialog ->
                TextButton(onClick = {
                    dialog.closeThen {
                        mcpDeleting = null
                        scope.launch {
                            runCatching { Core.mcp.remove(target.name) }
                                .onSuccess { notify("已删除 ${target.name}") }
                                .onFailure { notify("删除失败：${it.message ?: "未知错误"}") }
                        }
                    }
                }) { Text("删除") }
            },
            dismissButton = { dialog ->
                TextButton(onClick = { dialog.closeThen { mcpDeleting = null } }) { Text("取消") }
            },
        )
    }

    skillViewing?.let { skill ->
        SkillViewDialog(skill = skill, onDismiss = { skillViewing = null })
    }

    skillDeleting?.let { target ->
        SuixingDialog(
            onDismissRequest = { skillDeleting = null },
            title = { Text("删除技能") },
            text = { Text("确定要删除「${target.name}」吗？删除后模型就读不到它了。") },
            confirmButton = { dialog ->
                TextButton(onClick = {
                    dialog.closeThen {
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
                    }
                }) { Text("删除") }
            },
            dismissButton = { dialog ->
                TextButton(onClick = { dialog.closeThen { skillDeleting = null } }) { Text("取消") }
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


@Composable
private fun ToolsTab() {
    // MCP tools appear and disappear with their server, so the snapshot is re-taken whenever
    // the MCP state changes; the builtin half never changes after Core.init.
    val servers by Core.mcp.servers.collectAsStateWithLifecycle()
    val capabilities by Core.capabilities.state.collectAsStateWithLifecycle()
    val tools = remember(servers) {
        // 引擎的虚拟工具不在注册表里（compress_context 在分发前被拦截，见 AgentEngine），
        // 但模型看得到它，所以页面上也要有这一行 —— 否则用户会觉得工具列表不全。
        Core.tools.all() + virtualTool(COMPRESS_CONTEXT_SPEC)
    }
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
        // 分组顺序只与工具表有关：每个 filtered 变化只取一次，不再「每次比较取一次」。
        val groupOrder = Core.tools.groupOrder()
        filtered.groupBy { groupOf(it.spec.name) }
            .toList()
            .sortedBy { (group, _) -> groupRank(groupOrder, group) }
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
                // 末行避让手势条（issue #47）：内容本身画到手势条底下，滚动时穿过去。
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 0.dp,
            bottom = navBarContentPadding().calculateBottomPadding(),
        ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                grouped.forEach { (group, groupTools) ->
                    item(key = "group-$group") { GroupHeader(group, groupTools.size) }
                    items(
                        groupTools,
                        key = { it.spec.name },
                        contentType = { "tool-row" },
                    ) { tool ->
                        ToolRow(
                            name = tool.spec.name,
                            description = toolCopyZh(tool.spec.name, tool.spec.description),
                            requirement = requirementOf(tool.tier, capabilities),
                        )
                    }
                }
            }
        }
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
