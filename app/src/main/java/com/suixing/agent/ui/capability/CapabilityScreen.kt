package com.suixing.agent.ui.capability

import com.suixing.agent.ui.components.SegmentedPill
import com.suixing.agent.ui.components.SettingsSwitch
import com.suixing.agent.ui.components.SettingsStyle
import com.suixing.agent.ui.components.settingsPagePadding
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.suixing.agent.R
import com.suixing.agent.capability.allFilesAccessIntents
import com.suixing.agent.capability.CapabilityState
import com.suixing.agent.capability.ShizukuManager
import com.suixing.agent.core.Core
import com.suixing.agent.data.AppSettings
import com.suixing.agent.service.FloatConsoleService
import com.suixing.agent.tools.TIER_ACCESSIBILITY
import com.suixing.agent.tools.TIER_NONE
import com.suixing.agent.tools.TIER_ROOT
import com.suixing.agent.tools.TIER_SHELL
import com.suixing.agent.ui.components.SuixingCard
import com.suixing.agent.ui.components.PageTopBar
import com.suixing.agent.ui.components.SectionCard
import com.suixing.agent.ui.components.SoftIcon
import com.suixing.agent.ui.components.StatusPill
import com.suixing.agent.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CapabilityScreen(onBack: () -> Unit) {
    val state by Core.capabilities.state.collectAsStateWithLifecycle()
    val settings by Core.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    var probing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var explainerOpen by remember { mutableStateOf(false) }
    val supportsAllFiles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    // Overlay permission lives in the system settings app, not in our process, so it has
    // to be re-read when the user comes back rather than remembered in state.
    val context = LocalContext.current
    var overlayGranted by remember { mutableStateOf(FloatConsoleService.canDrawOverlays(context)) }
    var batteryExempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
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

    // refresh() 不是 suspend，里面是同步的 PackageManager 查询加两次 binder 调用：直接在这里
    // 调用就是主线程等 IPC，冷启动进这一页会顿一下。它没有回调可等，所以只是挪走，不改成异步。
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Core.capabilities.refresh() } }

    // 根节点必须自己铺不透明底：转场里进页是**不淡入**的（见 SuixingApp 顶部那段），这一页
    // 若透出外壳的底，进页的那一帧就会看到上一页的残影。
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 顶栏不带副标题（用户：「任何页都不要副标题」）；无副标题时标题在行内居中 ⇒
        // 与返回箭头中心天然重合（结构性对齐，不需要任何补偿）。
        PageTopBar(title = "能力", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = settingsPagePadding(),
            verticalArrangement = Arrangement.spacedBy(SettingsStyle.SectionGap),
        ) {
            item(key = "capability-0", contentType = "capability-card") {
                CapabilityHero(state)
            }

            if (notice != null) item(key = "capability-1", contentType = "capability-card") {
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
                            Text(text, style = SettingsStyle.description)
                        }
                        }
            }
            }

            // 自选档位：Hero 说的是「机器现在给到了哪一档」，这一块说的是「用户想要哪一档」。
            // 想要的档还没开出来时照样记下来，等它可用就生效。放在提示条之后，是为了让
            // 下面那些卡片触发的提示条留在原来的位置上。
            item(key = "capability-2", contentType = "capability-card") {
                CapabilityTierPicker(
                preferred = settings.preferredCapability,
                state = state,
                settings = settings,
                onPick = { key ->
                    scope.launch { Core.settings.update { it.copy(preferredCapability = key) } }
                },
            )
            }

            // 第一档不是「还没开的权限」，而是这个应用本身：装上就有，其余三档都是它的叠加。
            item(key = "capability-3", contentType = "capability-card") {
                CapabilityCard(
                title = "标准档（默认）",
                icon = ImageVector.vectorResource(R.drawable.ic_bot),
                summary = "装上就有，不需要任何授权",
                granted = true,
                lines = tierLines(TIER_NONE, state, settings),
                actionLabel = "无需开启",
                actionEnabled = false,
                onAction = {},
            )
            }

            item(key = "capability-4", contentType = "capability-card") {
                CapabilityCard(
                title = "无障碍",
                icon = ImageVector.vectorResource(R.drawable.ic_pointer),
                summary = "读屏、点击、滑动、输入文字、截图",
                granted = state.accessibility,
                lines = tierLines(TIER_ACCESSIBILITY, state, settings),
                actionLabel = if (state.accessibility) "打开设置" else "去开启",
                onAction = { Core.capabilities.openAccessibilitySettings() },
            )
            }

            item(key = "capability-5", contentType = "capability-card") {
                CapabilityCard(
                title = "Shizuku（ADB 级）",
                icon = ImageVector.vectorResource(R.drawable.ic_terminal),
                summary = "am / pm / input / dumpsys，读写 /sdcard 与 Android/data",
                granted = state.shellReady,
                lines = tierLines(TIER_SHELL, state, settings),
                actionLabel = shizukuActionLabel(state),
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
            }

            item(key = "capability-6", contentType = "capability-card") {
                CapabilityCard(
                title = "Root",
                icon = ImageVector.vectorResource(R.drawable.ic_shield_user),
                summary = "最高权限，可直接读写系统文件",
                granted = state.rootReady,
                lines = tierLines(TIER_ROOT, state, settings),
                actionLabel = if (probing) "检测中…" else "检测",
                // 探测要跑几秒，期间按钮必须点不动：否则连点会叠起多次 probeRoot()。
                actionEnabled = !probing,
                onAction = {
                    scope.launch {
                        probing = true
                        Core.capabilities.probeRoot()
                        probing = false
                    }
                },
            )
            }

            item(key = "capability-7", contentType = "capability-card") {
                CapabilityCard(
                title = "悬浮窗控制台",
                icon = ImageVector.vectorResource(R.drawable.ic_picture_in_picture_2),
                summary = "agent 跑起来时，屏幕上有个能停止、能插话的悬浮球",
                granted = overlayGranted,
                lines = if (overlayGranted) {
                    listOf(
                        "已授权：系统权限已就绪",
                        if (settings.floatingConsole) {
                            "抽屉开关（常驻）：已打开，不在前台时球一直在"
                        } else {
                            "抽屉开关（常驻）：已关闭，去左侧抽屉底部的「悬浮窗控制台」那一行打开"
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
                footer = {
                    ConsoleRunningSwitch(
                        checked = settings.consoleWhileRunning,
                        onChange = { on ->
                            scope.launch { Core.settings.update { it.copy(consoleWhileRunning = on) } }
                        },
                    )
                },
            )
            }

            item(key = "capability-8", contentType = "capability-card") {
                CapabilityCard(
                title = "后台运行",
                icon = ImageVector.vectorResource(R.drawable.ic_shield_user),
                summary = "息屏或切到别的应用时，长任务与流式回答继续跑",
                granted = batteryExempt,
                lines = if (batteryExempt) {
                    listOf(
                        "已豁免电池优化：息屏后也能继续接收回答",
                        "run 期间持有唤醒锁，任务结束即释放",
                    )
                } else {
                    listOf(
                        "未豁免：系统可能在息屏后冻结本应用，回答会卡住直到你点亮屏幕",
                        "点右侧「允许后台运行」，在系统弹框里选允许",
                    )
                },
                actionLabel = if (batteryExempt) "去系统设置确认" else "允许后台运行",
                onAction = {
                    val launched = requestIgnoreBatteryOptimizations(context)
                    batteryExempt = isIgnoringBatteryOptimizations(context)
                    if (!launched) {
                        notice = "本机无法打开电池优化设置，请到系统设置里手动允许随行自启动。"
                    }
                },
            )
            }

            item(key = "capability-9", contentType = "capability-card") {
                SuixingCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SoftIcon(ImageVector.vectorResource(R.drawable.ic_folder_cog), tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("所有文件访问权限", style = SettingsStyle.cardTitle)
                        Text(
                            text = when {
                                !supportsAllFiles ->
                                    "安卓 10 及以下没有这个开关，改用 Shizuku 或 root 访问 /sdcard"
                                allFiles -> "已授予，文件工具可直接读写 /sdcard"
                                else -> "未授予，文件工具只能访问应用私有目录"
                            },
                            style = SettingsStyle.description,
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
                            val opened = allFilesAccessIntents(context).any { intent ->
                                runCatching { allFilesLauncher.launch(intent) }.isSuccess
                            }
                            notice = if (opened) {
                                null
                            } else {
                                "本机无法打开「所有文件访问权限」设置，请改用 Shizuku 或 root 获取全盘访问。"
                            }
                        },
                    ) { Text("打开设置") }
                    TextButton(
                        onClick = { scope.launch { withContext(Dispatchers.IO) { Core.capabilities.refresh() } } },
                    ) {
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
            }

            item(key = "capability-10", contentType = "capability-card") {
                SectionCard(
                title = "这四档权限分别能做什么",
                icon = ImageVector.vectorResource(R.drawable.ic_circle_question_mark),
                subtitle = if (explainerOpen) "点右侧按钮收起" else "点右侧按钮展开",
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
                        style = SettingsStyle.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            }

            item(key = "capability-11", contentType = "capability-card") {
                Spacer(Modifier.height(10.dp))
            }
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
            Column(Modifier.padding(14.dp)) {
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
                            style = SettingsStyle.metadata,
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
                        style = SettingsStyle.cardTitle,
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
                    style = SettingsStyle.body,
                    color = scheme.onPrimary.copy(alpha = 0.9f),
                )
            }
        }
    }
}

/** 自选档位的一格：[key] 存进 [AppSettings.preferredCapability]，[ready] 决定它是否置灰。 */
private data class TierOption(val key: String, val label: String, val ready: Boolean)

/**
 * 用户自选执行档。
 *
 * 四档的可用性用的是和下面四张卡片同一套判据（[CapabilityState]），置灰只说明「现在
 * 还用不了」，不是禁用：点下去仍然记下这个选择，并说清该去下面哪张卡片点哪个按钮——
 * 手指落在这一档上必须有回音。再点一次已选中的那一档就回到自动，否则选出去就退不回来。
 */
@Composable
private fun CapabilityTierPicker(
    preferred: String,
    state: CapabilityState,
    settings: AppSettings,
    onPick: (String) -> Unit,
) {
    val options = listOf(
        // 标准档装上就有，永远可用；另外三档的判定与下面三张卡片的 granted 一一对应。
        TierOption(TIER_NONE, "标准档", true),
        TierOption(TIER_ACCESSIBILITY, "无障碍", state.accessibility),
        TierOption(TIER_SHELL, "Shizuku", state.shellReady),
        TierOption(TIER_ROOT, "Root", state.rootReady),
    )
    val missing = options.any { it.key == preferred && !it.ready }
    SuixingCard {
        Text("执行档次", style = SettingsStyle.cardTitle)
        Text(
            text = "选择随行用哪一档权限执行操作；更高的档位能做的事更多，需要先开启。" +
                "点已选中的那一档可回到自动。",
            style = SettingsStyle.description,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        // 统一用共享的 SegmentedPill（与设置页同一形状/配色/拖动反馈）：
        // 通过 optionEnabled 置灰未开启的档位，onReselect 让「点已选中的一档」回到自动。
        SegmentedPill(
            options = options.map { it.label },
            selectedIndex = options.indexOfFirst { it.key == preferred },
            onSelect = { index -> onPick(options[index].key) },
            optionEnabled = { index -> options[index].ready },
            onReselect = { onPick("") },
            // 只在这一页加深一档（用户定 0.10）：四格常带置灰，默认 0.08 的边界偏弱。
            trackTint = 0.10f,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = tierSummary(preferred, state),
            style = SettingsStyle.description,
            // 选中的那一档还没开出来时用 error 说话：点了有反应这件事得看得见。
            color = if (missing) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        // 「选一档看这一档解锁了什么」：条目和下面四张卡片读同一份 tierLines，不另写一套文案。
        // 没固定档（自动）时按「当前已经开出来的最高档」画，和上面那句摘要说的是同一档。
        val shown = if (preferred.isNotBlank()) {
            preferred
        } else {
            when {
                state.rootReady -> TIER_ROOT
                state.shellReady -> TIER_SHELL
                state.accessibility -> TIER_ACCESSIBILITY
                else -> TIER_NONE
            }
        }
        Spacer(Modifier.height(6.dp))
        tierLines(shown, state, settings).forEach { line ->
            Row(Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.outline),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    text = line,
                    style = SettingsStyle.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 一档权限解锁了什么，按档取。
 *
 * 上面选择器的明细和下面四张卡片都读这一份，两处不会再各说各的。[settings] 只有 Root 那一条
 * 用得上（上次检测时间），其余条目只看 [state]。
 */
private fun tierLines(key: String, state: CapabilityState, settings: AppSettings): List<String> = when (key) {
    TIER_NONE -> listOf(
        "聊天、记忆、待办、定时、技能、网络与文件工具都在这一档",
        "文件只能读写应用私有目录，碰不到 /sdcard 的其它目录",
        "要操作别的应用、执行命令或改系统文件，再往下开一档",
    )
    TIER_ACCESSIBILITY -> listOf(
        if (state.accessibility) {
            "已连接，ui_* 系列工具可用"
        } else if (state.shellReady || state.rootReady) {
            "未连接。ui_* 会走 Shizuku / root 的 input、screencap 兜底，但打不了中文"
        } else {
            "未连接，且没有 Shizuku / root 兜底，ui_* 系列工具会被拒绝执行"
        },
    )
    TIER_SHELL -> listOf(
        if (state.shizukuInstalled) "Shizuku 已安装" else "未检测到 Shizuku",
        if (state.shizukuAlive) "服务正在运行" else "服务未运行，需先启动 Shizuku",
        if (state.shizukuGranted) "随行已获授权" else "随行未获授权",
    )
    TIER_ROOT -> listOfNotNull(
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
    )
    else -> listOf("自动：按当前能用的最高档执行。")
}

/** 选中的那一档现在意味着什么；不可用时直接说清去下面哪张卡片点哪个按钮。 */
private fun tierSummary(preferred: String, state: CapabilityState): String = when (preferred) {
    TIER_NONE -> "已选标准档：只用应用自身权限，不需要任何授权。"
    TIER_ACCESSIBILITY -> if (state.accessibility) {
        "已选无障碍：ui_* 系列工具走无障碍服务。"
    } else {
        "无障碍还没开：先在下面「无障碍」卡片点「去开启」。"
    }
    TIER_SHELL -> if (state.shellReady) {
        "已选 Shizuku：shell 类操作走 ADB。"
    } else {
        "Shizuku 还没就绪：先在下面「Shizuku（ADB 级）」卡片点「${shizukuActionLabel(state)}」。"
    }
    TIER_ROOT -> if (state.rootReady) {
        "已选 Root：需要最高权限的操作走 su。"
    } else {
        "Root 还没就绪：先在下面「Root」卡片点「检测」，用 KernelSU / Magisk 给随行授权。"
    }
    else -> "自动：按当前已开启的最高档执行。点下面任意一档可以固定它。"
}

/** Shizuku 卡片的按钮文案，也是「先去哪开」那句话里引用的那一个：两处必须是同一个。 */
private fun shizukuActionLabel(state: CapabilityState): String = when {
    !state.shizukuInstalled -> "去安装"
    !state.shizukuGranted -> "请求授权"
    else -> "重新授权"
}

@Composable
private fun ExplainerRow(title: String, body: String) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(title, style = SettingsStyle.sectionTitle)
        Text(
            text = body,
            style = SettingsStyle.description,
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
    /** 卡片底部的额外内容：目前只有悬浮窗那张卡片用它放自己的第二个开关。 */
    footer: (@Composable () -> Unit)? = null,
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
                Text(title, style = SettingsStyle.cardTitle)
                Text(
                    text = summary,
                    style = SettingsStyle.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(
                text = if (granted) "已就绪" else "未就绪",
                tone = if (granted) Tone.OK else Tone.WARN,
                icon = if (granted) ImageVector.vectorResource(R.drawable.ic_circle_check) else null,
            )
        }
        Spacer(Modifier.height(8.dp))
        lines.forEach { line ->
            Row(Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.outline),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    text = line,
                    style = SettingsStyle.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (!actionEnabled) {
            OutlinedButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.height(36.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
            ) { Text(actionLabel) }
        } else if (granted) {
            OutlinedButton(
                onClick = onAction,
                modifier = Modifier.height(36.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
            ) { Text(actionLabel) }
        } else {
            Button(
                onClick = onAction,
                modifier = Modifier.height(36.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
            ) { Text(actionLabel) }
        }
        footer?.let {
            Spacer(Modifier.height(10.dp))
            it()
        }
    }
}

/**
 * 悬浮窗的第二个开关：运行时显示。
 *
 * 与抽屉那行分开写，因为两者问的不是同一件事：抽屉那一个是「常驻」（不在前台就一直在），
 * 这一个只在常驻关掉时才有话说 —— 不在前台且正在跑任务才出现，跑完自动收起。三句话必须一次
 * 说全，否则很容易被读成常驻的加强版。
 */
@Composable
private fun ConsoleRunningSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("运行时显示", style = SettingsStyle.sectionTitle)
            Text(
                text = "不在前台时，只有正在跑任务才显示悬浮球，跑完自动收起。" +
                    "常驻打开时球一直都在，不看这个开关；两个都关，后台完全不显示。前台一律不显示。",
                style = SettingsStyle.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        SettingsSwitch(checked = checked, onCheckedChange = onChange)
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

/** 本应用是否已豁免电池优化（「允许后台运行」）。 */
private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val power = context.getSystemService(PowerManager::class.java) ?: return false
    return power.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * 请求豁免电池优化。
 *
 * 优先用 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 直接弹系统确认框；个别 ROM 不认这个
 * action，就退回「电池优化设置列表」让用户自己找。返回是否成功打开了某个界面。
 */
private fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:" + context.packageName),
    )
    if (runCatching { context.startActivity(direct) }.isSuccess) return true
    return runCatching {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }.isSuccess
}
