package com.suixing.agent.ui.terminal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.suixing.agent.core.Core
import com.suixing.agent.terminal.*
import kotlinx.coroutines.launch

@Composable
internal fun LinuxEnvironmentsDialog(
    onDismiss: () -> Unit, onOpen: (String) -> Unit, onInstallTools: (LinuxDistro) -> Unit,
) {
    val installer = Core.linux
    val states by installer.states.collectAsStateWithLifecycle()
    val sessions by Core.terminals.entries.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var removing by remember { mutableStateOf<LinuxDistro?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    removing?.let { distro ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("移除 " + distro.title + "？") },
            text = { Text("将删除该环境内的软件和 /root 文件；共享 /workspace 中的项目会保留。") },
            confirmButton = { TextButton(onClick = {
                removing = null
                scope.launch { runCatching { Core.terminals.removeLinux(distro) }.onFailure { error = it.message } }
            }) { Text("移除") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } })
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("可选 Linux 环境") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("实验性 · ARM64。按需下载，安装后会检查本机能否启动 Linux shell 和包管理器。",
                    style = MaterialTheme.typography.bodySmall)
                Text("PRoot 的 root 是模拟身份，仍使用 Android 应用权限，不提供文件、进程或网络安全隔离。",
                    style = MaterialTheme.typography.bodySmall)
                if (!installer.supported) Text("当前 CPU 或安装包不支持内置 Linux。", color = MaterialTheme.colorScheme.error)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                for (distro in LINUX_DISTRIBUTIONS) {
                    val state = states[distro.id] ?: LinuxState()
                    val busy = state.phase in LinuxInstaller.busyPhases
                    val anyBusy = states.values.any { it.phase in LinuxInstaller.busyPhases }
                    val running = sessions.any { it.info.kind == distro.id && it.info.phase == "running" }
                    HorizontalDivider()
                    Text(distro.title + " " + distro.version, style = MaterialTheme.typography.titleSmall)
                    Text("下载 " + (distro.downloadBytes / 1_000_000) + " MB · 基础空间约 " +
                        (distro.installedBytes / 1024 / 1024) + " MiB，安装软件需额外空间",
                        style = MaterialTheme.typography.bodySmall)
                    Text(if (distro.id == "alpine") "来源：Alpine 官方 minirootfs" else "来源：Termux proot-distro 固定 Debian 镜像",
                        style = MaterialTheme.typography.bodySmall)
                    SelectionContainer { Text("SHA-256: " + distro.sha256, style = MaterialTheme.typography.labelSmall) }
                    if (busy) {
                        Text(when (state.phase) {
                            LinuxPhase.DOWNLOADING -> "正在下载 " + (100 * state.bytes / distro.downloadBytes) + "%"
                            LinuxPhase.VERIFYING -> "正在校验 SHA-256"
                            LinuxPhase.EXTRACTING -> "正在解压"
                            LinuxPhase.CHECKING -> "正在检查本机运行兼容性"
                            else -> "正在移除"
                        }, style = MaterialTheme.typography.bodySmall)
                        if (state.phase == LinuxPhase.DOWNLOADING)
                            LinearProgressIndicator(progress = { (state.bytes.toFloat() / distro.downloadBytes).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (state.phase != LinuxPhase.REMOVING) TextButton(onClick = { installer.cancel() }) { Text("取消安装") }
                    }
                    state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                        color = if (state.phase == LinuxPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (state.available) {
                        Text("工作区：/workspace；软件包使用发行版签名校验。", style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(enabled = !busy, onClick = { onOpen(distro.id) }) { Text("打开") }
                            TextButton(enabled = !busy, onClick = { onInstallTools(distro) }) { Text("安装 Python / Node / Git") }
                        }
                        TextButton(enabled = !busy && !running, onClick = { removing = distro }) { Text("移除环境") }
                        if (running) Text("先停止该环境的终端会话后再移除。", style = MaterialTheme.typography.labelSmall)
                    } else if (!busy) TextButton(enabled = installer.supported && !anyBusy,
                        onClick = { installer.install(distro) }) { Text(if (state.phase == LinuxPhase.FAILED) "重试安装" else "安装") }
                }
                Text("基础镜像中的各软件遵循各自许可证；可在环境内查看 /usr/share/doc。", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}
