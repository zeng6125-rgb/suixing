@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.suixing.agent.ui

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import com.suixing.agent.ui.components.ImeInsetFilter
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.suixing.agent.core.Core
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.Assistant
import com.suixing.agent.data.Conversation
import com.suixing.agent.service.FloatConsoleService
import com.suixing.agent.ui.assistant.AssistantEditor
import com.suixing.agent.ui.assistant.AssistantSheet
import com.suixing.agent.ui.capability.CapabilityScreen
import com.suixing.agent.ui.chat.ChatScreen
import com.suixing.agent.ui.chat.ChatViewModel
import com.suixing.agent.ui.chat.ObserveConversationRead
import com.suixing.agent.ui.components.FullScreenOverlay
import com.suixing.agent.ui.components.LocalDrawerBusy
import com.suixing.agent.ui.components.LocalHeldImePx
import com.suixing.agent.ui.components.LocalOpenSidebar
import com.suixing.agent.ui.components.LocalSetSelectionActive
import com.suixing.agent.ui.components.NAV_ENTER_MS
import com.suixing.agent.ui.components.NAV_EXIT_MS
import com.suixing.agent.ui.components.NAV_SHIFT_DIVISOR
import com.suixing.agent.ui.components.imePaddingBottom
import com.suixing.agent.ui.drawer.DRAWER_CLOSE_SPEC
import com.suixing.agent.ui.drawer.DRAWER_OPEN_SPEC
import com.suixing.agent.ui.drawer.Drawer
import com.suixing.agent.ui.drawer.DrawerBackHandler
import com.suixing.agent.ui.extension.ExtensionScreen
import com.suixing.agent.ui.files.FilesScreen
import com.suixing.agent.ui.onboarding.OnboardingHost
import com.suixing.agent.ui.schedules.SchedulesScreen
import com.suixing.agent.ui.settings.AboutScreen
import com.suixing.agent.ui.settings.AssistantScreen
import com.suixing.agent.ui.settings.ProfileScreen
import com.suixing.agent.ui.settings.SettingsPageRoute
import com.suixing.agent.ui.settings.SettingsScreen
import com.suixing.agent.ui.settings.ToolsScreen
import com.suixing.agent.ui.tasks.TasksScreen
import com.suixing.agent.ui.theme.LocalDarkTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
/**
 * 全站唯一的转场规格：旧页**当帧消失**，新页整幅滑入（纯位移、无淡化 —— 淡化必然有
 * "两页同时可见"的中间帧，那正是历次被否掉的残影）。抽屉不随导航关闭，新页压着它滑入。
 */

/** 0.8.91 原样：新页滑入的时长。全站统一数字在 [com.suixing.agent.ui.components.NAV_ENTER_MS]。 */
private const val NAV_SLIDE_MS = NAV_ENTER_MS

/** 新页滑入的幅度：屏宽 ÷ 这个数（12 ⇒ 约 8% 屏宽）。同 [NAV_SHIFT_DIVISOR]。 */
private const val NAV_SHIFT = NAV_SHIFT_DIVISOR

/**
 * 推入式的时长：整幅位移比 1/12 的小滑入需要更长一点，才读成"推进"而不是"闪一下"。
 * 旧页同时往左退 1/4 屏（见 NavHost 的 exitTransition），两段位移是同一段时间。
 */
private const val NAV_PUSH_MS = 280

/**
 * **只有返回**用这一条（用户要的「极轻的蒙版由深到没有」）：被关掉的那页淡出，下面那页不动。
 * 进页**不用**它 —— 进页是 0.8.91 的滑入（用户：「点进去的动画可以」）。
 */
private const val NAV_FADE_MS = NAV_EXIT_MS






/**
 * The app shell.
 *
 * Navigation lives in the left drawer, and the drawer is mostly a conversation list:
 * switching conversations is what a chat app is for, so it gets the top of the panel
 * and everything that is not a conversation sits at the bottom, out of the way. The
 * handle is drawn by [com.suixing.agent.ui.components.SuixingTopBar], which is why the
 * shell only has to publish [LocalOpenSidebar] once.
 */
@Composable
fun SuixingApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    // 当前**覆盖层**的路由；"empty" 是栈底那层透明的占位 —— 也就是"chat 露着"。
    val route = backStackEntry?.destination?.route?.substringBefore('?') ?: "empty"
    // 覆盖层是否盖着（0＝没有，1＝有）：推进/退回时下面那层跟着让位用。
    // 与 NavHost 的位移同一时长、同一曲线，两层的动作才是**一件事**。
    // **不在组合期解包**（`by` 会立刻读一次，于是推进/退回的 300ms 里外壳每帧重组一遍，
    // 抽屉内容、聊天页都跟着重跑）。保留 State 引用，值在下面 `graphicsLayer` 里（绘制期）读。
    val baseShiftState = animateFloatAsState(
        targetValue = if (route == "empty") 0f else 1f,
        animationSpec = tween(NAV_PUSH_MS, easing = FastOutSlowInEasing),
        label = "baseShift",
    )

    // Owned here rather than inside the chat screen: the drawer lists and switches the
    // very conversations the screen is showing.
    val chat: ChatViewModel = viewModel()
    // **会话列表不在外壳根部订阅**（用户：侧边栏卡）。
    // 原来这一行订在这里，而它唯一的使用者是抽屉 ⇒ 会话列表每变一次（流式期间每次存盘
    // 都会刷新 updatedAt/messageCount，列表就换新实例）整个外壳就重组一遍 ——
    // 包括正在滑动的抽屉那一层，于是抽屉一开就卡。
    // 现在订阅下沉到 `drawerContent` 作用域里，外壳不再跟着变。
    // The one id alone, not the whole chat state: the shell must not recompose once per
    // streamed token just to keep one row highlighted.
    val activeConversationId by remember(chat) {
        chat.state.map { it.conversationId }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = "")
    // Which conversations have a run in flight, straight off the engine's own map: the
    // drawer's dots follow it without the view model narrating anyone else's run.
    val runningIds by remember {
        Core.engine.runsFlow.map { it.keys }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = emptySet<String>())
    // Same reason as the ids above: the picker needs one field, not the whole transcript.
    val assistantId by remember(chat) {
        chat.state.map { it.assistantId }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = "")

    // 抽屉本体交给 M3 的 ModalNavigationDrawer：手势（边缘拖动跟手）、遮罩、面板位移全归库。
    // 自绘那版只能整段出现/消失——边缘拖不动，用户点名的就是这条。
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    // 抽屉里那张半屏助手表：和聊天页那张是同一个 AssistantSheet，只是宿主换成了外壳。
    // remember 而不是 rememberSaveable（与聊天页那张表一致）：进程重启后它不该自己弹回来。
    var shellAssistants by remember { mutableStateOf(false) }
    // 抽屉路线的助手编辑器（主窗口全屏覆盖层，结构路 0.8.105）。
    var editorAssistantId by remember { mutableStateOf<String?>(null) }
    // 首次启动引导：盖在应用上面的一层（不是应用里的一页，所以不占路由、不进返回栈）。
    // 打开它的理由只有两个：第一次启动（设置里那个「看过引导」的标记还没有），
    // 以及从「设置 → 新手引导」自己再进来一次。
    var showOnboarding by remember { mutableStateOf(false) }
    // **只有首启那一次**才算「首启」：从设置里「重新看一遍引导」时不能把常驻的 chat 拆了
    // （那正是「返回像重新加载」的来源）。
    var onboardingIsFirstRun by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // 读**落盘后**的那一份，不是 settings flow 的初始值——初始值里 onboardingSeen 是
        // false，拿它判断会在第一帧就弹出来闪一下。
        if (!Core.settings.current().onboardingSeen) {
            onboardingIsFirstRun = true
            showOnboarding = true
        }
    }
    // **整份 AppSettings 不在外壳根部订阅**（性能审计 #54 5.48）：那一行让任何一个设置变化
    // （切 provider、改窗口大小、换个头像…）都把整个外壳连同下面的页面、抽屉、覆盖层重跑一遍，
    // 和上面「会话列表不在外壳根部订阅」是同一个毛病。外壳自己只用到 `floatingConsole`，
    // 所以这里按值订阅这一个字段；另外三处要整份 settings 的地方（助手设置页、抽屉那张半屏表、
    // 助手编辑器覆盖层）都下沉成各自的子组合（见文件末尾三个 private composable），
    // 与 `DrawerBackHandler` 同一个思路。
    val consoleOn by remember {
        Core.settings.flow.map { it.floatingConsole }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Returning from the system's overlay page is the only moment the grant can have
    // changed, so the switch is decided here rather than on the way out.
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (FloatConsoleService.canDrawOverlays(context)) {
            scope.launch { Core.settings.update { it.copy(floatingConsole = true) } }
            FloatConsoleService.start(context)
        }
    }

    // Top-level destinations replace each other: the drawer is a switch, not a stack.
    //
    // 点抽屉里的一行：抽屉**瞬间消失**，不播滑出（用户原话：「GPT 的抽屉根本就不动」——他要的是
    // "它不在那儿了"，不是"它滑走了"），页面上只发生一次淡入（见文件顶部那组 NAV_FADE_*）。
    // snapTo 而不是 close()：close 会播库那条 `FastEffects`（约 130ms）的左滑；UNDISPATCHED 让这次状态写入不排到
    // 下一个主线程消息，与导航同一帧落地，否则会先看见抽屉还盖着、页面已经在后面淡入。
    // 点**当前页那一行**同样只收抽屉、不导航：`popUpTo` 会把这一页弹掉再建一份，等于整页重建。
    //
    // 同时记下"从哪一页走的、去了哪一页"：用户要**保留离开时的状态**——从抽屉点走的那一页，
    // 返回落到它时抽屉要**还是开着的**（见下面的结算块）。用户对这件事改口过很多次，最终两句
    // 合起来才是完整规格：点行时抽屉不动地消失（这句）、返回时它又在那儿（那句）。
    // 抽屉里点走的那一页：标记只在「还停在这一页」的期间有效。没有它，一个陈旧的名字会留到
    // 下一次无关的导航上——从覆盖层页开抽屉去别页、返回落到 chat 之后，再从聊天页顶栏点设置，
    // route 恰好等于那个陈旧名字，返回动画的判据就会错判。
    val drawerOrigin = remember { arrayOfNulls<String>(1) }
    // 抽屉是否正在播动画：状态对象本身下发（不在这里解包，避免外壳重组），
    // 读它的是 ChatScreen 的填充协程 —— 见 `LocalDrawerBusy`。
    val drawerBusyState = remember { mutableStateOf(false) }

    val routeNow by androidx.compose.runtime.rememberUpdatedState(route)
    val open: (String) -> Unit = remember(navController) { { target ->
        // **不关抽屉**（用户：「不用关掉侧边栏」「新页不应该在页面都最上面移动的吗？」）：
        // 覆盖层现在画在 ModalNavigationDrawer 之上（见下面 Scaffold/Box 那段），所以新页是
        // 压着抽屉滑进来的，抽屉保持打开、原样留在下面 —— 返回时揭开的就是它，状态天然保留。
        if (target != routeNow) {
            // drawerOrigin[0] 只用来决定"返回时要不要播动画"（见 popExitTransition）：从抽屉点进的
            // 那一页，返回要瞬时揭开出发页。
            drawerOrigin[0] = target
            navController.navigate(target) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    } }
    // A row inside the settings page pushes, so back returns to the list it came from.
    val push: (String) -> Unit = { target -> navController.navigate(target) }
    // chat 常驻（见下面 NavHost 那段），所以"回聊天"不是导航到某个目的地，而是**把覆盖层收掉**：
    // pop 到透明的 "empty" 就等于"什么都没有在盖着 chat"。
    val showChat: () -> Unit = { navController.popBackStack("empty", inclusive = false) }

    // 用户自己收起抽屉（遮罩、返回键、左缘拖动）：这是用户自己的动作，走库的动画。同时清掉
    // 出发页标记——用户手动把抽屉收了，返回时就不该再自己弹出来（"状态"是他刚亲手关掉的这个）。
    val closeDrawer: () -> Unit = {
        // 收起用 [DRAWER_CLOSE_SPEC] = tween(210, FastOutLinearIn)。点遮罩不在这里（见下）
        // （自带遮罩层调用本函数），四条路径手感一致。
        scope.launch { drawerState.animateTo(DrawerValue.Closed, DRAWER_CLOSE_SPEC) }
        drawerOrigin[0] = null
    }
    // 点会话 / 新对话：与点页面行不同——那是"换我在读的那一份内容"，覆盖层收掉、抽屉也收起来，
    // 用户直接落在会话里（ChatGPT 的抽屉在这里同样会关）。这里一并清掉出发页标记。
    val chatFromDrawer: () -> Unit = {
        drawerOrigin[0] = null
        // **与 `closeDrawer()`（助手 / 遮罩 / 返回键）逐字相同**：只启动同一条
        // `animateTo(Closed, DRAWER_CLOSE_SPEC)` —— 同一帧开始、同一时长、同一曲线。
        scope.launch { drawerState.animateTo(DrawerValue.Closed, DRAWER_CLOSE_SPEC) }
        showChat()
    }
    /**
     * 点抽屉里的一行会话：**切换立刻发生，收起动画并行播完**（方案 C）。
     *
     * 历史沿革（两次反转，都记着，免得以后又改回去）：
     *  · 最初是同一帧做（`openConversation` + 收起动画），换会话要整页换状态
     *    （分组、解析、冷组合），主线程被占住、动画帧被吃掉 ⇒ 收起看着和别处不一样；
     *  · 于是改成**串行**（用户原话「先有关闭侧边栏，再会话」）：动画播完再切。
     *    但动画时长直接变成切换延迟的下限（当时 160ms），用户觉得「点了要等一会」；
     *  · 现在改回**并行**（用户选 C）：`showChat()` / `openConversation()` 立刻执行，
     *    收起动画自己跑。
     *
     * 实测依据（§73，文件探针，8 轮）：`tap -> state_set` 仅 2~3ms、
     * `state_set -> content_ready` 12~37ms（冷读大会话也一样，只加载尾部窗口 24 行）。
     * 所以"切换慢"不是数据加载，而是 **动画期间的点击死区**（见 `DRAWER_CLOSE_SPEC` 注释）。
     *
     * `drawerBusyState` 仍跟动画走：它管的是聊天页填充协程，不该在抽屉还在动时抢帧。
     */
    // **连点保护**：只有最后一次点击算数（第一次的协程会被第二次的动画取消，令牌随之失效）。
    val drawerSwitchToken = remember { intArrayOf(0) }
    val switchFromDrawer: (Conversation) -> Unit = { conversation ->
        drawerOrigin[0] = null
        val token = ++drawerSwitchToken[0]
        drawerBusyState.value = true
        // **方案 C：切换立刻发生，收起动画并行播完**（用户选 C）。
        // 原来是串行 —— 先 `animateTo(Closed)` 播完再切，于是动画时长直接变成切换延迟的下限；
        // `DRAWER_CLOSE_SPEC` 是 `tween(210)`。现在切换不再被动画挡住（实测见上面的 KDoc）。
        // `drawerBusyState` 仍跟动画走（它管的是聊天页的填充协程，不该在抽屉还在动时抢帧）。
        showChat()
        chat.openConversation(conversation)
        scope.launch {
            try {
                drawerState.animateTo(DrawerValue.Closed, DRAWER_CLOSE_SPEC)
            } finally {
                // 被后一次点击取消时不要清忙：那一次已经重新置 true 并在跑自己的动画。
                if (drawerSwitchToken[0] == token) drawerBusyState.value = false
            }
        }
    }
    // 返回上一页：顶栏按钮与系统返回都走这里。
    val goBack: () -> Unit = { navController.popBackStack() }

    // 结算：离开抽屉点走的那一页时清标记。抽屉不再需要"摆回开着"——它从头到尾没关过
    // （见 open()），返回时揭开的就是原来那份。
    if (drawerOrigin[0] != null && route != drawerOrigin[0]) {
        SideEffect { drawerOrigin[0] = null }
    }

    // The keyboard is a layout change, not a scroll, and the platform already animates the
    // IME inset frame by frame. The shell lays itself out by a *clamped* copy of that inset:
    // the platform's own animation ends with a rebound of a few pixels the other way
    // (measured on device: the composer overshot ~6px past its resting place and snapped
    // back, which on a 120Hz panel reads as a hitch at the end of every close), and an
    // opposite-direction change inside the clamp is that rebound and is dropped; real
    // transition frames are 30-80px and pass untouched.
    val rootDensity = LocalDensity.current
    // 保留**状态对象本身**（而不是用 `by` 解包）：下发的是引用，读点自己在布局期取 .value，
    // 这样键盘动画的每一帧不会让整棵树重组（见 LocalHeldImePx 的说明）。
    // 长按/选择期间冻结 clamp（用户：「抬起时对话框与输入法的高度有时变得很大」）：抬手那一刻
    // 选择手柄/菜单出现，这台 ROM 把它的高度报进 IME inset，clamp 一接受、输入框底垫就暴涨。
    // 守护窗（按下到抬手后 400ms）里不接受任何 inset 变化；窗口外照常。
    var selectionActive by remember { mutableStateOf(false) }
    // 在消费节点的布局/绘制阶段读取原始 inset 和系统目标，不订阅外壳组合。
    // 长按期间冻结，窗口外按像素单调跟随目标，不量化，也不等定时器解锁。
    val imeInsets = WindowInsets.ime
    val imeTarget = WindowInsets.imeAnimationTarget
    val imeFilter = remember { ImeInsetFilter() }
    val heldImeState = remember(rootDensity, imeInsets, imeTarget) {
        derivedStateOf {
            // Read the system inset in the consumer's layout pass. Relaying it through a
            // collector and another state could leave the layout one frame behind IME.
            imeFilter.update(
                imeInsets.getBottom(rootDensity),
                imeTarget.getBottom(rootDensity),
                selectionActive,
            )
        }
    }
    val openSidebar: () -> Unit = remember(scope, drawerState) { {
        if (routeNow == "empty") scope.launch {
            // 打开用 [DRAWER_OPEN_SPEC]：**同样 210ms**，但曲线是减速的，末端缓停不"生硬"
            // （用户：「那个汉堡键动画改回去有点太生硬了」）。死区时长不变。
            drawerState.animateTo(DrawerValue.Open, DRAWER_OPEN_SPEC)
        }
    } }
    val setSelectionActive: (Boolean) -> Unit = remember { { selectionActive = it } }
    CompositionLocalProvider(
        LocalDrawerBusy provides drawerBusyState,
        // 用户自己拉开抽屉（聊天页那个汉堡键，也是全仓唯一的正常开抽屉路径）：演库自己那
        // 一段滑入——用户反对的从来是「返回时把它重放一遍」，不是手动打开时的动画。
        // 只在会话页可用（抽屉只属于会话页，见 ModalNavigationDrawer）：子页没有汉堡入口，
        // 这一层判断是防止以后有人把汉堡画到子页上时它还能把抽屉拉出来。
        LocalOpenSidebar provides openSidebar,
        // The clamped keyboard height, for children that have to subtract it from an inset of
        // their own (the composer's navigation-bar padding: while the keyboard covers the
        // gesture bar that padding is dead space, which is the band the user photographed).
        LocalHeldImePx provides heldImeState,
        LocalSetSelectionActive provides setSelectionActive,
    ) {
        // 用户明确要求保留这一段（2026-10-11：先被自审删掉，用户看到键盘后面那块颜色变了，要求回退）。
        // 把窗口底色刷成主题 surface：键盘圆角/键盘后面那条缝露的就是这个颜色；删掉之后窗口是透明的，
        // 缝里露的是壁纸 —— 用户不要那个效果。
        val activity = LocalContext.current as? Activity
        val surfaceArgb = MaterialTheme.colorScheme.surface.toArgb()
        LaunchedEffect(surfaceArgb) {
            activity?.window?.setBackgroundDrawable(ColorDrawable(surfaceArgb))
        }
        // 底色只画到键盘上沿，键盘后面（含它的圆角）留白给壁纸 —— 这是其它 App 的做法；
        // 铺满整窗会让渐变的结束色出现在圆角里，和画布顶边对不上。渐变终点钉在画布底边
        // （窗高 − 键盘高），两段渐变才在交界处同色。
        // 渐变终点（画布底边 = 窗高 − 键盘高）**在绘制期算**：键盘高度每帧都在变，
        // 在组合期读它等于让外壳逐帧重组（见上面 `snapshotFlow` 的说明）。
        // `drawBehind` 的 lambda 是绘制阶段才读快照的：键盘动画期间这里既不重组也不重布局。
        val canvasTopColor = MaterialTheme.colorScheme.surface
        val canvasTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
        val screenHeightPx = with(rootDensity) {
            LocalConfiguration.current.screenHeightDp.dp.toPx()
        }
        val darkShell = LocalDarkTheme.current
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (darkShell) {
                        Modifier.background(SolidColor(canvasTopColor))
                    } else {
                        Modifier.drawBehind {
                            val endY = (screenHeightPx - heldImeState.value).coerceAtLeast(1f)
                            drawRect(
                                Brush.verticalGradient(
                                    colors = listOf(canvasTopColor, canvasTint),
                                    startY = 0f,
                                    endY = endY,
                                ),
                            )
                        }
                    },
                ),
        ) {
            // **抽屉在下、覆盖层在上**（用户：「新页不应该在页面都最上面移动的吗？」）：
            // 覆盖层的 NavHost 必须画在 ModalNavigationDrawer 之上，否则从抽屉点进新页时，
            // 页面只能在抽屉面板**下面**滑入 —— 用户看到的还是抽屉。现在抽屉不再关（见 open()），
            // 新页从右侧滑进来时压着它，滑完就把抽屉盖住；返回时揭开的就是那份仍开着的抽屉。
            Scaffold(
                // **外壳不再按 IME 上缩**（用户：「侧边栏还是被顶起来」）：
                // 这里原来是 `Modifier.padding(bottom = heldImePx)`，它把整个外壳 ——
                // 包括 ModalNavigationDrawer 的抽屉面板 —— 一起顶上去，抽屉就被键盘挤短了。
                // 现在键盘让位只作用在**聊天内容**上（见下面 ChatScreen 外层），
                // 抽屉保持整屏高度。
                // **底色不在这里铺**（见下面 Box 里那条背景）：0.8.229 把外壳改成整屏之后，
                // 这里的 surface 会把键盘后面那条缝也涂上，键盘圆角里就露出一个比画布更白的色块。
                containerColor = Color.Transparent,
                // Screens own their top inset (their headers paint behind the status
                // bar) and the composer owns the bottom one, so the scaffold reserves
                // nothing for itself.
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
            ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
            // **底色只铺到键盘顶边为止**（0.8.229 的回归）：外壳改成整屏之后，Scaffold 的
            // containerColor 会把键盘后面那条缝一起涂上，键盘圆角里于是露出一个比画布更白的
            // 色块（用户：「输入法的圆角 色块怎么又回来了」）。这里把 surface 铺到画布底边
            // （= 屏高 − IME）为止，下面那条缝留给根节点的渐变 —— 和 0.8.229 之前
            // （外壳被 IME 顶起、那条缝根本不画）逐像素一致；键盘收起时 heldImePx=0，铺满全屏。
            val surfaceColor = MaterialTheme.colorScheme.surface
            Box(
                Modifier
                    .matchParentSize()
                    // **绘制期读键盘高度**：只在 draw 阶段取值 ⇒ 键盘动画期间既不重组也不重布局，
                    // 但绘制范围仍按"画布底边 = 屏高 − IME"裁剪，键盘圆角里那圈色块不会回来。
                    .drawBehind {
                        val h = (size.height - heldImeState.value).coerceAtLeast(0f)
                        drawRect(color = surfaceColor, size = androidx.compose.ui.geometry.Size(size.width, h))
                    },
            )
            // 推进时下面那层（抽屉 + 聊天）跟着往左让 10%：**两层一起动**才读成"推上来"，
            // 只让新页动会像贴上去。只用位移、不做透明度 —— 淡化会露出下面一层，就是残影。
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = -size.width * 0.10f * baseShiftState.value },
            ) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                // 边缘拖动跟手（拖到一半松手会回弹）、遮罩与面板位移全归库——用户点名的「不要自绘」。
                // 但**只在会话页开着**：子页（设置主页 + 六个设置子页 + 会话管理 + 权限/工具/
                // 文件/任务/定时任务/用量/关于/扩展/画像/助手）都是 push 上去的独立页面，各有返回
                // 箭头，从屏幕左缘还能拉出抽屉是错的——用户原话「设置页到底是不是单独页？为什么在
                // 设置页里面还能侧滑出侧边栏？」。只有 chat 那一层（route == "empty"）为 true。
                gesturesEnabled = route == "empty",
                // 遮罩浓度照搬自绘那版：M3 默认 scrim 是 32%，这块面板从第一天起是 45% 的黑。
                // 遮罩的**视觉**仍归库（它画在面板下面：圆角、层次、浓度都对）。
                // 遮罩的点击关闭**已交回库**：`DRAWER_CLOSE_SPEC` = `tween(210, FastOutLinearIn)`，
                // 与库的 `FastEffects`（`spring(1.0, 3800)`，解析收敛 ≈152ms）不是同一条 ——
                // 差异来自库内部 token，App 改不了（见 `Drawer.kt` 的说明）。
                // 用户已要求删掉那层「接管遮罩点击/左滑」的透明层，所以现在遮罩只保留库自己的点击关闭。
                scrimColor = Color.Black.copy(alpha = 0.45f),
                drawerContent = {
                    val drawerConversations by chat.conversations.collectAsStateWithLifecycle()
                    Drawer(
                        open = drawerState.isOpen,
                        route = route,
                        conversations = drawerConversations,
                        activeId = activeConversationId,
                        runningId = runningIds,
                        onNew = {
                            chat.newConversation()
                            chatFromDrawer()
                        },
                        onOpenConversation = { conversation ->
                            // 收起动画与助手/遮罩那条完全一致，播完再换会话（见 `switchFromDrawer`）。
                            switchFromDrawer(conversation)
                        },
                        onDeleteConversation = { chat.deleteConversation(it) },
                        // 改名必须走 ViewModel：它手里有一份标题拷贝，每轮结束会写回盘（见
                        // ChatViewModel.renameConversation）。置顶是纯标签，直接落仓库就够。
                        onRenameConversation = { conversation, title ->
                            chat.renameConversation(conversation.id, title)
                        },
                        onTogglePin = { conversation ->
                            scope.launch {
                                Core.chats.setPinned(conversation.id, !conversation.pinned)
                            }
                        },
                        // 归档 = 纯标签（不动 updatedAt，也不动列表顺序）：抽屉不再显示它，
                        // 已归档的那份在「设置 → 会话管理」里翻出来取消归档。
                        onToggleArchive = { conversation ->
                            scope.launch {
                                Core.chats.setArchived(conversation.id, !conversation.archived)
                            }
                        },
                        onNavigate = open,
                        onAssistants = {
                            // 半屏表挂在外壳上：抽屉先收，表从底下上来，不必再走一次页面切换。
                            closeDrawer()
                            shellAssistants = true
                        },
                        consoleOn = consoleOn,
                        onToggleConsole = {
                            if (consoleOn) {
                                // Written first, then stopped: the switch is what the run checks,
                                // and a console left running after the switch went off would
                                // linger until the run ended on its own.
                                scope.launch { Core.settings.update { it.copy(floatingConsole = false) } }
                                FloatConsoleService.stop(context)
                            } else if (FloatConsoleService.canDrawOverlays(context)) {
                                scope.launch { Core.settings.update { it.copy(floatingConsole = true) } }
                                FloatConsoleService.start(context)
                            } else {
                                // Not persisted on the way out: without the grant the switch
                                // would read ON while no ball could ever appear.
                                overlayPermissionLauncher.launch(
                                    FloatConsoleService.overlayPermissionIntent(context),
                                )
                            }
                        },
                    )
                },
            ) {
                // chat 常驻：NavHost 在离开一个目的地之后会把它**销毁**，pop 回来时是从零
                // 重新组合的（长列表重建、markdown 重解析）——那正是用户说的「返回像重新加载」，
                // 三处 rememberSaveable 只救了状态、救不了重建。所以 chat 移出 NavHost、永远
                // 留在组合里，其余页面改成**盖在它上面的覆盖层**：进设置是往覆盖层 push，
                // 返回是把覆盖层 pop 掉 ⇒ 揭开的就是原来那份 chat，零重建、零重载。
                // 键盘让位只作用在聊天内容上（抽屉不再被顶起，见上面 Scaffold 的说明）。
                // **不要在这里按 IME 缩高整棵聊天树**（IME 起落优化第 1 步）：
                // `imePaddingBottom` 每帧改 Box 的高度约束 ⇒ Scaffold、Composer、整份列表
                // 连同它们的布局修饰符链一起被重新测量（不重组 ≠ 不重测）。
                // 现在整页保持满高，IME 只在聊天内部被消费一次：
                // 输入框按 IME 做放置偏移，列表/空状态在**布局期**把键盘那一截让出来。
                Box(Modifier.fillMaxSize()) {
                    // **首启的引导还没走完时，先别组合 chat**（用户：「第一次出现引导页感觉卡卡的」）。
                    // 冷启动时 chat 要做 turnPage、receipts/transcript 统计、每条消息的 Markdown
                    // 渲染（ChatScreen.kt:465/512/560/621、ChatBlocks.kt:895/1519），
                    // 与引导层 200ms 的入场动画抢同一条主线程 ⇒ 首帧掉帧。引导是全屏覆盖层，
                    // 底下的 chat 这时看不见 ⇒ 不组合它没有代价。
                    //
                    // **只在首启时这样**：设置里「重新看一遍引导」时 chat 已经在组合里，
                    // 盖一层引导不能把常驻的 chat 拆了。
                    if (!(showOnboarding && onboardingIsFirstRun)) {
                        val chatVisible = route == "empty" && !showOnboarding && editorAssistantId == null
                        val chatLifecycle = com.suixing.agent.ui.chat.rememberChatUiLifecycleOwner(chatVisible)
                        CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides chatLifecycle) {
                            ObserveConversationRead(chat, visible = chatVisible)
                            ChatScreen(viewModel = chat, onOpenSettings = remember(open) { { open("settings") } })
                        }
                    }
                }
            }
            }
            // **遮罩交互全部交回库**（用户：「A 删掉」）。
            // 原来这里有一层透明的 `DrawerDismissLayer` 接管遮罩点击、后来又加了左滑关闭。
            // 它的存在理由本是「把遮罩的关闭动效改成和别处一样」——但**现在仍不一致**：
            // 库的 scrim 点击走 `close()` ⇒ `closeDrawerMotionSpec` = `FastEffects`
            // （`spring(1.0, 3800)`，解析收敛 ≈152ms），与我们这条 `tween(210)` 不是同一条。
            // 要连遮罩也统一，只能把这层「只接管点击」的东西加回来 —— 用户已要求删掉，暂不加。
            // 而遮罩上的左滑又不跟手（`DrawerState` 没有公开的 `dragTo`），收益不抵它带来的
            // 手势冲突风险，所以整层删掉。现在遮罩：点击关闭（走库的 `FastEffects`，约 130ms）；
            // 面板：拖动跟手（库）。**遮罩的关闭曲线与其余路径仍不同**——要统一只能把这层加回来。
            NavHost(
                            navController = navController,
                            // 覆盖层的栈底：什么都不画（透明），所以"当前是 empty"就等于"chat 露着"。
                            startDestination = "empty",
                            modifier = Modifier
                                .fillMaxSize()
                                // **这里不铺底色**（原来有一层 `.background(if (route == "empty") …)`，已删）：
                                // 用户逐帧截图显示，返回的**第一帧右侧是一块空白** —— pop 开始时 route 还是
                                // 旧页那条 ⇒ 这层底色仍在，而旧页已经开始淡出 ⇒ 露出来的就是底色本身。
                                // 每个覆盖层页面自己就有不透明底（各页 `Scaffold(containerColor = background)`），
                                // 外面这层多余；删掉后返回第一帧露出的直接是**聊天页** ✔。
                                // 代价（认了）：push 时新页首帧若没画满，可能露出一帧聊天页 —— 比「每次返回
                                // 都空白 200ms」轻得多。**不要再加回「转场期间保持不透明」那套**（`opaque`
                                // + delay 会把窗口拉到 200ms，空白更明显）。
                                // 覆盖层盖住了 chat，但页面自己的根只画底色、不消费触摸：点在页面
                                // 空白处（不是按钮/列表的地方）会"穿透"到下面仍在组合里的聊天页，
                                // 打到输入框、消息行、麦克风上。这一层只在真有覆盖层时才加（route
                                // 是 "empty" 时一个 pointerInput 节点都不加，否则 chat 自己也收不到
                                // 触摸），它把落在空白处的**点**吃掉。
                                // 只吃"点"：拖动不消费，左缘拖出抽屉仍归 ModalNavigationDrawer；
                                // 而它一旦被命中，下面的 chat 就整个不在命中路径里，滚动也透不过去。
                                .then(
                                    if (route == "empty") {
                                        Modifier
                                    } else {
                                        Modifier.pointerInput(route) { detectTapGestures { } }
                                    },
                                ),
                            // 进页**不播动画**（按最小、最保守的形态落地）：
                            // · 抽屉路线（点抽屉进页面）= 0.8.91 原样，从右侧滑入 —— 用户认可的版本，
                            // 进页 = **淡入、无位移**（用户逐帧看录屏：点抽屉一行时要「抽屉滑出 + 页面
                            // 淡进」同时进行，加位移会变成两段动作）。旧页当帧让位，露出来的先是那层
                            // 不透明底（route != "empty" ⇒ 底色在），所以淡入期间不会透出聊天页。
                            // **推入式**（用户：「想要旧页跟着一起动（推入式）」）：新页从右边
                            // 整幅滑进来，同时下面那一页往左退 1/4 屏 —— 两页一起动，才读成"推进"。
                            // 淡入淡出不用：任何 alpha 过渡都会有"两页同时可见"的中间帧（残影，
                            // 用户明确否过）。位移没有中间态：新页一出现就是满的，只是还没到位。
                            enterTransition = {
                                slideInHorizontally(
                                    animationSpec = tween(
                                        durationMillis = NAV_PUSH_MS,
                                        easing = FastOutSlowInEasing,
                                    ),
                                ) { fullWidth -> fullWidth }
                            },
                            exitTransition = {
                                slideOutHorizontally(
                                    animationSpec = tween(
                                        durationMillis = NAV_PUSH_MS,
                                        easing = FastOutSlowInEasing,
                                    ),
                                ) { fullWidth -> -fullWidth / 4 }
                            },
                            // 返回是同一件事倒过来：退出的页滑回右边，下面那页从左侧 1/4 处归位。
                            popEnterTransition = {
                                slideInHorizontally(
                                    animationSpec = tween(
                                        durationMillis = NAV_PUSH_MS,
                                        easing = FastOutSlowInEasing,
                                    ),
                                ) { fullWidth -> -fullWidth / 4 }
                            },
                            popExitTransition = {
                                // 用户再三强调的规则（0.8.98 立过、0.8.106 统一动画时被我一并盖掉，
                                // 这轮恢复）：**从侧边栏点进的那一页，返回不要动画**（瞬时揭开出发页）；
                                // 设置里的子页（push 进的）返回走推入的反向。判据用 drawerOrigin[0]：
                                // open() 置位、结算块在 route 离开它时清掉，所以 pop 那一刻它仍指向被退的页。
                                // 这条 lambda 是接收者形式（AnimatedContentTransitionScope），被退的
                                // 目的地在 initialState 上，不是 lambda 参数。
                                if (drawerOrigin[0] != null &&
                                    initialState.destination.route == drawerOrigin[0]
                                ) {
                                    ExitTransition.None
                                } else {
                                    slideOutHorizontally(
                                        animationSpec = tween(
                                            durationMillis = NAV_PUSH_MS,
                                            easing = FastOutSlowInEasing,
                                        ),
                                    ) { fullWidth -> fullWidth }
                                }
                            },
                        ) {
                            // 栈底那一层：透明，什么都不画（chat 就在它下面）。
                                composable("empty") { }
                                composable("files") { FilesScreen(onBack = goBack) }
                            composable("tasks") { TasksScreen(onBack = goBack) }
                            // 定时任务是「到点自己跑」的那一份，与 agent 的 todo 看板（"tasks"）是两件
                            // 事：抽屉那行说的「任务」一直指的是后者，用户以为是摆设，才有了这一页。
                            composable("schedules") { SchedulesScreen(onBack = goBack) }
                            composable("settings") {
                                SettingsScreen(
                                    onOpen = push,
                                    onBack = goBack,
                                    // 「重新看一遍引导」：走的是同一个覆盖层，不是另一条路由。
                                    onOpenOnboarding = { showOnboarding = true },
                                )
                            }
                            // 设置里的七个子页各是一条真路由，而不是设置页内部的 when 分支：同一
                            // 个目的地里换内容，NavHost 不参与，于是进出完全没有转场——用户说的
                            // 「就是复用的，根本没有切换动画」正是这个。走 push 而不是 open：抽屉
                            // 是同级切换，设置子页是往下一层，返回要回到那份列表。
                            listOf(
                                "settings/model",
                                "settings/conversation",
                                "settings/appearance",
                                "settings/network",
                                "settings/data",
                                // 会话管理：数据页那行「会话管理」指过来（打开/导出/删除聊天记录）。
                                "settings/chats",
                                "settings/usage",
                                // 性能页：设置 →「数据与关于」里那行「性能」指过来。
                                "settings/perf",
                            ).forEach { route ->
                                composable(route) {
                                    SettingsPageRoute(
                                        route = route,
                                        onOpen = push,
                                        onBack = goBack,
                                        // 「打开」一个会话 = 切到它 + 收掉覆盖层，与抽屉里点会话
                                        // 完全同一条路（chat 常驻，所以这不是一次导航）。
                                        onOpenConversation = { id ->
                                            // 回调里读当前值就够（不在组合期订阅）：外壳根部
                                            // 订阅会让整个外壳跟着会话列表每次变化重组。
                                            chat.conversations.value.firstOrNull { it.id == id }
                                                ?.let { chat.openConversation(it) }
                                            showChat()
                                        },
                                    )
                                }
                            }
                            composable("capability") { CapabilityScreen(onBack = goBack) }
                            composable("tools") { ToolsScreen(onBack = goBack) }
                            composable("about") { AboutScreen(onBack = goBack) }
                            // 记忆：路由名仍是 extension，页面只剩记忆（MCP 与技能搬去了工具页）。
                            composable("extension") {
                                ExtensionScreen(onBack = goBack)
                            }
                            // 用户画像：一份纯文本自述（filesDir/profile.md），随每次请求注入提示词。
                            composable("profile") {
                                ProfileScreen(onBack = goBack)
                            }
                            // 助手设置：一整页，不是半屏的表。抽屉那一行和设置页那一行都推这一页，
                            // 于是「编辑助手」只有一条路、一个实现；半屏的表只剩聊天页那个随手
                            // 切换助手的窗口。
                            composable("assistants") {
                                AssistantsRoute(
                                    activeId = assistantId,
                                    onUse = { chat.useAssistant(it) },
                                    onSetDefault = { chat.setDefaultAssistant(it) },
                                    onSave = { chat.saveAssistant(it) },
                                    onDelete = { chat.deleteAssistant(it) },
                                    onBack = goBack,
                                )
                            }
                        }
                        // 抽屉开着时 BACK 先关抽屉：ModalNavigationDrawer 只接管预测性返回的手势，
                        // 普通 BACK 会交给 Activity，不收这一句就会退出应用。位置必须在 NavHost
                        // **之后**：OnBackPressedDispatcher 是后注册者优先（LIFO），注册在 NavHost
                        // 之前时，覆盖层页上按 BACK 会先被 NavHost 的回调 pop 掉、抽屉反而留着——
                        // 而这里的意图是"抽屉先关"。助手那张半屏表自己吞 BACK，审批卡是 dialog 也吞，
                        // 所以只剩抽屉这一种。chat 上（route == "empty"）NavHost 的回调不消费，
                        // BACK 仍落到 Activity ⇒ 退出应用，不会多出一层空页。
                        // **只在会话页**接管 BACK：抽屉现在不随导航关闭（见 open()），覆盖层页
                        // 盖在它上面时抽屉可能还开着 —— 那种情况下 BACK 应该先退页，不是去关一个
                        // 看不见的抽屉。route != "empty" 时这里让位，交给 NavHost 自己的返回。
                        // **不要把 `isOpen` 读进外壳的组合作用域**（用户实测：抽屉动画
                        // **播完那一帧**点遮罩要等一会才生效，动画期间反而立刻生效）。
                        // `isOpen` 只在动画结束时翻转（`settledValue` 由 `anchoredDrag` 收尾写），
                        // 在外壳函数体里读它 ⇒ 那一帧**整个 SuixingApp 重组**，其中包括
                        // `ChatScreen(...)`（参数里有每次重组新建的 lambda，跳不过去）⇒
                        // 聊天页整段重跑（分组、轮号、token 估算、行顶数组重建），主线程被占住，
                        // 这期间的点击只能排队。挪进 `DrawerBackHandler` 后，重组被限制在
                        // 那个只含 BackHandler 的小作用域里，外壳与聊天页不再跟着动。
                        DrawerBackHandler(drawerState, route, closeDrawer)
                    }
                }
            }
        }

    // Rendered outside the navigation host so a prompt cannot be missed while the
    // user is on another screen — the agent is blocked until it is answered.
    PendingApprovals(chat.conversations)

    // 首次启动引导：与助手编辑器同一个位置——盖在导航宿主与抽屉之上的一切。
    // AnimatedVisibility 只在 visible 为真（或退场那 200ms）时组合内容，所以引导页
    // 不显示的时候它一行代码都不跑。
    FullScreenOverlay(visible = showOnboarding) {
        OnboardingHost(onClose = { showOnboarding = false })
    }

    // 抽屉里那张半屏表。挂在外壳、不在任何一页里：抽屉是外壳自己的面板，表跟着它走，
    // 与聊天页那张共用同一个 AssistantSheet（回调也同一套）。
    if (shellAssistants) {
        ShellAssistantSheet(
            activeId = assistantId,
            onUse = { chat.useAssistant(it) },
            onSetDefault = { chat.setDefaultAssistant(it) },
            // 编辑/新建：收掉半屏表，编辑器画在主窗口的全屏覆盖层（结构路，0.8.105）。
            onOpenEditor = { id ->
                shellAssistants = false
                editorAssistantId = id
            },
            onDismiss = { shellAssistants = false },
        )
    }

    // 助手编辑器：主窗口里的全屏一层，盖住抽屉与半屏表之上的一切。Dialog 窗口在这台设备上
    // 的状态栏行为修了四轮都不对，所以这一页不再有自己的窗口；BACK 由它自己的 BackHandler 收。
    // 进出场与导航页同一套动画（FullScreenOverlay），退场那 200ms 里内容靠 lastEditorId 留住。
    var lastEditorId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(editorAssistantId) {
        if (editorAssistantId != null) lastEditorId = editorAssistantId
    }
    FullScreenOverlay(visible = editorAssistantId != null) {
        val id = editorAssistantId ?: lastEditorId
        if (id != null) {
            AssistantEditorHost(
                id = id,
                backEnabled = editorAssistantId != null,
                onSave = {
                    chat.saveAssistant(it)
                    editorAssistantId = null
                },
                onDelete = {
                    chat.deleteAssistant(it)
                    editorAssistantId = null
                },
                onDismiss = { editorAssistantId = null },
            )
        }
    }
}


/**
 * 助手设置整页。整份 settings 下沉到这一层订阅（#54 5.48）：改任何设置只重跑这一页，
 * 不再把外壳连同抽屉、聊天页一起拖进来重组 —— 与上面 `DrawerBackHandler` 同一个思路。
 */
@Composable
private fun AssistantsRoute(
    activeId: String,
    onUse: (Assistant) -> Unit,
    onSetDefault: (Assistant) -> Unit,
    onSave: (Assistant) -> Unit,
    onDelete: (Assistant) -> Unit,
    onBack: () -> Unit,
) {
    val settings by Core.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    AssistantScreen(
        assistants = settings.assistants,
        activeId = activeId.ifBlank { settings.activeAssistantId },
        settings = settings,
        onUse = onUse,
        onSetDefault = onSetDefault,
        onSave = onSave,
        onDelete = onDelete,
        onBack = onBack,
    )
}

/** 抽屉那张半屏助手表，订阅同理（#54 5.48）。 */
@Composable
private fun ShellAssistantSheet(
    activeId: String,
    onUse: (Assistant) -> Unit,
    onSetDefault: (Assistant) -> Unit,
    onOpenEditor: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val settings by Core.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    AssistantSheet(
        assistants = settings.assistants,
        activeId = activeId.ifBlank { settings.activeAssistantId },
        settings = settings,
        onUse = onUse,
        onSetDefault = onSetDefault,
        onOpenEditor = onOpenEditor,
        onDismiss = onDismiss,
    )
}

/**
 * 助手编辑器的内容（主窗口全屏覆盖层里那一层）。订阅同样下沉到这里：
 * 覆盖层开着时改设置，只有编辑器本身重跑（#54 5.48）。
 */
@Composable
private fun AssistantEditorHost(
    id: String,
    backEnabled: Boolean,
    onSave: (Assistant) -> Unit,
    onDelete: (Assistant) -> Unit,
    onDismiss: () -> Unit,
) {
    val settings by Core.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val servers by Core.mcp.servers.collectAsStateWithLifecycle()
    val toolNames = remember(servers) {
        Core.tools.names() + com.suixing.agent.agent.SUBAGENT_SPECS.map { it.name }
    }
    val target = settings.assistants.firstOrNull { it.id == id } ?: Assistant(id = id, avatar = "🧭")
    AssistantEditor(
        initial = target,
        settings = settings,
        toolNames = toolNames,
        backEnabled = backEnabled,
        onSave = onSave,
        // 只剩一个助手时不给删（删完就没有「当前助手」了）。
        onDelete = if (settings.assistants.size > 1) { { onDelete(target) } } else null,
        onDismiss = onDismiss,
    )
}
