package com.suixing.agent.ui.settings

import com.suixing.agent.ui.components.settingsPagePadding
import com.suixing.agent.ui.components.SettingsStyle
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import com.suixing.agent.ui.components.SuixingDialog
import com.suixing.agent.data.AppLinks
import com.suixing.agent.data.ReleaseChecker
import com.suixing.agent.data.ReleaseInfo
import com.suixing.agent.data.ReleaseRelation
import com.suixing.agent.data.releaseRelation
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.suixing.agent.R
import com.suixing.agent.ui.components.PageTopBar
import com.suixing.agent.ui.components.RowChevron
import com.suixing.agent.ui.components.SettingRow
import com.suixing.agent.ui.components.SoftIcon
import com.suixing.agent.ui.components.SuixingCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 关于：版本、这个应用是什么、密钥放在哪、以及用到的开源件与参考项目。
 *
 * 单独一页而不是塞在数据页末尾：这些是「读一次就够」的说明文字，和数据页那些
 * 「点进去要动手」的入口不是一回事。图标许可声明按 ISC 的要求必须留在应用里，
 * 这一页就是它唯一的出处。
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // getPackageInfo 是 binder 调用：放在组合期（主线程）读会在冷启动那一帧上排队，所以先给
    // 占位的 "?"，版本号从 IO 回来再填。读一次就够：版本号在进程活着的时候不会变。
    val version by produceState(initialValue = "?") {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull().orEmpty().ifBlank { "?" }
        }
    }

    val scope = rememberCoroutineScope()
    val checker = remember { ReleaseChecker() }
    var checking by remember { mutableStateOf(false) }
    var showUpdate by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<ReleaseInfo?>(null) }
    var updateMessage by remember { mutableStateOf("") }
    var updateAvailable by remember { mutableStateOf(false) }
    fun checkUpdates() {
        if (checking || version == "?") return
        checking = true
        scope.launch {
            try {
                val info = checker.latest()
                updateInfo = info
                val relation = info?.let { releaseRelation(version, it.version) }
                updateAvailable = relation == ReleaseRelation.UPDATE_AVAILABLE
                updateMessage = when (relation) {
                    ReleaseRelation.UPDATE_AVAILABLE -> "当前版本 v$version，可更新至 ${info?.version}。"
                    ReleaseRelation.CURRENT -> "当前已是最新正式版本 v$version。"
                    ReleaseRelation.LOCAL_AHEAD -> "当前 v$version，已领先最新正式版 ${info?.version}。"
                    null -> "暂时没有可供检查的正式版本。"
                }
                showUpdate = true
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                updateInfo = null
                updateAvailable = false
                updateMessage = "检查更新失败：${failure.message ?: "请检查网络后重试"}"
                showUpdate = true
            } finally { checking = false }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { PageTopBar(title = "关于", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(settingsPagePadding()),
            verticalArrangement = Arrangement.spacedBy(SettingsStyle.SectionGap),
        ) {
            SuixingCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SoftIcon(ImageVector.vectorResource(R.drawable.ic_bot))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("随行", style = SettingsStyle.cardTitle)
                        Text(
                            text = "v$version",
                            style = SettingsStyle.description,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "跟着你走的手机智能体：说一句话，它就能读屏、点按、跑命令、翻文件。",
                    style = SettingsStyle.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SuixingCard {
                SettingRow(title = "检查更新", subtitle = if (checking) "正在查询最新正式版本…" else "查看最新正式版本与更新说明",
                    onClick = { checkUpdates() },
                    control = { if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else RowChevron() })
                AboutLink("问题反馈", "填写复现步骤，提交前可自行检查内容", AppLinks.feedback(version))
                AboutLink("源代码仓库", "源码、构建说明与项目文档", AppLinks.REPOSITORY)
            }

            SuixingCard {
                Text("隐私", style = SettingsStyle.cardTitle)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "回答由你自己配置的模型服务提供；对话、记忆与密钥都只留在这台手机上。",
                    style = SettingsStyle.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_badge_check),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.height(15.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "API Key 只保存在本机应用私有目录，不会上传到任何第三方服务。",
                        style = SettingsStyle.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SuixingCard {
                Text("开源与致谢", style = SettingsStyle.cardTitle)
                Spacer(Modifier.height(8.dp))
                Text(
                    // Lucide 是 ISC 许可：它要求保留版权与许可声明，这一页是 App 唯一的出处说明。
                    text = "图标：Lucide（ISC License）",
                    style = SettingsStyle.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                AboutLink(
                    title = "Aether 扶摇",
                    subtitle = "配色与排版的参考",
                    url = "https://github.com/Zhou-Shilin/Aether",
                )
                AboutLink(
                    title = "Operit",
                    subtitle = "能力清单与交互约定的参考",
                    url = "https://github.com/AAswordman/Operit",
                )
                AboutLink(
                    title = "Model Context Protocol",
                    subtitle = "MCP 协议规范",
                    url = "https://modelcontextprotocol.io/",
                )
            }

            // 随包分发的 GPL 组件（自带 shell）。GPL 要求分发二进制时给出对应源码的获取途径，
            // 这一页就是那个"书面承诺"的落点：组件、版本、许可、以及**本应用的源码仓库**
            // （仓库里 app/src/main/jniLibs/arm64-v8a/ 下的两个文件就是这两个二进制的原样拷贝，
            // 上游源码与构建方式一并写在下面）。
            SuixingCard {
                Text(
                    text = "随包分发的开源组件（GPL）",
                    style = SettingsStyle.cardTitle,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "为了在手机上有一个完整的 shell，本应用自带两个静态编译的 AArch64 二进制" +
                        "（随 APK 分发，不是下载的）：",
                    style = SettingsStyle.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "· bash 5.3 —— GPL-3.0-or-later（来自 Debian bash-static）\n" +
                        "· BusyBox 1.38.0 —— GPL-2.0-only（来自 Debian busybox-static）",
                    style = SettingsStyle.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "对应源码：https://salsa.debian.org/debian/bash 与 " +
                        "https://salsa.debian.org/debian/busybox（或任一 Debian 镜像的 " +
                        "bash-static / busybox-static 源码包）。如需书面提供源码，按仓库里的 " +
                        "issue 或邮件索取即可。",
                    style = SettingsStyle.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                AboutLink(
                    title = "构建说明与文档",
                    subtitle = "如何从源码自己编译出这个应用",
                    url = "https://github.com/zeng6125-rgb/suixing#readme",
                )
            }

        }
    }
    if (showUpdate) SuixingDialog(
        onDismissRequest = { showUpdate = false },
        title = { Text(if (updateAvailable) "发现新版本" else "检查更新") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(updateMessage, style = SettingsStyle.body)
                val info = updateInfo
                if (updateAvailable && info != null && info.notes.isNotBlank()) {
                    Text(info.notes.take(2000), style = SettingsStyle.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { dialog ->
            val info = updateInfo
            TextButton(onClick = { dialog.closeThen {
                showUpdate = false
                if (info != null) openAboutUrl(context, info.url)
            } }) { Text(if (info == null) "知道了" else if (updateAvailable) "前往下载" else "查看发布页") }
        },
        dismissButton = { dialog ->
            if (updateInfo != null) TextButton(onClick = { dialog.closeThen { showUpdate = false } }) { Text("关闭") }
        },
    )

}

/** 一行外链：点开交给系统浏览器，打不开就明说。 */
@Composable
private fun AboutLink(title: String, subtitle: String, url: String) {
    val context = LocalContext.current
    // 行本身只有一份实现（Common.SettingRow）：几何、字号、行高都在那里。
    SettingRow(
        title = title,
        subtitle = subtitle,
        onClick = { openAboutUrl(context, url) },
        control = { RowChevron() },
    )
}

private fun openAboutUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { Toast.makeText(context, "打不开链接：设备上没有可用的浏览器", Toast.LENGTH_SHORT).show() }
}
