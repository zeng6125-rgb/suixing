@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.suixing.agent.ui

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import com.suixing.agent.ui.components.ImeInsetFilter
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import com.suixing.agent.ui.drawer.SuixingDrawer
import com.suixing.agent.ui.drawer.rememberSuixingDrawerState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
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
import com.suixing.agent.ui.terminal.TerminalScreen
import com.suixing.agent.ui.components.FullScreenOverlay
import com.suixing.agent.ui.components.LocalDrawerBusy
import com.suixing.agent.ui.components.LocalHeldImePx
import com.suixing.agent.ui.components.LocalOpenSidebar
import com.suixing.agent.ui.components.LocalSetSelectionActive
import com.suixing.agent.ui.components.imePaddingBottom
import com.suixing.agent.ui.drawer.DRAWER_SHEET_WIDTH
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
import com.suixing.agent.ui.settings.SUBAGENT_SETTINGS_ROUTE
import com.suixing.agent.ui.settings.SettingsScreen
import com.suixing.agent.ui.settings.ToolsScreen
import com.suixing.agent.ui.tasks.TasksScreen
import com.suixing.agent.ui.theme.LocalDarkTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
/**
 * 主页四角的圆角半径 —— **对齐屏幕自身的物理圆角**。
 *
 * 用户要的是「四角从一开始就是屏幕边缘模样，屏幕边缘圆角」：
 * 抽屉关着时页面铺满屏幕，这个圆角正好压在屏幕的物理圆角上 ⇒ 看不出来；
 * 抽屉滑开、页面右移后才显形，读成「一块和屏幕同形的面板浮在侧边栏上」。
 *
 * **这个数是量出来的，不是猜的**。本机（Redmi 2510DRK44C / annibale）：
 *   `adb shell dumpsys display` → `roundedCorners RoundedCorners{[RoundedCorner{position=TopLeft,
 *     radius=172, center=Point(172, 172)}, … 四角均 radius=172]}`
 *   `density 480` ⇒ 缩放 480/160 = 3.0
 *   ⇒ **172 / 3.0 = 57.33dp**
 * （上一版先写了 30dp 的猜测值，量完发现小了一倍。）
 *
 * 换机型时用同一条命令读出 `radius` 与 `density`，`radius / (density/160)` 就是这里的值。
 */
private const val SCREEN_CORNER_DP = 57.33f

/** 顶栏「打开浏览器」的目的地；与关于页的源码入口一致。 */
private const val BROWSER_HOME_URL = "https://github.com/zeng6125-rgb/suixing"






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
        animationSpec = PageMotion.spec(entering = route != "empty"),
        label = "baseShift",
    )

    // Owned here rather than inside the chat screen: the drawer lists and switches the
    // very conversations the screen is showing.
    val chat: ChatViewModel = viewModel()
    LaunchedEffect(Unit) {
        Core.settings.writeFailures.collect { message ->
            android.widget.Toast.makeText(Core.appContext, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

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

    // 抽屉本体交给 M3 的 **DismissibleNavigationDrawer**（推挤式）：手势（边缘拖动跟手）、
    // 面板位移全归库。自绘那版只能整段出现/消失——边缘拖不动，用户点名的就是这条。
    // 换成 Dismissible 的原因见绘图处那段注释（内容右移 = 面板宽度，留一条缝）。
    val drawerState = rememberSuixingDrawerState()
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

    // Opening a destination preserves the drawer underneath. Only conversation switches close it.
    // Publish the busy holder without subscribing the shell to every animation frame.
    val switchIntents = remember { ConversationSwitchIntents() }
    val drawerBusyState = switchIntents.busy

    val routeNow by androidx.compose.runtime.rememberUpdatedState(route)
    val open: (String) -> Unit = remember(navController) { { target ->
        // **不关抽屉**（用户：「不用关掉侧边栏」「新页不应该在页面都最上面移动的吗？」）：
        // 覆盖层现在画在自建抽屉宿主之上（见下面 Scaffold/Box 那段），所以新页是
        // 压着抽屉滑进来的，抽屉保持打开、原样留在下面 —— 返回时揭开的就是它，状态天然保留。
        if (target != routeNow) {
            switchIntents.invalidate()
            navController.navigate(target) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    } }
    // A row inside the settings page pushes, so back returns to the list it came from.
    val push: (String) -> Unit = { target ->
        switchIntents.invalidate()
        navController.navigate(target)
    }
    // chat 常驻（见下面 NavHost 那段），所以"回聊天"不是导航到某个目的地，而是**把覆盖层收掉**：
    // pop 到透明的 "empty" 就等于"什么都没有在盖着 chat"。
    val showChat: () -> Unit = { navController.popBackStack("empty", inclusive = false) }

    // 顶栏溢出菜单：浏览器交给系统默认浏览器（不内嵌 WebView）；终端走覆盖层路由。
    // 关于页用同一个仓库地址，保持一致。
    val openBrowser: () -> Unit = {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(BROWSER_HOME_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            Toast.makeText(context, "打不开链接：设备上没有可用的浏览器", Toast.LENGTH_SHORT).show()
        }
    }
    val openTerminal: () -> Unit = { open("terminal") }
    LaunchedEffect(Unit) {
        Core.terminals.openRequested.collect { requested ->
            if (requested) {
                Core.terminals.openRequested.value = false
                open("terminal")
            }
        }
    }

    // Back and explicit drawer actions share the same close motion.
    val closeDrawer: () -> Unit = {
        switchIntents.invalidate()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            drawerState.animateTo(DrawerValue.Closed, DRAWER_CLOSE_SPEC)
        }
    }
    // 点会话 / 新对话：与点页面行不同——那是"换我在读的那一份内容"，覆盖层收掉、抽屉也收起来，
    // 用户直接落在会话里。
    val chatFromDrawer: () -> Unit = {
        switchIntents.invalidate()
        // **与 `closeDrawer()`（助手 / 返回键）逐字相同**：只启动同一条
        // `animateTo(Closed, DRAWER_CLOSE_SPEC)` —— 同一帧开始、同一时长、同一曲线。
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            drawerState.animateTo(DrawerValue.Closed, DRAWER_CLOSE_SPEC)
        }
        showChat()
    }
    /** Replace the source immediately; restore and reveal only the latest target. */
    // Repeated taps remain latest-wins; a superseded close cannot release the latest busy flag.
    val switchFromDrawer: (Conversation) -> Unit = { conversation ->
        val token = switchIntents.begin()
        val handoff = ConversationSwitchHandoff()
        // Start the state animation before publishing the new conversation. UNDISPATCHED
        // runs it to its first frame wait, so the close and switch have no extra launch-turn gap.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                handoff.close { drawerState.animateTo(DrawerValue.Closed, DRAWER_CLOSE_SPEC) }
            } finally {
                switchIntents.finish(token)
            }
        }
        scope.launch {
            // 旧正文不再淡出：等抽屉出现第一个真实关闭帧后直接发布目标会话。
            // 挂起期间若来了更新的意图或抽屉关闭被打断，awaitClosingFrame 会拒绝交接。
            if (!handoff.awaitClosingFrame(
                    isCurrent = { switchIntents.isCurrent(token) },
                ) { drawerState.progress.value }) return@launch
            showChat()
            chat.openConversation(conversation)
        }
    }
    // 返回上一页：顶栏按钮与系统返回都走这里。
    val goBack: () -> Unit = {
        switchIntents.invalidate()
        navController.popBackStack()
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
    val openSidebar: () -> Unit = remember(scope, drawerState, switchIntents) { {
        if (routeNow == "empty") {
            switchIntents.invalidate()
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                // Claim the shared motion before the next frame, just like close and conversation switch.
                drawerState.animateTo(DrawerValue.Open, DRAWER_OPEN_SPEC)
            }
        }
    } }
    val setSelectionActive: (Boolean) -> Unit = remember { { selectionActive = it } }
    CompositionLocalProvider(
        LocalDrawerBusy provides drawerBusyState,
        // 用户自己拉开抽屉（聊天页那个汉堡键，也是全仓唯一的正常开抽屉路径）：演库自己那
        // 一段滑入——用户反对的从来是「返回时把它重放一遍」，不是手动打开时的动画。
        // 只在会话页可用（抽屉只属于会话页，见 SuixingDrawer）：子页没有汉堡入口，
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
        // 绘制缓存仍在 draw 阶段读键盘高度；只有终点、尺寸或主题变化才重建渐变，
        // 抽屉/页面的其它重绘复用 Brush，键盘动画不触发外壳重组或重布局。
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
                        Modifier.drawWithCache {
                            val endY = (screenHeightPx - heldImeState.value).coerceAtLeast(1f)
                            val gradient = Brush.verticalGradient(
                                colors = listOf(canvasTopColor, canvasTint),
                                startY = 0f,
                                endY = endY,
                            )
                            onDrawBehind { drawRect(gradient) }
                        }
                    },
                ),
        ) {
            // **抽屉在下、覆盖层在上**（用户：「新页不应该在页面都最上面移动的吗？」）：
            // 覆盖层的 NavHost 必须画在自建抽屉宿主之上，否则从抽屉点进新页时，
            // 页面只能在抽屉面板**下面**滑入 —— 用户看到的还是抽屉。现在抽屉不再关（见 open()），
            // 新页从右侧滑进来时压着它，滑完就把抽屉盖住；返回时揭开的就是那份仍开着的抽屉。
            Scaffold(
                // **外壳不再按 IME 上缩**（用户：「侧边栏还是被顶起来」）：
                // 这里原来是 `Modifier.padding(bottom = heldImePx)`，它把整个外壳 ——
                // 包括抽屉面板 —— 一起顶上去，抽屉就被键盘挤短了。
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
            // 抽屉打开时，主页缩放会在四周留出边距，露出的是**根底色**。
            // 若根底色一直是 surface，那圈边距就是白的 —— 用户看到的「主页被套了个白框」。
            // 所以让它随抽屉进度渐变到侧边栏底色（surfaceContainerHigh）：
            // 主页四周露出的就变成"侧边栏"，与 DeepSeek 一致。
            val drawerSurfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh
            val closedBackgroundColor = remember(surfaceColor, drawerSurfaceColor) {
                androidx.compose.ui.graphics.lerp(surfaceColor, drawerSurfaceColor, 0f)
            }
            val exposedBackgroundColor = remember(surfaceColor, drawerSurfaceColor) {
                androidx.compose.ui.graphics.lerp(surfaceColor, drawerSurfaceColor, 1f)
            }
            val drawerProgress = drawerState.visualProgress
            // The solid backing only changes at closed/exposed, not on every drag pixel.
            val drawerExposed = remember(drawerProgress) {
                derivedStateOf(structuralEqualityPolicy()) { drawerProgress.value > 0f }
            }
            Box(
                Modifier
                    .matchParentSize()
                    // **绘制期读**键盘高度与抽屉进度：只在 draw 阶段取值 ⇒ 动画期间既不重组也不重布局，
                    // 但绘制范围仍按"画布底边 = 屏高 − IME"裁剪，键盘圆角里那圈色块不会回来。
                    .drawBehind {
                        val h = (size.height - heldImeState.value).coerceAtLeast(0f)
                        // **一开始滑就切换**（用户：「滑动中还是没融合，刚滑的时候一点都不融合」）。
                        // p*4 在起始阶段仍然很白（p=0.02 → 8%），所以看上去"刚滑时一点不融合"。
                        // 改成阈值：只要抽屉动了一点，底色立刻就是侧边栏色。
                        // 判 p=0 时页面完全盖住，切换看不出来；关闭动画回到 0 时同理。
                        val bg = if (drawerExposed.value) exposedBackgroundColor else closedBackgroundColor
                        drawRect(color = bg, size = androidx.compose.ui.geometry.Size(size.width, h))
                    },
            )
            // Match the route underneath a nested page: same depth, duration and easing. Layers
            // remain opaque so text from two different pages never crossfades together.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = -size.width * PageMotion.DEPTH_FRACTION * baseShiftState.value },
            ) {
            SuixingDrawer(
                state = drawerState,
                // 边缘拖动跟手（拖到一半松手会回弹）、面板位移全归库——用户点名的「不要自绘」。
                // 但**只在会话页开着**：子页（设置主页 + 六个设置子页 + 会话管理 + 权限/工具/
                // 文件/任务/定时任务/用量/关于/扩展/画像/助手）都是 push 上去的独立页面，各有返回
                // 箭头，从屏幕左缘还能拉出抽屉是错的——用户原话「设置页到底是不是单独页？为什么在
                // 设置页里面还能侧滑出侧边栏？」。只有 chat 那一层（route == "empty"）为 true。
                gesturesEnabled = route == "empty",
                // **挤压式抽屉**（用户要的 DeepSeek 形态）：主页右移、缩放 0.95、屏幕圆角、
                // 左缘阴影；侧边栏铺满打底。宿主是自建的 [com.suixing.agent.ui.drawer.SuixingDrawer]
                //（M3 两代组件的布局/动画限制见它的 KDoc），拖动进度、松手回弹曲线全部自控。
                // 面板 **300dp**（[DRAWER_SHEET_WIDTH]）：屏宽 385dp 时主页右侧露出 **85dp**；
                // 抽屉开着时露出的主页被冻结层接管（轻点=关抽屉、横拖=开合、竖滑吞掉）。
                // 主页那层的变换（位移/缩放/圆角/阴影）在 content 的 graphicsLayer 里，见下。
                drawerContent = {
                    // 侧边栏本体：宿主按 progress 平移这一层，才有跟手/回弹动画。
                    val drawerVisible = drawerState.homeShieldActive && route == "empty"
                    val drawerLifecycle = com.suixing.agent.ui.chat.rememberChatUiLifecycleOwner(drawerVisible)
                    // Pause high-rate list updates, while retaining the header/avatar's normal preload.
                    val drawerConversations by chat.conversations.collectAsStateWithLifecycle(
                        lifecycleOwner = drawerLifecycle,
                    )
                Drawer(
                    open = drawerState.isOpen,
                    route = route,
                    conversations = drawerConversations,
                    activeId = activeConversationId,
                    runningId = runningIds,
                    runningAnimationActive = drawerVisible,
                    onNew = {
                        switchIntents.invalidate()
                        chat.newConversation()
                        chatFromDrawer()
                    },
                    onOpenConversation = { conversation ->
                        // 收起动画与助手那条完全一致（见 `switchFromDrawer`）。
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
                // The panel, home transform, background and shield share one bounded progress.
                // Read it only in the layer: chat keeps its layout and composition during motion.
                // Home retains the accepted 300dp travel, 0.95 scale and constant screen corners.
                val panelWidthPx = with(LocalDensity.current) { DRAWER_SHEET_WIDTH.dp.toPx() }
                val homeShape = remember { RoundedCornerShape(SCREEN_CORNER_DP.dp) }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // 主页浮在侧边栏之上：右移 + 轻微缩放 + 恒定圆角裁剪 + 淡投影。
                            // 全部在绘制期做，不改布局尺寸，也不在组合作用域里读进度。
                            val progress = drawerProgress.value
                            // 自建宿主只平移侧边栏本体，主页这一层得自己跟上进度，否则会露出
                            // 一块没动的内容。位移 = 面板宽 × 进度，与面板的 translationX 互补。
                            translationX = panelWidthPx * progress
                            // 缩放：关 1.0 → 开 0.95。端点必须写对，否则关着时整页常年缩小。
                            val s = 1f - 0.05f * progress
                            scaleX = s
                            scaleY = s
                            // **阴影属于主页自己**（用户：「边缘阴影圆角都是主页的」）。
                            // 用 graphicsLayer 的 shadowElevation 得到真实柔和投影，
                            // 因为主页现在在侧边栏**之上**（侧边栏已移到 content 里），阴影能压上去。
                            // 阴影跟着进度淡入：16dp 扩散、24% 浓度，得到"范围宽、颜色浅"的效果
                            // （用户：「扩散小一点点，深一点点就 ok 了」⇒ 22 → 16dp、10% → 24%）。
                            // API 28+ 生效，以下机型自动忽略。
                            shadowElevation = 16.dp.toPx() * progress
                            spotShadowColor = Color.Black.copy(alpha = 0.24f)
                            ambientShadowColor = Color.Black.copy(alpha = 0.24f)
                            // 从中心缩放，读成"整块浮起来"；从左拉伸会像贴纸。
                            transformOrigin = TransformOrigin(0.5f, 0.5f)
                            // **恒定圆角**：与屏幕四角半径一致。关着时页面铺满屏幕，
                            // 这个圆角正好落在屏幕自身的圆角上 ⇒ 肉眼看不出；
                            // 移动期间同时裁剪内容；完全关闭时让窗口负责屏幕边界，聊天无需常驻圆角遮罩。
                            shape = homeShape
                            clip = progress > 0f
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
                            ChatScreen(
                                viewModel = chat,
                                onOpenSettings = remember(open) { { open("settings") } },
                                onOpenBrowser = openBrowser,
                                onOpenTerminal = openTerminal,
                            )
                        }
                    }
                }
                }
            }
            }
            // SuixingDrawer owns the transparent right-side input shield while open;
            // tapping it closes the drawer. Back and drawer actions share DRAWER_CLOSE_SPEC.
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
                                // 只吃"点"：拖动不消费，左缘拖出抽屉仍归抽屉宿主的水平拖拽；
                                // 而它一旦被命中，下面的 chat 就整个不在命中路径里，滚动也透不过去。
                                .then(
                                    if (route == "empty") {
                                        Modifier
                                    } else {
                                        Modifier.pointerInput(route) { detectTapGestures { } }
                                    },
                                ),
                            // 进页**不播动画**（按最小、最保守的形态落地）：
                            // Foreground and background routes share one depth/timing policy.
                            enterTransition = {
                                slideInHorizontally(
                                    animationSpec = PageMotion.spec(entering = true),
                                ) { fullWidth -> fullWidth }
                            },
                            exitTransition = {
                                slideOutHorizontally(
                                    animationSpec = PageMotion.spec(entering = true),
                                ) { fullWidth -> -(fullWidth * PageMotion.DEPTH_FRACTION).toInt() }
                            },
                            // Pop reveals the source page at the same depth as a forward push.
                            popEnterTransition = {
                                slideInHorizontally(
                                    animationSpec = PageMotion.spec(entering = false),
                                ) { fullWidth -> -(fullWidth * PageMotion.DEPTH_FRACTION).toInt() }
                            },
                            popExitTransition = {
                                // Top-level and nested pages reveal their source with the same
                                // continuous motion; an opaque page never vanishes in one frame.
                                slideOutHorizontally(
                                    animationSpec = PageMotion.spec(entering = false),
                                ) { fullWidth -> fullWidth }
                            },
                        ) {
                            // 栈底那一层：透明，什么都不画（chat 就在它下面）。
                                composable("empty") { }
                                composable("files") {
                                    FilesScreen(
                                        onBack = goBack,
                                        onReference = { file ->
                                            if (chat.referenceFile(file.absolutePath)) chatFromDrawer()
                                        },
                                    )
                                }
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
                                SUBAGENT_SETTINGS_ROUTE,
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
                            // 内置终端：App UID 的交互式 PTY 会话（#61 PR-0 探针 + 最小会话管理）。
                            composable("terminal") { TerminalScreen(onBack = goBack, chatId = activeConversationId) }
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
                        // 抽屉开着时 BACK 先关抽屉：而系统返回手势不会替我们关，
                        // 普通 BACK 会交给 Activity，不收这一句就会退出应用。位置必须在 NavHost
                        // **之后**：OnBackPressedDispatcher 是后注册者优先（LIFO），注册在 NavHost
                        // 之前时，覆盖层页上按 BACK 会先被 NavHost 的回调 pop 掉、抽屉反而留着——
                        // 而这里的意图是"抽屉先关"。助手那张半屏表自己吞 BACK，审批卡是 dialog 也吞，
                        // 所以只剩抽屉这一种。chat 上（route == "empty"）NavHost 的回调不消费，
                        // BACK 仍落到 Activity ⇒ 退出应用，不会多出一层空页。
                        // **只在会话页**接管 BACK：抽屉现在不随导航关闭（见 open()），覆盖层页
                        // 盖在它上面时抽屉可能还开着 —— 那种情况下 BACK 应该先退页，不是去关一个
                        // 看不见的抽屉。route != "empty" 时这里让位，交给 NavHost 自己的返回。
                        // **不要把 `isOpen` 读进外壳的组合作用域**。历史依据是当时（还带遮罩、
                        // 还能点遮罩关闭的年代）用户实测：抽屉动画**播完那一帧**点遮罩要等一会才生效。
                        // 现在虽已没有遮罩，但**结论不变** —— 这里读 `isOpen` 会让那一帧整屏重组。
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
