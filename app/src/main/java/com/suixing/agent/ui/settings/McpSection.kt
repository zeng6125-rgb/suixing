package com.suixing.agent.ui.settings

import com.suixing.agent.ui.components.settingsPagePadding
import com.suixing.agent.ui.components.SettingsStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suixing.agent.R
import com.suixing.agent.core.Core
import com.suixing.agent.data.McpServerConfig
import com.suixing.agent.mcp.MCP_MAX_RECONNECT_ATTEMPTS
import com.suixing.agent.mcp.McpServerState
import com.suixing.agent.mcp.McpStatus
import com.suixing.agent.ui.components.SegmentedPill
import com.suixing.agent.ui.components.SoftIcon
import com.suixing.agent.ui.components.StatusPill
import com.suixing.agent.ui.components.SuixingCard
import com.suixing.agent.ui.components.SuixingDialog
import com.suixing.agent.ui.components.navBarContentPadding
import com.suixing.agent.ui.theme.LocalDarkTheme
import com.suixing.agent.ui.theme.MonoStyle
import com.suixing.agent.ui.theme.Tone
import com.suixing.agent.ui.theme.color
import kotlinx.coroutines.launch
@Composable
internal fun McpSection(
    onEdit: (McpServerConfig) -> Unit,
    onDelete: (McpServerConfig) -> Unit,
    onNotify: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val servers by Core.mcp.servers.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // 末行避让手势条（issue #47）：内容本身画到手势条底下，滚动时穿过去。
        contentPadding = settingsPagePadding(),
        verticalArrangement = Arrangement.spacedBy(SettingsStyle.SectionGap),
    ) {
        item { McpIntroCard() }

        if (servers.isEmpty()) {
            item {
                Text(
                    text = "还没有配置服务器。点右上角「+」，填一条启动命令（stdio）或一个地址（http）即可。",
                    style = SettingsStyle.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(
            servers,
            key = { it.config.name },
            contentType = { "mcp-row" },
        ) { state ->
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
            Text("什么是 MCP", style = SettingsStyle.cardTitle)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "MCP（Model Context Protocol）是给模型外挂工具的标准协议。接上服务器后，" +
                "它提供的每个工具都会以 mcp__服务器名__工具名 出现在模型可调用的工具列表里，" +
                "和内置工具一样受审批闸门管控。",
            style = SettingsStyle.description,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "stdio：填一条启动命令，随行会把它当子进程跑，用管道收发 JSON-RPC。" +
                "手机上的进程默认以应用身份运行，读不到 Termux 私有目录；" +
                "装了 Magisk/KernelSU 的话可以用 su -c \"...\" 前缀借 root 跑。\n" +
                "http：填服务器地址（如 http://127.0.0.1:3000/mcp），适合跑在 Termux、电脑或公网的服务。",
            style = SettingsStyle.description,
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
                    style = SettingsStyle.cardTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.statusLine(),
                    style = SettingsStyle.description,
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
internal fun McpEditorDialog(
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

    SuixingDialog(
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
                    textStyle = SettingsStyle.fieldText,
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
                        textStyle = SettingsStyle.fieldText,
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
                        textStyle = SettingsStyle.fieldText,
                        value = env,
                        onValueChange = { env = it },
                        label = { Text("环境变量（每行 KEY=VALUE，可空）") },
                        minLines = 1,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        textStyle = SettingsStyle.fieldText,
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
                        textStyle = SettingsStyle.fieldText,
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
                    Text("启用（启动时自动连接）", style = SettingsStyle.body)
                }

                result?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = it,
                        style = SettingsStyle.description,
                        color = if (it.startsWith("连接成功")) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        },
        confirmButton = { dialog ->
            TextButton(
                onClick = { dialog.closeThen { onSave(current()) } },
                enabled = nameOk && targetOk,
            ) { Text("保存") }
        },
        dismissButton = { dialog ->
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
                TextButton(onClick = { dialog.closeThen(onDismiss) }) { Text("取消") }
            }
        },
    )
}
