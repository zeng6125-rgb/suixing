package com.suixing.agent.ui.settings

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
import androidx.compose.foundation.layout.navigationBarsPadding
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { PageTopBar(title = "关于", onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SuixingCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SoftIcon(ImageVector.vectorResource(R.drawable.ic_bot))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("随行", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "v$version",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "跟着你走的手机智能体：说一句话，它就能读屏、点按、跑命令、翻文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SuixingCard {
                Text("隐私", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "回答由你自己配置的模型服务提供；对话、记忆与密钥都只留在这台手机上。",
                    style = MaterialTheme.typography.bodySmall,
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
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SuixingCard {
                Text("开源与致谢", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text(
                    // Lucide 是 ISC 许可：它要求保留版权与许可声明，这一页是 App 唯一的出处说明。
                    text = "图标：Lucide（ISC License）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                AboutLink(
                    title = "Aether 扶摇",
                    subtitle = "配色与排版的参考",
                    url = "https://github.com/Zhou-Shilin/Aether",
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AboutLink(
                    title = "Operit",
                    subtitle = "能力清单与交互约定的参考",
                    url = "https://github.com/AAswordman/Operit",
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AboutLink(
                    title = "Model Context Protocol",
                    subtitle = "MCP 协议规范",
                    url = "https://modelcontextprotocol.io/",
                )
            }

        }
    }
}

/** 一行外链：点开交给系统浏览器，打不开就明说。 */
@Composable
private fun AboutLink(title: String, subtitle: String, url: String) {
    val context = LocalContext.current
    // 行本身只有一份实现（Common.SettingRow）：几何、字号、行高都在那里。
    SettingRow(
        title = title,
        subtitle = subtitle,
        onClick = {
            // 设备上没有浏览器时 startActivity 会抛 ActivityNotFoundException：以前这里只
            // runCatching，异常被吞掉、屏幕一动不动，用户以为是自己没点中。
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                .onFailure {
                    // 说出原因：这条路径上失败几乎只有「没有应用能处理这个链接」一种。
                    Toast.makeText(context, "打不开链接：设备上没有可用的浏览器", Toast.LENGTH_SHORT).show()
                }
        },
        control = { RowChevron() },
    )
}
