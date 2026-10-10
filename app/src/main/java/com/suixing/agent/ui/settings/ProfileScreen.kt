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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.unit.dp
import com.suixing.agent.core.Core
import com.suixing.agent.data.UserProfileStore
import com.suixing.agent.ui.components.PageTopBar
import kotlinx.coroutines.launch

/**
 * 用户画像：一份用户写给模型的长期自述，存在 `filesDir/profile.md`。
 *
 * 它和记忆一样是提示词的一部分——保存后模型下一轮就看到——但写的人不同：记忆是模型
 * 自己记的，这一篇是用户自己说的，所以是一整块纯文本，而不是一条条键值。
 */
@Composable
fun ProfileScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val stored by Core.profile.text.collectAsState()
    // null = 输入框还没被碰过，显示 store 里的值；一旦开始输入就以草稿为准，免得后台的
    // 一次 load 把用户正在写的东西冲掉。
    var draft by remember { mutableStateOf<String?>(null) }

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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "这段文字会随每次请求发给模型；建议写称呼/偏好/长期习惯，不要写一次性任务。",
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
