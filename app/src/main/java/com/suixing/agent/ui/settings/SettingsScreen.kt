@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.suixing.agent.ui.settings

import com.suixing.agent.ui.components.settingsPagePadding
import com.suixing.agent.ui.components.SettingsStyle
import com.suixing.agent.ui.components.CompletionDot

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
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
import com.suixing.agent.ui.components.CompactOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import com.suixing.agent.ui.components.SettingsSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlin.math.roundToInt
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
import com.suixing.agent.data.ChatRepository
import com.suixing.agent.data.decrypted
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
import com.suixing.agent.llm.LocalNetworkAccess
import com.suixing.agent.llm.describeError
import com.suixing.agent.ui.drawer.DrawerOrder
import com.suixing.agent.ui.drawer.DrawerOrderStore
import com.suixing.agent.ui.drawer.drawerSections
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
import com.suixing.agent.ui.components.SuixingDialog
import com.suixing.agent.ui.theme.LocalDarkTheme
import com.suixing.agent.ui.theme.Tone
import com.suixing.agent.ui.theme.color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect

/** Outcome of a connection test, shown in the fixed bar above the buttons. */
private data class TestOutcome(val ok: Boolean, val message: String)


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
fun SettingsScreen(
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    /** 「重新看一遍引导」：把首启那五页再放出来一次（走的是外壳上的覆盖层，不是路由）。 */
    onOpenOnboarding: () -> Unit = {},
) {
    SettingsRoot(
        onBack = onBack,
        onOpen = onOpen,
        onOpenPage = { onOpen(it.route) },
        onOpenOnboarding = onOpenOnboarding,
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
    onOpenOnboarding: () -> Unit,
) {
    val capabilities by Core.capabilities.state.collectAsStateWithLifecycle()
    val settings by Core.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())

    val openNow = androidx.compose.runtime.rememberUpdatedState(onOpen)
    val onboardingNow = androidx.compose.runtime.rememberUpdatedState(onOpenOnboarding)
    val modelRows = remember {
        listOf(
            // 走 `openNow.value(...route)`（真路由）而不是 `onOpenPage(...)`（同一个目的地里换内容）：
            // 内部切换不经过 NavHost ⇒ 没有任何转场。路由本来就存在（`SettingsPageRoute` 按
            // `SettingsPage.route` 分发），所以这里只是改走它；用 `SettingsPage.X.route` 而不是
            // 手写字符串，避免两处各写一份路由名。
            SettingsGroupRow("模型与生成", "接口、模型与采样") {
                openNow.value(SettingsPage.MODEL.route)
            },
            SettingsGroupRow("对话配置", "输入、运行、失败重试与显示") {
                openNow.value(SettingsPage.CONVERSATION.route)
            },
            SettingsGroupRow("外观", "主题、配色与排版") {
                openNow.value(SettingsPage.APPEARANCE.route)
            },
        )
    }
    val connectionRows = remember {
        listOf(
            // 权限不是这一页的子页：能力清单是一份用户管理的清单，不是一组开关。
            SettingsGroupRow("权限", "无障碍、Shizuku、root 与悬浮窗") {
                openNow.value("capability")
            },
            SettingsGroupRow("工具", "工具、Skill 与 MCP 的管理") {
                openNow.value("tools")
            },
            SettingsGroupRow("网络与语音", "搜索走哪里、回复读不读出来") {
                openNow.value(SettingsPage.NETWORK.route)
            },
        )
    }
    val dataRows = remember {
        listOf(
            SettingsGroupRow("数据", "记忆、助手与会话文件") {
                openNow.value(SettingsPage.DATA.route)
            },
            SettingsGroupRow("用量", "tokens · 30 日柱状 · 按模型") {
                openNow.value(SettingsPage.USAGE.route)
            },
            // 性能页回答的是「这一轮到底慢在哪」：耗时、tokens、缓存命中都来自端点
            // 自己的回报，不是估算；没采集到的项显示「—」，不用 0 冒充测量。
            SettingsGroupRow("性能", "首字延迟 · 整轮耗时 · 缓存命中 · 输出速度") {
                openNow.value(SettingsPage.PERF.route)
            },
            // 再看一遍首启那五页。走的是外壳上的覆盖层（不是一条路由），
            // 所以它不进返回栈——从这儿进去，走完还是这一页。
            SettingsGroupRow("新手引导", "再走一遍：供应商 · 模型 · 权限") {
                onboardingNow.value()
            },
            SettingsGroupRow("关于", "版本、隐私与开源许可") {
                openNow.value("about")
            },
        )
    }

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
                .padding(padding),
            // 末行避让手势条（issue #47）：内容本身画到手势条底下，滚动时穿过去。
            contentPadding = settingsPagePadding(),
        ) {
            item { SettingsStatusCard(capabilities, settings) }
            item {
                SettingsGroup(
                    title = "模型与对话",
                    rows = modelRows,
                )
            }
            item {
                SettingsGroup(
                    title = "能力与连接",
                    rows = connectionRows,
                )
            }
            item {
                SettingsGroup(
                    title = "数据与关于",
                    rows = dataRows,
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
                Text("当前能力", style = SettingsStyle.cardTitle)
                Text(
                    text = checks,
                    style = SettingsStyle.description,
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
            style = SettingsStyle.description,
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
        com.suixing.agent.ui.components.SettingSectionTitle(title)
        val outline = MaterialTheme.colorScheme.outlineVariant
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            // 用户反馈「设置项的背景太浅，有点看不清」（设置首页的分组卡片）。
            // 原来是 surfaceContainerLow —— 比页面底色只深一点点，分组卡片几乎浮不起来；
            // 改成 surfaceContainer（与全站 SuixingCard 同档）再加一圈 outlineVariant 描边，
            // 卡片边界和层级才看得出来。
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, outline),
        ) {
            // 行铺满卡片（横向留白交给 SettingRow 的 contentPadding）：涟漪因此能碰到卡片的左右
            // 边缘与圆角，而不是被容器的 16dp 缩在中间 —— 用户报的「涟漪没填满/没变化」就是它。
            // 分割线仍缩进 16dp，与文字左缘对齐。
            Column {
                rows.forEach { row ->
                    SettingsLink(
                        title = row.title,
                        subtitle = row.summary,
                        onClick = row.onClick,
                        shape = MaterialTheme.shapes.large,
                        contentPadding = PaddingValues(horizontal = SettingsStyle.PageMargin),
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
 * 模型与生成: the only page with a draft and a save button, because its fields are a
 * group — a new address with an old key is not a state anyone wants half of.
 */
@Composable
private fun ModelSettingsPage(onBack: () -> Unit) {
    val pendingFields = rememberDebouncedSettingsWrites()
    // Null distinguishes loading from a genuinely empty supplier list.
    val loadedStored by produceState<AppSettings?>(null) {
        // **密文没变就不重解密**（性能审计）：`decrypted()` 会解密顶层 apiKey + **每个**
        // provider 的 apiKey，而 `SecretBox.decrypt` 每次都要 `KeyStore.getInstance(
        // "AndroidKeyStore").load(null)` —— N+1 次 Keystore 往返。原来只要 settings 流发射
        // （改主题、改字号、改任何字段）就整套重解一次。
        //
        // 判据用**密文指纹**：顶层 apiKey + 每个 provider 的 apiKey 按顺序。指纹相同时
        // 说明密钥压根没变，只需把非密钥字段换成新的、密钥沿用上次解密结果。
        // 指纹里含 provider 个数 ⇒ 增删供应商必然指纹不同，按位合并不存在错位。
        var lastSecrets: Pair<String, List<String>>? = null
        var lastDecrypted: AppSettings? = null
        Core.settings.flow.collect { incoming ->
            val secrets = incoming.apiKey to incoming.providers.map { it.apiKey }
            val cached = lastDecrypted
            val reused = cached != null && secrets == lastSecrets &&
                cached.providers.size == incoming.providers.size
            val decrypted = if (reused) {
                incoming.copy(
                    apiKey = cached!!.apiKey,
                    providers = incoming.providers.mapIndexed { index, provider ->
                        provider.copy(apiKey = cached.providers[index].apiKey)
                    },
                )
            } else {
                withContext(Dispatchers.IO) { incoming.decrypted() }
            }
            lastSecrets = secrets
            lastDecrypted = decrypted
            value = decrypted
        }
    }
    val stored = loadedStored ?: AppSettings()
    var form by remember { mutableStateOf(stored) }
    var seeded by remember { mutableStateOf(false) }
    LaunchedEffect(loadedStored) {
        if (!seeded && loadedStored != null) {
            form = stored
            seeded = true
        }
    }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        try { Core.settings.cleanupEmptyProviders() }
        catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            snackbar.showSnackbar("供应商清理未保存，请稍后重试")
        }
    }
    val client = remember { Core.llm }
    var testing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var loadingModels by remember { mutableStateOf(false) }
    var providerPickerOpen by remember { mutableStateOf(false) }
    var modelsOpen by remember { mutableStateOf(false) }
    var addingModel by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<TestOutcome?>(null) }
    val context = LocalContext.current
    // Android 17 起访问局域网要先拿到 ACCESS_LOCAL_NETWORK。这里只在**目标像内网**时请求，
    // 公网接口的用户永远不会看到这个系统对话框；授权结果回来后提示「再点一次」，
    // 不自动重放请求（重放会在用户还没点允许时就发出注定超时的请求）。
    val localNetworkLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        outcome = if (granted) {
            TestOutcome(true, "已获得局域网访问权限，再点一次即可。")
        } else {
            TestOutcome(false, "没有局域网访问权限：Android 17 起访问内网模型必须允许这项权限。")
        }
    }
    fun ensureLocalNetwork(): Boolean {
        if (!LocalNetworkAccess.blockedByMissingPermission(context, form.baseUrl)) return true
        localNetworkLauncher.launch(android.Manifest.permission.ACCESS_LOCAL_NETWORK)
        outcome = TestOutcome(false, "已请求「局域网访问」权限，允许后再点一次。")
        return false
    }
    // Keep selection by identity while the supplier's editable draft changes.
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
    // Names, credentials and models now share the same explicit save boundary.
    var leavingWithDraft by remember { mutableStateOf(false) }
    var pendingSwitch by remember { mutableStateOf<String?>(null) }
    val switchProvider: (ProviderConfig) -> Unit = { provider ->
        selectedId = provider.id
        modelsOpen = false
        outcome = null
        form = form.discardUncommittedProviders(stored, selected?.id).copy(
            baseUrl = provider.baseUrl,
            apiKey = provider.apiKey,
            protocol = provider.protocol,
            model = provider.enabled.firstOrNull() ?: form.model,
        )
    }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    val deleteProvider: (ProviderConfig) -> Unit = { provider ->
        val rest = form.providers.filterNot { it.id == provider.id }
        val next = rest.firstOrNull()
        selectedId = next?.id.orEmpty()
        form = form.copy(
            providers = rest, providersInitialized = true,
            baseUrl = next?.baseUrl ?: stored.baseUrl,
            apiKey = next?.apiKey ?: stored.apiKey,
            protocol = next?.protocol ?: stored.protocol,
            model = next?.enabled?.firstOrNull() ?: stored.model,
        )
    }
    fun leavePage() {
        val kept = form.discardUncommittedProviders(stored, selected?.id)
        val remaining = kept.providers.firstOrNull { it.id == selected?.id }
            ?: kept.activeProvider() ?: kept.providers.firstOrNull()
        form = kept
        selectedId = remaining?.id.orEmpty()
        if (endpointHandEdited(kept, stored, remaining) || kept.differsOutsideEndpoint(stored)) leavingWithDraft = true
        else onBack()
    }
    suspend fun saveForm() {
        val snapshot = form
        val saved = snapshot.forSaving(stored)
        Core.settings.save(saved)
        // Do not replace edits typed while persistence was running.
        if (form === snapshot) {
            form = saved
            if (saved.providers.none { it.id == selectedId }) {
                selectedId = saved.activeProvider()?.id ?: saved.providers.firstOrNull()?.id.orEmpty()
            }
        }
    }
    androidx.activity.compose.BackHandler(enabled = seeded) { leavePage() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            PageTopBar(
                title = SettingsPage.MODEL.title,
                onBack = { if (seeded) leavePage() else onBack() },
                // Draft status stays in the fixed action area, without shifting form rows.
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
                        .padding(horizontal = SettingsStyle.PageMargin)
                        .padding(top = 8.dp, bottom = 12.dp),
                ) {
                    outcome?.let { current ->
                        OutcomeCard(current) { outcome = null }
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val statusColor = if (dirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(5.dp).background(statusColor.copy(alpha = 0.65f), CircleShape))
                            Text(when {
                                saving -> "保存中"
                                !seeded -> "读取中"
                                dirty -> "未保存"
                                else -> "已保存"
                            }, style = SettingsStyle.description, color = statusColor, maxLines = 1)
                        }
                        ModelActionButton(
                            text = if (testing) "测试中" else "测试连接", busy = testing,
                            enabled = seeded && !saving && !loadingModels,
                            icon = R.drawable.ic_rotate_cw,
                            onClick = {
                                scope.launch {
                                    if (!ensureLocalNetwork()) return@launch
                                    testing = true
                                    outcome = null
                                    try {
                                        // Test the draft without committing an unsaved supplier.
                                        outcome = runCatching {
                                            client.complete(settings = form,
                                                messages = listOf(ChatMessage.user("你好，请只回复两个字：可用")),
                                                tools = emptyList())
                                        }.fold(
                                            onSuccess = { TestOutcome(true, "连接成功，模型回了：${it.text.trim().take(60)}") },
                                            onFailure = { TestOutcome(false, describeError(it)) },
                                        )
                                    } finally { testing = false }
                                }
                            },
                        )
                        ModelActionButton(
                            text = if (saving) "保存中" else "保存", primary = true, busy = saving,
                            enabled = seeded && !testing && !loadingModels, icon = R.drawable.ic_check,
                            onClick = {
                                scope.launch {
                                    saving = true
                                    try { saveForm() } finally { saving = false }
                                    snackbar.showSnackbar("已保存")
                                }
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            // 末行避让手势条（issue #47）：内容本身画到手势条底下，滚动时穿过去。
            contentPadding = settingsPagePadding(),
            verticalArrangement = Arrangement.spacedBy(SettingsStyle.RowGap),
        ) {
            item {
                ProviderDropdown(
                    providers = form.providers, selected = selected, enabled = seeded && !loadingModels && !testing && !saving,
                    onSelect = { provider ->
                        if (provider.id != selected?.id) {
                            if (endpointEdited && stored.providers.any { it.id == selected?.id }) pendingSwitch = provider.id
                            else switchProvider(provider)
                        }
                    },
                    onAdd = {
                        if (endpointEdited && stored.providers.any { it.id == selected?.id })
                            outcome = TestOutcome(false, "请先保存当前接口改动，再添加供应商。")
                        else providerPickerOpen = true
                    },
                    onDelete = { selected?.let { provider ->
                        if (endpointEdited) pendingDelete = provider.id else deleteProvider(provider)
                    } },
                )
            }
            item { SectionHeader("接口配置") }
            item {
                // 协议在地址之上：换了协议，同一个地址要拼的路径、要带鉴权头都不一样，
                // 所以用户是先选「这个端点说哪种话」，再填地址。
                CompactProtocolPicker(
                    protocol = selected?.protocol.orEmpty(),
                    onPick = { picked ->
                        form = form.copy(protocol = picked).mapProvider(selected?.id) { it.copy(protocol = picked) }
                    },
                )
                // Anthropic 的提示只在这个协议下出现：默认地址栏那句「填到 /v1 即可」
                // 对它的意义最具体 —— 路径由客户端补 /messages。
                if (selected?.protocol == PROTOCOL_ANTHROPIC) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "地址填到 /v1 即可，/messages 会自动补上。",
                        style = SettingsStyle.description,
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
                    // Compact field shares the 48dp editing minimum with selectors/actions;
                    // floating labels, supporting text and larger fonts may grow naturally.
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
                    modifier = Modifier.fillMaxWidth().heightIn(min = SettingsStyle.ControlHeight),
                )
            }
            item {
                // The provider's own name, editable in place: the chip row's long-press
                // rename stays, but the name is read every day and typed once a month.
                OutlinedTextField(
                    // Uses CompactOutlinedTextField via the import alias; the shared
                    // editing minimum stays flexible rather than clipping scaled text.
                    textStyle = settingsFieldText,
                    value = selected?.name.orEmpty(),
                    onValueChange = { value ->
                        form = form.mapProvider(selected?.id) { it.copy(name = value) }
                    },
                    label = { Text("提供方名称") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().heightIn(min = SettingsStyle.ControlHeight),
                )
            }
            item {
                SettingRow(
                    title = "允许自签名证书",
                    subtitle = "接口是 https 且证书自签时打开。局域网上的反代（例如 " +
                        "https://192.168.0.50:8914/v1）基本都要开，开了就不再校验证书与域名。",
                    control = {
                        SettingsSwitch(
                            checked = form.allowInsecureTls,
                            onCheckedChange = { form = form.copy(allowInsecureTls = it) },
                        )
                    },
                )
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
                    modifier = Modifier.fillMaxWidth().heightIn(min = SettingsStyle.ControlHeight),
                )
            }
            item {
                // **Key 存在哪**（用户要求「更加保密化」后新增）：现在是 Android Keystore 加密后
                // 落盘，密钥在系统里、不随应用数据走 ⇒ 换机后解不开，用户要在这里重填一次。
                // 说清楚比不说好 —— 否则用户换了手机发现 Key 没了，以为是 bug。
                Text(
                    text = "Key 用系统 Keystore 加密后存在这台手机上，不随备份迁移；换机后需要重填一次。",
                    style = SettingsStyle.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
            item { SectionHeader("模型列表") }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        enabled = !loadingModels && selected != null && form.baseUrl.isNotBlank(),
                        modifier = Modifier.heightIn(min = 36.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        shape = MaterialTheme.shapes.small,
                        onClick = {
                            scope.launch {
                                // 内网地址且还没授权时，先弹系统授权；不发出注定超时的请求。
                                if (!ensureLocalNetwork()) return@launch
                                loadingModels = true
                                outcome = null
                                val requestForm = form
                                val requestProviderId = selectedId
                                runCatching { client.listModelDetails(requestForm) }.fold(
                                    onSuccess = fetchedModels@{ details ->
                                        if (selectedId != requestProviderId || form.baseUrl != requestForm.baseUrl ||
                                            form.apiKey != requestForm.apiKey || form.protocol != requestForm.protocol) {
                                            outcome = TestOutcome(false, "接口配置已变化，请重新获取模型列表。")
                                            return@fetchedModels
                                        }
                                        val fetched = details.map { it.id }.distinct()
                                        // **上游说了算**（issue #3）：原来这里是
                                        // `(fetched + selected?.models.orEmpty()).distinct()` —— 和上一次
                                        // 保存的清单做并集，网关删掉的模型一个都不会消失（面板 103 个 vs
                                        // 网关 46 个）。这个 App 没有手填模型的入口，`/models` 就是唯一
                                        // 来源，并集没有任何东西需要保护，只会让上游的删除失效。
                                        val merged = fetched
                                        // 上游声明的窗口只在用户没设过时填入（withUpstreamDefaults：
                                        // 键已存在就一个字都不动），并和清单在同一事务里落盘。
                                        val windows = details.mapNotNull { model ->
                                            model.contextLength?.let { model.id to it }
                                        }.toMap()
                                        val providers = form.mapProvider(selected?.id) {
                                            it.copy(
                                                models = merged,
                                                // picker 读的是 `enabled` 而不是 `models`：不同步清掉的话，
                                                // 从清单里消失的模型仍会留在选择器里（issue #3 第 3 条）。
                                                enabled = it.enabled.filter { id -> id in merged },
                                            )
                                        }.providers
                                        // 上下文窗口表是**全局**的（多 provider 共用一个 Map），所以只能按
                                        // 「所有 provider 的并集」清 —— 按这一个 provider 的清单清，拉一次
                                        // A 就会把 B 的窗口一起删掉。
                                        val allModels = providers.flatMap { it.models }.toSet()
                                        form = form.copy(
                                            providers = providers,
                                            modelContexts = form.modelContexts
                                                .withUpstreamDefaults(windows)
                                                .filterKeys { it in allModels },
                                        )
                                        modelsOpen = true
                                        if (merged.isEmpty()) {
                                            outcome = TestOutcome(false, "接口没返回任何模型，直接手填吧。")
                                        } else {
                                            outcome = TestOutcome(true, "已获取 ${merged.size} 个模型。")
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
                        Text(if (loadingModels) "获取中…" else "获取模型列表", style = SettingsStyle.metadata)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "已启用 ${selected?.enabled?.size ?: 0} 个",
                        style = SettingsStyle.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "从接口获取后在弹窗选择模型，修改后点底部保存。",
                    style = SettingsStyle.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { SectionHeader("已启用模型") }
            if (selected?.enabled.isNullOrEmpty()) {
                item { Text("尚未启用模型。获取后在弹窗选择，或添加自定义模型。",
                    style = SettingsStyle.description, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(selected?.enabled.orEmpty(), key = { "enabled:${selected?.id}:$it" },
                contentType = { "enabled-model" }) { id ->
                EnabledModelCard(
                    id = id, alias = selected?.aliases?.get(id).orEmpty(),
                    contextWindow = form.modelContexts[id] ?: 0, maxTokens = form.modelMaxTokens[id] ?: 0,
                    testModel = form.model == id,
                    onTest = { form = form.copy(model = id) },
                    onEdit = { alias, contextWindow, output ->
                        form = form.mapProvider(selected?.id) {
                            it.copy(aliases = if (alias.isBlank()) it.aliases - id else it.aliases + (id to alias))
                        }.let { updated -> updated.copy(
                            modelContexts = updated.modelContexts.withContextWindow(id, contextWindow),
                            modelMaxTokens = updated.modelMaxTokens.withMaxTokens(id, output),
                        ) }
                    },
                    onRemove = { form = form.mapProvider(selected?.id) { it.withModelEnabled(id, false) } },
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = selected != null, onClick = { modelsOpen = true }) { Text("选择模型") }
                    TextButton(onClick = { addingModel = true }) { Text("添加自定义模型") }
                }
            }
            item {
                OutlinedTextField(value = form.model, onValueChange = { form = form.copy(model = it) },
                    textStyle = settingsFieldText, label = { Text("测试连接使用的模型 ID") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
            }
            item {
                SettingRow(
                    title = "支持图片输入",
                    subtitle = "截图会随消息发给模型，需要视觉模型；纯文字模型开着会直接报错。",
                    control = {
                        SettingsSwitch(
                            checked = form.imageInput,
                            onCheckedChange = { form = form.copy(imageInput = it) },
                        )
                    },
                )
            }
            item { SectionHeader("生成参数") }
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
                // **思考强度是初级参数**（用户 2026-10-23：「思考强度不用放进高级参数里面」）：
                // 它是日常最常调的那一个，藏在折叠里每次都要多点两下。
                val own = selected?.takeIf { it.ownSampling }
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
                        // 七档全英文之后（Default/Low/…），旧判据 `wire == name` 永远不成立
                        // ⇒ 会显示成「Minimal (minimal)」。改成忽略大小写比较，只在真正不同
                        // （接口自造的值）时才附上 wire。
                        label = "思考强度 · " +
                            if (wire.isEmpty() || wire.equals(name, ignoreCase = true)) {
                                name
                            } else {
                                "$name ($wire)"
                            },
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
            }
            item {
                AdvancedSection {
                    // 独立采样: off = the four knobs below read and write the globals,
                    // on = they read and write this provider's nullable overrides.
                    SettingRow(
                        title = "独立采样参数",
                        subtitle = "开启后温度 / Top P / 思考强度只作用于当前提供方。",
                        control = {
                            SettingsSwitch(
                                checked = selected?.ownSampling == true,
                                onCheckedChange = { on ->
                                    form = form.mapProvider(selected?.id) { it.copy(ownSampling = on) }
                                },
                            )
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    val own = selected?.takeIf { it.ownSampling }
                    LabeledSlider(
                        label = if (!form.sendTemperature) {
                            "温度 · 不发送"
                        } else {
                            "温度 ${"%.1f".format(own?.temperature ?: form.temperature)}"
                        },
                        // **最左一档 = 不发送**（用户 2026-10-23：「只保留滑块就行了，
                        // 俩滑块左侧或者右侧就是不发的档次」）。
                        // 档位：0 = 不发送；1..21 = 0.0 .. 2.0（步长 0.1）。
                        hint = "最左「不发」；其余是采样温度（推理型端点常拒收 temperature）。",
                        value = if (!form.sendTemperature) 0f
                        else (((own?.temperature ?: form.temperature) * 10.0) + 1.0)
                            .toFloat().coerceIn(1f, 21f),
                        range = 0f..21f,
                        steps = 20,
                        onValueChange = { v ->
                            val stop = v.toInt()
                            val send = stop > 0
                            val temp = if (send) (stop - 1) / 10.0 else (own?.temperature ?: form.temperature)
                            form = if (own != null) {
                                form.mapProvider(selected?.id) {
                                    it.copy(temperature = temp)
                                }.copy(sendTemperature = send)
                            } else {
                                form.copy(temperature = temp, sendTemperature = send)
                            }
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    LabeledSlider(
                        label = if (!form.sendTopP) {
                            "Top P · 不发送"
                        } else {
                            "Top P ${"%.2f".format(own?.topP ?: form.topP)}"
                        },
                        // 档位：0 = 不发送；1..10 = 0.1 .. 1.0。
                        hint = "最左「不发」；其余是核采样阈值（1.0 = 不过滤）。",
                        value = if (!form.sendTopP) 0f
                        else ((own?.topP ?: form.topP) * 10f).roundToInt().toFloat().coerceIn(1f, 10f),
                        range = 0f..10f,
                        steps = 9,
                        onValueChange = { v ->
                            val stop = v.toInt()
                            val send = stop > 0
                            val p = if (send) stop / 10f else (own?.topP ?: form.topP)
                            form = if (own != null) {
                                form.mapProvider(selected?.id) { it.copy(topP = p) }
                                    .copy(sendTopP = send)
                            } else {
                                form.copy(topP = p, sendTopP = send)
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
                            textStyle = SettingsStyle.fieldText,
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
                                pendingFields.put("request-headers") { it.copy(requestHeaders = parsed) }
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

    if (modelsOpen && selected != null) {
        androidx.compose.runtime.key(selected.id) {
            FetchedModelsDialog(
                provider = selected,
                onToggle = { id, enabled -> form = form.mapProvider(selected.id) { it.withModelEnabled(id, enabled) } },
                onDismiss = { modelsOpen = false },
            )
        }
    }
    if (providerPickerOpen) {
        AddProviderDialog(onDismiss = { providerPickerOpen = false }, onAdd = { provider ->
            val retained = form.discardUncommittedProviders(stored, selected?.id)
            form = retained.copy(providers = retained.providers + provider,
                baseUrl = provider.baseUrl, apiKey = provider.apiKey, model = "", protocol = provider.protocol)
            selectedId = provider.id
            modelsOpen = false
            providerPickerOpen = false
        })
    }
    if (addingModel) {
        AddModelDialog(onDismiss = { addingModel = false }, onConfirm = { id ->
            form = form.mapProvider(selected?.id) { it.withModelEnabled(id, true) }
            addingModel = false
        })
    }
    if (leavingWithDraft) {
        SuixingDialog(
            onDismissRequest = { leavingWithDraft = false },
            title = { Text("保存模型配置？") },
            text = { Text("供应商、已启用模型或生成参数还有未保存的改动。") },
            confirmButton = { dialog ->
                TextButton(onClick = { dialog.closeThen {
                    leavingWithDraft = false
                    scope.launch {
                        saveForm()
                        onBack()
                    }
                } }) { Text("保存并返回") }
            },
            dismissButton = { dialog ->
                Row {
                    TextButton(onClick = { dialog.closeThen { leavingWithDraft = false; onBack() } }) { Text("放弃") }
                    TextButton(onClick = { dialog.closeThen { leavingWithDraft = false } }) { Text("继续编辑") }
                }
            },
        )
    }

    pendingSwitch?.let { id ->
        SuixingDialog(
            onDismissRequest = { pendingSwitch = null },
            title = { Text("切换提供方？") },
            text = { Text("当前提供方的接口地址、API Key 或模型还没保存，切过去会丢掉这些改动。") },
            confirmButton = { scope ->
                TextButton(
                    onClick = {
                        scope.closeThen {
                            pendingSwitch = null
                            form.providers.firstOrNull { it.id == id }?.let(switchProvider)
                        }
                    },
                ) { Text("继续") }
            },
            dismissButton = { scope -> TextButton(onClick = { scope.closeThen { pendingSwitch = null } }) { Text("取消") } },
        )
    }

    pendingDelete?.let { id ->
        SuixingDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除提供方？") },
            text = { Text("当前提供方还有未保存的改动，删除会连同这些改动一起丢掉，并切到下一个提供方。") },
            confirmButton = { scope ->
                TextButton(
                    onClick = {
                        scope.closeThen {
                            pendingDelete = null
                            form.providers.firstOrNull { it.id == id }?.let(deleteProvider)
                        }
                    },
                ) { Text("删除") }
            },
            dismissButton = { scope -> TextButton(onClick = { scope.closeThen { pendingDelete = null } }) { Text("取消") } },
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
    val stored by Core.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val allowed by Core.gate.alwaysAllowedTools.collectAsStateWithLifecycle()
    val denied by Core.gate.alwaysDeniedTools.collectAsStateWithLifecycle()
    // 排序只依赖这两个集合：别每次重组都在 items() 里重排一遍。
    val allowSorted = remember(allowed) { allowed.sorted() }
    val denySorted = remember(denied) { denied.sorted() }

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
                .padding(padding),
            // 末行避让手势条（issue #47）：内容本身画到手势条底下，滚动时穿过去。
            contentPadding = settingsPagePadding(),
            verticalArrangement = Arrangement.spacedBy(SettingsStyle.RowGap),
        ) {
            item { SectionHeader("输入与插话", first = true) }
            item {
                SettingRow(
                    title = "回车键发送",
                    subtitle = "开：按回车直接发送（运行中则当作插话送进去）；关：回车换行，" +
                        "用发送键提交。",
                    control = {
                        SettingsSwitch(
                            checked = stored.enterKeySends,
                            onCheckedChange = { on -> scope.persist { it.copy(enterKeySends = on) } },
                        )
                    },
                )
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
            item { SectionHeader("工具执行") }
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
                SettingRow(title = "并行子任务",
                    subtitle = "复杂任务可拆分处理；共用当前模型与工具权限，可能增加模型调用。",
                    control = { SettingsSwitch(checked = stored.subagentsEnabled,
                        onCheckedChange = { on -> scope.persist { it.copy(subagentsEnabled = on) } }) })
            }
            if (stored.subagentsEnabled) item {
                LabeledSlider(label = "子任务并行数 ${stored.subagentParallelism.coerceIn(1, 3)}",
                    hint = "每轮最多创建6个子任务；父任务结束或停止时，未完成的子任务会一同取消。",
                    value = stored.subagentParallelism.coerceIn(1, 3).toFloat(), range = 1f..3f, steps = 1,
                    onValueChange = { value -> scope.persist { it.copy(subagentParallelism = value.toInt()) } })
            }
            item { SectionHeader("审批与授权") }
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
                    style = SettingsStyle.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (stored.approvalMode == ApprovalMode.ASK) {
                item {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("总是允许的工具", style = SettingsStyle.sectionTitle)
                            Text(
                                text = if (allowed.isEmpty()) {
                                    "还没有。审批时选「总是允许」，这里会记住它，重启也在。"
                                } else {
                                    "${allowed.size} 个，重启后依然有效"
                                },
                                style = SettingsStyle.description,
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
                items(allowSorted, key = { "allow-$it" }) { name ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = name,
                            style = SettingsStyle.body,
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
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("总是拒绝的工具", style = SettingsStyle.sectionTitle)
                            Text(
                                text = if (denied.isEmpty()) {
                                    "还没有。审批时选「总是拒绝」，它不会再问第二次。"
                                } else {
                                    "${denied.size} 个，重启后依然有效"
                                },
                                style = SettingsStyle.description,
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
                items(denySorted, key = { "deny-$it" }) { name ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = name,
                            style = SettingsStyle.body,
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
            item { SectionHeader("上下文管理") }
            item {
                SettingRow(
                    title = "自动压缩上下文",
                    subtitle = "上下文接近窗口上限时把旧消息压成摘要。超窗是接口直接报错，" +
                        "压缩最坏只是丢些细节；只在「模型与生成」里设了上下文条数时才可能触发。",
                    control = {
                        SettingsSwitch(
                            checked = stored.autoCompress,
                            onCheckedChange = { on -> scope.persist { it.copy(autoCompress = on) } },
                        )
                    },
                )
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
                                style = SettingsStyle.rowTitle,
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
            item { SectionHeader("记忆与画像") }
            item {
                SettingRow(
                    title = "注入记忆提示词",
                    subtitle = "开：每次请求都把「你记得的事」（记忆条目）拼进系统提示词；" +
                        "关：这一段完全不出现，模型不知道你记过什么，但省下这部分上下文。",
                    control = {
                        SettingsSwitch(
                            checked = stored.injectMemory,
                            onCheckedChange = { on -> scope.persist { it.copy(injectMemory = on) } },
                        )
                    },
                )
            }
            item {
                SettingRow(
                    title = "注入用户画像提示词",
                    subtitle = "开：把「关于用户（画像）」拼进系统提示词，模型先知道「这是谁」；" +
                        "关：整段不出现（画像本身不会被删，只是这次不发给模型）。",
                    control = {
                        SettingsSwitch(
                            checked = stored.injectProfile,
                            onCheckedChange = { on -> scope.persist { it.copy(injectProfile = on) } },
                        )
                    },
                )
            }
            item { SectionHeader("失败重试") }
            item {
                SettingRow(
                    title = "失败自动重试",
                    subtitle = "只对网络中断、超时、5xx 与 429 重试；已经开始回答则不重试——那会把" +
                        "半截回答重来一遍，比重试更糟。",
                    control = {
                        SettingsSwitch(
                            checked = stored.retryOnFailure,
                            onCheckedChange = { on -> scope.persist { it.copy(retryOnFailure = on) } },
                        )
                    },
                )
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
            item { SectionHeader("思考过程") }
            item {
                SettingRow(
                    title = "显示思考过程",
                    subtitle = "把模型的思考流式画在回复上方。关掉只是不显示，思考强度照旧生效。",
                    control = {
                        SettingsSwitch(
                            checked = stored.showReasoning,
                            onCheckedChange = { on -> scope.persist { it.copy(showReasoning = on) } },
                        )
                    },
                )
            }
            item {
                SettingRow(
                    title = "历史思考块展开",
                    subtitle = "正在思考的那一块总是展开。开＝历史思考块也展开；关＝历史块收起成一行标题，点它仍可逐块展开。",
                    control = {
                        SettingsSwitch(
                            checked = stored.expandReasoning,
                            onCheckedChange = { on -> scope.persist { it.copy(expandReasoning = on) } },
                        )
                    },
                )
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
            }
            item {
                SettingRow(
                    title = "思考块跟随最新",
                    subtitle = "思考过程流式输出时自动滚到底部；关掉后停在当前位置",
                    control = {
                        SettingsSwitch(
                            checked = stored.followReasoning,
                            onCheckedChange = { on -> scope.persist { it.copy(followReasoning = on) } },
                        )
                    },
                )
            }
            item { SectionHeader("消息显示") }
            item {
                SettingRow(
                    title = "自动跟随新消息",
                    subtitle = "在底部时新回复自动滚到底；上滑看历史就暂停跟随。",
                    control = {
                        SettingsSwitch(
                            checked = stored.autoFollow,
                            onCheckedChange = { on -> scope.persist { it.copy(autoFollow = on) } },
                        )
                    },
                )
            }
            item {
                SettingRow(
                    title = "显示时间",
                    subtitle = "在每条消息下面显示发送时间。",
                    control = {
                        SettingsSwitch(
                            checked = stored.showTimestamps,
                            onCheckedChange = { on -> scope.persist { it.copy(showTimestamps = on) } },
                        )
                    },
                )
            }
            item {
                SettingRow(
                    title = "工具调用默认展开",
                    subtitle = "关掉时工具卡片折叠成一行，点开才看参数与结果。",
                    control = {
                        SettingsSwitch(
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
    val stored by Core.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { PageTopBar(title = SettingsPage.APPEARANCE.title, onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            // 末行避让手势条（issue #47）：内容本身画到手势条底下，滚动时穿过去。
            contentPadding = settingsPagePadding(),
            verticalArrangement = Arrangement.spacedBy(SettingsStyle.RowGap),
        ) {
            item { SectionHeader("主题与配色", first = true) }
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
            }
            item { SectionHeader("文字与布局") }
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
            }
            item { SectionHeader("动效与提示") }
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
            }
            item {
                LabeledBlock(
                    label = "边缘光",
                    hint = "agent 在操作这台手机时，屏幕四周亮起一圈彩虹光。关掉只是不显示，" +
                        "自动化、自动返回与诊断日志全都照旧。**这是总开关**：关掉之后一律不显示。",
                ) {
                    SegmentedPill(
                        options = listOf("开", "关"),
                        selectedIndex = if (stored.edgeLight) 0 else 1,
                        onSelect = { index -> scope.persist { it.copy(edgeLight = index == 0) } },
                    )
                }
            }
            item {
                // **不独立**（用户 2026-10-23）：只有边缘光总开关开着时这一项才起作用。
                // 总开关关掉时置灰，但值仍然保留 —— 重新打开边缘光时它还是原来的选择。
                LabeledBlock(
                    label = "前台显示",
                    hint = if (stored.edgeLight) {
                        "关掉之后：随行自己在前台时不显示边缘光，退到后台操作别的 app 照常显示。"
                    } else {
                        "边缘光已关闭，这一项暂不生效。"
                    },
                ) {
                    SegmentedPill(
                        options = listOf("开", "关"),
                        selectedIndex = if (stored.edgeLightInForeground) 0 else 1,
                        onSelect = { index ->
                            // 总开关关着时不动这个值（不独立 = 不起作用，而不是被重置）。
                            if (stored.edgeLight) {
                                scope.persist { it.copy(edgeLightInForeground = index == 0) }
                            }
                        },
                        modifier = if (stored.edgeLight) Modifier else Modifier.alpha(0.38f),
                    )
                }
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
                                // 这一页现在是八项：提示条时长、打字机效果与边缘光也在「恢复默认外观」里，
                                // 否则按了恢复却留着上一轮的设置，用户会以为按钮坏了。
                                bannerSeconds = defaults.bannerSeconds,
                                typewriter = defaults.typewriter,
                                edgeLight = defaults.edgeLight,
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
    val stored by Core.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val scope = rememberCoroutineScope()
    val pendingFields = rememberDebouncedSettingsWrites()
    var searchEndpoint by remember { mutableStateOf(stored.searchEndpoint) }
    var searchApiKey by remember { mutableStateOf(stored.searchApiKey) }
    var endpointEdited by remember { mutableStateOf(false) }
    var keyEdited by remember { mutableStateOf(false) }
    // 语音转写模型原来逐击键 `persist{}`：一个字符一次「整份 JSON 编码 + Keystore 加密 +
    // 文件重写 + 广播」，而输入框显示的值就来自那条写回 —— 打得快会看到字符回退、光标跳。
    // 与上面两个搜索字段同一套（#54 C-1）：本地态即时显示，**停手 400ms 才落盘**。
    var asrModelText by remember { mutableStateOf(stored.asrModel) }
    var asrModelEdited by remember { mutableStateOf(false) }
    LaunchedEffect(stored.searchEndpoint, stored.searchApiKey, stored.asrModel) {
        // Seed from the first real repository emission, without a delayed disk echo
        // replacing characters typed after an earlier debounced batch was dispatched.
        if (!endpointEdited) searchEndpoint = stored.searchEndpoint
        if (!keyEdited) searchApiKey = stored.searchApiKey
        if (!asrModelEdited) asrModelText = stored.asrModel
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { PageTopBar(title = SettingsPage.NETWORK.title, onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            // 末行避让手势条（issue #47）：内容本身画到手势条底下，滚动时穿过去。
            contentPadding = settingsPagePadding(),
            verticalArrangement = Arrangement.spacedBy(SettingsStyle.RowGap),
        ) {
            item { SectionHeader("联网搜索", first = true) }
            item {
                OutlinedTextField(
                    textStyle = settingsFieldText,
                    value = searchEndpoint,
                    onValueChange = { value ->
                        endpointEdited = true
                        searchEndpoint = value
                        pendingFields.put("search-endpoint") { it.copy(searchEndpoint = value) }
                    },
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
                    value = searchApiKey,
                    onValueChange = { value ->
                        keyEdited = true
                        searchApiKey = value
                        pendingFields.put("search-api-key") { it.copy(searchApiKey = value) }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    label = { Text("搜索接口 Key（可留空）") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
            item { SectionHeader("语音朗读") }
            item {
                val engineLabel by Core.speaker.engineLabel.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { Core.speaker.prepare() }
                SettingRow(
                    title = "朗读回复",
                    subtitle = "用系统语音引擎读出来。引擎状态：${engineLabel}。" +
                        "输入框左边的麦克风按钮负责语音输入。",
                    control = {
                        SettingsSwitch(
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
            item { SectionHeader("语音转写") }
            item {
                SettingRow(
                    title = "语音输入",
                    subtitle = "开＝输入框右侧显示麦克风，用随行自己的录音发给你配置的转写接口（不会再弹系统识别面板）；" +
                        "关＝完全不显示麦克风。",
                    control = {
                        SettingsSwitch(
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
                        value = asrModelText,
                        onValueChange = { value ->
                            asrModelEdited = true
                            asrModelText = value
                            pendingFields.put("asr-model") { it.copy(asrModel = value) }
                        },
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
                .padding(padding),
            // 末行避让手势条（issue #47）：内容本身画到手势条底下，滚动时穿过去。
            contentPadding = settingsPagePadding(),
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
    val conversations by Core.chats.conversations.collectAsStateWithLifecycle()
    val unreadCompleted by Core.conversationReads.unread.collectAsStateWithLifecycle()
    val runningIds by remember {
        Core.engine.runsFlow.map { it.keys }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(emptySet())
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }
    var pendingExport by remember { mutableStateOf<Conversation?>(null) }
    // 详情弹窗（用户：「会话管理要有会话的文件，可以看会话详细日志、大小等等」）：文件路径/大小/
    // 修改时间 + 原始 JSON 尾部。文件信息在 IO 上读，弹窗开着才读一次。
    var pendingInfo by remember { mutableStateOf<Conversation?>(null) }
    var chatFileInfo by remember { mutableStateOf<ChatRepository.ChatFile?>(null) }
    // 手动顺序只读（这一页不提供拖动）：读出来是为了让置顶区与抽屉长得一样 ——
    // 同一枚置顶在抽屉里排在最上面、在这里却按时间散着，这正是用户说的「置顶逻辑没理清」。
    val orderStore = remember(context) { DrawerOrderStore(context) }
    var drawerOrder by remember { mutableStateOf(DrawerOrder()) }
    LaunchedEffect(Unit) { drawerOrder = orderStore.load() }
    LaunchedEffect(pendingInfo) {
        // 换一条会话（或关掉弹窗）先把上一份文件信息清掉：读盘在 IO 上要几十毫秒，而弹窗
        // 在点下去那一帧就已经画出来了 —— 留着旧的会先闪一眼**上一条会话**的路径与大小
        // （issue #55 5.27：chatFileInfo 切会话不复位）。
        chatFileInfo = null
        val chat = pendingInfo ?: return@LaunchedEffect
        // **走仓库的 chatFile**：它知道日志在 `chats/store/<id>.<gen>.jsonl`。
        // 原来这里读旧的 `chats/<id>.json`，迁移之后那个文件不存在 ⇒ 对每个会话都显示
        // 「这个会话在磁盘上没有对应的文件」（用户实测）。
        chatFileInfo = withContext(Dispatchers.IO) { Core.chats.chatFile(chat.id) }
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
                    // **不能直接拷 `chats/<id>.json`**：迁移之后它不存在了（导出必然失败）。
                    // 也不能拷 JSONL 日志 —— 用户打不开。这里序列化成整份 JSON。
                    val text = Core.chats.exportJson(chat.id) ?: error("读不到会话内容")
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
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
        // 排序/过滤只依赖这两个输入：算一次，别跟着每次重组重来。
        val sections = remember(conversations, drawerOrder) { drawerSections(conversations, drawerOrder) }
        val archivedList = remember(conversations) {
            conversations.filter { it.archived }.sortedByDescending { it.updatedAt }
        }
        // 活动会话的顺序（置顶区在前、普通区在后，与抽屉共用同一套排序）。
        // **提到 LazyColumn 外面**：下面要把它们的字节数一次性批量取好，见 sizes。
        val active = remember(sections) { sections.pinned + sections.normal }
        // **字节数一次批量取**（性能审计）：原来每行一个 LaunchedEffect 调 chatSize，
        // 那是每行一次 `withContext(Dispatchers.IO)` + 一次 `ChatRepository.lock`（那把锁和
        // 会话保存、流式写盘共用）；滚动一次就抢 N 次全局锁，而且 `remember` 在 item 作用域，
        // 滚出去再滚回来又重读一遍。现在一次线程切换、一次锁、一次遍历。
        var sizes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
        LaunchedEffect(active) {
            sizes = Core.chats.chatSizes(active.map { it.id })
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            // 末行避让手势条（issue #47）：内容本身画到手势条底下，滚动时穿过去。
            contentPadding = settingsPagePadding(),
            verticalArrangement = Arrangement.spacedBy(SettingsStyle.SectionGap),
        ) {
            // 与抽屉共用同一套排序（drawerSections）：置顶在前、置顶区按手动顺序、普通区按
            // updatedAt。这一页原来是纯时间序、也不显示置顶 —— 同一枚置顶两个页面两个样子。
            // `active` 已在 LazyColumn 外面算好（见 sizes 的批量取）。
            val archived = archivedList
            if (active.isEmpty() && archived.isEmpty()) {
                item {
                    EmptyState(
                        title = "还没有会话",
                        subtitle = "聊过一次之后，会话会出现在这里，可以打开、导出或删除。",
                        modifier = Modifier.padding(top = 56.dp),
                    )
                }
            } else {
                items(
                    active,
                    key = { it.id },
                    contentType = { "conversation-row" },
                ) { conversation ->
                    // **读 store 里的日志**（#40 之后磁盘上已无 `<id>.json`，旧路径恒返回 -1）
                    // ⇒ 副标题里的"体积"一直不显示。`chatFile` 给的就是 meta 指向的那份日志。
                    // 字节数已在页面级**批量取好**（sizes），这里只查表，不再每行做 IO。
                    val bytes = sizes[conversation.id] ?: -1L
                    // 副标题是一次相对时间格式化（DateUtils）+ 一次字节数格式化。按真正影响输出
                    // 的三个字段记住它：别跟着每次重组重来。文案、分隔符与原来逐字相同。
                    val subtitle = remember(
                        conversation.id,
                        conversation.updatedAt,
                        conversation.messageCount,
                        bytes,
                    ) {
                        "${lastUpdated(conversation.updatedAt)} · " +
                            "${conversation.messageCount} 条消息" +
                            (if (bytes >= 0) " · ${humanBytes(bytes)}" else "")
                    }
                    ConversationRow(
                        title = conversation.title,
                        pinned = conversation.pinned,
                        completedUnread = conversation.id in unreadCompleted && conversation.id !in runningIds,
                        subtitle = subtitle,
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
                    item(key = "archived-header", contentType = "header") {
                        Text(
                            text = "已归档（${archived.size}）",
                            style = SettingsStyle.sectionTitle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                        )
                    }
                    items(
                        archived,
                        key = { it.id },
                        contentType = { "conversation-row" },
                    ) { conversation ->
                        // 同活动会话行：这里的相对时间也只在 updatedAt / 条数变了时才重算。
                        val subtitle = remember(
                            conversation.id,
                            conversation.updatedAt,
                            conversation.messageCount,
                        ) {
                            "已归档 · ${lastUpdated(conversation.updatedAt)} · " +
                                "${conversation.messageCount} 条消息"
                        }
                        ConversationRow(
                            title = conversation.title,
                            completedUnread = conversation.id in unreadCompleted && conversation.id !in runningIds,
                            // **用 meta 的 messageCount**：`messages` 是空列表
                            // （`load()` 只读 meta），拿它算会显示成「0 条消息」。
                            subtitle = subtitle,
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
        SuixingDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这个会话？") },
            text = {
                Text(
                    "「${chat.title}」的聊天记录（${chat.messageCount} 条消息）会被永久删除，" +
                        "不能撤销。工作区里的文件不受影响。",
                )
            },
            confirmButton = { dialog ->
                TextButton(
                    onClick = {
                        dialog.closeThen {
                            pendingDelete = null
                            scope.launch {
                                Core.chats.delete(chat.id)
                                snackbar.showSnackbar("已删除「${chat.title}」")
                            }
                        }
                    },
                ) { Text("删除") }
            },
            dismissButton = { dialog -> TextButton(onClick = { dialog.closeThen { pendingDelete = null } }) { Text("取消") } },
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
    /** 置顶标记：抽屉那份顺序会把它排到最前，这一页至少得看得出「它是置顶的」。 */
    pinned: Boolean = false,
    completedUnread: Boolean = false,
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (completedUnread) {
                        CompletionDot()
                        Spacer(Modifier.width(2.dp))
                    }
                    if (pinned) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_star),
                            contentDescription = "已置顶",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        text = title,
                        style = SettingsStyle.rowTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = subtitle,
                    style = SettingsStyle.badge,
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
/** 字节数用人话：1.2 KB / 3.4 MB。 */
private fun humanBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

/** 会话详情弹窗：文件三要素 + 原始 JSON 尾部（可滚动、等宽）。 */
@Composable
private fun ChatInfoDialog(info: ChatRepository.ChatFile?, onDismiss: () -> Unit) {
    SuixingDialog(
        onDismissRequest = onDismiss,
        title = { Text("会话文件") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (info == null) {
                    Text("这个会话在磁盘上没有对应的文件（可能还没落盘）。")
                } else {
                    Text("路径：${info.path}", style = SettingsStyle.badge)
                    Text(
                        "大小：${humanBytes(info.bytes)} · 修改：${lastUpdated(info.modifiedAt)}",
                        style = SettingsStyle.badge,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("原始记录（尾部 4000 字）：", style = SettingsStyle.badge)
                    Text(
                        text = info.tail,
                        style = SettingsStyle.badge.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        },
        confirmButton = { scope -> TextButton(onClick = { scope.closeThen(onDismiss) }) { Text("关闭") } },
    )
}

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
                    style = SettingsStyle.controlLabel,
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
    SuixingDialog(
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
        confirmButton = { scope ->
            TextButton(onClick = { scope.closeThen { onConfirm(name) } }) { Text("确定") }
        },
        dismissButton = { scope -> TextButton(onClick = { scope.closeThen(onDismiss) }) { Text("取消") } },
    )
}

/** 一个带说明的控件块：标题、why 行在上，控件在下（卡片式设置页的统一骨架）。 */
@Composable
private fun LabeledBlock(
    label: String,
    hint: String? = null,
    content: @Composable () -> Unit,
) {
    Column(Modifier.padding(top = SettingsStyle.RowPadding)) {
        Text(label, style = SettingsStyle.rowTitle)
        if (hint != null) {
            Spacer(Modifier.height(SettingsStyle.RowGap))
            Text(
                text = hint,
                style = SettingsStyle.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(SettingsStyle.FieldGap))
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
                style = SettingsStyle.description,
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
    SuixingDialog(
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
                    Text("自定义模型", style = SettingsStyle.badge)
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
                        style = SettingsStyle.body,
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
        confirmButton = { scope ->
            TextButton(onClick = { scope.closeThen { onConfirm(models.filter { it in picked }) } }) {
                Text("确定（${picked.size}）")
            }
        },
        dismissButton = { scope -> TextButton(onClick = { scope.closeThen(onDismiss) }) { Text("取消") } },
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
            style = SettingsStyle.badge,
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
                style = SettingsStyle.badge,
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
                style = SettingsStyle.body,
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
                    style = SettingsStyle.badge,
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
                    style = SettingsStyle.badge,
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
        Text("接口协议", style = SettingsStyle.body)
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
                    Text(label, style = SettingsStyle.body, modifier = Modifier.weight(1f))
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
    SuixingDialog(
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
        confirmButton = { scope -> TextButton(onClick = { scope.closeThen { onConfirm(value.trim()) } }) { Text("保存") } },
        dismissButton = { scope -> TextButton(onClick = { scope.closeThen(onDismiss) }) { Text("取消") } },
    )
}

/** Type a model id the endpoint never listed; it joins the list at once. */
@Composable
private fun AddModelDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    SuixingDialog(
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
        confirmButton = { scope ->
            TextButton(
                enabled = value.isNotBlank(),
                onClick = { scope.closeThen { onConfirm(value.trim()) } },
            ) { Text("添加") }
        },
        dismissButton = { scope -> TextButton(onClick = { scope.closeThen(onDismiss) }) { Text("取消") } },
    )
}

/** Shared 15sp input role; the theme applies the selected font scale. */
private val settingsFieldText: TextStyle
    @Composable get() = SettingsStyle.fieldText

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

/** Controls already own their top inset; avoid adding a second gap below the heading. */
@Composable
private fun SectionHeader(text: String, first: Boolean = false) {
    com.suixing.agent.ui.components.SettingSectionTitle(
        text,
        contentPadding = PaddingValues(
            start = SettingsStyle.SectionIndent,
            top = if (first) 0.dp else SettingsStyle.SectionTop,
            bottom = 0.dp,
        ),
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
    Column(Modifier.padding(top = SettingsStyle.RowPadding)) {
        Text(label, style = SettingsStyle.rowTitle)
        if (hint != null) {
            Text(
                text = hint,
                style = SettingsStyle.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // **拖动期间只改本地态，松手才落盘**（性能审计 #1）：`onValueChange` 是每个
        // delta 一次回调，原来直接进 `persist{}` —— 而 Settings 的写入是
        // 「整份 JSON 编码 + Keystore 加密 + 文件重写 + 广播」，且 9 处订阅者会整屏重组
        // ⇒ 拖一秒 = 60 次全量写盘 + 60 次全 app 重组。这就是拖滑杆卡顿的直接原因。
        var local by remember(range) { mutableFloatStateOf(value) }
        // 外部值变化（重置/切换配置）时同步进来；拖动期间外部不会变。
        LaunchedEffect(value) { local = value }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onValueChange(local) },
            valueRange = range,
            steps = steps,
        )
    }
}
