package com.lingshu.agent.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lingshu.agent.core.Core
import com.lingshu.agent.data.AppSettings
import com.lingshu.agent.data.ApprovalMode
import com.lingshu.agent.llm.ChatMessage
import com.lingshu.agent.llm.LlmClient
import com.lingshu.agent.llm.describeError
import com.lingshu.agent.ui.components.LingshuCard
import com.lingshu.agent.ui.components.LingshuTopBar
import com.lingshu.agent.ui.components.SectionCard
import com.lingshu.agent.ui.components.SegmentedPill
import com.lingshu.agent.ui.components.SettingRow
import com.lingshu.agent.ui.theme.LocalDarkTheme
import com.lingshu.agent.ui.theme.Tone
import com.lingshu.agent.ui.theme.color
import kotlinx.coroutines.launch

/** Outcome of a connection test, shown in the fixed bar above the buttons. */
private data class TestOutcome(val ok: Boolean, val message: String)

@Composable
fun SettingsScreen() {
    val stored by Core.settings.flow.collectAsState(initial = AppSettings())
    var form by remember(stored) { mutableStateOf(stored) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val client = remember { LlmClient() }
    var testing by remember { mutableStateOf(false) }
    var loadingModels by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var pickerOpen by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<TestOutcome?>(null) }
    val dirty = form != stored

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LingshuTopBar(
                title = "设置",
                subtitle = if (dirty) "有未保存的改动" else "模型 · 权限 · 外观",
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 8.dp, bottom = 12.dp),
                ) {
                    outcome?.let { current ->
                        OutcomeCard(current) { outcome = null }
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    Core.settings.save(form)
                                    snackbar.showSnackbar("已保存")
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text(if (dirty) "保存改动" else "保存") }
                        OutlinedButton(
                            enabled = !testing,
                            onClick = {
                                scope.launch {
                                    testing = true
                                    outcome = null
                                    Core.settings.save(form)
                                    outcome = runCatching {
                                        client.complete(
                                            settings = form,
                                            messages = listOf(ChatMessage.user("你好，请只回复两个字：可用")),
                                            tools = emptyList(),
                                        )
                                    }.fold(
                                        onSuccess = {
                                            TestOutcome(true, "连接成功，模型回了：${it.text.trim().take(60)}")
                                        },
                                        onFailure = { TestOutcome(false, describeError(it)) },
                                    )
                                    testing = false
                                }
                            },
                        ) {
                            if (testing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.height(16.dp).padding(end = 8.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                            Text(if (testing) "测试中" else "测试连接")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            SectionCard(
                title = "模型",
                icon = Icons.Outlined.SmartToy,
                subtitle = "OpenAI 兼容接口",
            ) {
                OutlinedTextField(
                    value = form.baseUrl,
                    onValueChange = { form = form.copy(baseUrl = it) },
                    label = { Text("接口地址") },
                    supportingText = { Text("例如 https://api.deepseek.com/v1") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                SettingRow(
                    title = "允许自签名证书",
                    subtitle = "接口是 https 且证书自签时打开。局域网上的反代（例如 " +
                        "https://192.168.0.50:8914/v1）基本都要开，开了就不再校验证书与域名。",
                    control = {
                        Switch(
                            checked = form.allowInsecureTls,
                            onCheckedChange = { form = form.copy(allowInsecureTls = it) },
                        )
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = form.apiKey,
                    onValueChange = { form = form.copy(apiKey = it) },
                    label = { Text("API Key") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = form.model,
                    onValueChange = { form = form.copy(model = it) },
                    label = { Text("模型") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        enabled = !loadingModels,
                        onClick = {
                            scope.launch {
                                loadingModels = true
                                outcome = null
                                runCatching { client.listModels(form) }.fold(
                                    onSuccess = {
                                        models = it
                                        pickerOpen = true
                                    },
                                    onFailure = { outcome = TestOutcome(false, describeError(it)) },
                                )
                                loadingModels = false
                            }
                        },
                    ) {
                        if (loadingModels) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(if (loadingModels) "获取中…" else "获取模型列表")
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "从接口的 /models 拉一份可用模型，省得手打；也可以直接填。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                SettingRow(
                    title = "支持图片输入",
                    subtitle = "截图会随消息发给模型，需要视觉模型；纯文字模型开着会直接报错。",
                    control = {
                        Switch(
                            checked = form.imageInput,
                            onCheckedChange = { form = form.copy(imageInput = it) },
                        )
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LabeledSlider(
                    label = "温度 ${"%.1f".format(form.temperature)}",
                    hint = "越高越随机，日常 0.3 – 0.8",
                    value = form.temperature.toFloat(),
                    range = 0f..2f,
                    steps = 0,
                    onValueChange = { form = form.copy(temperature = it.toDouble()) },
                )
                LabeledSlider(
                    label = "最大工具轮数 ${form.maxToolRounds}",
                    hint = "一次任务里最多连续调用多少次工具",
                    value = form.maxToolRounds.toFloat(),
                    range = 1f..40f,
                    steps = 38,
                    onValueChange = { form = form.copy(maxToolRounds = it.toInt()) },
                )
            }

            SectionCard(
                title = "联网搜索",
                icon = Icons.Outlined.TravelExplore,
                subtitle = "web_search 走哪里查",
            ) {
                OutlinedTextField(
                    value = form.searchEndpoint,
                    onValueChange = { form = form.copy(searchEndpoint = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索接口（可留空）") },
                    placeholder = { Text("https://api.tavily.com/search") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    supportingText = {
                        Text("留空时用内置的 DuckDuckGo 抓取，容易被限流；填 Tavily 兼容接口更稳。")
                    },
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = form.searchApiKey,
                    onValueChange = { form = form.copy(searchApiKey = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索接口 Key（可留空）") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }

            SectionCard(
                title = "语音",
                icon = Icons.Outlined.RecordVoiceOver,
                subtitle = "输入与朗读",
            ) {
                SettingRow(
                    title = "朗读回复",
                    subtitle = "用系统语音引擎读出来。引擎状态：${Core.speaker.languageLabel()}。" +
                        "输入框左边的麦克风按钮负责语音输入。",
                    control = {
                        Switch(
                            checked = form.speakReplies,
                            onCheckedChange = { form = form.copy(speakReplies = it) },
                        )
                    },
                )
                if (form.speakReplies) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { Core.speaker.speak("你好，我是灵枢。") }) {
                            Text("试听")
                        }
                        OutlinedButton(onClick = { Core.speaker.stop() }) { Text("停止朗读") }
                    }
                }
            }

            SectionCard(
                title = "工具审批",
                icon = Icons.Outlined.VerifiedUser,
                subtitle = "模型每次动手前要不要问你",
            ) {
                SegmentedPill(
                    options = ApprovalMode.entries.map { modeLabel(it) },
                    selectedIndex = ApprovalMode.entries.indexOf(form.approvalMode),
                    onSelect = { form = form.copy(approvalMode = ApprovalMode.entries[it]) },
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = when (form.approvalMode) {
                        ApprovalMode.AUTO -> "模型可以连续调用工具，不再打断你。"
                        ApprovalMode.ASK -> "每次调用工具前都会问你一次，可以选「总是允许」。"
                        ApprovalMode.DENY -> "禁止一切工具调用，只能聊天。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (form.approvalMode == ApprovalMode.ASK) {
                    TextButton(
                        onClick = {
                            Core.gate.forgetAlways()
                            scope.launch { snackbar.showSnackbar("已清除「总是允许」的记忆") }
                        },
                    ) { Text("清除「总是允许」的记忆") }
                }
            }

            SectionCard(
                title = "外观",
                icon = Icons.Outlined.Palette,
                subtitle = "主题与配色",
            ) {
                SegmentedPill(
                    options = listOf("跟随系统", "浅色", "深色"),
                    selectedIndex = form.themeMode.coerceIn(0, 2),
                    onSelect = { form = form.copy(themeMode = it) },
                )
                Spacer(Modifier.height(12.dp))
                SegmentedPill(
                    options = listOf("灵枢配色", "跟随壁纸"),
                    selectedIndex = form.paletteMode.coerceIn(0, 1),
                    onSelect = { form = form.copy(paletteMode = it) },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "「跟随壁纸」需要安卓 12 以上，会改用系统动态取色。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(
                title = "高级",
                icon = Icons.Outlined.Tune,
                subtitle = "风险操作",
            ) {
                SettingRow(
                    title = "允许 root 工具",
                    subtitle = "开启后 run_shell 可以走 su 执行命令，风险自负。",
                    control = {
                        Switch(
                            checked = form.enableRoot,
                            onCheckedChange = { form = form.copy(enableRoot = it) },
                        )
                    },
                )
            }

            SectionCard(
                title = "系统提示词",
                icon = Icons.Outlined.EditNote,
                subtitle = "模型的人设与工作方式",
            ) {
                OutlinedTextField(
                    value = form.systemPrompt,
                    onValueChange = { form = form.copy(systemPrompt = it) },
                    minLines = 6,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { form = form.copy(systemPrompt = AppSettings().systemPrompt) },
                ) { Text("恢复默认") }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.VerifiedUser,
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
            Spacer(Modifier.height(8.dp))
        }
    }

    if (pickerOpen && models.isNotEmpty()) {
        ModelPickerDialog(
            models = models,
            current = form.model,
            onPick = {
                form = form.copy(model = it)
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

/** Result of a connection test or a model-list fetch, pinned above the buttons. */
@Composable
private fun OutcomeCard(outcome: TestOutcome, onDismiss: () -> Unit) {
    val tint = if (outcome.ok) Tone.OK.color(LocalDarkTheme.current) else Tone.WARN.color(LocalDarkTheme.current)
    LingshuCard(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = if (outcome.ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = outcome.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "关闭",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

/** Picks one of the ids the endpoint reported, with a filter for long lists. */
@Composable
private fun ModelPickerDialog(
    models: List<String>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(models, query) {
        if (query.isBlank()) models else models.filter { it.contains(query.trim(), ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择模型") },
        text = {
            Column {
                if (models.size > 8) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("筛选") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (shown.isEmpty()) {
                    Text(
                        text = "没有匹配的模型。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(shown) { id ->
                            val selected = id == current
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.small)
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                    )
                                    .clickable { onPick(id) }
                                    .padding(horizontal = 12.dp, vertical = 11.dp),
                            ) {
                                Text(
                                    text = id,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                if (selected) {
                                    Icon(
                                        imageVector = Icons.Outlined.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(17.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    hint: String? = null,
) {
    Column(Modifier.padding(top = 10.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
        )
    }
}

private fun modeLabel(mode: ApprovalMode): String = when (mode) {
    ApprovalMode.AUTO -> "自动允许"
    ApprovalMode.ASK -> "每次询问"
    ApprovalMode.DENY -> "禁止"
}
