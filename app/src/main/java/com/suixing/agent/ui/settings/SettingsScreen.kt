@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.suixing.agent.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ripple
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suixing.agent.R
import com.suixing.agent.core.Core
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.ProviderConfig
import com.suixing.agent.data.groupModels
import com.suixing.agent.llm.ChatMessage
import com.suixing.agent.llm.LlmClient
import com.suixing.agent.llm.describeError
import com.suixing.agent.ui.components.PageTopBar
import com.suixing.agent.ui.components.REASONING_LEVELS
import com.suixing.agent.ui.components.SegmentedPill
import com.suixing.agent.ui.components.SettingRow
import com.suixing.agent.ui.components.SuixingCard
import com.suixing.agent.ui.theme.LocalDarkTheme
import com.suixing.agent.ui.theme.Tone
import com.suixing.agent.ui.theme.color
import com.suixing.agent.ui.usage.UsageScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Outcome of a connection test, shown in the fixed bar above the buttons. */
private data class TestOutcome(val ok: Boolean, val message: String)

/**
 * The context-window presets for the current model, tokens to 档位名。0 是「不限」，
 * written as a deleted key rather than a stored 0, so 「没设过」 and 「设成不限」 stay
 * indistinguishable — which is what [AppSettings.contextWindowFor] already assumes.
 */
private val CONTEXT_PRESETS = listOf(
    0 to "不限",
    32_000 to "32k",
    64_000 to "64k",
    128_000 to "128k",
    256_000 to "256k",
    1_000_000 to "1M",
)

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
    USAGE("用量"),
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
        SettingsPage.USAGE -> UsageScreen(onBack = { page = null })
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
                    icon = ImageVector.vectorResource(R.drawable.ic_bot),
                    onClick = { onOpenPage(SettingsPage.MODEL) },
                )
            }
            item {
                SettingsEntry(
                    title = "插话与运行",
                    summary = "插话落点、压缩与工具审批",
                    icon = ImageVector.vectorResource(R.drawable.ic_sliders_horizontal),
                    onClick = { onOpenPage(SettingsPage.STEER) },
                )
            }
            item {
                SettingsEntry(
                    title = "外观",
                    summary = "主题、配色与排版",
                    icon = ImageVector.vectorResource(R.drawable.ic_palette),
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
                    icon = ImageVector.vectorResource(R.drawable.ic_shield_user),
                    onClick = { onOpen("capability") },
                )
            }
            item {
                SettingsEntry(
                    title = "网络与语音",
                    summary = "搜索走哪里、回复读不读出来",
                    icon = ImageVector.vectorResource(R.drawable.ic_compass),
                    onClick = { onOpenPage(SettingsPage.NETWORK) },
                )
            }
            item {
                SettingsEntry(
                    title = "用量",
                    summary = "tokens · 30 日柱状 · 按模型",
                    icon = ImageVector.vectorResource(R.drawable.ic_chart_column),
                    onClick = { onOpenPage(SettingsPage.USAGE) },
                )
            }
            item {
                SettingsEntry(
                    title = "数据",
                    summary = "MCP、记忆、悬浮窗与关于",
                    icon = ImageVector.vectorResource(R.drawable.ic_database),
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
            imageVector = ImageVector.vectorResource(R.drawable.ic_chevron_right),
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
 * Writes one provider's change straight through, addressed by id *inside* the update block:
 * the draft may hold edits that are not saved yet, and handing it the whole provider list
 * would write those down with it — and could carry a stale list over a concurrent writer's
 * change. [com.suixing.agent.data.SettingsRepository.update] runs the block inside the
 * DataStore edit, so the read-modify-write is one transaction.
 */
private fun CoroutineScope.persistProvider(id: String?, block: (ProviderConfig) -> ProviderConfig) {
    if (id.isNullOrEmpty()) return
    launch {
        Core.settings.update { current ->
            current.copy(providers = current.providers.map { if (it.id == id) block(it) else it })
        }
    }
}

/**
 * 模型与生成: the only page with a draft and a save button, because its fields are a
 * group — a new address with an old key is not a state anyone wants half of.
 */
@Composable
private fun ModelSettingsPage(onBack: () -> Unit) {
    val stored by Core.settings.flow.collectAsState(initial = AppSettings())
    // The draft starts from whatever the repository holds at first emission. The flow's
    // initial value is the raw default (no providers yet — migration runs inside the
    // flow), so the draft is re-seeded once the migrated settings arrive; without that
    // the provider chip row never appeared and the page reported unsaved changes on
    // entry. Keyless on purpose: a write-through below rewrites `stored`, and a keyed
    // remember would reset the draft and throw away unsaved edits to baseUrl/apiKey/model.
    var form by remember { mutableStateOf(stored) }
    var seeded by remember { mutableStateOf(false) }
    LaunchedEffect(stored) {
        if (!seeded && stored.providers.isNotEmpty()) {
            form = stored
            seeded = true
        }
    }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val client = remember { LlmClient() }
    var testing by remember { mutableStateOf(false) }
    var loadingModels by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<TestOutcome?>(null) }
    // The chip being edited. Keyed on the provider ids, never on `stored` itself: the name
    // field writes through on every keystroke, and a selection re-derived from `stored` on
    // each of those writes would snap back to the active provider mid-word — the rest of the
    // name would then be written to whichever provider it snapped to.
    var selectedId by remember { mutableStateOf("") }
    LaunchedEffect(stored.providers.map { it.id }) {
        // Re-point only when the chip the user is on has gone away (migration, a deletion
        // elsewhere): the draft is what this page shows, so it stays where the user put it.
        if (stored.providers.none { it.id == selectedId }) {
            selectedId = stored.activeProvider()?.id ?: stored.providers.firstOrNull()?.id.orEmpty()
        }
    }
    // The endpoint the model fields edit; tapping a chip also makes it the active one.
    val selected = form.providers.firstOrNull { it.id == selectedId } ?: form.providers.firstOrNull()
    // A chip tap rewrites the endpoint triple (address, key, model) in the draft without
    // writing to disk, so whether the draft holds a hand edit to it is a rule of its own —
    // see ModelDraft.kt, where it is unit tested.
    val endpointEdited = endpointHandEdited(form, stored, selected)
    // Only real edits count: the draft is compared against the migrated stored settings, and
    // before the first emission there is nothing to be dirty about. The endpoint triple is
    // left out on purpose — tapping a chip rewrites it in the draft without writing to disk,
    // so counting it would call a plain switch "unsaved"; a hand edit there is exactly what
    // `endpointEdited` reports. Saving writes the draft (a blank address keeps the live
    // endpoint — see forSaving), so this clears by itself.
    val dirty = seeded && (endpointEdited || form.differsOutsideEndpoint(stored))
    var pendingSwitch by remember { mutableStateOf<String?>(null) }
    val switchProvider: (ProviderConfig) -> Unit = { provider ->
        selectedId = provider.id
        form = form.copy(
            baseUrl = provider.baseUrl,
            apiKey = provider.apiKey,
            model = provider.enabled.firstOrNull() ?: form.model,
        )
    }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    val deleteProvider: (ProviderConfig) -> Unit = { provider ->
        val rest = form.providers.filterNot { it.id == provider.id }
        val next = rest.first()
        selectedId = next.id
        form = form.copy(
            providers = rest,
            baseUrl = next.baseUrl,
            apiKey = next.apiKey,
            model = next.enabled.firstOrNull() ?: form.model,
        )
        // 同新建：删除也是结构操作，草稿里删了磁盘上还在会让切换器继续列出已经不存在的提供方。
        // 但全局 active 只在下一个提供方真的填过地址时才跟着换：落到一个还没填的空壳上，
        // 会把正在跑的会话指到空地址，下一轮就带着空 model 出去（同「新建」那条）。
        scope.persist {
            if (next.baseUrl.isNotBlank()) {
                it.copy(
                    providers = form.providers,
                    baseUrl = form.baseUrl,
                    apiKey = form.apiKey,
                    model = form.model,
                )
            } else {
                it.copy(providers = form.providers)
            }
        }
    }

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
                                    Core.settings.save(form.forSaving(stored))
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
                                    // 同保存：空地址不写进全局 active（新建后停在空 provider 上
                                    // 点测试，不该把正在用的接口清掉）。
                                    Core.settings.save(form.forSaving(stored))
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
                // The provider's own name, editable in place: the chip row's long-press
                // rename stays, but the name is read every day and typed once a month.
                OutlinedTextField(
                    value = selected?.name.orEmpty(),
                    onValueChange = { value ->
                        form = form.mapProvider(selected?.id) { it.copy(name = value) }
                        // 按 id 只写这一个提供方：整份 providers 覆盖会把页面上其它还没保存的
                        // 草稿一起写下去，也会拿旧快照盖掉别人刚写的值。
                        scope.persistProvider(selected?.id) { it.copy(name = value) }
                    },
                    label = { Text("提供方名称") },
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
                // The raw id, not the alias: what is typed here is what goes on the wire, so
                // a display name typed or pasted in would be sent as the model and 404. The
                // alias stays read-only, under the field and on the picker's chips.
                val alias = selected?.aliases?.get(form.model)
                OutlinedTextField(
                    value = form.model,
                    onValueChange = { form = form.copy(model = it) },
                    label = { Text("当前模型") },
                    supportingText = if (alias != null) {
                        { Text("别名：$alias") }
                    } else {
                        null
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                // 模型列表里那枚 chip 要先进对话框、再展开输入框才能改一个数；当前模型
                // 的窗口是天天要调的，所以在页面上直接给一档预设。
                val model = form.model
                // The page names the model by its alias everywhere else, so the hint does too;
                // the raw id is still what the window is filed under in modelContexts.
                val modelLabel = selected?.displayModel(model) ?: model
                val window = form.contextWindowFor(model)
                var customOpen by remember { mutableStateOf(false) }
                val presetIndex = CONTEXT_PRESETS.indexOfFirst { it.first == window }
                LabeledBlock(
                    label = "上下文窗口",
                    hint = "只影响当前模型（$modelLabel）：超过这个长度就丢掉最早的对话，不限则整段发过去。",
                ) {
                    SegmentedPill(
                        options = CONTEXT_PRESETS.map { it.second } + "自定义",
                        // 存了个预设之外的值时停在「自定义」，下面那行把真实值说出来。
                        selectedIndex = if (presetIndex >= 0) presetIndex else CONTEXT_PRESETS.size,
                        onSelect = { index ->
                            if (index == CONTEXT_PRESETS.size) {
                                customOpen = true
                                return@SegmentedPill
                            }
                            val tokens = CONTEXT_PRESETS[index].first
                            // Draft first, disk second: the window belongs to the model and
                            // lands immediately, but routing it through the draft keeps
                            // whatever else is unsaved on this page intact.
                            form = form.copy(
                                modelContexts = if (tokens <= 0) {
                                    form.modelContexts - model
                                } else {
                                    form.modelContexts + (model to tokens)
                                },
                            )
                            scope.persist { it.copy(modelContexts = form.modelContexts) }
                        },
                    )
                    if (window > 0 && CONTEXT_PRESETS.none { it.first == window }) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "自定义：$window",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (customOpen) {
                    ContextCustomDialog(
                        current = window,
                        onDismiss = { customOpen = false },
                        onConfirm = { tokens ->
                            customOpen = false
                            form = form.copy(
                                modelContexts = if (tokens <= 0) {
                                    form.modelContexts - model
                                } else {
                                    form.modelContexts + (model to tokens)
                                },
                            )
                            scope.persist { it.copy(modelContexts = form.modelContexts) }
                        },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item {
                // The provider row: one chip per endpoint, the active one filled, plus
                // 新建. Renaming and deleting ride each chip's own long-press menu, so
                // the row stays a row. It sits after the context presets, in the field
                // order the page keeps.
                if (form.providers.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        form.providers.forEach { provider ->
                            var menuOpen by remember { mutableStateOf(false) }
                            var renaming by remember { mutableStateOf(false) }
                            Box {
                                FilterChip(
                                    selected = provider.id == selected?.id,
                                    onClick = {
                                        if (endpointEdited) {
                                            pendingSwitch = provider.id
                                        } else {
                                            switchProvider(provider)
                                        }
                                    },
                                    label = {
                                        Text(
                                            text = provider.label,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = 110.dp),
                                        )
                                    },
                                    modifier = Modifier.combinedClickable(
                                        onClick = {
                                            if (endpointEdited) {
                                                pendingSwitch = provider.id
                                            } else {
                                                switchProvider(provider)
                                            }
                                        },
                                        onLongClick = { menuOpen = true },
                                    ),
                                )
                                DropdownMenu(
                                    expanded = menuOpen,
                                    onDismissRequest = { menuOpen = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("重命名") },
                                        onClick = {
                                            menuOpen = false
                                            renaming = true
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("删除") },
                                        enabled = form.providers.size > 1,
                                        onClick = {
                                            menuOpen = false
                                            if (endpointEdited) {
                                                pendingDelete = provider.id
                                            } else {
                                                deleteProvider(provider)
                                            }
                                        },
                                    )
                                }
                            }
                            if (renaming) {
                                RenameDialog(
                                    initial = provider.name,
                                    onDismiss = { renaming = false },
                                    onConfirm = { name ->
                                        renaming = false
                                        form = form.mapProvider(provider.id) { it.copy(name = name) }
                                        scope.persist {
                                            it.copy(providers = form.providers)
                                        }
                                    },
                                )
                            }
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
                                // 点选即落盘：新建的提供方若只留在草稿里，离开页面就没了。
                                // 只落 providers：草稿里那三个空值属于还没填完的新提供方，
                                // 一起写下去会把全局 active 的接口地址/Key/模型清空，正在跑的
                                // 会话下一轮就会带着空 model 出去。
                                scope.persist { it.copy(providers = form.providers) }
                            },
                            label = { Text("新建") },
                            leadingIcon = {
                                Icon(
                                    imageVector = ImageVector.vectorResource(R.drawable.ic_plus),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
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
                                        // 拉到的清单属于提供方结构，同样点选即落盘。
                                        scope.persist {
                                            it.copy(
                                                providers = form.providers,
                                                baseUrl = form.baseUrl,
                                                apiKey = form.apiKey,
                                                model = form.model,
                                            )
                                        }
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
                                imageVector = ImageVector.vectorResource(R.drawable.ic_rotate_cw),
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
                // 最大输出 belongs to the isolation group: with 独立采样 on it reads and
                // writes the provider's nullable field, where an empty box is null.
                val ownMax = selected?.takeIf { it.ownSampling }?.maxTokens
                OutlinedTextField(
                    value = if (selected?.ownSampling == true) {
                        if (ownMax == null || ownMax <= 0) "" else ownMax.toString()
                    } else {
                        if (form.maxTokens <= 0) "" else form.maxTokens.toString()
                    },
                    onValueChange = { raw ->
                        val digits = raw.filter { it.isDigit() }.take(6)
                        val parsed = digits.toIntOrNull() ?: 0
                        form = if (selected?.ownSampling == true) {
                            form.mapProvider(selected.id) { it.copy(maxTokens = parsed.takeIf { v -> v > 0 }) }
                        } else {
                            form.copy(maxTokens = parsed)
                        }
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
                    // 独立采样: off = the four knobs below read and write the globals,
                    // on = they read and write this provider's nullable overrides.
                    SettingRow(
                        title = "独立采样参数",
                        subtitle = "开启后温度 / Top P / 思考强度 / 最大输出只作用于当前提供方。",
                        control = {
                            Switch(
                                checked = selected?.ownSampling == true,
                                onCheckedChange = { on ->
                                    form = form.mapProvider(selected?.id) { it.copy(ownSampling = on) }
                                    scope.persist { it.copy(providers = form.providers) }
                                },
                            )
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    val own = selected?.takeIf { it.ownSampling }
                    LabeledSlider(
                        label = "温度 ${"%.1f".format(own?.temperature ?: form.temperature)}",
                        hint = "越高越随机，日常 0.3 – 0.8",
                        value = (own?.temperature ?: form.temperature).toFloat(),
                        range = 0f..2f,
                        steps = 0,
                        onValueChange = { v ->
                            form = if (own != null) {
                                form.mapProvider(selected?.id) { it.copy(temperature = v.toDouble()) }
                            } else {
                                form.copy(temperature = v.toDouble())
                            }
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    LabeledSlider(
                        label = "Top P ${"%.2f".format(own?.topP ?: form.topP)}",
                        hint = "核采样。1.00 表示不过滤，一般不用动。",
                        value = own?.topP ?: form.topP,
                        range = 0.1f..1f,
                        steps = 17,
                        onValueChange = { v ->
                            form = if (own != null) {
                                form.mapProvider(selected?.id) { it.copy(topP = v) }
                            } else {
                                form.copy(topP = v)
                            }
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    // 滑杆而不是 pill：六档在一行 pill 上每格只剩两个字的宽度，滑杆顺带
                    // 把线上值写进标题，不用另起一行解释 reasoning_effort 收到的是什么。
                    // 同源显示：provider(own) > global，与 forAssistant 的解析顺序一致。
                    val effortWire = own?.reasoningEffort ?: form.reasoningEffort
                    val effort = REASONING_LEVELS
                        .indexOfFirst { it.first == effortWire }
                        // 旧的/接口自造的值只显示成「默认」，不写回：拨动滑杆才落盘。
                        .coerceAtLeast(0)
                    val (wire, name) = REASONING_LEVELS[effort]
                    LabeledSlider(
                        label = "思考强度 · " + if (wire.isEmpty() || wire == name) name else "$name ($wire)",
                        hint = "对应接口的 reasoning_effort；xhigh 仅部分模型支持，不支持的忽略。",
                        value = effort.toFloat(),
                        range = 0f..REASONING_LEVELS.lastIndex.toFloat(),
                        steps = REASONING_LEVELS.size - 2,
                        onValueChange = { index ->
                            val level = REASONING_LEVELS[index.toInt()].first
                            form = if (own != null) {
                                form.mapProvider(selected?.id) { it.copy(reasoningEffort = level) }
                            } else {
                                form.copy(reasoningEffort = level)
                            }
                        },
                    )
                }
            }
        }
    }

    if (pickerOpen && selected != null && selected.models.isNotEmpty()) {
        ModelPickerDialog(
            models = selected.models,
            enabled = selected.enabled,
            contexts = form.modelContexts,
            provider = selected,
            onContext = { model, window ->
                // Written through immediately: the window belongs to the model, and the
                // dialog's confirm button is about which models are enabled. The draft is
                // updated first so the page's other unsaved edits survive the write.
                form = form.copy(
                    modelContexts = if (window <= 0) {
                        form.modelContexts - model
                    } else {
                        form.modelContexts + (model to window)
                    },
                )
                scope.persist { it.copy(modelContexts = form.modelContexts) }
            },
            onAlias = { model, name ->
                form = form.mapProvider(selected.id) { cfg ->
                    cfg.copy(
                        aliases = if (name.isEmpty()) cfg.aliases - model else cfg.aliases + (model to name),
                    )
                }
                scope.persist { it.copy(providers = form.providers) }
            },
            onAdd = { modelId ->
                form = form.mapProvider(selected.id) { cfg ->
                    cfg.copy(models = (cfg.models + modelId).distinct(), enabled = (cfg.enabled + modelId).distinct())
                }
                scope.persist { it.copy(providers = form.providers) }
            },
            onRemove = { modelId ->
                form = form.mapProvider(selected.id) { cfg ->
                    cfg.copy(models = cfg.models - modelId, enabled = cfg.enabled - modelId)
                }
                scope.persist { it.copy(providers = form.providers) }
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

    pendingSwitch?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingSwitch = null },
            title = { Text("切换提供方？") },
            text = { Text("当前提供方的接口地址、API Key 或模型还没保存，切过去会丢掉这些改动。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingSwitch = null
                        form.providers.firstOrNull { it.id == id }?.let(switchProvider)
                    },
                ) { Text("继续") }
            },
            dismissButton = { TextButton(onClick = { pendingSwitch = null }) { Text("取消") } },
        )
    }

    pendingDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除提供方？") },
            text = { Text("当前提供方还有未保存的改动，删除会连同这些改动一起丢掉，并切到下一个提供方。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        form.providers.firstOrNull { it.id == id }?.let(deleteProvider)
                    },
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
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
                                ImageVector.vectorResource(R.drawable.ic_x),
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
                                ImageVector.vectorResource(R.drawable.ic_x),
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
                LabeledBlock(label = "思考块高度", hint = "展开的思考过程最多长这么高，超出部分在块内滚动。") {
                    SegmentedPill(
                        options = listOf("180 矮", "240 中", "320 高"),
                        selectedIndex = when (stored.reasoningHeight) {
                            240 -> 1
                            320 -> 2
                            else -> 0
                        },
                        onSelect = { index ->
                            val height = when (index) {
                                1 -> 240
                                2 -> 320
                                else -> 180
                            }
                            scope.persist { it.copy(reasoningHeight = height) }
                        },
                    )
                }
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
                                reasoningHeight = defaults.reasoningHeight,
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
                // Read-only on purpose: the switch is the drawer's bottom row, next to the
                // run it starts, so a second one here would be a second source of truth.
                SettingRow(
                    title = "悬浮窗控制台",
                    subtitle = "开关在左侧抽屉底部的「悬浮窗控制台」那一行；这里只说明权限",
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
            item {
                // Lucide 是 ISC 许可：它要求保留版权与许可声明，这一页是 App 唯一的出处说明。
                Text(
                    text = "图标：Lucide（ISC License）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                    imageVector = ImageVector.vectorResource(R.drawable.ic_sliders_horizontal),
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
                        ImageVector.vectorResource(R.drawable.ic_chevron_up)
                    } else {
                        ImageVector.vectorResource(R.drawable.ic_chevron_down)
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

/** A provider's new name, asked for in place: renaming is a structural edit like
 * 新建/删除, so it lands on disk the moment it is confirmed. */
@Composable
private fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名提供方") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** The token count behind the context window's 自定义 segment. */
@Composable
private fun ContextCustomDialog(
    current: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(if (current > 0) current.toString() else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义上下文窗口") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { value -> text = value.filter { it.isDigit() }.take(9) },
                label = { Text("tokens") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.toIntOrNull() ?: 0) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
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
                imageVector = if (outcome.ok) ImageVector.vectorResource(R.drawable.ic_circle_check) else ImageVector.vectorResource(R.drawable.ic_circle_alert),
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
                    imageVector = ImageVector.vectorResource(R.drawable.ic_x),
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
    /** The provider the list belongs to: names and aliases are per provider. */
    provider: ProviderConfig,
    onContext: (String, Int) -> Unit,
    /** Rename one model for this provider; a blank name drops the alias. */
    onAlias: (String, String) -> Unit,
    /** Add a model id the endpoint did not list. */
    onAdd: (String) -> Unit,
    /** Drop a model from the provider's list (aliases stay, harmless). */
    onRemove: (String) -> Unit,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var picked by remember(models) { mutableStateOf(enabled.toSet()) }
    var aliasing by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
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
                // Endpoints that hide a model behind a flag still serve it: typing the
                // id is the way in, and the row appears as soon as it is added.
                TextButton(
                    onClick = { adding = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text("自定义模型", style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(4.dp))
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
                                    label = provider.displayModel(id),
                                    checked = id in picked,
                                    contextWindow = contexts[id] ?: 0,
                                    onToggle = {
                                        picked = if (id in picked) picked - id else picked + id
                                    },
                                    onContext = { onContext(id, it) },
                                    onAlias = { aliasing = id },
                                    onRemove = { onRemove(id) },
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

    aliasing?.let { id ->
        AliasDialog(
            initial = provider.aliases[id].orEmpty(),
            onDismiss = { aliasing = null },
            onConfirm = { name ->
                onAlias(id, name)
                aliasing = null
            },
        )
    }
    if (adding) {
        AddModelDialog(
            onDismiss = { adding = false },
            onConfirm = { modelId ->
                onAdd(modelId)
                adding = false
            },
        )
    }
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
    /** What the row says: the alias when the user gave one, the raw id otherwise. */
    label: String,
    checked: Boolean,
    contextWindow: Int,
    onToggle: () -> Unit,
    onContext: (Int) -> Unit,
    onAlias: () -> Unit,
    onRemove: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var draft by remember(editing) {
        mutableStateOf(if (contextWindow > 0) contextWindow.toString() else "")
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .combinedClickable(
                    onClick = onToggle,
                    onLongClick = { menuOpen = true },
                )
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // The alias is a rename of one model for this provider alone; a blank name
            // in the dialog drops the alias and the row falls back to the raw id.
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_square_pen),
                contentDescription = "改别名",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(),
                        onClick = onAlias,
                    ),
            )
            Spacer(Modifier.width(8.dp))
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
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("移出清单") },
                onClick = {
                    menuOpen = false
                    onRemove()
                },
            )
        }
    }
}

/** One-line rename of a single model for one provider; blank drops the alias. */
@Composable
private fun AliasDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("模型别名") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("显示名（留空 = 用回原 id）") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** Type a model id the endpoint never listed; it joins the list at once. */
@Composable
private fun AddModelDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义模型") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("模型 id") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = value.isNotBlank(),
                onClick = { onConfirm(value.trim()) },
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
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
            imageVector = ImageVector.vectorResource(R.drawable.ic_chevron_right),
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
