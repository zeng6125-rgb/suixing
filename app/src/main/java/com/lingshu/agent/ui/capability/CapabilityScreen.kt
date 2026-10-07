package com.lingshu.agent.ui.capability

import android.os.Build
import android.os.Environment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lingshu.agent.core.Core
import kotlinx.coroutines.launch

@Composable
fun CapabilityScreen() {
    val state by Core.capabilities.state.collectAsState()
    val scope = rememberCoroutineScope()
    var probing by remember { mutableStateOf(false) }
    val allFiles = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    LaunchedEffect(Unit) { Core.capabilities.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("权限分档", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "灵枢按三档权限工作，越往下能力越强、风险也越大。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        CapabilityCard(
            title = "无障碍服务",
            summary = "读屏、点击、滑动、输入文字、截图",
            granted = state.accessibility,
            lines = listOf(
                if (state.accessibility) "已连接，ui_* 系列工具可用" else "未连接，ui_* 系列工具会被拒绝执行",
            ),
            actionLabel = if (state.accessibility) "打开设置" else "去开启",
            onAction = { Core.capabilities.openAccessibilitySettings() },
        )

        CapabilityCard(
            title = "Shizuku（ADB 级权限）",
            summary = "am / pm / input / dumpsys，读写 /sdcard 与 Android/data",
            granted = state.shellReady,
            lines = buildList {
                add(if (state.shizukuInstalled) "Shizuku 已安装" else "未检测到 Shizuku")
                add(if (state.shizukuAlive) "服务正在运行" else "服务未运行，需先启动 Shizuku")
                add(if (state.shizukuGranted) "灵枢已获授权" else "灵枢未获授权")
            },
            actionLabel = when {
                !state.shizukuInstalled -> "安装 Shizuku"
                !state.shizukuGranted -> "请求授权"
                else -> "重新授权"
            },
            onAction = {
                if (state.shizukuInstalled) Core.capabilities.requestShizuku()
                else Core.capabilities.openShizukuApp()
            },
        )

        CapabilityCard(
            title = "Root",
            summary = "最高权限，可直接读写系统文件",
            granted = state.rootReady,
            lines = listOf(
                when {
                    !state.rootProbed -> "尚未检测"
                    state.root -> "检测到 su 可用"
                    else -> "未检测到 su"
                },
            ),
            actionLabel = if (probing) "检测中…" else "检测 root",
            onAction = {
                scope.launch {
                    probing = true
                    Core.capabilities.probeRoot()
                    probing = false
                }
            },
        )

        HorizontalDivider()

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("所有文件访问权限", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (allFiles) {
                        "已授予，文件工具可直接读写 /sdcard"
                    } else {
                        "未授予，文件工具只能访问应用私有目录"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { Core.capabilities.openAllFilesSettings() }) { Text("设置") }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "权限变化后如果状态没更新，手动刷新一次。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { Core.capabilities.refresh() }) { Text("刷新") }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CapabilityCard(
    title: String,
    summary: String,
    granted: Boolean,
    lines: List<String>,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (granted) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    text = if (granted) "已就绪" else "未就绪",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            lines.forEach { line ->
                Text("· $line", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
