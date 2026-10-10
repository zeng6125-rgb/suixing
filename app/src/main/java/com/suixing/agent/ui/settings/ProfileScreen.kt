package com.suixing.agent.ui.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import com.suixing.agent.agent.ProfileSummary
import com.suixing.agent.core.Core
import com.suixing.agent.data.UserProfileStore
import com.suixing.agent.ui.components.PageTopBar
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
    val stored by Core.profile.text.collectAsState()
    val summary by Core.profile.summary.collectAsState()
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
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
                    style = MaterialTheme.typography.labelMedium,
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
                                val messages = Core.chats.conversations.value
                                    .firstOrNull()?.messages.orEmpty()
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
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { draft = it },
                label = { Text("画像") },
                placeholder = { Text("例如：叫我老王；住杭州；回复尽量短，别客套。") },
                isError = over,
                minLines = 8,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 360.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${text.length} / ${UserProfileStore.MAX_LENGTH}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (over) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (over) {
                        Text(
                            text = "超出 ${text.length - UserProfileStore.MAX_LENGTH} 字，请精简后再保存",
                            style = MaterialTheme.typography.labelSmall,
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
        }
    }
}

/**
 * 「最近更新」的时间戳：今天的只给时分（12:03），更早的带上月日——只写 12:03 会让一周前的
 * 一次总结看起来像刚发生的。
 */
private fun stamp(at: Long): String {
    val calendar = Calendar.getInstance()
    val today = calendar.get(Calendar.DAY_OF_YEAR)
    val year = calendar.get(Calendar.YEAR)
    calendar.timeInMillis = at
    val sameDay = calendar.get(Calendar.YEAR) == year && calendar.get(Calendar.DAY_OF_YEAR) == today
    val pattern = if (sameDay) "HH:mm" else "M月d日 HH:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(at))
}
