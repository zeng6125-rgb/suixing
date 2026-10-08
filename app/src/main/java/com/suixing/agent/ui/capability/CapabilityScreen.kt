package com.suixing.agent.ui.capability

import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ShieldMoon
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suixing.agent.capability.CapabilityState
import com.suixing.agent.core.Core
import com.suixing.agent.data.AppSettings
import com.suixing.agent.service.FloatConsoleService
import com.suixing.agent.ui.components.SuixingCard
import com.suixing.agent.ui.components.SuixingTopBar
import com.suixing.agent.ui.components.SectionCard
import com.suixing.agent.ui.components.SoftIcon
import com.suixing.agent.ui.components.StatusPill
import com.suixing.agent.ui.theme.Tone
import kotlinx.coroutines.launch

@Composable
fun CapabilityScreen() {
    val state by Core.capabilities.state.collectAsState()
    val settings by Core.settings.flow.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    var probing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var explainerOpen by remember { mutableStateOf(false) }
    val supportsAllFiles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    val allFiles = supportsAllFiles && Environment.isExternalStorageManager()

    // Overlay permission lives in the system settings app, not in our process, so it has
    // to be re-read when the user comes back rather than remembered in state.
    val context = LocalContext.current
    var overlayGranted by remember { mutableStateOf(FloatConsoleService.canDrawOverlays(context)) }
    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { overlayGranted = FloatConsoleService.canDrawOverlays(context) }

    LaunchedEffect(Unit) { Core.capabilities.refresh() }

    Column(Modifier.fillMaxSize()) {
        SuixingTopBar(title = "能力", subtitle = "随行能碰到什么，取决于这里")
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            CapabilityHero(state)

            notice?.let { text ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(text, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            CapabilityCard(
                title = "无障碍服务",
                icon = Icons.Outlined.TouchApp,
                summary = "读屏、点击、滑动、输入文字、截图",
                granted = state.accessibility,
                lines = listOf(
                    if (state.accessibility) {
                        "已连接，ui_* 系列工具可用"
                    } else if (state.shellReady || state.rootReady) {
                        "未连接。ui_* 会走 Shizuku / root 的 input、screencap 兜底，但打不了中文"
                    } else {
                        "未连接，且没有 Shizuku / root 兜底，ui_* 系列工具会被拒绝执行"
                    },
                ),
                actionLabel = if (state.accessibility) "打开设置" else "去开启",
                onAction = { Core.capabilities.openAccessibilitySettings() },
            )

            CapabilityCard(
                title = "Shizuku（ADB 级权限）",
                icon = Icons.Outlined.Terminal,
                summary = "am / pm / input / dumpsys，读写 /sdcard 与 Android/data",
                granted = state.shellReady,
                lines = buildList {
                    add(if (state.shizukuInstalled) "Shizuku 已安装" else "未检测到 Shizuku")
                    add(if (state.shizukuAlive) "服务正在运行" else "服务未运行，需先启动 Shizuku")
                    add(if (state.shizukuGranted) "随行已获授权" else "随行未获授权")
                },
                actionLabel = when {
                    !state.shizukuInstalled -> "安装 Shizuku"
                    !state.shizukuGranted -> "请求授权"
                    else -> "重新授权"
                },
                onAction = {
                    if (!state.shizukuInstalled) {
                        Core.capabilities.openShizukuApp()
                    } else if (Core.capabilities.requestShizuku()) {
                        notice = null
                    } else {
                        notice = "Shizuku 服务未运行：先打开 Shizuku 应用并启动服务，再回来请求授权。"
                    }
                },
            )

            CapabilityCard(
                title = "Root",
                icon = Icons.Outlined.AdminPanelSettings,
                summary = "最高权限，可直接读写系统文件",
                granted = state.rootReady,
                lines = listOfNotNull(
                    when {
                        !state.rootProbed -> "尚未检测"
                        state.root -> "检测到 su 可用"
                        else -> "未检测到 su"
                    },
                    if (state.rootProbed && settings.rootCheckedAt > 0L) {
                        "上次检测：${formatAgo(settings.rootCheckedAt)}（结果已记住，下次打开自动复核）"
                    } else {
                        "检测一次即可，之后每次打开都会自动复核"
                    },
                ),
                actionLabel = if (probing) "检测中…" else "重新检测",
                onAction = {
                    scope.launch {
                        probing = true
                        Core.capabilities.probeRoot()
                        probing = false
                    }
                },
            )

            CapabilityCard(
                title = "悬浮窗控制台",
                icon = Icons.Outlined.PictureInPictureAlt,
                summary = "agent 跑起来时，屏幕上有个能停止、能插话的悬浮球",
                granted = overlayGranted,
                lines = if (overlayGranted) {
                    listOf("已授权：agent 运行时悬浮球自动出现，不用手动开")
                } else {
                    listOf(
                        "未授权：任务跑起来后，屏幕上不会有悬浮球和停止按钮",
                        "这个开关在系统设置里，只有你能打开",
                    )
                },
                actionLabel = if (overlayGranted) "打开系统设置" else "去授权",
                onAction = {
                    overlayLauncher.launch(FloatConsoleService.overlayPermissionIntent(context))
                },
            )

            SuixingCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SoftIcon(Icons.Outlined.FolderSpecial, tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("所有文件访问权限", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = when {
                                !supportsAllFiles ->
                                    "安卓 10 及以下没有这个开关，改用 Shizuku 或 root 访问 /sdcard"
                                allFiles -> "已授予，文件工具可直接读写 /sdcard"
                                else -> "未授予，文件工具只能访问应用私有目录"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (Core.capabilities.openAllFilesSettings()) {
                                notice = null
                            } else {
                                notice = "本机无法打开「所有文件访问权限」设置，请改用 Shizuku 或 root 获取全盘访问。"
                            }
                        },
                    ) { Text("打开设置") }
                    TextButton(onClick = { Core.capabilities.refresh() }) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("刷新状态")
                    }
                }
            }

            SectionCard(
                title = "这三档权限分别能做什么",
                icon = Icons.AutoMirrored.Outlined.HelpOutline,
                subtitle = if (explainerOpen) "点标题收起" else "点标题展开",
                trailing = {
                    TextButton(onClick = { explainerOpen = !explainerOpen }) {
                        Text(if (explainerOpen) "收起" else "展开")
                    }
                },
            ) {
                if (explainerOpen) {
                    ExplainerRow("无障碍", "看得见屏幕、点得动按钮，但碰不到命令与系统目录。")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ExplainerRow("Shizuku", "相当于把手机连上电脑用 adb：能执行 shell、装卸应用、读写 Android/data，重启后要重新启动服务。")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ExplainerRow("Root", "最稳也最危险，系统分区可写。不开也能正常用。")
                } else {
                    Text(
                        text = "三档权限是叠加的：拿到 Shizuku 就自动拥有无障碍的能力，root 又覆盖前两档。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun CapabilityHero(state: CapabilityState) {
    val scheme = MaterialTheme.colorScheme
    val level = when {
        state.rootReady -> 3
        state.shellReady -> 2
        state.accessibility -> 1
        else -> 0
    }
    val label = when (level) {
        3 -> "最高权限"
        2 -> "无障碍 + ADB"
        1 -> "仅无障碍"
        else -> "基础模式"
    }
    val detail = when (level) {
        3 -> "系统文件可写，自动化最稳"
        2 -> "能跑 shell、装卸应用、读 Android/data"
        1 -> "能读屏点击，但不能执行命令"
        else -> "只能聊天与读写应用私有目录"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = Color.Transparent,
    ) {
        Box(
            Modifier.background(
                Brush.linearGradient(
                    listOf(scheme.primary.copy(alpha = 0.85f), scheme.secondary.copy(alpha = 0.45f)),
                ),
            ),
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(42.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = scheme.onPrimary.copy(alpha = 0.18f),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Outlined.ShieldMoon,
                                contentDescription = null,
                                tint = scheme.onPrimary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "当前权限",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onPrimary.copy(alpha = 0.75f),
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onPrimary,
                        )
                    }
                    Text(
                        text = "$level / 3",
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onPrimary.copy(alpha = 0.85f),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(3) { index ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    if (index < level) {
                                        scheme.onPrimary
                                    } else {
                                        scheme.onPrimary.copy(alpha = 0.25f)
                                    },
                                ),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onPrimary.copy(alpha = 0.9f),
                )
            }
        }
    }
}

@Composable
private fun ExplainerRow(title: String, body: String) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CapabilityCard(
    title: String,
    icon: ImageVector,
    summary: String,
    granted: Boolean,
    lines: List<String>,
    actionLabel: String,
    onAction: () -> Unit,
) {
    SuixingCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SoftIcon(
                icon = icon,
                tint = if (granted) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(
                text = if (granted) "已就绪" else "未就绪",
                tone = if (granted) Tone.OK else Tone.WARN,
                icon = if (granted) Icons.Filled.CheckCircle else null,
            )
        }
        Spacer(Modifier.height(12.dp))
        lines.forEach { line ->
            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.outline),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        if (granted) {
            OutlinedButton(onClick = onAction) { Text(actionLabel) }
        } else {
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** 「刚刚 / N 分钟前 / N 小时前 / N 天前」for the remembered root probe. */
private fun formatAgo(timestamp: Long): String {
    val delta = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
    return when {
        delta < 60_000L -> "刚刚"
        delta < 3_600_000L -> "${delta / 60_000L} 分钟前"
        delta < 86_400_000L -> "${delta / 3_600_000L} 小时前"
        else -> "${delta / 86_400_000L} 天前"
    }
}
