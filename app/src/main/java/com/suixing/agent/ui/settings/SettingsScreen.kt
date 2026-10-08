package com.suixing.agent.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.suixing.agent.ui.components.PageTopBar
import com.suixing.agent.ui.components.SegmentedPill
import com.suixing.agent.ui.components.SettingRow
import com.suixing.agent.ui.components.SuixingCard
import com.suixing.agent.ui.theme.LocalDarkTheme
import com.suixing.agent.ui.theme.Tone
import com.suixing.agent.ui.theme.color
import kotlinx.coroutines.CoroutineScope
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
 * The pages the root list opens. 权限 is deliberately missing: it is not a page of this
 * screen at all, it hands off to the capability list, which is a list the user manages
 * rather than settings the user toggles.
 */
private enum class SettingsPage(val title: String) {
    MODEL("模型与生成"),
    STEER("插话与运行"),
    APPEARANCE("外观"),
    NETWORK("网络与语音"),
    DATA("数据"),
}

/**
 * Settings, in two levels: six rows, each opening its own page.
 *
 * Which level is showing is internal state, not a navigation route — the shell's back
 * stack only ever knows "settings". So the arrow in the header goes to [onBack] (out of
 * the screen), and the way back to the list is the system back button, which
 * [BackHandler] takes over while a page is up.
 *
 * [onOpen] is still how a row that is really another screen gets there — MCP, memory and
 * the capability list each keep their own page.
 */
@Composable
fun SettingsScreen(onOpen: (String) -> Unit, onBack: () -> Unit) {
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    BackHandler(enabled = page != null) { page = null }

    when (page) {
        null -> SettingsRoot(
            onBack = onBack,
            onOpen = onOpen,
            onOpenPage = { page = it },
        )

        SettingsPage.MODEL -> ModelSettingsPage(onBack = { page = null })
        SettingsPage.STEER -> SteerSettingsPage(onBack = { page = null })
        SettingsPage.APPEARANCE -> AppearanceSettingsPage(onBack = { page = null })
        SettingsPage.NETWORK -> NetworkVoiceSettingsPage(onBack = { page = null })
        SettingsPage.DATA -> DataSettingsPage(onBack = { page = null }, onOpen = onOpen)
    }
}

/** The six ways in. Plain rows, not cards: a card per entry would be six boxes of air. */
@Composable
private fun SettingsRoot(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenPage: (SettingsPage) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // The screen owns its insets: the header pads the status bar itself, and the list
        // below pads the navigation bar itself. Letting the scaffold reserve them too
        // would leave a second empty strip under the last row.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { PageTopBar(title = "设置", onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SettingsEntry(
                    title = "模型与生成",
                    summary = "接口、模型与采样",
                    icon = Icons.Rounded.SmartToy,
                    onClick = { onOpenPage(SettingsPage.MODEL) },
                )
            }
            item {
                SettingsEntry(
                    title = "插话与运行",
                    summary = "插话落点、压缩与工具审批",
                    icon = Icons.Rounded.Tune,
                    onClick = { onOpenPage(SettingsPage.STEER) },
                )
            }
            item {
                SettingsEntry(
                    title = "外观",
                    summary = "主题、配色与排版",
                    icon = Icons.Rounded.Palette,
                    onClick = { onOpenPage(SettingsPage.APPEARANCE) },
                )
            }
            item {
                // No page of its own: the capabilities themselves live on a screen the
                // user already has, and repeating them here would be a second copy to
                // keep in sync.
                SettingsEntry(
                    title = "权限",
                    summary = "root 与系统能力",
                    icon = Icons.Rounded.AdminPanelSettings,
                    onClick = { onOpen("capability") },
                )
            }
            item {
                SettingsEntry(
                    title = "网络与语音",
                    summary = "搜索走哪里、回复读不读出来",
                    icon = Icons.Rounded.TravelExplore,
                    onClick = { onOpenPage(SettingsPage.NETWORK) },
                )
            }
            item {
                SettingsEntry(
                    title = "数据",
                    summary = "MCP、记忆与关于",
                    icon = Icons.Rounded.Storage,
                    onClick = { onOpenPage(SettingsPage.DATA) },
                )
            }
        }
    }
}

/** One way into a page: icon tile, title, one-line summary, chevron. */
@Composable
private fun SettingsEntry(
    title: String,
    summary: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The write path for every page without a save button: the control's new value goes
 * straight into the repository, so a switch that is flipped and then left behind is
 * already saved when the process dies. 模型与生成 keeps a draft instead, because a
 * half-typed endpoint is not a setting yet.
 */
private fun CoroutineScope.persist(block: (AppSettings) -> AppSettings) {
    launch { Core.settings.update(block) }
}

/**
 * 模型与生成: the only page with a draft and a save button, because its fields are a
 * group — a new address with an old key is not a state anyone wants half of.
 */
@Composable
private fun ModelSettingsPage(onBack: () -> Unit) {
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
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            PageTopBar(
                title = SettingsPage.MODEL.title,
                onBack = onBack,
                subtitle = if (dirty) "有未保存的改动" else null,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // The bar is the bottom-most thing on the page, so it is what
                        // has to clear the navigation bar.
                        .navigationBarsPadding()
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
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
            }
            item {
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
            }
            item {
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
            }
            item {
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
            }
            item {
                OutlinedTextField(
                    value = form.model,
                    onValueChange = { form = form.copy(model = it) },
                    label = { Text("当前模型") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
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
            }
            item {
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
            }
            item {
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
            }
            item {
                LabeledSlider(
                    label = "最大工具轮数 ${form.maxToolRounds}",
                    hint = "一次任务里最多连续调用多少次工具；0 = 不限制。",
                    value = form.maxToolRounds.toFloat(),
                    range = 0f..40f,
                    steps = 39,
                    onValueChange = { form = form.copy(maxToolRounds = it.toInt()) },
                )
            }
            item {
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
            }
            item {
                LabeledBlock(
                    label = "系统提示词",
                    hint = "模型的人设与工作方式，每次请求都会带上。",
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
            }
            // Sampling lives behind a fold: these three are set once and then never
            // touched, and they were the rows pushing the daily switches off the screen.
            item {
                AdvancedSection {
                    LabeledSlider(
                        label = "温度 ${"%.1f".format(form.temperature)}",
                        hint = "越高越随机，日常 0.3 – 0.8",
                        value = form.temperature.toFloat(),
                        range = 0f..2f,
                        steps = 0,
                        onValueChange = { form = form.copy(temperature = it.toDouble()) },
                    )
                    Spacer(Modifier.height(12.dp))
                    LabeledSlider(
                        label = "Top P ${"%.2f".format(form.topP)}",
                        hint = "核采样。1.00 表示不过滤，一般不用动。",
                        value = form.topP,
                        range = 0.1f..1f,
                        steps = 17,
                        onValueChange = { form = form.copy(topP = it) },
                    )
                    Spacer(Modifier.height(12.dp))
                    LabeledBlock(
                        label = "思考强度",
                        hint = "对应接口的 reasoning_effort。只对支持档位的模型有效，不支持的会忽略这一项。",
                    ) {
                        SegmentedPill(
                            options = REASONING_LEVELS.map { it.second },
                            selectedIndex = REASONING_LEVELS
                                .indexOfFirst { it.first == form.reasoningEffort }
                                .coerceAtLeast(0),
                            onSelect = { index ->
                                form = form.copy(reasoningEffort = REASONING_LEVELS[index].first)
                            },
                        )
                    }
                }
            }
        }
    }

    if (pickerOpen && selected != null && selected.models.isNotEmpty()) {
        ModelPickerDialog(
            models = selected.models,
            enabled = selected.enabled,
            contexts = form.modelContexts,
            onContext = { model, window ->
                // Written through immediately: the window belongs to the model, and the
                // dialog's confirm button is about which models are enabled.
                scope.persist { settings ->
                    settings.copy(
                        modelContexts = if (window <= 0) {
                            settings.modelContexts - model
                        } else {
                            settings.modelContexts + (model to window)
                        },
                    )
                }
            },
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

/** 插话与运行: steering, auto-compression, and what the agent may do without asking. */
@Composable
private fun SteerSettingsPage(onBack: () -> Unit) {
    val stored by Core.settings.flow.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val allowed by Core.gate.alwaysAllowedTools.collectAsState()
    val denied by Core.gate.alwaysDeniedTools.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { PageTopBar(title = SettingsPage.STEER.title, onBack = onBack) },
        snackbarHost = {
            SnackbarHost(snackbar, modifier = Modifier.navigationBarsPadding())
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                // 0 排队 / 1 立即，见 AppSettings.steerMode：打断在途的回复是有损的，
                // 所以默认排队，只有用户主动选「立即」才掐断。
                LabeledBlock(
                    label = "插话方式",
                    hint = "你干活时插的话落在哪里。排队：等这一轮说完再送进去，模型把它当成" +
                        "中途听到的话；立即：打断在途的回复，模型马上看到，代价是这一轮输出作废。",
                ) {
                    SegmentedPill(
                        options = listOf("排队", "立即"),
                        selectedIndex = stored.steerMode.coerceIn(0, 1),
                        onSelect = { index -> scope.persist { it.copy(steerMode = index) } },
                    )
                }
            }
            item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
            item {
                SettingRow(
                    title = "自动压缩上下文",
                    subtitle = "上下文接近窗口上限时把旧消息压成摘要。超窗是接口直接报错，" +
                        "压缩最坏只是丢些细节；只在「模型与生成」里设了上下文条数时才可能触发。",
                    control = {
                        Switch(
                            checked = stored.autoCompress,
                            onCheckedChange = { on -> scope.persist { it.copy(autoCompress = on) } },
                        )
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item {
                LabeledBlock(label = "工具审批", hint = "模型每次动手前要不要问你。") {
                    SegmentedPill(
                        options = ApprovalMode.entries.map { modeLabel(it) },
                        selectedIndex = ApprovalMode.entries.indexOf(stored.approvalMode),
                        onSelect = { index ->
                            scope.persist { it.copy(approvalMode = ApprovalMode.entries[index]) }
                        },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = when (stored.approvalMode) {
                        ApprovalMode.AUTO -> "模型可以连续调用工具，不再打断你。"
                        ApprovalMode.ASK -> "每次调用工具前都会问你一次，可以选「总是允许」。"
                        ApprovalMode.DENY -> "禁止一切工具调用，只能聊天。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (stored.approvalMode == ApprovalMode.ASK) {
                item {
                    HorizontalDivider()
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("总是允许的工具", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = if (allowed.isEmpty()) {
                                    "还没有。审批时选「总是允许」，这里会记住它，重启也在。"
                                } else {
                                    "${allowed.size} 个，重启后依然有效"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (allowed.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    Core.gate.forgetAlways()
                                    scope.launch { snackbar.showSnackbar("已清除全部记忆") }
                                },
                            ) { Text("全部清除") }
                        }
                    }
                }
                // Namespaced keys: the two lists share one LazyColumn, and a name that
                // moved from one to the other must not be two items with one key.
                items(allowed.sorted(), key = { "allow-$it" }) { name ->
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
                item {
                    HorizontalDivider()
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("总是拒绝的工具", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = if (denied.isEmpty()) {
                                    "还没有。审批时选「总是拒绝」，它不会再问第二次。"
                                } else {
                                    "${denied.size} 个，重启后依然有效"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (denied.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    Core.gate.forgetDenied()
                                    scope.launch { snackbar.showSnackbar("已清除全部拒绝记忆") }
                                },
                            ) { Text("全部清除") }
                        }
                    }
                }
                items(denied.sorted(), key = { "deny-$it" }) { name ->
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
    }
}

/** 外观: theme, palette, type scale and what the transcript shows by default. */
@Composable
private fun AppearanceSettingsPage(onBack: () -> Unit) {
    val stored by Core.settings.flow.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { PageTopBar(title = SettingsPage.APPEARANCE.title, onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                LabeledBlock(label = "主题") {
                    SegmentedPill(
                        options = listOf("跟随系统", "浅色", "深色"),
                        selectedIndex = stored.themeMode.coerceIn(0, 2),
                        onSelect = { index -> scope.persist { it.copy(themeMode = index) } },
                    )
                }
            }
            item {
                LabeledBlock(
                    label = "配色",
                    hint = "「跟随壁纸」需要安卓 12 以上，会改用系统动态取色。",
                ) {
                    SegmentedPill(
                        options = listOf("随行配色", "跟随壁纸"),
                        selectedIndex = stored.paletteMode.coerceIn(0, 1),
                        onSelect = { index -> scope.persist { it.copy(paletteMode = index) } },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item {
                LabeledBlock(label = "字号", hint = "正文字号，立即生效。") {
                    SegmentedPill(
                        options = listOf("小", "标准", "大", "特大"),
                        selectedIndex = stored.fontScale.coerceIn(0, 3),
                        onSelect = { index -> scope.persist { it.copy(fontScale = index) } },
                    )
                }
            }
            item {
                LabeledBlock(label = "圆角", hint = "卡片、按钮与输入框的圆角大小。") {
                    SegmentedPill(
                        options = listOf("方正", "标准", "圆润"),
                        selectedIndex = stored.cornerScale.coerceIn(0, 2),
                        onSelect = { index -> scope.persist { it.copy(cornerScale = index) } },
                    )
                }
            }
            item {
                LabeledBlock(label = "气泡宽度", hint = "助手回复最多占屏幕的宽度比例。") {
                    SegmentedPill(
                        options = listOf("窄", "标准", "宽"),
                        selectedIndex = stored.bubbleWidth.coerceIn(0, 2),
                        onSelect = { index -> scope.persist { it.copy(bubbleWidth = index) } },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item {
                SettingRow(
                    title = "思考过程默认展开",
                    subtitle = "关掉时思考过程默认收起，只显示用时和 token 数。",
                    control = {
                        Switch(
                            checked = stored.expandReasoning,
                            onCheckedChange = { on -> scope.persist { it.copy(expandReasoning = on) } },
                        )
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item {
                SettingRow(
                    title = "自动跟随新消息",
                    subtitle = "在底部时新回复自动滚到底；上滑看历史就暂停跟随。",
                    control = {
                        Switch(
                            checked = stored.autoFollow,
                            onCheckedChange = { on -> scope.persist { it.copy(autoFollow = on) } },
                        )
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item {
                SettingRow(
                    title = "显示时间",
                    subtitle = "在每条消息下面显示发送时间。",
                    control = {
                        Switch(
                            checked = stored.showTimestamps,
                            onCheckedChange = { on -> scope.persist { it.copy(showTimestamps = on) } },
                        )
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item {
                SettingRow(
                    title = "工具调用默认展开",
                    subtitle = "关掉时工具卡片折叠成一行，点开才看参数与结果。",
                    control = {
                        Switch(
                            checked = stored.expandTools,
                            onCheckedChange = { on -> scope.persist { it.copy(expandTools = on) } },
                        )
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item {
                TextButton(
                    onClick = {
                        val defaults = AppSettings()
                        scope.persist {
                            it.copy(
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
                        }
                    },
                ) { Text("恢复默认外观") }
            }
        }
    }
}

/** 网络与语音: where search goes, and whether replies are read out loud. */
@Composable
private fun NetworkVoiceSettingsPage(onBack: () -> Unit) {
    val stored by Core.settings.flow.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { PageTopBar(title = SettingsPage.NETWORK.title, onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                OutlinedTextField(
                    value = stored.searchEndpoint,
                    onValueChange = { value -> scope.persist { it.copy(searchEndpoint = value) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索接口（可留空）") },
                    placeholder = { Text("https://api.tavily.com/search") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    supportingText = {
                        Text("留空时用内置的 DuckDuckGo 抓取，容易被限流；填 Tavily 兼容接口更稳。")
                    },
                )
            }
            item {
                OutlinedTextField(
                    value = stored.searchApiKey,
                    onValueChange = { value -> scope.persist { it.copy(searchApiKey = value) } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索接口 Key（可留空）") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    visualTransformation = PasswordVisualTransformation(),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item {
                SettingRow(
                    title = "朗读回复",
                    subtitle = "用系统语音引擎读出来。引擎状态：${Core.speaker.languageLabel()}。" +
                        "输入框左边的麦克风按钮负责语音输入。",
                    control = {
                        Switch(
                            checked = stored.speakReplies,
                            onCheckedChange = { on -> scope.persist { it.copy(speakReplies = on) } },
                        )
                    },
                )
            }
            if (stored.speakReplies) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { Core.speaker.speak("你好，我是随行。") }) {
                            Text("试听")
                        }
                        OutlinedButton(onClick = { Core.speaker.stop() }) { Text("停止朗读") }
                    }
                }
            }
        }
    }
}

/**
 * 数据: the two lists that live on their own screens, plus everything that is text only.
 *
 * The 悬浮窗控制台 row is here rather than on a 权限 page because there is no 权限 page —
 * that row hands off to the capability screen — and this page is already where the
 * read-only text (关于, the key note) lives, which is what an explanation is.
 */
@Composable
private fun DataSettingsPage(onBack: () -> Unit, onOpen: (String) -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { PageTopBar(title = SettingsPage.DATA.title, onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                SettingsLink(
                    title = "记忆",
                    subtitle = "模型自己写下的长期备忘",
                    onClick = { onOpen("extension?tab=2") },
                )
            }
            item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
            item {
                SettingsLink(
                    title = "MCP 服务器",
                    subtitle = "给模型外挂工具",
                    onClick = { onOpen("extension?tab=0") },
                )
            }
            item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
            item {
                // Read-only on purpose: the switch is on the chat header, next to the run
                // it starts, so a second one here would be a second source of truth.
                SettingRow(
                    title = "悬浮窗控制台",
                    subtitle = "开关在聊天页顶栏的悬浮窗钮上；这里只说明权限",
                )
            }
            item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
            item {
                Text(
                    text = "关于",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            item {
                Text(
                    text = "跟着你走的手机智能体：说一句话，它就能读屏、点按、跑命令、翻文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Text(
                    text = "回答由你自己配置的模型服务提供；对话、记忆与密钥都只留在这台手机上。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
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
            }
        }
    }
}

/** A labelled control: the label and the why-line above it, the way the cards stacked them. */
/**
 * A folded block of settings that are set once and then left alone.
 *
 * Same chrome as the process cards in the chat screen: one shape for everything that
 * opens and closes, so a fold here does not look like a different kind of control.
 */
@Composable
private fun AdvancedSection(content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "高级参数",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) {
                        Icons.Rounded.KeyboardArrowUp
                    } else {
                        Icons.Rounded.KeyboardArrowDown
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (expanded) {
                Column(Modifier.padding(12.dp), content = content)
            }
        }
    }
}

@Composable
private fun LabeledBlock(
    label: String,
    hint: String? = null,
    content: @Composable () -> Unit,
) {
    Column(Modifier.padding(top = 10.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (hint != null) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(9.dp))
        content()
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
    /** Per-model context windows, keyed by model name; 0 or missing means 不限. */
    contexts: Map<String, Int>,
    onContext: (String, Int) -> Unit,
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
                                    contextWindow = contexts[id] ?: 0,
                                    onToggle = {
                                        picked = if (id in picked) picked - id else picked + id
                                    },
                                    onContext = { onContext(id, it) },
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

/**
 * One model in the picker: the checkbox picks it, the chip on the right sets how much
 * context it gets.
 *
 * The window is per model because the models differ — a 128k model and an 8k one share
 * neither the price nor the point at which the transcript has to be compressed.
 */
@Composable
private fun ModelCheckRow(
    id: String,
    checked: Boolean,
    contextWindow: Int,
    onToggle: () -> Unit,
    onContext: (Int) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(editing) {
        mutableStateOf(if (contextWindow > 0) contextWindow.toString() else "")
    }
    Column(Modifier.fillMaxWidth()) {
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
            // The chip is the way in, so tapping the name still only picks the model.
            Surface(
                onClick = { editing = !editing },
                shape = CircleShape,
                color = if (contextWindow > 0) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            ) {
                Text(
                    text = if (contextWindow > 0) "${contextWindow / 1000}k" else "不限",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (contextWindow > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        if (editing) {
            OutlinedTextField(
                value = draft,
                onValueChange = { raw -> draft = raw.filter { it.isDigit() }.take(7) },
                label = { Text("上下文窗口（tokens，留空 = 不限）") },
                placeholder = { Text("例如 128000") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = MaterialTheme.shapes.small,
                trailingIcon = {
                    TextButton(onClick = {
                        onContext(draft.toIntOrNull() ?: 0)
                        editing = false
                    }) { Text("保存") }
                },
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, bottom = 6.dp),
            )
        }
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
