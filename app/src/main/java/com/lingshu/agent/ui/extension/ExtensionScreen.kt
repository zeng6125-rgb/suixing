package com.lingshu.agent.ui.extension

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lingshu.agent.core.Core
import com.lingshu.agent.data.McpServerConfig
import com.lingshu.agent.mcp.McpServerState
import com.lingshu.agent.mcp.McpStatus
import com.lingshu.agent.skills.Skill
import kotlinx.coroutines.launch

/**
 * One screen for everything that adds capability at runtime: MCP servers, which
 * contribute tools, and skills, which contribute instructions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionScreen() {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableStateOf(0) }

    var mcpEditorOpen by remember { mutableStateOf(false) }
    var mcpEditing by remember { mutableStateOf<McpServerConfig?>(null) }
    var mcpDeleting by remember { mutableStateOf<McpServerConfig?>(null) }

    var skillViewing by remember { mutableStateOf<Skill?>(null) }
    var skillDeleting by remember { mutableStateOf<Skill?>(null) }
    var skillRefresh by remember { mutableStateOf(0) }

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

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("扩展") },
                    actions = {
                        IconButton(
                            onClick = {
                                if (tab == 0) {
                                    scope.launch {
                                        Core.mcp.load()
                                        notify("已重新加载 MCP 配置")
                                    }
                                } else {
                                    skillRefresh++
                                }
                            },
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                        }
                    },
                )
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("MCP") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("技能") })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (tab == 0) {
                        mcpEditing = null
                        mcpEditorOpen = true
                    } else {
                        importer.launch(null)
                    }
                },
                icon = {
                    Icon(
                        imageVector = if (tab == 0) Icons.Filled.Add else Icons.Filled.FileDownload,
                        contentDescription = null,
                    )
                },
                text = { Text(if (tab == 0) "添加服务器" else "导入技能") },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (tab) {
                0 -> McpSection(
                    onEdit = {
                        mcpEditing = it
                        mcpEditorOpen = true
                    },
                    onDelete = { mcpDeleting = it },
                    onNotify = ::notify,
                )

                else -> SkillsSection(
                    refresh = skillRefresh,
                    onView = { skillViewing = it },
                    onDelete = { skillDeleting = it },
                )
            }
        }
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
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { McpIntroCard() }

        if (servers.isEmpty()) {
            item {
                Text(
                    text = "还没有配置服务器。点右下角「添加服务器」，填一条启动命令（stdio）或一个地址（http）即可。",
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("什么是 MCP", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "MCP（Model Context Protocol）是给模型外挂工具的标准协议。接上服务器后，" +
                    "它提供的每个工具都会以 mcp__服务器名__工具名 出现在模型可调用的工具列表里，" +
                    "和内置工具一样受审批闸门管控。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "stdio：填一条启动命令，灵枢会把它当子进程跑，用管道收发 JSON-RPC。" +
                    "手机上的进程默认以应用身份运行，读不到 Termux 私有目录；" +
                    "装了 Magisk/KernelSU 的话可以用 su -c \"...\" 前缀借 root 跑。\n" +
                    "http：填服务器地址（如 http://127.0.0.1:3000/mcp），适合跑在 Termux、电脑或公网的服务。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.config.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AssistChip(
                    onClick = { },
                    label = { Text(if (state.config.isStdio) "stdio" else "http") },
                )
                Switch(checked = state.config.enabled, onCheckedChange = onToggleEnabled)
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = state.statusLine(),
                style = MaterialTheme.typography.bodySmall,
                color = when (state.status) {
                    McpStatus.READY -> MaterialTheme.colorScheme.primary
                    McpStatus.ERROR -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            val target = if (state.config.isStdio) state.config.command else state.config.url
            if (target.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = target,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.status == McpStatus.READY) {
                    TextButton(onClick = onDisconnect) { Text("断开") }
                    TextButton(onClick = { onConnect() }) { Text("刷新工具") }
                } else {
                    TextButton(onClick = onConnect, enabled = state.status != McpStatus.CONNECTING) {
                        Text(if (state.status == McpStatus.CONNECTING) "连接中…" else "连接")
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "编辑")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                }
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
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（会进工具名，建议用英文）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = stdio,
                        onClick = { transport = McpServerConfig.TRANSPORT_STDIO },
                        label = { Text("stdio") },
                    )
                    FilterChip(
                        selected = !stdio,
                        onClick = { transport = McpServerConfig.TRANSPORT_HTTP },
                        label = { Text("http") },
                    )
                }
                Spacer(Modifier.height(10.dp))

                if (stdio) {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text("启动命令") },
                        placeholder = { Text("python /sdcard/mcp/server.py") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = env,
                        onValueChange = { env = it },
                        label = { Text("环境变量（每行 KEY=VALUE，可空）") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("服务器地址") },
                        placeholder = { Text("http://127.0.0.1:3000/mcp") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = headers,
                        onValueChange = { headers = it },
                        label = { Text("请求头（每行 Name: value，可空）") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                    Spacer(Modifier.width(8.dp))
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
