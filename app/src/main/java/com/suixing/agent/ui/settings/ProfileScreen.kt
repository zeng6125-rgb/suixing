package com.suixing.agent.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height

import com.suixing.agent.ui.components.settingsPagePadding
import com.suixing.agent.ui.components.SettingsStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.suixing.agent.ui.components.CompactOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.suixing.agent.ui.components.SettingsSwitch
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.suixing.agent.agent.ProfileSummary
import com.suixing.agent.core.Core
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.PROFILE_MAX_TURNS
import com.suixing.agent.data.PROFILE_MIN_TURNS
import com.suixing.agent.data.PROFILE_STEP_TURNS
import com.suixing.agent.data.UserProfileStore
import com.suixing.agent.ui.components.PageTopBar
import com.suixing.agent.ui.components.SettingRow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 用户画像：模型自己维护的、关于这个人的长期摘要，存在 `filesDir/profile.md`。
 *
 * 它和记忆一样是提示词的一部分——保存后模型下一轮就看到——但两条链路的来路不同：记忆是
 * 模型在任务里主动调用工具记下的键值对（`memory.json`），这一篇是它隔几轮从对话里总结出来的
 * 一整块纯文本（[com.suixing.agent.agent.ProfileSummarizer]），所以是一篇可以整篇重写的
 * 自述，而不是一条条键值。用户仍然可以在这里手改，那是补充与纠正。
 */
@Composable
fun ProfileScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val stored by Core.profile.text.collectAsStateWithLifecycle()
    val summary by Core.profile.summary.collectAsStateWithLifecycle()
    // 这两行「自动整理」的开关原来在「插话与运行」页，现在跟着画像本身走（字段与存储没动）。
    val settings by Core.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    // null = 输入框还没被碰过，显示 store 里的值；一旦开始输入就以草稿为准，免得后台的
    // 一次 load 把用户正在写的东西冲掉。
    var draft by remember { mutableStateOf<String?>(null) }
    var summarizing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { Core.profile.load() }

    val text = draft ?: stored
    val over = text.length > UserProfileStore.MAX_LENGTH

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { PageTopBar(title = "用户画像", onBack = onBack) },
        snackbarHost = {
            SnackbarHost(snackbar, modifier = Modifier.navigationBarsPadding())
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(settingsPagePadding()),
            verticalArrangement = Arrangement.spacedBy(SettingsStyle.SectionGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (summary.lastProfileAt > 0) {
                        "由模型自动维护 · 最近更新 ${stamp(summary.lastProfileAt)}"
                    } else {
                        "由模型自动维护 · 尚未总结"
                    },
                    style = SettingsStyle.metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (summarizing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    TextButton(
                        onClick = {
                            // 先置位再 launch：这一位决定按钮换成转圈，置在协程里会留出一帧
                            // 连点窗口，两次 summarizeProfileNow 并发写同一份画像。
                            summarizing = true
                            scope.launch {
                                // 画像页可能先于会话页被打开：chats 还没 load 过就没有对话可总结。
                                if (Core.chats.conversations.value.isEmpty()) Core.chats.load()
                                // **列表里没有正文了**（#30：load() 只读 meta）⇒ 画像总结要自己读全文。
                                // 这里本来就要把整份对话喂给总结，readAll 是唯一正确的读法。
                                val newest = Core.chats.conversations.value.maxByOrNull { it.updatedAt }
                                val messages = newest?.let { Core.chats.readAll(it.id) }.orEmpty()
                                val outcome = Core.engine.summarizeProfileNow(
                                    store = Core.profile,
                                    settings = Core.settings.current(),
                                    messages = messages,
                                )
                                summarizing = false
                                snackbar.showSnackbar(
                                    when (outcome) {
                                        ProfileSummary.Updated -> "画像已更新"
                                        ProfileSummary.Unchanged -> "没有新信息，画像未变"
                                        is ProfileSummary.Failed -> outcome.reason
                                    },
                                )
                            }
                        },
                    ) { Text("重新总结") }
                }
            }
            Text(
                text = "模型会自动维护这段画像；你也可以在这里手动补充或纠正。" +
                    "它随每次请求发给模型，写称呼、偏好、长期习惯这类关于你本人的长期信息，" +
                    "不要写一次性任务。",
                style = SettingsStyle.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                textStyle = SettingsStyle.fieldText,
                value = text,
                onValueChange = { draft = it },
                label = { Text("画像") },
                placeholder = { Text("例如：叫我老王；住杭州；回复尽量短，别客套。") },
                isError = over,
                minLines = 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 112.dp, max = 360.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${text.length} / ${UserProfileStore.MAX_LENGTH}",
                        style = SettingsStyle.badge,
                        color = if (over) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (over) {
                        Text(
                            text = "超出 ${text.length - UserProfileStore.MAX_LENGTH} 字，请精简后再保存",
                            style = SettingsStyle.badge,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Button(
                    enabled = !over,
                    onClick = {
                        scope.launch {
                            val ok = Core.profile.write(text)
                            // 写失败时草稿留在输入框里：把用户的字换成盘上的旧值才是更糟的失败。
                            if (ok) draft = null
                            snackbar.showSnackbar(if (ok) "已保存" else "保存失败")
                        }
                    },
                ) { Text("保存") }
            }
            // 「自动整理」原来在设置→插话与运行页。它管的是这一篇画像怎么维护（每几轮重跑一次
            // 总结），放在画像页比放在「数据」页合适：那一页是纯入口列表，一行一个跳转，
            // 塞开关会破坏它的形态。
            Spacer(Modifier.height(SettingsStyle.SectionGap))
            Text(
                text = "自动整理",
                style = SettingsStyle.cardTitle,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
            )
            SettingRow(
                title = "自动整理用户画像",
                subtitle = "每几轮之后，用这个会话的端点单独跑一次总结，把关于用户这个人的长期信息" +
                    "写进画像。与记忆不同：记忆是模型在任务里主动记下的键值，画像是它定期从对话里" +
                    "读出来的滚动摘要。总结失败不打扰你，也不改动已有画像。",
                control = {
                    SettingsSwitch(
                        checked = settings.autoProfile,
                        onCheckedChange = { on -> scope.persist { it.copy(autoProfile = on) } },
                    )
                },
            )
            SettingRow(
                title = "画像整理间隔",
                subtitle = "每这么多轮整理一次；一轮 = 一次跑完的运行，跨会话累计。" +
                    (if (settings.autoProfile) "" else "（自动整理已关闭，这一项暂不生效。）"),
                control = {
                    // 与压缩阈值同款的 −/+ 步进：关掉开关也留着这一行，只是置灰。
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.alpha(if (settings.autoProfile) 1f else 0.38f),
                    ) {
                        IconButton(
                            enabled = settings.autoProfile,
                            onClick = {
                                scope.persist { s ->
                                    s.copy(
                                        profileEveryTurns =
                                            (s.profileTurnThreshold - PROFILE_STEP_TURNS)
                                                .coerceIn(PROFILE_MIN_TURNS, PROFILE_MAX_TURNS),
                                    )
                                }
                            },
                        ) { Text("−", style = MaterialTheme.typography.titleLarge) }
                        Text(
                            text = "${settings.profileTurnThreshold} 轮",
                            style = SettingsStyle.rowTitle,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(min = 44.dp),
                        )
                        IconButton(
                            enabled = settings.autoProfile,
                            onClick = {
                                scope.persist { s ->
                                    s.copy(
                                        profileEveryTurns =
                                            (s.profileTurnThreshold + PROFILE_STEP_TURNS)
                                                .coerceIn(PROFILE_MIN_TURNS, PROFILE_MAX_TURNS),
                                    )
                                }
                            },
                        ) { Text("+", style = MaterialTheme.typography.titleLarge) }
                    }
                },
            )
        }
    }
}

/**
 * 「最近更新」的时间戳：今天的只给时分（12:03），更早的带上月日——只写 12:03 会让一周前的
 * 一次总结看起来像刚发生的。
 */
// 两种 pattern 各留一个（ThreadLocal 保线程安全）：原来每次调用都 new 一个
// SimpleDateFormat（要解析 pattern）。输出逐字节相同。
private val SAME_DAY_STAMP = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("HH:mm", Locale.getDefault())
}
private val OTHER_DAY_STAMP = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())
}

private fun stamp(at: Long): String {
    val calendar = Calendar.getInstance()
    val today = calendar.get(Calendar.DAY_OF_YEAR)
    val year = calendar.get(Calendar.YEAR)
    calendar.timeInMillis = at
    val sameDay = calendar.get(Calendar.YEAR) == year && calendar.get(Calendar.DAY_OF_YEAR) == today
    val format = if (sameDay) SAME_DAY_STAMP.get()!! else OTHER_DAY_STAMP.get()!!
    return format.format(Date(at))
}
