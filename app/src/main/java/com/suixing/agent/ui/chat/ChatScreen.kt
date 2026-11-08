package com.suixing.agent.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suixing.agent.core.Core
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.ui.chat.markdown.LocalPathClick
import com.suixing.agent.ui.components.LocalHeldImePx
import com.suixing.agent.ui.components.LocalSetSelectionActive
import com.suixing.agent.ui.theme.LocalDarkTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal val SUGGESTIONS = listOf(
    "看看现在屏幕上是什么",
    "把下载目录里最新的三个文件列出来",
    "搜一下今天有什么科技新闻",
    "打开设置看看还剩多少存储空间",
)

/** 聊天页：常驻的会话正文、输入区与右侧回合导航条。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel(),
    /** The generation popup's empty-list escape hatch into the model settings page. */
    onOpenSettings: () -> Unit = {},
    /** 顶栏溢出菜单：拉起系统浏览器 / 打开内置终端页。 */
    onOpenBrowser: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
) {
    val actions = remember(viewModel) { ChatScreenActions(viewModel) }
    val state by actions.screenState.collectAsStateWithLifecycle()
    val accessibleActions = rememberChatAccessibility(state.conversationId)
    val subagentUi = remember(state.conversationId) { SubagentUiState() }
    SubagentDialogHost(state.conversationId, subagentUi)
    // Only changes consumed by this page and its children invalidate the screen integration.
    // Floating-console geometry is excluded; voice/model settings still reach their consumers.
    val settings by remember {
        Core.settings.flow.distinctUntilChanged { a, b -> a.sameForChat(b) }
    }.collectAsStateWithLifecycle(initialValue = AppSettings())
    val positionStorageReady by Core.conversationViewports.ready.collectAsStateWithLifecycle()
    val openingBookmark = remember(state.conversationId, positionStorageReady) {
        if (positionStorageReady) Core.conversationViewports.get(state.conversationId) else null
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // The unsent text is the view model's, keyed by conversation: typing here, opening
    // another chat and coming back restores exactly what was left, and nothing leaks
    // across the switch.
    // What `/` can run here: the same persona filter the agent run uses, with the goal alias.
    // MCP connects and drops tools after this screen composed; the registry's revision is
    // what makes the slash list follow it (issue #76 F-05).
    val catalogRevision by Core.tools.catalogRevision.collectAsStateWithLifecycle()
    val slashTools = remember(state.assistantId, settings.assistants, settings.activeAssistantId, catalogRevision) {
        val assistant = settings.assistant(state.assistantId) ?: settings.activeAssistant()
        val disabled = assistant?.disabledTools?.toSet().orEmpty()
        slashCommands(
            Core.tools.excluding(disabled).all()
                .map { tool ->
                    tool.spec.name to tool.spec.description.lineSequence().firstOrNull().orEmpty()
                },
        )
    }

    val dialogs = rememberChatDialogState(state)
    val inputBindings = rememberChatInputBindings(state, actions, scope, snackbarHostState, dialogs)
    // **按会话重置**（评审 S4）：它持有的是某条消息对象，切会话后若还能被读到，
    // `editMessage(id, …)` 会把 A 的编辑改到 B 上。目前靠"编辑弹窗是模态"侥幸挡住，
    // 但任何非模态切换路径（通知/deep link/分屏）都会踩。
    var editing by dialogs.editingState
    val editMessage = rememberStableCallback1<StoredMessage> { editing = it }
    // The composer stack's own measured height: the node inside all of its paddings, so the
    // gesture bar's strip and its own 12dp of air are not in it (they are added back below).
    var composerH by remember { mutableStateOf(0.dp) }
    // The keyboard's clamped inset, and the gesture bar's own: the composer's bottom padding is
    // the difference between them (see the stack's own modifier), and it is the one term of the
    // stack's footprint that moves while the keyboard does.
    // 键盘高度通过 State 引用下传，在底部避让的布局节点读；动画不逐帧订阅整页组合。
    val imePxState = LocalHeldImePx.current
    // 手势条高度在组合期算一次：它是 @Composable 调用（`WindowInsets.navigationBars`），
    // 而且它在键盘动画期间不变。
    val navBarPx = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this) }
    // 让位＝**只用一个来源**：输入框自己的高度 + 它的下内边距 + 手势条。键盘动画中唯一在变的量是
    // heldIme，而它正是输入框自身位置的同一个驱动量 ⇒ 两者同步移动，可见空隙在任何一帧都一样
    // （用户要恒定）。原来用「列表底 − 输入框顶」两个节点的测量值相减，两个 onGloballyPositioned
    // 不同帧到位 ⇒ 动画中间态抖动。
    // 读历史期间沿用的底边距（见 LazyColumn 的 `bottom`）。
    // **null = 还没种过**，不是 0.dp（评审确证）：用 0.dp 当哨兵时，恢复位置进会话会先以
    // 0 当作底边距、等 `composerH` 量到才补成真实值 ⇒ 底边距 0 → ~一个输入框高，
    // 而 `scrollToItem` 已经执行过 ⇒ 整段恢复位置被推移一次。
    // **按会话 key**（评审）：底边距是"读历史期间冻结的那一个"，不同会话的输入框/让位不同，
    // 不 key 的话切到另一个会话会沿用上一个会话冻结值。
    var heldFootprint by remember(state.conversationId) { mutableStateOf<Dp?>(null) }
    // **不含手势条那一截**：它在布局期由列表/空状态自己让出来（[keyboardAwareImePad]），
    // 这样这一项不随键盘逐帧变 —— 否则它就是上面说过的「每帧整页重组」。
    // 几何上与旧写法等价：列表视口少了 navBarPad、正文底边距也少了 navBarPad，
    // 最后一张卡与输入框之间的距离一分不差（键盘全程恒定）。
    val stackFootprint = with(LocalDensity.current) {
        // `composerH` 是**内边距以内**的高度：`onSizeChanged` 挂在
        // `.padding(bottom = COMPOSER_BOTTOM_PAD)` **之后**（TurnScrubber.kt:757-758），
        // Compose 的修饰符顺序让 padding 先吃掉空间，所以测到的不含这 12dp。
        // 这里把它加回来才是输入框真实的占位 —— **不要删**（我删过一次，是错的）。
        composerH + COMPOSER_BOTTOM_PAD +
            // **恒定 12dp**（用户定案：所有垂直间距统一 12dp —— 静态项间距、状态行上下、
            // 底部让位全一致；顺带保持「run 结束时几何完全不变」）。
            12.dp +
            // **手势条那一截改由 contentPadding 让**（用户：「手势条有背景颜色会挡到内容」）。
            // 原来它写在列表的布局修饰符里（[keyboardAwareFullPad]），那是**裁剪视口**：
            // 内容到条子上沿就被切掉，条子下面只剩一底色 —— 正是「不沉浸」。
            // 设置页一直是 contentPadding 写法（只让最后一行避开条子，滚动中内容从条下穿过），
            // 这里对齐它；键盘那一截仍在布局期让（见 keyboardAwareImePad），不引入逐帧重组。
            with(LocalDensity.current) { navBarPx.toDp() }
    }

    val latestUserAt = remember(state.conversationId, state.messagesRevision) {
        state.messages.lastOrNull { it.role == Roles.USER }?.timestamp ?: 0L
    }


    val live by actions.liveActive.collectAsStateWithLifecycle()
    val transcript = rememberChatTranscriptProjection(state, positionStorageReady, openingBookmark, live)
    val messagePositions = transcript.messagePositions
    val shownTurns = transcript.shownTurns
    val visible = transcript.visible
    val rowKeys = transcript.rowKeys
    val rowKeyIndices = transcript.rowKeyIndices
    val viewport = transcript.viewport
    val rowOffset = transcript.rowOffset
    val listState = transcript.listState
    val itemCount = transcript.itemCount
    // Content activity is observed separately; the reply card itself exists from run start.
    // 流式气泡开关与 pin 信号都只需要「有没有活内容」这个布尔：
    // 收 VM 的 liveActive（distinct 去抖），思考期的 40ms 批次不再重组整屏。

    val contextReadout = rememberChatContextReadout(state, settings, messagePositions, viewport, actions, catalogRevision)

    val runPresentation = rememberChatRunPresentation(state)
    val reasoningExpansion = runPresentation.reasoningExpansion
    // Reading is natively top-anchored; explicit follow-to-end lives in ChatScroll.kt.
    // A swipe disables following, so neither streaming nor settlement seeks the list.

    val gesture = remember { ChatReadingGesture() }
    // **长按才 arm**（跟手性）：400ms 的守护窗口是给「长按选文字」用的（选区建立期间不许列表
    // 自己动），但原来每次按下都 arm ⇒ 每个拖动手势之后 400ms 都有一个逐帧协程 + 冻结 IME clamp，
    // 正好落在惯性滚动期，观感就是「滑完发涩」。
    // 长按阈值：composable 作用域里没有 `PointerInputScope.viewConfiguration`，要取 Local。
    val viewConfig = LocalViewConfiguration.current
    val selectionFocus = LocalFocusManager.current
    val selectionTracker = remember(state.conversationId) { SelectionFocusTracker() }
    // **组合期不订阅 `pressed`**（审计 1§3）：原来 `LaunchedEffect(pressed)` 的 key
    // 是状态读 —— 每次按下/抬起都把整个 ChatScreen（58 个子组合）重组一遍，反向手势
    // 100ms 内 3~4 次。改成在协程里 snapshotFlow 订阅：读发生在协程，组合期零订阅。
    // composition local 只能在组合里读：先在组合层捕获，守护协程里用这个引用。
    val setSelectionActive = LocalSetSelectionActive.current
    // **分批填充**（打开/切换会话时 12 轮一次性组合 = 100~150ms，frame.log 实测：
    val scroll = rememberChatScroll(
        // 内容版本：消息条数 + 流式/思考字符数。只在真正"长高"时变，滚动本身不会触发。
        listState = listState,
        autoFollow = settings.autoFollow,
        pressedNow = { gesture.pressed },
        guardNow = { gesture.pressGuardOn },
        conversationId = state.conversationId,
        initialFollowing = openingBookmark?.followLatest != false,
    )


    val transcriptPosition = rememberChatReadingPosition(state, settings, transcript, gesture, scroll, reasoningExpansion)

    val earlierPaging = rememberChatEarlierPaging(state, transcript, actions, scope, scroll, gesture, live)
    val loadingEarlier by earlierPaging.loading
    val loadEarlier = earlierPaging.onLoad
    val prepareRows = rememberChatMarkdownPreparation(state, transcript, actions)
    key(state.conversationId) {
        BindConversationViewport(
            viewport, actions, scroll, visible, rowKeys, rowKeyIndices, rowOffset, itemCount, shownTurns, state.turnCount,
            MaterialTheme.typography.bodyMedium.fontSize.value * LocalDensity.current.fontScale,
            positionStorageReady, state.messagesRevision, prepareRows,
        )
    }
    // 正常按合并和放置解除 restoring；异常准备等待有上限，异步失败由 onComplete 解锁。
    // 贴底时持续记住当前占位；离开底部（开始读历史）就冻住它 ——
    // 输入框高度在流式期间会变（状态行出现/消失），不冻住就会把整列内容推几 dp。
    // **key 里不要放 `stickToBottom`**（性能静态审查①）：那是 SnapshotStateMap 里的值，
    // 放在 key 位等于组合期订阅 —— 它每变一次就整屏重组，而拖动过程中它会被反复写。
    // 改成协程内订阅：语义等价（footprint 变或 stick 变都重算），但组合期零订阅。
    LaunchedEffect(stackFootprint) {
        // 贴底时持续跟随；**另外**只要还没种过（0.dp）就种一次 —— 恢复位置进会话时
        // `stickToBottom` 一开始就是 false，条件永远不成立 ⇒ 整个阅读期间底边距是 0。
        // 贴底时持续跟随；没种过（null）也种一次 —— 恢复位置进会话时 `stickToBottom` 一开始就是
        // false，否则整个阅读期间都取不到值。
        // 读放在协程里（snapshotFlow），组合期不再订阅这个高频变化的值。
        snapshotFlow { scroll.stickToBottom }.collect { stick ->
            if (stick || heldFootprint == null) heldFootprint = stackFootprint
        }
    }

    val revealGuardSpec = rememberChatSelectionGuard(gesture, listState, scroll, viewConfig, setSelectionActive)
    // The target's entrance starts only after its first valid viewport is ready.
    // **按 item key 锚定阅读位置**（issue #11，取代 #10 的「钉住高度」）。
    PruneChatPresentation(actions, scroll, transcript, runPresentation)
    // 流式行长高时的位置稳定由 **TranscriptPosition 在布局期锚定**实现（issue #17 已验收）；
    // 早先那套「高度推迟一帧 + dispatchRawDelta 同帧补偿」的方案已经不存在了，
    // 注释留着会把人引向一个不存在的实现。
    // **不是移出列表**：流式行仍在 LazyColumn 里。
    // 只在「用户上翻读历史」时启用；贴底时高度直接跟自然值，行为与以前完全一致。
    // 外观 → 气泡宽度. Measured against the screen, which is what the setting promises
    // in its own words: 标准 (0.94) stays wider than the transcript's own 16dp margins,
    // so the default leaves every card exactly as wide as it has always been, and only
    // 窄 pulls the reply in.
    val bubbleMaxWidth = with(LocalConfiguration.current) { screenWidthDp.dp } *
        settings.bubbleWidthFraction

    fun jumpToLatest() {
        viewport.followOverride = true
        scope.launch {
            scroll.allowBottomResume = true
            scroll.stickToBottom = true
            if (itemCount > 0) {
                scroll.toBottom()
            }
        }
    }

    // 顶部提示条已移除，这里不再需要倒计时收起它的定时器（失败原因画在转录里的红色回执卡上）。
    // 文件路径点击 → 弹菜单（复制 / 让JAgent读取），LINK-CLICK-SPEC §3.2 一期。回调经 LocalPathClick
    // 下传，四个 MarkdownText 调用点与中间各层组件都不用加参数。lambda 用 remember 稳定引用：否则
    // 每次重组新建实例会让 InlineText 的 remember(pathClick) 失效、流式时每帧重排文本。
    var pathMenuPath by dialogs.pathMenuPathState
    val onPathMenu = remember { { path: String -> pathMenuPath = path } }
    CompositionLocalProvider(LocalPathClick provides onPathMenu, LocalAccessibleChatActions provides accessibleActions) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // No topBar slot: the header floats over the transcript as three discs, so the
        // scaffold must not reserve a strip for it — content scrolling behind the
        // buttons is the whole point of the floating header.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // Light mode fades a whisper of brand colour into the bottom of the canvas, the
        // way the reference apps tint their own surface; dark stays flat because the same
        // gradient over #0F1412 bands on OLED.
        val canvas = if (LocalDarkTheme.current) {
            SolidColor(MaterialTheme.colorScheme.surface)
        } else {
            Brush.verticalGradient(
                listOf(
                    MaterialTheme.colorScheme.surface,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                ),
            )
        }
        // The top fade layer needs one solid colour: the canvas brush's own top stop.
        val canvasColor = MaterialTheme.colorScheme.surface
        // The header is a real bar again: it owns the status bar strip and the transcript
        // starts below it, so nothing scrolls under a translucent band of controls.
        // The transcript and the jump button share one box: the button belongs to the
        // reading area, not to the composer, so it must not sit under the pill.
        // **深色下不要再刷一遍整屏**（重复渲染清理）：`Scaffold(containerColor = background)`
        // 已经把整屏涂成 `background`，而深色主题里 `background` 与 `surface` 是同一个色值
        // （`theme/Color.kt` 都是 `0xFF0F1412`）⇒ 这层 `background(canvas)` 在深色下是纯重复绘制，
        // 每帧多一次全屏填充、像素完全不变。
        // 浅色下 `canvas` 是带一点品牌色的竖向渐变（不是纯色），必须保留。
        Box(
            Modifier
                .fillMaxSize()
                .then(if (LocalDarkTheme.current) Modifier else Modifier.background(canvas))
                .padding(padding),
        ) {

            ChatTranscriptViewport(
                state, settings, transcript, contextReadout, runPresentation, actions, inputBindings,
                transcriptPosition, revealGuardSpec, gesture, scroll, selectionTracker, viewConfig,
                selectionFocus, setSelectionActive, composerH, navBarPx, imePxState,
                { heldFootprint }, stackFootprint, bubbleMaxWidth, live,
                loadingEarlier, loadEarlier, editMessage, onOpenSettings,
            )

            ChatScreenHeader(state, actions, contextReadout, subagentUi, canvasColor, onOpenBrowser, onOpenTerminal)

            ChatTurnNavigation(state, transcript, live, actions, scope, scroll,
                transcriptPosition, prepareRows, imePxState)

            ChatComposerPanel(
                state, settings, actions, inputBindings, dialogs, contextReadout, subagentUi,
                slashTools, latestUserAt, scope, snackbarHostState, scroll, itemCount, imePxState,
                onOpenSettings, ::jumpToLatest, onHeightChanged = { composerH = it },
            )
        }
        ChatInlineOverlays(state, settings, dialogs, inputBindings, composerH, onOpenSettings)
    }
    }

    ChatDialogs(state, settings, dialogs, actions, scope, snackbarHostState)

}

