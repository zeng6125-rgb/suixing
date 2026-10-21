package com.suixing.agent.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.suixing.agent.R
import com.suixing.agent.core.Core
import com.suixing.agent.service.FloatConsoleService
import com.suixing.agent.ui.components.SectionCard
import com.suixing.agent.ui.components.StatusPill
import com.suixing.agent.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 第四页：权限——按**用户旅程**讲，不按系统权限清单讲。
 *
 * 技术清单（「无障碍 / 通知 / 电池优化 / 悬浮窗」）对懂行的人友好，对绝大多数用户是黑话。
 * 所以每一段都写成三句：**要做什么 → 需要什么 → 缺了会怎样**，中间那一句才提「需要开哪个
 * 权限」——用户知道自己要什么之后，权限名才有意义。
 *
 * 四个开启动作全部走 [Core.capabilities]（与「能力」页同一套实现），不另写一份跳转逻辑。
 * 每一项都可以跳过：不开只是不能做那一类事，聊天照常。
 */
@Composable
fun PermissionPage() {
    val state by Core.capabilities.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var probing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    // 悬浮窗授权在系统设置里决定，所以回来时要重读一遍，不能记在本地状态里。
    var overlayGranted by remember { mutableStateOf(FloatConsoleService.canDrawOverlays(context)) }
    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { overlayGranted = FloatConsoleService.canDrawOverlays(context) }

    // 状态是系统给的，回到这一页时重读一次（无障碍服务、Shizuku 都可能在设置里被改过）。
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Core.capabilities.refresh() } }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text("让它能替你做事", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "聊天只要一个 Key。下面这些是让它**动手**需要的——每一项都可以以后再开，不开只是少一类活儿。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        PermissionCard(
            icon = ImageVector.vectorResource(R.drawable.ic_pointer),
            want = "想让它看着屏幕动手",
            need = "无障碍服务：读屏、点按钮、滑动、替你打字、截图",
            without = "不开就只有聊天——它看不见你的屏幕，也点不了任何东西。",
            granted = state.accessibility,
            actionLabel = if (state.accessibility) "已开启" else "去开启",
            actionEnabled = !state.accessibility,
            onAction = { Core.capabilities.openAccessibilitySettings() },
        )

        PermissionCard(
            icon = ImageVector.vectorResource(R.drawable.ic_terminal),
            want = "想让它执行命令、管别的应用",
            need = "Shizuku：相当于把手机接上电脑用 adb（adb / input / 读写 Android/data）",
            without = "不开它就只能在应用自己的目录里干活，碰不到别的应用。",
            granted = state.shellReady,
            actionLabel = if (state.shellReady) "已就绪" else "去开启",
            actionEnabled = !state.shellReady,
            onAction = {
                when {
                    !state.shizukuInstalled -> notice =
                        "本机没装 Shizuku。先装好（包名 moe.shizuku.privileged.api），再回来看这一项。"
                    Core.capabilities.requestShizuku() -> notice = null
                    else -> {
                        Core.capabilities.openShizukuApp()
                        notice = "Shizuku 服务没在跑，已为你打开它的应用：在里面启动服务，再回来点一次「去开启」。"
                    }
                }
            },
        )

        PermissionCard(
            icon = ImageVector.vectorResource(R.drawable.ic_shield_user),
            want = "想让它碰系统文件",
            need = "Root：系统分区可写，最高权限",
            without = "不开照样能用——只是它到不了系统目录。",
            granted = state.rootReady,
            actionLabel = if (state.rootReady) "已就绪" else "检测",
            actionEnabled = !probing,
            onAction = {
                scope.launch {
                    probing = true
                    Core.capabilities.probeRoot()
                    probing = false
                }
            },
        )

        PermissionCard(
            icon = ImageVector.vectorResource(R.drawable.ic_picture_in_picture_2),
            want = "想让它跑起来时能随时叫停、能插话",
            need = "悬浮窗：屏幕上留一个球",
            without = "不开就没有那个球——任务跑起来时你只能回来看。",
            granted = overlayGranted,
            actionLabel = if (overlayGranted) "已授权" else "去授权",
            actionEnabled = !overlayGranted,
            onAction = {
                overlayLauncher.launch(FloatConsoleService.overlayPermissionIntent(context))
            },
        )

        if (notice != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = notice.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "这些开关之后都能在「设置 → 权限」里改，档次（只用应用自己 / 无障碍 / Shizuku / root）也在那儿定。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
    }
}

/**
 * 一项权限，写成「要做什么 → 需要什么 → 缺了会怎样」，右边一个状态与一个开启动作。
 */
@Composable
private fun PermissionCard(
    icon: ImageVector,
    want: String,
    need: String,
    without: String,
    granted: Boolean,
    actionLabel: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
) {
    SectionCard(
        title = want,
        icon = icon,
        subtitle = need,
    ) {
        Text(
            text = without,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(
                text = if (granted) "已就绪" else "未开启",
                tone = if (granted) Tone.OK else Tone.OFF,
            )
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                enabled = actionEnabled,
                onClick = onAction,
            ) { Text(actionLabel) }
        }
    }
    Spacer(Modifier.height(10.dp))
}
