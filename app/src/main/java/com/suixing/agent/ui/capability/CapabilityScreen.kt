package com.suixing.agent.ui.capability

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.suixing.agent.R
import com.suixing.agent.capability.CapabilityState
import com.suixing.agent.capability.ShizukuManager
import com.suixing.agent.core.Core
import com.suixing.agent.data.AppSettings
import com.suixing.agent.service.FloatConsoleService
import com.suixing.agent.ui.components.SuixingCard
import com.suixing.agent.ui.components.PageTopBar
import com.suixing.agent.ui.components.SectionCard
import com.suixing.agent.ui.components.SoftIcon
import com.suixing.agent.ui.components.StatusPill
import com.suixing.agent.ui.theme.Tone
import kotlinx.coroutines.launch

@Composable
fun CapabilityScreen(onBack: () -> Unit) {
    val state by Core.capabilities.state.collectAsState()
    val settings by Core.settings.flow.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    var probing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var explainerOpen by remember { mutableStateOf(false) }
    val supportsAllFiles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    // Overlay permission lives in the system settings app, not in our process, so it has
    // to be re-read when the user comes back rather than remembered in state.
    val context = LocalContext.current
    var overlayGranted by remember { mutableStateOf(FloatConsoleService.canDrawOverlays(context)) }
    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { overlayGranted = FloatConsoleService.canDrawOverlays(context) }

    // Same story for "all files access": the grant is decided in the system settings app, so
    // it is re-read on the way back. Both reads stay guarded by the API check — the call only
    // exists from Android 11, and below that the card has to keep saying 不支持, not 未授予.
    var allFilesGranted by remember {
        mutableStateOf(supportsAllFiles && Environment.isExternalStorageManager())
    }
    val allFiles = supportsAllFiles && allFilesGranted
    val allFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { allFilesGranted = supportsAllFiles && Environment.isExternalStorageManager() }

    LaunchedEffect(Unit) { Core.capabilities.refresh() }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        PageTopBar(title = "能力", subtitle = "随行能碰到什么，取决于这里", onBack = onBack)
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
                            ImageVector.vectorResource(R.drawable.ic_circle_alert),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(text, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // 第一档不是「还没开的权限」，而是这个应用本身：装上就有，其余三档都是它的叠加。
            CapabilityCard(
                title = "标准档（默认）",
                icon = ImageVector.vectorResource(R.drawable.ic_bot),
                summary = "装上就有，不需要任何授权",
                granted = true,
                lines = listOf(
                    "聊天、记忆、待办、定时、技能、网络与文件工具都在这一档",
                    "文件只能读写应用私有目录，碰不到 /sdcard 的其它目录",
                    "要操作别的应用、执行命令或改系统文件，再往下开一档",
                ),
                actionLabel = "无需开启",
                actionEnabled = false,
                onAction = {},
            )

            CapabilityCard(
                title = "无障碍",
                icon = ImageVector.vectorResource(R.drawable.ic_pointer),
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
                title = "Shizuku（ADB 级）",
                icon = ImageVector.vectorResource(R.drawable.ic_terminal),
                summary = "am / pm / input / dumpsys，读写 /sdcard 与 Android/data",
                granted = state.shellReady,
                lines = buildList {
                    add(if (state.shizukuInstalled) "Shizuku 已安装" else "未检测到 Shizuku")
                    add(if (state.shizukuAlive) "服务正在运行" else "服务未运行，需先启动 Shizuku")
                    add(if (state.shizukuGranted) "随行已获授权" else "随行未获授权")
                },
                actionLabel = when {
                    !state.shizukuInstalled -> "去安装"
                    !state.shizukuGranted -> "请求授权"
                    else -> "重新授权"
                },
                onAction = {
                    when {
                        !state.shizukuInstalled -> {
                            val opened = openShizukuStore(context)
                            notice = if (opened) {
                                null
                            } else {
                                "本机没有能打开商店页的应用，请手动搜索安装 Shizuku" +
                                    "（包名 moe.shizuku.privileged.api）。"
                            }
                        }

                        Core.capabilities.requestShizuku() -> notice = null
                        else -> {
                            // 服务没起来时，用户下一步只有一件事可做：去 Shizuku 应用里把它启动。
                            Core.capabilities.openShizukuApp()
                            notice = "Shizuku 服务未运行，已为你打开 Shizuku 应用：" +
                                "在里面启动服务，再回来点「请求授权」。"
                        }
                    }
                },
            )

            CapabilityCard(
                title = "Root",
                icon = ImageVector.vectorResource(R.drawable.ic_shield_user),
                summary = "最高权限，可直接读写系统文件",
                granted = state.rootReady,
                lines = listOfNotNull(
                    when {
                        !state.rootProbed -> "尚未检测"
                        state.root -> "检测到 su 可用"
                        else -> "未检测到 su"
                    },
                    "这一档要靠 KernelSU / Magisk 给 su 授权，没装就没有",
                    if (state.rootProbed && settings.rootCheckedAt > 0L) {
                        "上次检测：${formatAgo(settings.rootCheckedAt)}（结果已记住，下次打开自动复核）"
                    } else {
                        "检测一次即可，之后每次打开都会自动复核"
                    },
                ),
                actionLabel = if (probing) "检测中…" else "检测",
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
                icon = ImageVector.vectorResource(R.drawable.ic_picture_in_picture_2),
                summary = "agent 跑起来时，屏幕上有个能停止、能插话的悬浮球",
                granted = overlayGranted,
                lines = if (overlayGranted) {
                    // Read-only here: the switch itself lives in the drawer's bottom row, and
                    // two places to flip it would disagree the moment one of them lagged.
                    listOf(
                        "已授权：系统权限已就绪",
                        if (settings.floatingConsole) {
                            "抽屉开关：已打开，agent 运行时悬浮球会出现"
                        } else {
                            "抽屉开关：已关闭，去左侧抽屉底部的「悬浮窗控制台」那一行打开"
                        },
                    )
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
                    SoftIcon(ImageVector.vectorResource(R.drawable.ic_folder_cog), tint = MaterialTheme.colorScheme.tertiary)
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
                            // Started through the launcher, not through the hub: the launcher
                            // is what reports that the user came back. The global list is the
                            // fallback for ROMs that only ship that one.
                            val opened = allFilesSettingsIntents(context).any { intent ->
                                runCatching { allFilesLauncher.launch(intent) }.isSuccess
                            }
                            notice = if (opened) {
                                null
                            } else {
                                "本机无法打开「所有文件访问权限」设置，请改用 Shizuku 或 root 获取全盘访问。"
                            }
                        },
                    ) { Text("打开设置") }
                    TextButton(onClick = { Core.capabilities.refresh() }) {
                        Icon(
                            ImageVector.vectorResource(R.drawable.ic_rotate_cw),
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("刷新状态")
                    }
                }
            }

            SectionCard(
                title = "这四档权限分别能做什么",
                icon = ImageVector.vectorResource(R.drawable.ic_circle_question_mark),
                subtitle = if (explainerOpen) "点标题收起" else "点标题展开",
                trailing = {
                    TextButton(onClick = { explainerOpen = !explainerOpen }) {
                        Text(if (explainerOpen) "收起" else "展开")
                    }
                },
            ) {
                if (explainerOpen) {
                    ExplainerRow("标准档", "默认就在这一档：聊天、记忆与文件工具，只能碰应用私有目录。")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ExplainerRow("无障碍", "看得见屏幕、点得动按钮，但碰不到命令与系统目录。")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ExplainerRow("Shizuku", "相当于把手机连上电脑用 adb：能执行 shell、装卸应用、读写 Android/data，重启后要重新启动服务。")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ExplainerRow("Root", "最稳也最危险，系统分区可写。不开也能正常用。")
                } else {
                    Text(
                        text = "四档权限是叠加的：标准档人人都有，拿到 Shizuku 就自动拥有无障碍的能力，root 又覆盖前两档。",
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
        3 -> "最高权限（root）"
        2 -> "标准档 + ADB"
        1 -> "标准档 + 无障碍"
        else -> "标准档"
    }
    val detail = when (level) {
        3 -> "系统文件可写，自动化最稳"
        2 -> "能跑 shell、装卸应用、读 Android/data"
        1 -> "能读屏点击，但不能执行命令"
        else -> "无需授权：聊天、记忆、待办与文件工具（应用私有目录）"
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
                                ImageVector.vectorResource(R.drawable.ic_shield_alert),
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
                        text = "${level + 1} / 4",
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onPrimary.copy(alpha = 0.85f),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(4) { index ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    if (index <= level) {
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
    /** False for a tier with nothing to open (标准档): the button is shown, but inert. */
    actionEnabled: Boolean = true,
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
                icon = if (granted) ImageVector.vectorResource(R.drawable.ic_circle_check) else null,
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
        if (!actionEnabled) {
            OutlinedButton(onClick = {}, enabled = false) { Text(actionLabel) }
        } else if (granted) {
            OutlinedButton(onClick = onAction) { Text(actionLabel) }
        } else {
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * The store page for Shizuku, with the browser as the fallback for a device without a
 * market app. [CapabilityHub.openShizukuApp] launches the app itself and falls back to our
 * own app-details page — which is a dead end when Shizuku is not installed yet, so the
 * "not installed" case goes here instead.
 */
private fun openShizukuStore(context: Context): Boolean {
    val id = ShizukuManager.SHIZUKU_PACKAGE
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(market) }.isSuccess) return true
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(web) }.isSuccess
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

/**
 * The "all files access" pages, in the order they should be tried: the per-app entry is the
 * precise target, the global list is what some ROMs ship instead.
 *
 * Second copy of the same two intents as capability/CapabilityHub.kt:128-141. That one starts
 * the page itself, which tells us nothing about the user coming back; the launcher here needs
 * the Intent in hand, so the list is built locally. Keep the two in step.
 */
private fun allFilesSettingsIntents(context: Context): List<Intent> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
    return listOf(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
    )
}
