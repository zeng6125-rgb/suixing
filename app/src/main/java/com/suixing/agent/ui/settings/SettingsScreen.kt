package com.suixing.agent.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suixing.agent.core.Core
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.ProviderConfig
import com.suixing.agent.data.groupModels
import com.suixing.agent.llm.ChatMessage
import com.suixing.agent.llm.LlmClient
import com.suixing.agent.llm.describeError
import com.suixing.agent.ui.components.SuixingCard
import com.suixing.agent.ui.components.SuixingTopBar
import com.suixing.agent.ui.components.SectionCard
import com.suixing.agent.ui.components.SegmentedPill
import com.suixing.agent.ui.components.SettingRow
import com.suixing.agent.ui.theme.LocalDarkTheme
import com.suixing.agent.ui.theme.Tone
import com.suixing.agent.ui.theme.color
import kotlinx.coroutines.launch

/** Outcome of a connection test, shown in the fixed bar above the buttons. */
private data class TestOutcome(val ok: Boolean, val message: String)

/**
 * The values the OpenAI-compatible `reasoning_effort` field accepts. The empty
 * string means "say nothing", which leaves the model on its own default.
 */
private val REASONING_LEVELS = listOf(
    "" to "默认",
    "low" to "低",
    "medium" to "中",
    "high" to "高",
)

/** Rewrites one saved endpoint in place; a null or unknown id is a no-op. */
private fun AppSettings.mapProvider(id: String?, block: (ProviderConfig) -> ProviderConfig): AppSettings {
    if (id == null || providers.none { it.id == id }) return this
    return copy(providers = providers.map { if (it.id == id) block(it) else it })
}

/**
 * Everything that used to be scattered across its own screen or dropdown, in one page.
 *
 * [onOpen] is how a row that is really another screen gets there — MCP, memory and the
 * capability list each keep their own page, because they are lists the user manages,
 * not settings the user toggles.
 */
@Composable
fun SettingsScreen(onOpen: (String) -> Unit) {
    val stored by Core.settings.flow.collectAsState(initial = AppSettings())
    var form by remember(stored) { mutableStateOf(stored) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val client = remember { LlmClient() }
    var testing by remember { mutableStateOf(false) }
    var loadingModels by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<TestOutcome?>(null) }
    var selectedId by remember(stored) {
        mutableStateOf(stored.activeProvider()?.id ?: stored.providers.firstOrNull()?.id.orEmpty())
    }
    val dirty = form != stored
    // The endpoint the model fields edit; tapping a chip also makes it the active one.
    val selected = form.providers.firstOrNull { it.id == selectedId } ?: form.providers.firstOrNull()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            SuixingTopBar(
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
                title = "模型与生成",
                icon = Icons.Rounded.SmartToy,
                subtitle = "接口、模型与采样",
            ) {
                if (form.providers.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        form.providers.forEach { provider ->
                            FilterChip(
                                selected = provider.id == selected?.id,
                                onClick = {
                                    selectedId = provider.id
                                    form = form.copy(
                                        baseUrl = provider.baseUrl,
                                        apiKey = provider.apiKey,
                                        model = provider.enabled.firstOrNull() ?: form.model,
                                    )
                                },
                                label = {
                                    Text(
                                        text = provider.shortLabel,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 110.dp),
                                    )
                                },
                            )
                        }
                        AssistChip(
                            onClick = {
                                val id = "p" + System.currentTimeMillis().toString(36)
                                selectedId = id
                                form = form.copy(
                                    providers = form.providers + ProviderConfig(id = id),
                                    baseUrl = "",
                                    apiKey = "",
                                    model = "",
                                )
                            },
                            label = { Text("新建") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                        if (form.providers.size > 1 && selected != null) {
                            TextButton(
                                onClick = {
                                    val rest = form.providers.filterNot { it.id == selected.id }
                                    val next = rest.first()
                                    selectedId = next.id
                                    form = form.copy(
                                        providers = rest,
                                        baseUrl = next.baseUrl,
                                        apiKey = next.apiKey,
                                        model = next.enabled.firstOrNull() ?: form.model,
                                    )
                                },
                            ) { Text("删除") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = form.baseUrl,
                    onValueChange = { value ->
                        form = form.copy(baseUrl = value)
                            .mapProvider(selected?.id) { it.copy(baseUrl = value) }
                    },
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
                    onValueChange = { value ->
                        form = form.copy(apiKey = value)
                            .mapProvider(selected?.id) { it.copy(apiKey = value) }
                    },
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
                    label = { Text("当前模型") },
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
                                    onSuccess = { fetched ->
                                        val merged = (fetched + selected?.models.orEmpty()).distinct()
                                        form = form.mapProvider(selected?.id) { it.copy(models = merged) }
                                        if (merged.isEmpty()) {
                                            outcome = TestOutcome(false, "接口没返回任何模型，直接手填吧。")
                                        } else {
                                            pickerOpen = true
                                        }
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
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(if (loadingModels) "获取中…" else "获取模型列表")
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "已启用 ${selected?.enabled?.size ?: 0} 个",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "从接口的 /models 拉一份清单，勾选后按提供方分组出现在顶部的模型切换里。",
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
                    label = "Top P ${"%.2f".format(form.topP)}",
                    hint = "核采样。1.00 表示不过滤，一般不用动。",
                    value = form.topP,
                    range = 0.1f..1f,
                    steps = 17,
                    onValueChange = { form = form.copy(topP = it) },
                )
                OutlinedTextField(
                    value = if (form.maxTokens <= 0) "" else form.maxTokens.toString(),
                    onValueChange = { raw ->
                        val digits = raw.filter { it.isDigit() }.take(6)
                        form = form.copy(maxTokens = digits.toIntOrNull() ?: 0)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("最大输出 tokens（留空 = 不限）") },
                    placeholder = { Text("例如 4096") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = MaterialTheme.shapes.small,
                )
                LabeledSlider(
                    label = "上下文条数 " +
                        if (form.contextWindow <= 0) "不限" else "${form.contextWindow}",
                    hint = "每次请求只带最近多少条消息。不限时历史一长就变慢、也更贵。",
                    value = form.contextWindow.toFloat(),
                    range = 0f..80f,
                    steps = 79,
                    onValueChange = { form = form.copy(contextWindow = it.toInt()) },
                )
                LabeledSlider(
                    label = "最大工具轮数 ${form.maxToolRounds}",
                    hint = "一次任务里最多连续调用多少次工具；0 = 不限制。",
                    value = form.maxToolRounds.toFloat(),
                    range = 0f..40f,
                    steps = 39,
                    onValueChange = { form = form.copy(maxToolRounds = it.toInt()) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(
                    title = "显示思考过程",
                    subtitle = "把模型的思考流式画在回复上方。关掉只是不显示，思考强度照旧生效。",
                    control = {
                        Switch(
                            checked = form.showReasoning,
                            onCheckedChange = { form = form.copy(showReasoning = it) },
                        )
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(10.dp))
                Text(text = "思考强度", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "对应接口的 reasoning_effort。只对支持档位的模型有效，不支持的会忽略这一项。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(9.dp))
                SegmentedPill(
                    options = REASONING_LEVELS.map { it.second },
                    selectedIndex = REASONING_LEVELS
                        .indexOfFirst { it.first == form.reasoningEffort }
                        .coerceAtLeast(0),
                    onSelect = { index ->
                        form = form.copy(reasoningEffort = REASONING_LEVELS[index].first)
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(10.dp))
                Text(text = "系统提示词", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "模型的人设与工作方式，每次请求都会带上。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(9.dp))
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

            SectionCard(
                title = "插话与运行",
                icon = Icons.Rounded.Tune,
                subtitle = "插话落点、压缩与工具审批",
            ) {
                // 0 排队 / 1 立即，见 AppSettings.steerMode：打断在途的回复是有损的，
                // 所以默认排队，只有用户主动选「立即」才掐断。
                Text(text = "插话方式", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "你干活时插的话落在哪里。排队：等这一轮说完再送进去，模型把它当成" +
                        "中途听到的话；立即：打断在途的回复，模型马上看到，代价是这一轮输出作废。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(9.dp))
                SegmentedPill(
                    options = listOf("排队", "立即"),
                    selectedIndex = form.steerMode.coerceIn(0, 1),
                    onSelect = { form = form.copy(steerMode = it) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(
                    title = "自动压缩上下文",
                    subtitle = "上下文接近窗口上限时把旧消息压成摘要。超窗是接口直接报错，" +
                        "压缩最坏只是丢些细节；只在上面设了上下文条数时才可能触发。",
                    control = {
                        Switch(
                            checked = form.autoCompress,
                            onCheckedChange = { form = form.copy(autoCompress = it) },
                        )
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(10.dp))
                Text(text = "工具审批", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "模型每次动手前要不要问你。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(9.dp))
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
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                    val remembered by Core.gate.alwaysAllowedTools.collectAsState()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("总是允许的工具", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = if (remembered.isEmpty()) {
                                    "还没有。审批时选「总是允许」，这里会记住它，重启也在。"
                                } else {
                                    "${remembered.size} 个，重启后依然有效"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (remembered.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    Core.gate.forgetAlways()
                                    scope.launch { snackbar.showSnackbar("已清除全部记忆") }
                                },
                            ) { Text("全部清除") }
                        }
                    }
                    remembered.sorted().forEach { name ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { Core.gate.forgetAlways(name) }) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = "忘记 $name",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                    val refused by Core.gate.alwaysDeniedTools.collectAsState()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("总是拒绝的工具", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = if (refused.isEmpty()) {
                                    "还没有。审批时选「总是拒绝」，它不会再问第二次。"
                                } else {
                                    "${refused.size} 个，重启后依然有效"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (refused.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    Core.gate.forgetDenied()
                                    scope.launch { snackbar.showSnackbar("已清除全部拒绝记忆") }
                                },
                            ) { Text("全部清除") }
                        }
                    }
                    refused.sorted().forEach { name ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { Core.gate.forgetDenied(name) }) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = "不再拒绝 $name",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            SectionCard(
                title = "外观",
                icon = Icons.Rounded.Palette,
                subtitle = "主题、配色与排版",
            ) {
                SegmentedPill(
                    options = listOf("跟随系统", "浅色", "深色"),
                    selectedIndex = form.themeMode.coerceIn(0, 2),
                    onSelect = { form = form.copy(themeMode = it) },
                )
                Spacer(Modifier.height(12.dp))
                SegmentedPill(
                    options = listOf("随行配色", "跟随壁纸"),
                    selectedIndex = form.paletteMode.coerceIn(0, 1),
                    onSelect = { form = form.copy(paletteMode = it) },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "「跟随壁纸」需要安卓 12 以上，会改用系统动态取色。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))
                Text(text = "字号", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "正文字号，立即生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(9.dp))
                SegmentedPill(
                    options = listOf("小", "标准", "大", "特大"),
                    selectedIndex = form.fontScale.coerceIn(0, 3),
                    onSelect = { form = form.copy(fontScale = it) },
                )
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))
                Text(text = "圆角", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "卡片、按钮与输入框的圆角大小。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(9.dp))
                SegmentedPill(
                    options = listOf("方正", "标准", "圆润"),
                    selectedIndex = form.cornerScale.coerceIn(0, 2),
                    onSelect = { form = form.copy(cornerScale = it) },
                )
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))
                Text(text = "气泡宽度", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "助手回复最多占屏幕的宽度比例。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(9.dp))
                SegmentedPill(
                    options = listOf("窄", "标准", "宽"),
                    selectedIndex = form.bubbleWidth.coerceIn(0, 2),
                    onSelect = { form = form.copy(bubbleWidth = it) },
                )
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(
                    title = "思考过程默认展开",
                    subtitle = "关掉时思考过程默认收起，只显示用时和 token 数。",
                    control = {
                        Switch(
                            checked = form.expandReasoning,
                            onCheckedChange = { form = form.copy(expandReasoning = it) },
                        )
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(
                    title = "自动跟随新消息",
                    subtitle = "在底部时新回复自动滚到底；上滑看历史就暂停跟随。",
                    control = {
                        Switch(
                            checked = form.autoFollow,
                            onCheckedChange = { form = form.copy(autoFollow = it) },
                        )
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(
                    title = "显示时间",
                    subtitle = "在每条消息下面显示发送时间。",
                    control = {
                        Switch(
                            checked = form.showTimestamps,
                            onCheckedChange = { form = form.copy(showTimestamps = it) },
                        )
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(
                    title = "工具调用默认展开",
                    subtitle = "关掉时工具卡片折叠成一行，点开才看参数与结果。",
                    control = {
                        Switch(
                            checked = form.expandTools,
                            onCheckedChange = { form = form.copy(expandTools = it) },
                        )
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = {
                        val defaults = AppSettings()
                        form = form.copy(
                            themeMode = defaults.themeMode,
                            paletteMode = defaults.paletteMode,
                            fontScale = defaults.fontScale,
                            bubbleWidth = defaults.bubbleWidth,
                            cornerScale = defaults.cornerScale,
                            showTimestamps = defaults.showTimestamps,
                            expandTools = defaults.expandTools,
                            expandReasoning = defaults.expandReasoning,
                            autoFollow = defaults.autoFollow,
                        )
                    },
                ) { Text("恢复默认外观") }
            }

            SectionCard(
                title = "权限",
                icon = Icons.Rounded.AdminPanelSettings,
                subtitle = "root 与系统能力",
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
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsLink(
                    title = "权限与能力",
                    subtitle = "无障碍、Shizuku、root 检测",
                    onClick = { onOpen("capability") },
                )
            }

            SectionCard(
                title = "网络与语音",
                icon = Icons.Rounded.TravelExplore,
                subtitle = "搜索走哪里、回复读不读出来",
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
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                        OutlinedButton(onClick = { Core.speaker.speak("你好，我是随行。") }) {
                            Text("试听")
                        }
                        OutlinedButton(onClick = { Core.speaker.stop() }) { Text("停止朗读") }
                    }
                }
            }

            SectionCard(
                title = "数据",
                icon = Icons.Rounded.Storage,
                subtitle = "MCP 与记忆",
            ) {
                SettingsLink(
                    title = "记忆",
                    subtitle = "模型自己写下的长期备忘",
                    onClick = { onOpen("extension?tab=2") },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsLink(
                    title = "MCP 服务器",
                    subtitle = "给模型外挂工具",
                    onClick = { onOpen("extension?tab=0") },
                )
            }

            SectionCard(
                title = "关于",
                icon = Icons.Rounded.Info,
                subtitle = "随行 Suixing",
            ) {
                Text(
                    text = "跟着你走的手机智能体：说一句话，它就能读屏、点按、跑命令、翻文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "回答由你自己配置的模型服务提供；对话、记忆与密钥都只留在这台手机上。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.VerifiedUser,
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

    if (pickerOpen && selected != null && selected.models.isNotEmpty()) {
        ModelPickerDialog(
            models = selected.models,
            enabled = selected.enabled,
            onConfirm = { picked ->
                form = form.mapProvider(selected.id) { it.copy(enabled = picked) }
                if (form.model.isBlank() && picked.isNotEmpty()) {
                    form = form.copy(model = picked.first())
                }
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
    SuixingCard(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = if (outcome.ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
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
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "关闭",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

/**
 * Multi-select over everything `/models` reported, grouped by the upstream vendor
 * that an aggregating proxy prefixes onto each id (`deepseek/deepseek-chat`).
 */
@Composable
private fun ModelPickerDialog(
    models: List<String>,
    enabled: List<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var picked by remember(models) { mutableStateOf(enabled.toSet()) }
    val groups = remember(models, query) {
        val shown = if (query.isBlank()) {
            models
        } else {
            models.filter { it.contains(query.trim(), ignoreCase = true) }
        }
        groupModels(shown)
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
                if (groups.isEmpty()) {
                    Text(
                        text = "没有匹配的模型。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                        groups.forEach { (group, ids) ->
                            item(key = "group-$group") {
                                GroupHeader(
                                    group = group,
                                    ids = ids,
                                    picked = picked,
                                    onToggle = { pickAll ->
                                        picked = if (pickAll) picked + ids else picked - ids.toSet()
                                    },
                                )
                            }
                            items(ids, key = { "model-$it" }) { id ->
                                ModelCheckRow(
                                    id = id,
                                    checked = id in picked,
                                    onToggle = {
                                        picked = if (id in picked) picked - id else picked + id
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(models.filter { it in picked }) }) {
                Text("确定（${picked.size}）")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** One upstream vendor's slice of the list, with a select-all shortcut. */
@Composable
private fun GroupHeader(
    group: String,
    ids: List<String>,
    picked: Set<String>,
    onToggle: (Boolean) -> Unit,
) {
    val all = ids.all { it in picked }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { onToggle(!all) },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Text(
                text = if (all) "取消全选" else "全选",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun ModelCheckRow(id: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onToggle)
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(
            text = id,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A row that leaves this page: the chevron is what says so before it is tapped. */
@Composable
private fun SettingsLink(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
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
