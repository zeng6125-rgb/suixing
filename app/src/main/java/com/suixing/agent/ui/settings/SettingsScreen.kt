@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.suixing.agent.ui.settings

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suixing.agent.R
import com.suixing.agent.capability.CapabilityState
import com.suixing.agent.core.Core
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.Conversation
import com.suixing.agent.data.label
import com.suixing.agent.data.COMPRESS_MAX_PERCENT
import com.suixing.agent.data.COMPRESS_MIN_PERCENT
import com.suixing.agent.data.COMPRESS_STEP_PERCENT
import com.suixing.agent.llm.MAX_RETRY_ATTEMPTS
import com.suixing.agent.llm.MAX_RETRY_DELAY_SECONDS
import com.suixing.agent.llm.MIN_RETRY_DELAY_SECONDS
import com.suixing.agent.data.ProviderConfig
import com.suixing.agent.data.PROTOCOL_ANTHROPIC
import com.suixing.agent.data.PROTOCOL_OPENAI
import com.suixing.agent.data.PROTOCOL_RESPONSES
import com.suixing.agent.data.groupModels
import com.suixing.agent.llm.ChatMessage
import com.suixing.agent.llm.LlmClient
import com.suixing.agent.llm.describeError
import com.suixing.agent.ui.components.EmptyState
import com.suixing.agent.ui.components.PageTopBar
import com.suixing.agent.ui.components.REASONING_LEVELS
import com.suixing.agent.ui.components.RowChevron
import com.suixing.agent.ui.components.SegmentedPill
import com.suixing.agent.ui.components.SettingRow
import com.suixing.agent.ui.components.SoftIcon
import com.suixing.agent.ui.components.StatusPill
import com.suixing.agent.ui.components.SuixingCard
import com.suixing.agent.ui.components.suixingClickable
import com.suixing.agent.ui.theme.LocalDarkTheme
import com.suixing.agent.ui.theme.Tone
import com.suixing.agent.ui.theme.color
import com.suixing.agent.ui.usage.UsageScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

/** How long the provider-name field waits after the last keystroke before writing through. */
private const val NAME_WRITE_DEBOUNCE_MS = 400L

/**
 * The pages the root list opens. 权限 is deliberately missing: it is not a page of this
 * screen at all, it hands off to the capability list, which is a list the user manages
 * rather than settings the user toggles.
 *
 * Each row is its own shell route ([route]), not a branch of a `when`: a page that swaps
 * in place inside one destination gets no enter/exit transition at all — the shell's
 * NavHost is the only thing that animates, and it only animates between destinations.
 */
private enum class SettingsPage(val title: String, val route: String) {
    MODEL("模型与生成", "settings/model"),
    CONVERSATION("对话配置", "settings/conversation"),
    APPEARANCE("外观", "settings/appearance"),
    NETWORK("网络与语音", "settings/network"),
    DATA("数据", "settings/data"),
    CHATS("会话管理", "settings/chats"),
    USAGE("用量", "settings/usage"),
    PERF("性能", "settings/perf"),
}

/**
 * Settings, in two levels: six rows, each pushing its own route.
 *
 * The list is all this screen is; every sub-page is a separate destination of its own
 * (see [SettingsPageRoute]), which is what gives them a transition and a real back entry.
 * The arrow in the header goes to [onBack] (out of the screen); the way back to the list
 * is [onBack] of the sub-page, which the shell hands `popBackStack`.
 *
 * [onOpen] is also how a row that is really another screen gets there — MCP, memory and
 * the capability list each keep their own page.
 */
@Composable
fun SettingsScreen(onOpen: (String) -> Unit, onBack: () -> Unit) {
    SettingsRoot(
        onBack = onBack,
        onOpen = onOpen,
        onOpenPage = { onOpen(it.route) },
    )
}

/**
 * One settings sub-page, by route. The pages themselves are private to this file, so the
 * shell has to come through here rather than naming them itself.
 *
 * A route that is not one of [SettingsPage]'s draws nothing: the shell only ever registers
 * these six, and a blank frame beats crashing a released app over a typo.
 */
@Composable
internal fun SettingsPageRoute(
    route: String,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit = {},
) {
    when (SettingsPage.entries.firstOrNull { it.route == route }) {
        SettingsPage.MODEL -> ModelSettingsPage(onBack = onBack)
        SettingsPage.CONVERSATION -> ConversationSettingsPage(onBack = onBack)
        SettingsPage.APPEARANCE -> AppearanceSettingsPage(onBack = onBack)
        SettingsPage.NETWORK -> NetworkVoiceSettingsPage(onBack = onBack)
        SettingsPage.DATA -> DataSettingsPage(onBack = onBack, onOpen = onOpen)
        SettingsPage.CHATS -> ConversationManagerScreen(
            onBack = onBack,
            onOpenConversation = onOpenConversation,
        )
        SettingsPage.USAGE -> UsageScreen(onBack = onBack)
        SettingsPage.PERF -> PerfScreen(onBack = onBack)
        null -> Unit
    }
}

/**
 * The home list: a live status card first, then every entry in a group.
 *
 * 状态先给，入口后给；一组一张圆角卡片，行是「标题 + 当前值 + 箭头」，点进去才是控件。
 * 这一页只做导航：每一行都通到一个独立页面，没有一个开关留在列表里。
 */
@Composable
private fun SettingsRoot(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenPage: (SettingsPage) -> Unit,
) {
    val capabilities by Core.capabilities.state.collectAsState()
    val settings by Core.settings.flow.collectAsState(initial = AppSettings())

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
            // 72dp 的页尾：最后一行不被导航条压住。
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SettingsStatusCard(capabilities, settings) }
            item {
                SettingsGroup(
                    title = "模型与对话",
                    rows = listOf(
                        // 走 `onOpen(...route)`（真路由）而不是 `onOpenPage(...)`（同一个目的地里换内容）：
                        // 内部切换不经过 NavHost ⇒ 没有任何转场。路由本来就存在（`SettingsPageRoute` 按
                        // `SettingsPage.route` 分发），所以这里只是改走它；用 `SettingsPage.X.route` 而不是
                        // 手写字符串，避免两处各写一份路由名。
                        SettingsGroupRow("模型与生成", "接口、模型与采样") {
                            onOpen(SettingsPage.MODEL.route)
                        },
                        SettingsGroupRow("对话配置", "输入、运行、失败重试与显示") {
                            onOpen(SettingsPage.CONVERSATION.route)
                        },
                        SettingsGroupRow("外观", "主题、配色与排版") {
                            onOpen(SettingsPage.APPEARANCE.route)
                        },
                    ),
                )
            }
            item {
                SettingsGroup(
                    title = "能力与连接",
                    rows = listOf(
                        // 权限不是这一页的子页：能力清单是一份用户管理的清单，不是一组开关。
                        SettingsGroupRow("权限", "无障碍、Shizuku、root 与悬浮窗") {
                            onOpen("capability")
                        },
                        SettingsGroupRow("工具", "工具、Skill 与 MCP 的管理") {
                            onOpen("tools")
                        },
                        SettingsGroupRow("网络与语音", "搜索走哪里、回复读不读出来") {
                            onOpen(SettingsPage.NETWORK.route)
                        },
                    ),
                )
            }
            item {
                SettingsGroup(
                    title = "数据与关于",
                    rows = listOf(
                        SettingsGroupRow("数据", "记忆、助手与会话文件") {
                            onOpen(SettingsPage.DATA.route)
                        },
                        SettingsGroupRow("用量", "tokens · 30 日柱状 · 按模型") {
                            onOpen(SettingsPage.USAGE.route)
                        },
                        // 性能页回答的是「这一轮到底慢在哪」：耗时、tokens、缓存命中都来自端点
                        // 自己的回报，不是估算；没采集到的项显示「—」，不用 0 冒充测量。
                        SettingsGroupRow("性能", "首字延迟 · 整轮耗时 · 缓存命中 · 输出速度") {
                            onOpen(SettingsPage.PERF.route)
                        },
                        SettingsGroupRow("关于", "版本、隐私与开源许可") {
                            onOpen("about")
                        },
                    ),
                )
            }
        }
    }
}

/**
 * 实时状态卡：先给状态，再给入口。
 *
 * 两条都是现成的读数——[Core.capabilities] 的探测结果，和 [Core.settings] 里的当前模型——
 * 这里只是把它们放到第一屏，不新造数据源。
 */
@Composable
private fun SettingsStatusCard(capabilities: CapabilityState, settings: AppSettings) {
    val tier = when {
        capabilities.rootReady -> "Root 档"
        capabilities.shellReady -> "Shizuku 档"
        capabilities.accessibility -> "无障碍档"
        else -> "标准档"
    }
    val checks = buildString {
        append(if (capabilities.accessibility) "无障碍 已开" else "无障碍 未开")
        append(" · ")
        append(if (capabilities.shellReady) "Shizuku 已连" else "Shizuku 未连")
        append(" · ")
        append(if (capabilities.rootReady) "Root 已就绪" else "Root 未检测")
    }
    val model = if (settings.configured) {
        val provider = settings.activeProvider()?.label
        "模型：${settings.shortModel}" + if (provider.isNullOrBlank()) "" else "（$provider）"
    } else {
        "模型：还没配置接口与密钥"
    }

    SuixingCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SoftIcon(ImageVector.vectorResource(R.drawable.ic_shield_user))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("当前能力", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = checks,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            StatusPill(
                text = tier,
                tone = if (tier == "标准档") Tone.OFF else Tone.OK,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = model,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One row of a group: the title and the value it currently holds, plus the way in. */
private class SettingsGroupRow(
    val title: String,
    val summary: String,
    val onClick: () -> Unit,
)

/** A group name over one rounded card that holds all of that group's rows. */
@Composable
private fun SettingsGroup(title: String, rows: List<SettingsGroupRow>) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            // 行铺满卡片（横向留白交给 SettingRow 的 contentPadding）：涟漪因此能碰到卡片的左右
            // 边缘与圆角，而不是被容器的 16dp 缩在中间 —— 用户报的「涟漪没填满/没变化」就是它。
            // 分割线仍缩进 16dp，与文字左缘对齐。
            Column {
                rows.forEachIndexed { index, row ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    SettingsLink(
                        title = row.title,
                        subtitle = row.summary,
                        onClick = row.onClick,
                        shape = MaterialTheme.shapes.large,
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

/**
 * The write path for every page without a save button: the control's new value goes
 * straight into the repository, so a switch that is flipped and then left behind is
 * already saved when the process dies. 模型与生成 keeps a draft instead, because a
 * half-typed endpoint is not a setting yet.
 */
internal fun CoroutineScope.persist(block: (AppSettings) -> AppSettings) {
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
    // The name is a write-through field — nothing to save — but one DataStore transaction per
    // keystroke made every observer of the settings flow recompose, which is what turned the
    // subtitle above into a flicker and the title into a shake. So the draft takes the
    // keystroke and the write happens once the typing stops. The scope is the app's, not the
    // screen's: a rememberCoroutineScope would be cancelled on the way out and drop the name.
    var pendingName by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(pendingName) {
        val pending = pendingName ?: return@LaunchedEffect
        delay(NAME_WRITE_DEBOUNCE_MS)
        Core.appScope.persistProvider(pending.first) { it.copy(name = pending.second) }
        if (pendingName == pending) pendingName = null
    }
    // Leaving the page cancels the effect above, so a name still waiting out its debounce is
    // written here instead — the user typed it, it must not evaporate with the screen.
    DisposableEffect(Unit) {
        onDispose {
            pendingName?.let { pending ->
                Core.appScope.persistProvider(pending.first) { it.copy(name = pending.second) }
            }
        }
    }
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
                // 副标题（`if (dirty) "有未保存的改动"`）搬到内容区第一行 —— 它是这一页**唯一**的
                // 真信息，不能随「任何页都不要副标题」一起丢掉（见下面 LazyColumn 的第一项）。
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        // 两个按钮按内容居中：转圈那一枚比文字矮一点，Top 对齐会让这一排看起来
                        // "两个按钮不一样高"（用户截图里报的就是这个）。
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
                                // size，不是 height：只给高度的话，M3 的指示器会按自己的 40dp
                                // 直径去要宽度，最后画成一个 40×16 的扁弧——用户看到的就是
                                // 「icon 飞到哪里去了」。转圈只占图标的位置，标签照旧跟着它。
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 从顶栏搬下来的「有未保存的改动」（顶栏不再有副标题）：dirty 时才出现，放在内容区
            // 第一行 —— 用户改完设置抬头就能看到，不用去找保存键。
            if (dirty) {
                item {
                    Text(
                        text = "有未保存的改动",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
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
                                    // clip 必须在 combinedClickable **之前**：涟漪要跟着 chip 的圆角
                                    // 走，否则它是一块方角盖在圆角 chip 上（全文件唯一一处漏了 clip）。
                                    modifier = Modifier
                                        .clip(MaterialTheme.shapes.small)
                                        .combinedClickable(
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
                        // 删除原来只藏在「长按 chip」里（onLongClick），用户找不到 —— 这里给一个
                        // 可见入口，删的是**当前选中的**提供方；只剩一个时禁用（删完会没有接口可用，
                        // deleteProvider 也要求至少留一个）。
                        val current = form.providers.firstOrNull { it.id == selected?.id }
                        if (current != null) {
                            Spacer(Modifier.width(8.dp))
                            AssistChip(
                                enabled = form.providers.size > 1,
                                onClick = {
                                    if (endpointEdited) {
                                        pendingDelete = current.id
                                    } else {
                                        deleteProvider(current)
                                    }
                                },
                                label = { Text("删除") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = ImageVector.vectorResource(R.drawable.ic_x),
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            item {
                // 协议在地址之上：换了协议，同一个地址要拼的路径、要带鉴权头都不一样，
                // 所以用户是先选「这个端点说哪种话」，再填地址。
                ProtocolPicker(
                    protocol = selected?.protocol.orEmpty(),
                    onPick = { picked ->
                        form = form.mapProvider(selected?.id) { it.copy(protocol = picked) }
                        // 结构性选择（同「独立采样参数」）：点选即落盘，不留草稿。
                        scope.persist { it.copy(providers = form.providers) }
                    },
                )
                // Anthropic 的提示只在这个协议下出现：默认地址栏那句「填到 /v1 即可」
                // 对它的意义最具体 —— 路径由客户端补 /messages。
                if (selected?.protocol == PROTOCOL_ANTHROPIC) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "地址填到 /v1 即可，/messages 会自动补上。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                OutlinedTextField(
                    // 15sp 走比例式而不是 `15.sp`：写死 sp 会关掉「外观 → 字号」的缩放（规范第 6 条），
                    // 详见文件底部 settingsFieldText 的 KDoc。
                    textStyle = settingsFieldText,
                    value = form.baseUrl,
                    onValueChange = { value ->
                        form = form.copy(baseUrl = value)
                            .mapProvider(selected?.id) { it.copy(baseUrl = value) }
                    },
                    // 设置页输入框的统一规格（本文件其余十几处同此，不重复注释）：
                    // bodyMedium(14sp) + `heightIn(min = 52.dp)`。**别再压死高度**——写死
                    // height(48.dp) 会在「外观 → 字号」放大时裁掉汉字下缘（工具页搜索框就是这么
                    // 出事的，那段注释在 ToolsScreen.kt:524-527）；矮是字号给的，不是把盒子压扁来的。
                    label = { Text("接口地址") },
                    // 明文 http 是刻意留着的（局域网里的 Ollama / LM Studio 就只有 http），但
                    // key 会跟着明文走——这一句是用户敲下那个地址之前唯一的提醒。
                    supportingText = {
                        Text(
                            "例如 https://api.deepseek.com/v1\n" +
                                "填 http:// 地址时，API Key 会明文传输，仅建议在可信局域网内使用。",
                        )
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                )
            }
            item {
                // The provider's own name, editable in place: the chip row's long-press
                // rename stays, but the name is read every day and typed once a month.
                OutlinedTextField(
                    // 单行输入框的统一规格（本文件其余十几处同此，不重复注释）：
                    // bodyMedium(14sp) + `heightIn(min = 52.dp)`。
                    //
                    // **为什么看起来「没变矮」**（写给下一轮，别再试 `height(...)`）：
                    // material3 1.3.1 的 `OutlinedTextField` 自带 **56dp 最小高度**
                    // （TextFieldDefaults.MinHeight），而且**没有 `contentPadding` 参数**
                    // （OutlinedTextFieldDefaults.contentPadding() 只服务自绘 decoration）
                    // ⇒ 在不裁字的前提下**压不到 56dp 以下**；写死 `height(52.dp)` 是把 56dp 的
                    // 容器硬压扁，会裁掉汉字下缘（工具页搜索框就是这么出过事的）。要真的更矮，
                    // 只能手绘输入框 —— 那是另一个量级的活。所以这里用 min：跟着字号长、永不裁字。
                    textStyle = settingsFieldText,
                    value = selected?.name.orEmpty(),
                    onValueChange = { value ->
                        form = form.mapProvider(selected?.id) { it.copy(name = value) }
                        // 只改草稿：每敲一键写一次盘，会让所有观察 settings 的地方跟着重组，
                        // 内容区那一行「有未保存的改动」一闪一灭，看起来就是那行字在抖。停手后再落盘。
                        pendingName = selected?.id?.takeIf { it.isNotEmpty() }?.let { it to value }
                    },
                    label = { Text("提供方名称") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
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
                    textStyle = settingsFieldText,
                    value = form.apiKey,
                    onValueChange = { value ->
                        form = form.copy(apiKey = value)
                            .mapProvider(selected?.id) { it.copy(apiKey = value) }
                    },
                    label = { Text("API Key") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                )
            }
            item {
                // The raw id, not the alias: what is typed here is what goes on the wire, so
                // a display name typed or pasted in would be sent as the model and 404. The
                // alias stays read-only, under the field and on the picker's chips.
                val alias = selected?.aliases?.get(form.model)
                OutlinedTextField(
                    textStyle = settingsFieldText,
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
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
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
                                runCatching { client.listModelDetails(form) }.fold(
                                    onSuccess = { details ->
                                        val fetched = details.map { it.id }
                                        val merged = (fetched + selected?.models.orEmpty()).distinct()
                                        // 上游声明的窗口只在用户没设过时填入（withUpstreamDefaults：
                                        // 键已存在就一个字都不动），并和清单在同一事务里落盘。
                                        val windows = details.mapNotNull { model ->
                                            model.contextLength?.let { model.id to it }
                                        }.toMap()
                                        form = form.copy(
                                            modelContexts = form.modelContexts.withUpstreamDefaults(windows),
                                        ).mapProvider(selected?.id) { it.copy(models = merged) }
                                        // 拉到的清单属于提供方结构，同样点选即落盘。
                                        scope.persist {
                                            it.copy(
                                                providers = form.providers,
                                                modelContexts = form.modelContexts,
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
            item(key = "max-tokens") {
                // 最大输出**按模型**（用户：「取消全局性的，改用各模型默认＋可自定义，跟上下文
                // 窗口一样」）：存 [AppSettings.modelMaxTokens]，键是裸模型 id（与 modelContexts
                // 同一套键规则）；留空＝不说、模型/端点默认。老文件的 provider 级值由迁移搬进来。
                //
                // 输入框的 value 是**本地 raw 文本**，不是从 form 反推（用户：「输入一个，输入框自动
                // 关了」）：打第一个字符时 parsed 写回 form、整页重组、无 key 的 item 被重建 ⇒ 焦点
                // 丢、键盘收。raw 只跟手指走，form 只在解析后单向写；item 带 key 稳定身份。
                val model = form.model
                val modelMax = form.maxTokensFor(model)
                var raw by remember(model) {
                    mutableStateOf(if (modelMax <= 0) "" else modelMax.toString())
                }
                OutlinedTextField(
                    textStyle = settingsFieldText,
                    value = raw,
                    onValueChange = { input ->
                        val digits = input.filter { it.isDigit() }.take(6)
                        raw = digits
                        val parsed = digits.toIntOrNull() ?: 0
                        form = form.copy(
                            modelMaxTokens = form.modelMaxTokens.withMaxTokens(model, parsed),
                        )
                        scope.persist { it.copy(modelMaxTokens = form.modelMaxTokens) }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    label = { Text("当前模型最大输出 tokens（留空 = 模型默认）") },
                    placeholder = { Text("例如 4096") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = MaterialTheme.shapes.small,
                )
            }
            // 「最大工具轮数」与「显示思考过程」搬到了「对话配置」页：一个是它一次任务能跑几轮、
            // 一个是对话里显示什么，都不属于模型端点本身。字段与存储没动。
            //
            // 「系统提示词」编辑器已从这里移除（用户：「模型设置页不再支持修改系统提示词，
            // 反正助手页可更改」）：人设属于助手，改人设去「助手」页；这里再放一个全局编辑框，
            // 等于同一个东西有两个出处。settings.systemPrompt 字段本身保留 —— 引擎每轮仍然
            // 把它拼进请求（AgentEngine.systemPrompt 的第一段），助手页写的就是它。
            // Sampling lives behind a fold: these three are set once and then never
            // touched, and they were the rows pushing the daily switches off the screen.
            item {
                AdvancedSection {
                    // 独立采样: off = the four knobs below read and write the globals,
                    // on = they read and write this provider's nullable overrides.
                    SettingRow(
                        title = "独立采样参数",
                        subtitle = "开启后温度 / Top P / 思考强度只作用于当前提供方。",
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
                        hint = "对应接口的 reasoning_effort；xhigh / max 仅部分模型支持，不支持的忽略。",
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
                    Spacer(Modifier.height(12.dp))
                    // 自定义请求头（用户：「模型与生成要加入请求头的设置有些需要，加到高级参数」）：
                    // 一行一条 `Name: Value`，随每个请求发出（对话与模型列表都带）。raw 文本跟手指
                    // 走、form 解析后单向写 —— 与最大输出输入框同一条教训：value 从 form 反推会在
                    // 每次击键重组时丢焦点。
                    var headersRaw by remember {
                        mutableStateOf(
                            form.requestHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" },
                        )
                    }
                    LabeledBlock(
                        label = "自定义请求头",
                        hint = "一行一条，格式 Name: Value；随每个请求发送（含模型列表拉取）。留空＝不加。",
                    ) {
                        OutlinedTextField(
                            textStyle = MaterialTheme.typography.bodyMedium,
                            value = headersRaw,
                            onValueChange = { input ->
                                headersRaw = input
                                val parsed = input.lines()
                                    .mapNotNull { line ->
                                        val at = line.indexOf(':')
                                        if (at <= 0) return@mapNotNull null
                                        val name = line.substring(0, at).trim()
                                        val value = line.substring(at + 1).trim()
                                        if (name.isEmpty()) return@mapNotNull null
                                        name to value
                                    }
                                    .toMap()
                                form = form.copy(requestHeaders = parsed)
                                scope.persist { it.copy(requestHeaders = parsed) }
                            },
                            minLines = 3,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("X-Custom-Header: value") },
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
            maxTokensMap = form.modelMaxTokens,
            provider = selected,
            onContext = { model, window ->
                // Written through immediately: the window belongs to the model, and the
                // dialog's confirm button is about which models are enabled. The draft is
                // updated first so the page's other unsaved edits survive the write.
                form = form.copy(modelContexts = form.modelContexts.withContextWindow(model, window))
                scope.persist { it.copy(modelContexts = form.modelContexts) }
            },
            onMaxTokens = { model, tokens ->
                form = form.copy(modelMaxTokens = form.modelMaxTokens.withMaxTokens(model, tokens))
                scope.persist { it.copy(modelMaxTokens = form.modelMaxTokens) }
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
                // provider 不再默认指定模型（用户：「对于 provider 不再默认指定模型」）：
                // 勾选只是把模型加进这个提供方的可用清单，用哪个由聊天页的模型选择器决定。
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

/**
 * 对话配置: 一次对话从输入到显示的全部行为开关，四段按时间线排——输入（你敲下去之后发生
 * 什么）、运行（它怎么跑、能不能动手、上下文满了怎么办）、失败处理（网络/超时/5xx 怎么办）、
 * 显示（跑完之后你看到什么）。
 *
 * 这些开关从四个页里搬过来（模型与生成、插话与运行、外观，以及新加的三项重试），**字段名与
 * 存储一个都没动**：只换 UI 位置，老配置照旧生效。搬走的行在原页删掉了——同一个字段留两份
 * 草稿会互相覆盖。
 *
 * 本页用「即改即存」（[persist]），与外观/网络/数据三页一致；整页只有模型与生成那页是
 * 「草稿 + 保存」，因为它的字段要跟端点一起做整体校验，这些开关不需要。
 */
@Composable
private fun ConversationSettingsPage(onBack: () -> Unit) {
    val stored by Core.settings.flow.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val allowed by Core.gate.alwaysAllowedTools.collectAsState()
    val denied by Core.gate.alwaysDeniedTools.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { PageTopBar(title = SettingsPage.CONVERSATION.title, onBack = onBack) },
        snackbarHost = {
            SnackbarHost(snackbar, modifier = Modifier.navigationBarsPadding())
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { SectionHeader("输入") }
            item {
                SettingRow(
                    title = "回车键发送",
                    subtitle = "开：按回车直接发送（运行中则当作插话送进去）；关：回车换行，" +
                        "用发送键提交。",
                    control = {
                        Switch(
                            checked = stored.enterKeySends,
                            onCheckedChange = { on -> scope.persist { it.copy(enterKeySends = on) } },
                        )
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
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
            item { SectionHeader("运行") }
            item {
                // 0 是「不限制」，但把 0 直接摆在标签上读起来像个没配好的空值——用户原话
                // 「把 0 字样改成无限」。存储值不变（仍是 0），引擎那侧 `roundLimit <= 0`
                // 就是不限（AgentEngine.kt:975），所以这里只改给人看的那个词。
                LabeledSlider(
                    label = if (stored.maxToolRounds <= 0) {
                        "最大工具轮数 无限"
                    } else {
                        "最大工具轮数 ${stored.maxToolRounds}"
                    },
                    hint = "一次任务里最多连续调用多少次工具；0＝不限轮数（显示为「无限」），" +
                        "拖到最左就是不限制。",
                    value = stored.maxToolRounds.toFloat(),
                    range = 0f..40f,
                    steps = 39,
                    onValueChange = { turns -> scope.persist { it.copy(maxToolRounds = turns.toInt()) } },
                )
            }
            item {
                // 一轮里最多几条工具调用并发跑。1＝串行（默认，与历史行为一字不差）；N>1 时一轮内
                // 最多 N 条同时执行、结果仍按模型请求的顺序回写。只在 AUTO 审批档生效（ASK 档的
                // 授权弹窗每会话单槽，并发会互相顶掉），故 ASK 时引擎强制串行 —— 这里照常显示设置。
                LabeledSlider(
                    label = if (stored.toolParallel <= 1) {
                        "工具并行数 串行"
                    } else {
                        "工具并行数 ${stored.toolParallel}"
                    },
                    hint = "模型一轮里请求的多个工具，最多几条同时跑。1＝逐个串行（默认，最稳）；" +
                        "调大可加快多工具任务，但 shell 类工具并发可能互相踩（同时写一个文件）。" +
                        "审批档为「每次询问」时强制串行（授权弹窗一次只挂一个）。",
                    value = stored.toolParallel.toFloat(),
                    // 4 个档位（1/2/3/4）＝ steps+2，故 steps=2（与上面 0..40/steps=39 同构）。
                    range = 1f..4f,
                    steps = 2,
                    onValueChange = { n -> scope.persist { it.copy(toolParallel = n.toInt()) } },
                )
            }
            // 「自动整理用户画像」与「画像整理间隔」搬到了用户画像页（ProfileScreen）：它们管的是
            // 那份画像本身怎么维护，与这一页的对话行为不是一回事，而「数据」页是纯入口列表、
            // 放开关会破坏它的形态。字段与存储没动。
            item {
                LabeledBlock(label = "工具审批", hint = "模型每次动手前要不要问你。") {
                    SegmentedPill(
                        options = ApprovalMode.entries.map { it.label },
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
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                SettingRow(
                    title = "自动压缩阈值",
                    subtitle = "上下文用到窗口的这个比例时，自动把较早的对话压成摘要；" +
                        "调高更省钱但更容易触顶，调低更保险但会提前丢细节。" +
                        (if (stored.autoCompress) "" else "（自动压缩已关闭，这一项暂不生效。）"),
                    control = {
                        // 关掉开关也留着这一行，只是置灰：藏起来用户就找不到它了。
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.alpha(if (stored.autoCompress) 1f else 0.38f),
                        ) {
                            IconButton(
                                enabled = stored.autoCompress,
                                onClick = {
                                    scope.persist { s ->
                                        s.copy(
                                            compressAtPercent =
                                                (s.compressAtPercent - COMPRESS_STEP_PERCENT)
                                                    .coerceIn(COMPRESS_MIN_PERCENT, COMPRESS_MAX_PERCENT),
                                        )
                                    }
                                },
                            ) { Text("−", style = MaterialTheme.typography.titleLarge) }
                            Text(
                                text = "${stored.compressAtPercent.coerceIn(
                                    COMPRESS_MIN_PERCENT,
                                    COMPRESS_MAX_PERCENT,
                                )}%",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.widthIn(min = 44.dp),
                            )
                            IconButton(
                                enabled = stored.autoCompress,
                                onClick = {
                                    scope.persist { s ->
                                        s.copy(
                                            compressAtPercent =
                                                (s.compressAtPercent + COMPRESS_STEP_PERCENT)
                                                    .coerceIn(COMPRESS_MIN_PERCENT, COMPRESS_MAX_PERCENT),
                                        )
                                    }
                                },
                            ) { Text("+", style = MaterialTheme.typography.titleLarge) }
                        }
                    },
                )
            }
            item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
            item { SectionHeader("失败处理") }
            item {
                SettingRow(
                    title = "失败自动重试",
                    subtitle = "只对网络中断、超时、5xx 与 429 重试；已经开始回答则不重试——那会把" +
                        "半截回答重来一遍，比重试更糟。",
                    control = {
                        Switch(
                            checked = stored.retryOnFailure,
                            onCheckedChange = { on -> scope.persist { it.copy(retryOnFailure = on) } },
                        )
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item {
                LabeledSlider(
                    label = if (stored.retryMaxAttempts <= 0) {
                        "最多重试次数 不重试"
                    } else {
                        "最多重试次数 ${stored.retryMaxAttempts.coerceIn(0, MAX_RETRY_ATTEMPTS)}"
                    },
                    hint = "一次请求最多重试几次；0＝不重试。只在上面那个开关打开时生效。",
                    value = stored.retryMaxAttempts.coerceIn(0, MAX_RETRY_ATTEMPTS).toFloat(),
                    range = 0f..MAX_RETRY_ATTEMPTS.toFloat(),
                    steps = MAX_RETRY_ATTEMPTS - 1,
                    onValueChange = { n -> scope.persist { it.copy(retryMaxAttempts = n.toInt()) } },
                )
            }
            item {
                LabeledSlider(
                    label = "重试间隔 ${stored.retryDelaySeconds} 秒",
                    hint = "两次重试之间等多久。等得久一点更可能躲过对方的限流。",
                    value = stored.retryDelaySeconds
                        .coerceIn(MIN_RETRY_DELAY_SECONDS, MAX_RETRY_DELAY_SECONDS)
                        .toFloat(),
                    range = MIN_RETRY_DELAY_SECONDS.toFloat()..MAX_RETRY_DELAY_SECONDS.toFloat(),
                    steps = MAX_RETRY_DELAY_SECONDS - MIN_RETRY_DELAY_SECONDS - 1,
                    onValueChange = { s -> scope.persist { it.copy(retryDelaySeconds = s.toInt()) } },
                )
            }
            item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
            item { SectionHeader("显示") }
            item {
                SettingRow(
                    title = "显示思考过程",
                    subtitle = "把模型的思考流式画在回复上方。关掉只是不显示，思考强度照旧生效。",
                    control = {
                        Switch(
                            checked = stored.showReasoning,
                            onCheckedChange = { on -> scope.persist { it.copy(showReasoning = on) } },
                        )
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item {
                SettingRow(
                    title = "历史思考块展开",
                    subtitle = "正在思考的那一块总是展开。开＝历史思考块也展开；关＝历史块收起成一行标题，点它仍可逐块展开。",
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
                    title = "思考块跟随最新",
                    subtitle = "思考过程流式输出时自动滚到底部；关掉后停在当前位置",
                    control = {
                        Switch(
                            checked = stored.followReasoning,
                            onCheckedChange = { on -> scope.persist { it.copy(followReasoning = on) } },
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 72.dp),
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
                LabeledBlock(
                    label = "打字机效果",
                    hint = "正在生成的正文与思考逐字显示；关掉则一收到就整块出现。已结束的回答不受影响。",
                ) {
                    SegmentedPill(
                        options = listOf("开", "关"),
                        selectedIndex = if (stored.typewriter) 0 else 1,
                        onSelect = { index -> scope.persist { it.copy(typewriter = index == 0) } },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            // 思考块与「看到什么」的六项搬到了「对话配置」页：它们是对话内容的显示方式，不是
            // 主题/排版。字段与存储没动。
            item {
                LabeledBlock(
                    label = "提示条自动消失",
                    hint = "屏幕上方那条提示（例如连接中断）几秒后自己消失；「不消失」只能手点 × 关掉。" +
                        "回答卡片里的原因不受这里影响。",
                ) {
                    SegmentedPill(
                        options = listOf("5 秒", "10 秒", "30 秒", "不消失"),
                        selectedIndex = when (stored.bannerSeconds) {
                            5 -> 0
                            30 -> 2
                            0 -> 3
                            else -> 1
                        },
                        onSelect = { index ->
                            scope.persist { it.copy(bannerSeconds = listOf(5, 10, 30, 0)[index]) }
                        },
                    )
                }
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
                                // 这一页现在是七项：新加的提示条时长与打字机效果也在「恢复默认外观」里，
                                // 否则按了恢复却留着上一轮的设置，用户会以为按钮坏了。
                                bannerSeconds = defaults.bannerSeconds,
                                typewriter = defaults.typewriter,
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                OutlinedTextField(
                    textStyle = settingsFieldText,
                    value = stored.searchEndpoint,
                    onValueChange = { value -> scope.persist { it.copy(searchEndpoint = value) } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
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
                    textStyle = settingsFieldText,
                    value = stored.searchApiKey,
                    onValueChange = { value -> scope.persist { it.copy(searchApiKey = value) } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
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
            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(
                    title = "语音输入",
                    subtitle = "开＝输入框右侧显示麦克风，用随行自己的录音发给你配置的转写接口（不会再弹系统识别面板）；" +
                        "关＝完全不显示麦克风。",
                    control = {
                        Switch(
                            checked = stored.voiceInput,
                            onCheckedChange = { on -> scope.persist { it.copy(voiceInput = on) } },
                        )
                    },
                )
            }
            if (stored.voiceInput) {
                item {
                    OutlinedTextField(
                        textStyle = settingsFieldText,
                        value = stored.asrModel,
                        onValueChange = { value -> scope.persist { it.copy(asrModel = value) } },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                        label = { Text("语音转写模型") },
                        placeholder = { Text("whisper-1") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        supportingText = {
                            Text("留空时点麦克风只会提示来这里填。转写用上面那对服务商（baseUrl 与 Key），接口：/audio/transcriptions。")
                        },
                    )
                }
            }
        }
    }
}

/**
 * 数据：只有「内容存在哪」的几条入口，每一行都通到一个只属于数据的地方。
 *
 * MCP 与技能搬去了工具页，悬浮窗的授权卡本来就在权限页（CapabilityScreen 里的
 * 「悬浮窗控制台」卡，granted = FloatConsoleService.canDrawOverlays），关于自己成了一页
 * （[AboutScreen]）——这一页原来什么都放一点，重复的入口正是要删的东西。
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingsGroup(
                    title = "记忆",
                    rows = listOf(
                        SettingsGroupRow("用户记忆", "模型自己写下的长期备忘") {
                            onOpen("extension")
                        },
                        SettingsGroupRow("用户画像", "模型自己维护的长期自述：称呼、偏好与习惯，也可手改") {
                            onOpen("profile")
                        },
                    ),
                )
            }
            item {
                SettingsGroup(
                    title = "助手与文件",
                    rows = listOf(
                        SettingsGroupRow("助手设置", "每个助手的名字、提示词、模型与工具") {
                            onOpen("assistants")
                        },
                        // 会话记录本身的管理：打开 / 导出 / 删除。与下面那行区分开——那行是
                        // agent 读写的工作区文件，不是聊天记录。
                        SettingsGroupRow("会话管理", "打开、导出或删除聊天记录（不是工作区文件）") {
                            onOpen(SettingsPage.CHATS.route)
                        },
                        SettingsGroupRow("工作区文件", "agent 在会话里读写的工作区文件，与聊天记录无关") {
                            onOpen("files")
                        },
                    ),
                )
            }
        }
    }
}

/**
 * 会话管理：聊天记录本身的管理界面。
 *
 * 为什么是**一页**而不是数据页里的一块：数据页是纯入口列表（SettingsGroup + SettingsGroupRow），
 * 塞进一个可滚动列表加三种操作会把它变成另一种页面；而设置体系里没有 TabRow 这种形态，所以
 * 用户说的「作为新 tab」在这里的等价物就是一个同级子页——数据页那行「会话管理」指过来。
 *
 * 只做三件事，且都不碰存储格式（ChatRepository 的 JSON 与 Conversation 字段一个字节不改）：
 *  · 打开 —— 走既有导航（和抽屉里点会话是同一条路：切到那个会话、收掉覆盖层）；
 *  · 导出 —— 把 filesDir/chats/<id>.json **原样**复制到用户用系统选择器指定的位置（不重新
 *    序列化，导出的字节和磁盘上那份完全一致）；
 *  · 删除 —— Core.chats.delete(id)，它删的就是那份 JSON（外加草稿与可能指向它的 last-opened 标记）。
 * 没有重命名：那会引入一个新字段与新概念，用户明确说不要。
 */
@Composable
internal fun ConversationManagerScreen(
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val conversations by Core.chats.conversations.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }
    var pendingExport by remember { mutableStateOf<Conversation?>(null) }
    // 详情弹窗（用户：「会话管理要有会话的文件，可以看会话详细日志、大小等等」）：文件路径/大小/
    // 修改时间 + 原始 JSON 尾部。文件信息在 IO 上读，弹窗开着才读一次。
    var pendingInfo by remember { mutableStateOf<Conversation?>(null) }
    var chatFileInfo by remember { mutableStateOf<ChatFileInfo?>(null) }
    LaunchedEffect(pendingInfo) {
        val chat = pendingInfo ?: return@LaunchedEffect
        chatFileInfo = withContext(Dispatchers.IO) { readChatFileInfo(context, chat.id) }
    }
    // 系统选择器（SAF）：不需要 FileProvider、不改 manifest，用户自己决定存到哪。
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val chat = pendingExport
        pendingExport = null
        if (uri == null || chat == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val source = File(context.filesDir, "chats/${chat.id}.json")
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        source.inputStream().use { it.copyTo(out) }
                    } ?: error("打不开目标文件")
                }.isSuccess
            }
            snackbar.showSnackbar(if (ok) "已导出「${chat.title}」" else "导出失败，换个位置再试")
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { PageTopBar(title = SettingsPage.CHATS.title, onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val active = conversations.filterNot { it.archived }.sortedByDescending { it.updatedAt }
            val archived = conversations.filter { it.archived }.sortedByDescending { it.updatedAt }
            if (active.isEmpty() && archived.isEmpty()) {
                item {
                    EmptyState(
                        title = "还没有会话",
                        subtitle = "聊过一次之后，会话会出现在这里，可以打开、导出或删除。",
                        modifier = Modifier.padding(top = 56.dp),
                    )
                }
            } else {
                items(active, key = { it.id }) { conversation ->
                    val bytes = remember(conversation.id) {
                        File(context.filesDir, "chats/${conversation.id}.json").let {
                            if (it.exists()) it.length() else -1L
                        }
                    }
                    ConversationRow(
                        title = conversation.title,
                        subtitle = "${lastUpdated(conversation.updatedAt)} · " +
                            "${conversation.messages.size} 条消息" +
                            (if (bytes >= 0) " · ${humanBytes(bytes)}" else ""),
                        onOpen = { onOpenConversation(conversation.id) },
                        onInfo = { pendingInfo = conversation },
                        onExport = {
                            pendingExport = conversation
                            exporter.launch(exportFileName(conversation.title))
                        },
                        onDelete = { pendingDelete = conversation },
                    )
                }
                // 已归档：抽屉里看不到它们（归档就是把它从那份列表里收起来），这一页是唯一的
                // 入口 —— 用户指定「已归档的显示搞到会话管理那里」。分组排在活动会话下面，
                // 每行多一枚「取消归档」，其余动作（详情/导出/删除）与活动行一致。
                if (archived.isNotEmpty()) {
                    item(key = "archived-header") {
                        Text(
                            text = "已归档（${archived.size}）",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                        )
                    }
                    items(archived, key = { it.id }) { conversation ->
                        ConversationRow(
                            title = conversation.title,
                            subtitle = "已归档 · ${lastUpdated(conversation.updatedAt)} · " +
                                "${conversation.messages.size} 条消息",
                            onOpen = { onOpenConversation(conversation.id) },
                            onInfo = { pendingInfo = conversation },
                            onExport = {
                                pendingExport = conversation
                                exporter.launch(exportFileName(conversation.title))
                            },
                            onDelete = { pendingDelete = conversation },
                            onUnarchive = {
                                scope.launch {
                                    Core.chats.setArchived(conversation.id, false)
                                    snackbar.showSnackbar("已取消归档「${conversation.title}」")
                                }
                            },
                        )
                    }
                }
            }
        }
    }
    pendingDelete?.let { chat ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这个会话？") },
            text = {
                Text(
                    "「${chat.title}」的聊天记录（${chat.messages.size} 条消息）会被永久删除，" +
                        "不能撤销。工作区里的文件不受影响。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            Core.chats.delete(chat.id)
                            snackbar.showSnackbar("已删除「${chat.title}」")
                        }
                    },
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
    pendingInfo?.let { chat ->
        ChatInfoDialog(info = chatFileInfo, onDismiss = { pendingInfo = null })
    }
}

/** 一行会话：点行打开，右侧动作（详情、导出、删除；已归档的多一枚「取消归档」）。 */
@Composable
private fun ConversationRow(
    title: String,
    subtitle: String,
    onOpen: () -> Unit,
    onInfo: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    /** 只有已归档的行给这个动作：取消归档（归档的入口在抽屉的行菜单里）。 */
    onUnarchive: (() -> Unit)? = null,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // 整行可点、涟漪按卡片自己的圆角铺满：原来 clickable 挂在内容 Row 上、
            // 外层 Surface 才是可见形状 ⇒ 涟漪只在内容块里画一小块。
            modifier = Modifier.suixingClickable(MaterialTheme.shapes.medium) { onOpen() },
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp, top = 12.dp, bottom = 12.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onInfo) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_info),
                    contentDescription = "详情",
                    modifier = Modifier.size(18.dp),
                )
            }
            // 取消归档排在导出之前：它是这一行唯一的「把会话放回抽屉」的动作，最常用。
            onUnarchive?.let { unarchive ->
                IconButton(onClick = unarchive) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_folder_open),
                        contentDescription = "取消归档",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            IconButton(onClick = onExport) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_download),
                    contentDescription = "导出",
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_trash),
                    contentDescription = "删除",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** 「最后更新」用人话（系统本地化）：3 分钟前 / 昨天 / 2026年10月12日。 */
private fun lastUpdated(millis: Long): String = DateUtils.getRelativeTimeSpanString(
    millis,
    System.currentTimeMillis(),
    DateUtils.MINUTE_IN_MILLIS,
).toString()

/** 导出文件名：标题里的路径分隔符与空白换成下划线，空标题兜底。 */
private fun exportFileName(title: String): String {
    val base = title.trim()
        .replace(Regex("""[\\/:*?"<>|\s]+"""), "_")
        .trim('_')
        .take(40)
    return "${base.ifBlank { "会话" }}.json"
}

/**
 * 会话文件的白盒信息（用户：「会话管理要有会话的文件，可以看详细日志、大小等等」）。
 * [tail] 是原始 JSON 的尾部——排查「这一轮到底存了什么」时尾部就是最新那几条消息。
 */
private data class ChatFileInfo(
    val path: String,
    val bytes: Long,
    val modifiedAt: Long,
    val tail: String,
)

private fun readChatFileInfo(context: android.content.Context, id: String): ChatFileInfo? =
    runCatching {
        val file = File(context.filesDir, "chats/$id.json")
        if (!file.exists()) return null
        val text = file.readText()
        ChatFileInfo(
            path = file.absolutePath,
            bytes = file.length(),
            modifiedAt = file.lastModified(),
            tail = text.takeLast(4000),
        )
    }.getOrNull()

/** 字节数用人话：1.2 KB / 3.4 MB。 */
private fun humanBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

/** 会话详情弹窗：文件三要素 + 原始 JSON 尾部（可滚动、等宽）。 */
@Composable
private fun ChatInfoDialog(info: ChatFileInfo?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("会话文件") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (info == null) {
                    Text("这个会话在磁盘上没有对应的文件（可能还没落盘）。")
                } else {
                    Text("路径：${info.path}", style = MaterialTheme.typography.labelSmall)
                    Text(
                        "大小：${humanBytes(info.bytes)} · 修改：${lastUpdated(info.modifiedAt)}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("原始记录（尾部 4000 字）：", style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = info.tail,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
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
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    // clip(圆角) 在 clickable 之前：原来是裸 .clickable，涟漪是方角矩形压在
                    // 圆角 Surface 上（用户说"没有填充满"）。顺序必须是 clip → clickable → padding。
                    .suixingClickable(MaterialTheme.shapes.medium) { expanded = !expanded }
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
                textStyle = settingsFieldText,
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
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
    /** Per-model max output tokens, keyed by model name; 0 or missing means 模型默认. */
    maxTokensMap: Map<String, Int> = emptyMap(),
    /** The provider the list belongs to: names and aliases are per provider. */
    provider: ProviderConfig,
    onContext: (String, Int) -> Unit,
    onMaxTokens: (String, Int) -> Unit = { _, _ -> },
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
                        textStyle = settingsFieldText,
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("筛选") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
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
                                    maxTokens = maxTokensMap[id] ?: 0,
                                    onToggle = {
                                        picked = if (id in picked) picked - id else picked + id
                                    },
                                    onContext = { onContext(id, it) },
                                    onMaxTokens = { onMaxTokens(id, it) },
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
            // 组名是 `vendor/model`、`host:port` 这类断不开的 ASCII 串，右边又站着「全选」，
            // 所以给它两行、溢出才收成省略号：一行时它只会被硬裁掉半截。宽度不改（弹窗仍是
            // AlertDialog 的默认宽度），两行仍不够时是宽度问题，不在这一行里解决。
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
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
    /** 按模型的最大输出 tokens；0/缺席＝模型默认（用户：「跟上下文窗口一样可设置」）。 */
    maxTokens: Int = 0,
    onToggle: () -> Unit,
    onContext: (Int) -> Unit,
    onMaxTokens: (Int) -> Unit = {},
    onAlias: () -> Unit,
    onRemove: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var editingMax by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var draft by remember(editing) {
        mutableStateOf(if (contextWindow > 0) contextWindow.toString() else "")
    }
    var draftMax by remember(editingMax) {
        mutableStateOf(if (maxTokens > 0) maxTokens.toString() else "")
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
                    .suixingClickable(CircleShape) { onAlias() }
                    // 触控范围从 16dp 提到 28dp：原来是图标自己 .clickable，涟漪就是一个
                    // 16dp 的圆点（用户说的"圆点"）。clip(CircleShape) + clickable 在外层、
                    // padding 在内层 ⇒ 涟漪铺满这个圆、命中区也跟着变大。
                    .padding(6.dp)
                    .size(16.dp),
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
            Spacer(Modifier.width(6.dp))
            // 第二枚 chip：按模型的最大输出（与上下文窗口同一行、同一套内联编辑）。
            Surface(
                onClick = { editingMax = !editingMax },
                shape = CircleShape,
                color = if (maxTokens > 0) {
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            ) {
                Text(
                    text = if (maxTokens > 0) "出${maxTokens / 1000}k" else "出默认",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (maxTokens > 0) {
                        MaterialTheme.colorScheme.secondary
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
                textStyle = settingsFieldText,
                value = draft,
                // 与对话框同一套单位（k/m，见 parseWindowTokens）：裸数字＝tokens。
                onValueChange = { raw ->
                    draft = raw.filter {
                        it.isDigit() || it == '.' || it == 'k' || it == 'K' || it == 'm' || it == 'M'
                    }.take(10)
                },
                label = { Text("上下文窗口（tokens，可带 k / m；留空 = 不限）") },
                placeholder = { Text("例如 128k") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                shape = MaterialTheme.shapes.small,
                trailingIcon = {
                    TextButton(onClick = {
                        onContext(parseWindowTokens(draft) ?: 0)
                        editing = false
                    }) { Text("保存") }
                },
                // 这是 chip 行里的内联编辑器（自带 start/end/bottom 的紧凑内边距），高度同样收到
                // 52dp：与其它单行输入框一致，多出来的 4dp 只是让这一行略高一点。
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
                    .padding(start = 12.dp, end = 8.dp, bottom = 6.dp),
            )
        }
        if (editingMax) {
            OutlinedTextField(
                textStyle = settingsFieldText,
                value = draftMax,
                onValueChange = { raw -> draftMax = raw.filter { it.isDigit() }.take(6) },
                label = { Text("最大输出 tokens（留空 = 模型默认）") },
                placeholder = { Text("例如 4096") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = MaterialTheme.shapes.small,
                trailingIcon = {
                    TextButton(onClick = {
                        onMaxTokens(draftMax.toIntOrNull() ?: 0)
                        editingMax = false
                    }) { Text("保存") }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
                    .padding(start = 12.dp, end = 8.dp, bottom = 6.dp),
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

/**
 * The wire this endpoint speaks, as a dropdown.
 *
 * Hand-rolled from a Surface + DropdownMenu, which is how this file already does its one other
 * menu: `ExposedDropdownMenuBox` needs `menuAnchor` typing that this Material3 version shapes
 * differently, and reusing the existing pattern keeps the ripple and the corner radius the same.
 *
 * Blank is  [PROTOCOL_OPENAI]: every config written before this existed means exactly that, so
 * the default entry is the one an old file already implies.
 */
@Composable
private fun ProtocolPicker(protocol: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val options = listOf(
        PROTOCOL_OPENAI to "OpenAI 兼容",
        PROTOCOL_ANTHROPIC to "Anthropic",
        PROTOCOL_RESPONSES to "OpenAI Responses",
    )
    val label = options.firstOrNull { it.first == protocol }?.second ?: options.first().second
    Column {
        Text("接口协议", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        Box {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .suixingClickable(MaterialTheme.shapes.small) { open = true },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_chevron_down),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            open = false
                            onPick(value)
                        },
                    )
                }
            }
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
                textStyle = settingsFieldText,
                value = value,
                onValueChange = { value = it },
                label = { Text("显示名（留空 = 用回原 id）") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
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
                textStyle = settingsFieldText,
                value = value,
                onValueChange = { value = it },
                label = { Text("模型 id") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
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

/**
 * 设置页单行输入框的字号：**标准档 15sp**。
 *
 * **为什么是比例式而不是 `15.sp` 字面量**：全站字号由「外观 → 字号」经 `suixingTypography(scale)`
 * 整体缩放（`FONT_SCALES = 0.88 / 1 / 1.12 / 1.26`，见 `ui/theme/Type.kt`），写死的 sp 不参与缩放
 * ⇒ 用户把字号调大，这些框里的字纹丝不动（规范第 6 条）。比例式在标准档正好 15sp，特大档跟着
 * 变成约 18.9sp。先例：`ui/chat/ChatScreen.kt:1627` 的 `fontSize * (15f / 17f)`。
 *
 * `@Composable` getter 而不是顶层 `val`：`MaterialTheme` 只能在组合里读，顶层取会在类初始化时
 * 读到未提供的主题（要么崩、要么静默拿到默认值）。
 */
private val settingsFieldText: TextStyle
    @Composable get() = MaterialTheme.typography.bodyMedium.let { base ->
        base.copy(fontSize = base.fontSize * (15f / 14f))
    }

/** A row that leaves this page: the chevron is what says so before it is tapped. */
@Composable
private fun SettingsLink(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    /** 由 [SettingsGroup] 传卡片形状与 16dp 内容内边距：行要铺满卡片，涟漪才碰得到边缘。 */
    shape: Shape = MaterialTheme.shapes.small,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    // 行本身只有一份实现（Common.SettingRow），这里只接上「离开这一页」这件事：
    // 点击 + 右侧 chevron。几何、字号、行高都在 SettingRow 里，改那里就是改全站。
    SettingRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        control = { RowChevron() },
        shape = shape,
        contentPadding = contentPadding,
    )
}

/** 一页里的小节标题：四段之间的分界，靠字号与颜色分层，不加卡片。 */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
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
