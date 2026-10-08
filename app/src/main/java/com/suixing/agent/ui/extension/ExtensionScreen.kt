package com.suixing.agent.ui.extension

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suixing.agent.core.Core
import com.suixing.agent.data.McpServerConfig
import com.suixing.agent.mcp.McpServerState
import com.suixing.agent.mcp.McpStatus
import com.suixing.agent.skills.Skill
import com.suixing.agent.ui.components.SuixingCard
import com.suixing.agent.ui.components.SuixingTopBar
import com.suixing.agent.ui.components.SegmentedPill
import com.suixing.agent.ui.components.SoftIcon
import com.suixing.agent.ui.components.StatusPill
import com.suixing.agent.ui.theme.LocalDarkTheme
import com.suixing.agent.ui.theme.MonoStyle
import com.suixing.agent.ui.theme.Tone
import com.suixing.agent.ui.theme.color
import kotlinx.coroutines.launch

/**
 * One screen for everything that adds capability at runtime: MCP servers, which
 * contribute tools, and skills, which contribute instructions.
 *
 * [initialTab] lets a row elsewhere (the settings page) open straight onto the tab it
 * is talking about, instead of dropping the user on MCP and making them swipe.
 */
@Composable
fun ExtensionScreen(initialTab: Int = 0) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableIntStateOf(initialTab) }

    var mcpEditorOpen by remember { mutableStateOf(false) }
    var mcpEditing by remember { mutableStateOf<McpServerConfig?>(null) }
    var mcpDeleting by remember { mutableStateOf<McpServerConfig?>(null) }

    var skillViewing by remember { mutableStateOf<Skill?>(null) }
    var skillDeleting by remember { mutableStateOf<Skill?>(null) }
    var skillRefresh by remember { mutableIntStateOf(0) }

    var memoryRefresh by remember { mutableIntStateOf(0) }
    var memoryNew by remember { mutableIntStateOf(0) }

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
                if (count == 0) "没找到技能：需要含 SKILL.md 的文件夹，或一个 .md 文件" else "已导入 $count 个技能",
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SuixingTopBar(
                title = "扩展",
                subtitle = when (tab) {
                    0 -> "MCP 给模型加工具"
                    1 -> "技能给模型加知识"
                    else -> "记忆是模型自己写下的长期备忘"
                },
                actions = {
                    IconButton(
                        onClick = {
                            when (tab) {
                                0 -> scope.launch {
                                    Core.mcp.load()
                                    notify("已重新加载 MCP 配置")
                                }

                                1 -> skillRefresh++
                                else -> memoryRefresh++
                            }
                        },
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新", modifier = Modifier.size(20.dp))
                    }
                    IconButton(
                        onClick = {
                            when (tab) {
                                0 -> {
                                    mcpEditing = null
                                    mcpEditorOpen = true
                                }

                                1 -> importer.launch(null)
                                else -> memoryNew++
                            }
                        },
                    ) {
                        Icon(
                            imageVector = when (tab) {
                                0 -> Icons.Outlined.Add
                                1 -> Icons.Outlined.FileDownload
                                else -> Icons.Outlined.Add
                            },
                            contentDescription = when (tab) {
                                0 -> "添加服务器"
                                1 -> "导入技能"
                                else -> "新增记忆"
                            },
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
            )

            Box(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                SegmentedPill(
                    options = listOf("MCP", "技能", "记忆"),
                    selectedIndex = tab,
                    onSelect = { tab = it },
                )
            }
            Spacer(Modifier.height(12.dp))

            when (tab) {
                0 -> McpSection(
                    onEdit = {
                        mcpEditing = it
                        mcpEditorOpen = true
                    },
                    onDelete = { mcpDeleting = it },
                    onNotify = ::notify,
                )

                1 -> SkillsSection(
                    refresh = skillRefresh,
                    onView = { skillViewing = it },
                    onDelete = { skillDeleting = it },
                )

                else -> MemorySection(
                    refresh = memoryRefresh,
                    newRequest = memoryNew,
                    onNotify = ::notify,
                )
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
                    Core.mcp.upsert(config)
                    if (config.enabled) Core.mcp.connect(config.name)
                    notify("已保存 ${config.name}")
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
                        Core.mcp.remove(target.name)
                        notify("已删除 ${target.name}")
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
                        Core.skills.delete(target.id)
                        skillRefresh++
                        notify("已删除 ${target.name}")
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { skillDeleting = null }) { Text("取消") }
            },
        )
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
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 100.dp),
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
            SoftIcon(Icons.Outlined.Info, size = 34.dp, tint = MaterialTheme.colorScheme.secondary)
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
    onDisconnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val dark = LocalDarkTheme.current
    val tone = when (state.status) {
        McpStatus.READY -> Tone.OK
        McpStatus.ERROR -> Tone.WARN
        McpStatus.CONNECTING -> Tone.INFO
        McpStatus.DISCONNECTED -> Tone.OFF
    }
    SuixingCard(contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SoftIcon(Icons.Outlined.Dns, tint = tone.color(dark))
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
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    style = MonoStyle,
                    maxLines = 3,
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
            if (state.status == McpStatus.READY) {
                TextButton(onClick = onDisconnect) { Text("断开") }
                TextButton(onClick = onConnect) { Text("刷新工具") }
            } else {
                TextButton(onClick = onConnect, enabled = state.status != McpStatus.CONNECTING) {
                    Text(if (state.status == McpStatus.CONNECTING) "连接中…" else "连接")
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = "编辑", modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除", modifier = Modifier.size(20.dp))
            }
        }
    }
}

private fun McpServerState.statusLine(): String = when (status) {
    McpStatus.DISCONNECTED -> if (config.enabled) "未连接" else "已停用"
    McpStatus.CONNECTING -> "正在连接…"
    McpStatus.READY -> "已就绪 · $toolCount 个工具${if (serverName.isBlank()) "" else " · $serverName"}"
    McpStatus.ERROR -> "出错：${error.orEmpty()}"
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
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（会进工具名，建议用英文）") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))

                if (stdio) {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text("启动命令") },
                        placeholder = { Text("python /sdcard/mcp/server.py") },
                        minLines = 2,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = env,
                        onValueChange = { env = it },
                        label = { Text("环境变量（每行 KEY=VALUE，可空）") },
                        minLines = 2,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("服务器地址") },
                        placeholder = { Text("http://127.0.0.1:3000/mcp") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = headers,
                        onValueChange = { headers = it },
                        label = { Text("请求头（每行 Name: value，可空）") },
                        minLines = 2,
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
