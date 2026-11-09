@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.suixing.agent.ui.settings

import com.suixing.agent.data.SecretBox
import com.suixing.agent.ui.components.settingsPagePadding
import com.suixing.agent.ui.components.SettingsStyle
import com.suixing.agent.ui.components.CompletionDot

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.suixing.agent.ui.components.actionIconButtonColors
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
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
import com.suixing.agent.core.Core
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.SubagentModelSelection
import com.suixing.agent.data.resolve
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
import com.suixing.agent.llm.ChatMessage
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
import com.suixing.agent.ui.theme.AppearancePalette
import com.suixing.agent.ui.theme.LocalDarkTheme
import com.suixing.agent.ui.theme.Tone
import com.suixing.agent.ui.theme.color
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop

/** Outcome of a connection test, shown in the fixed bar above the buttons. */
internal data class TestOutcome(val ok: Boolean, val message: String)


/**
 * The pages the root list opens. 权限 is deliberately missing: it is not a page of this
 * screen at all, it hands off to the capability list, which is a list the user manages
 * rather than settings the user toggles.
 *
 * Each row is its own shell route ([route]), not a branch of a `when`: a page that swaps
 * in place inside one destination gets no enter/exit transition at all — the shell's
 * NavHost is the only thing that animates, and it only animates between destinations.
 */
internal const val SUBAGENT_SETTINGS_ROUTE = "settings/subagent"

internal enum class SettingsPage(val title: String, val route: String) {
    MODEL("模型与生成", "settings/model"),
    CONVERSATION("对话配置", "settings/conversation"),
    SUBAGENT("子任务（Subagent）", SUBAGENT_SETTINGS_ROUTE),
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
        SettingsPage.SUBAGENT -> SubagentSettingsPage(onBack = onBack)
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
internal fun SettingsRoot(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenPage: (SettingsPage) -> Unit,
    onOpenOnboarding: () -> Unit,
) {
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
            SettingsGroupRow(SettingsPage.SUBAGENT.title, "模板、助手、模型、工具与任务委派") {
                openNow.value(SettingsPage.SUBAGENT.route)
            },
            SettingsGroupRow("外观", "主题、配色与排版") {
                openNow.value(SettingsPage.APPEARANCE.route)
            },
        )
    }
    val connectionRows = remember {
        listOf(
            // 权限不是这一页的子页：能力清单是一份用户管理的清单，不是一组开关。
            SettingsGroupRow("UI自动化", "虚拟屏、预览与边缘光") {
                openNow.value(com.suixing.agent.ui.automation.UI_AUTOMATION_ROUTE)
            },
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
            item { SettingsStatusCard() }
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
internal fun SettingsStatusCard() {
    // **collect 下沉到唯一消费者**（任务4 #6）：原来在 SettingsRoot 页顶 collect，改任意
    // 设置（主题/字号/开关）都把整个 SettingsRoot + LazyColumn 拖进重组，而这份状态只有
    // 这张卡读。下沉后改设置只重组这张卡。
    val capabilities by Core.capabilities.state.collectAsStateWithLifecycle()
    val settings by Core.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    // 三个推导串 remember：只在状态真变时重拼（原来每次重组都 buildString）。
    val tier = remember(capabilities) {
        when {
            capabilities.rootReady -> "Root 档"
            capabilities.shellReady -> "Shizuku 档"
            capabilities.accessibility -> "无障碍档"
            else -> "标准档"
        }
    }
    val checks = remember(capabilities) {
        buildString {
            append(if (capabilities.accessibility) "无障碍 已开" else "无障碍 未开")
            append(" · ")
            append(if (capabilities.shellReady) "Shizuku 已连" else "Shizuku 未连")
            append(" · ")
            append(if (capabilities.rootReady) "Root 已就绪" else "Root 未检测")
        }
    }
    val model = remember(settings) {
        if (settings.configured) {
            val provider = settings.activeProvider()?.label
            "模型：${settings.shortModel}" + if (provider.isNullOrBlank()) "" else "（$provider）"
        } else {
            "模型：还没配置接口与密钥"
        }
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
internal class SettingsGroupRow(
    val title: String,
    val summary: String,
    val onClick: () -> Unit,
)

/** A group name over one rounded card that holds all of that group's rows. */
@Composable
internal fun SettingsGroup(title: String, rows: List<SettingsGroupRow>) {
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


/** 外观: theme, palette, type scale and what the transcript shows by default. */

/** 网络与语音: where search goes, and whether replies are read out loud. */

/**
 * 数据：只有「内容存在哪」的几条入口，每一行都通到一个只属于数据的地方。
 *
 * MCP 与技能搬去了工具页，悬浮窗的授权卡本来就在权限页（CapabilityScreen 里的
 * 「悬浮窗控制台」卡，granted = FloatConsoleService.canDrawOverlays），关于自己成了一页
 * （[AboutScreen]）——这一页原来什么都放一点，重复的入口正是要删的东西。
 */

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

/** 一行会话：点行打开，右侧动作（详情、导出、删除；已归档的多一枚「取消归档」）。 */
@Composable
internal fun ConversationRow(
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
            IconButton(onClick = onInfo, colors = actionIconButtonColors()) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_info),
                    contentDescription = "详情",
                    modifier = Modifier.size(18.dp),
                )
            }
            // 取消归档排在导出之前：它是这一行唯一的「把会话放回抽屉」的动作，最常用。
            onUnarchive?.let { unarchive ->
                IconButton(onClick = unarchive, colors = actionIconButtonColors()) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_folder_open),
                        contentDescription = "取消归档",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            IconButton(onClick = onExport, colors = actionIconButtonColors()) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_download),
                    contentDescription = "导出",
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onDelete, colors = actionIconButtonColors()) {
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
internal fun lastUpdated(millis: Long): String = DateUtils.getRelativeTimeSpanString(
    millis,
    System.currentTimeMillis(),
    DateUtils.MINUTE_IN_MILLIS,
).toString()

/** 导出文件名：标题里的路径分隔符与空白换成下划线，空标题兜底。 */
internal fun exportFileName(title: String): String {
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
internal fun humanBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

/** 会话详情弹窗：文件三要素 + 原始 JSON 尾部（可滚动、等宽）。 */
@Composable
internal fun ChatInfoDialog(info: ChatRepository.ChatFile?, onDismiss: () -> Unit) {
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
internal fun AdvancedSection(content: @Composable ColumnScope.() -> Unit) {
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

/** 一个带说明的控件块：标题、why 行在上，控件在下（卡片式设置页的统一骨架）。 */
@Composable
internal fun LabeledBlock(
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
internal fun OutcomeCard(outcome: TestOutcome, onDismiss: () -> Unit) {
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


@Composable
internal fun GroupHeader(
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


/** Type a model id the endpoint never listed; it joins the list at once. */
@Composable
internal fun AddModelDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
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
internal val settingsFieldText: TextStyle
    @Composable get() = SettingsStyle.fieldText

/** A row that leaves this page: the chevron is what says so before it is tapped. */
@Composable
internal fun SettingsLink(
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
internal fun SectionHeader(text: String, first: Boolean = false) {
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
internal fun LabeledSlider(
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
