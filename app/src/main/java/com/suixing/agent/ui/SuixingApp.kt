package com.suixing.agent.ui

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.graphics.toArgb
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlin.math.abs
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.suixing.agent.R
import com.suixing.agent.core.Core
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.Conversation
import com.suixing.agent.service.FloatConsoleService
import com.suixing.agent.ui.assistant.AssistantSheet
import com.suixing.agent.ui.capability.CapabilityScreen
import com.suixing.agent.ui.chat.ChatScreen
import com.suixing.agent.ui.chat.ChatViewModel
import com.suixing.agent.ui.components.LocalHeldImePx
import com.suixing.agent.ui.components.LocalOpenSidebar
import com.suixing.agent.ui.components.SuixingMark
import com.suixing.agent.ui.extension.ExtensionScreen
import com.suixing.agent.ui.files.FilesScreen
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

/**
 * The one curve every page transition rides, so push and pop are the same motion played
 * forwards and backwards rather than two hand-tuned pairs.
 *
 * `FastOutSlowInEasing` and not `LinearEasing`: the drawer already slides at a constant
 * speed, and a page that leaves at the same speed reads as a panel rather than a page.
 *
 * The enter is longer than the exit on purpose — the arriving page is the one being read,
 * and it has to still be moving when the eye lands on it; the leaving page only has to be
 * gone.
 *
 * 全站唯一的转场规格：进（enter）、出（exit）、返回进（popEnter）、返回出（popExit）四套全部
 * 只由下面这组常量和 [NAV_SHIFT_DIVISOR] 决定 —— NavHost 上一次配好，任何目的地都不再单独
 * 覆盖（唯一的例外是「从抽屉来源页返回」那一趟，见 NavHost 上那两条 pop）。
 *
 * **只有离场的那一页淡出，进场的那一页不淡入**：用户原话「感觉会残留上一页面的残影，才进入到」
 * —— 新页 alpha 0→1 的那 240ms 里，旧页正在它后面淡出，于是旧页"透"过新页。进页从第一帧就是
 * 不透明的（只做位移），观感变成「新页干净地滑进来、旧页退场」。
 *
 * 主通道是位移，只有屏宽/12。用户看过 ChatGPT 之后要的是「退出也有动画，连贯虽然不易察觉」：
 * 进页从右侧挪一小步、旧页向左让一小步并淡出，返回把这两步原样倒放（回来的那页从左侧挪回原位）。
 *
 * 缩放不再参与：0.8.67 修过 scaleIn(0.96) 在页边露缝（露出来的是外壳 Scaffold 的
 * containerColor = surface，与页面底色同色，等于没缩还多一条缝），位移是唯一对着静止的屏幕
 * 边框也看得见的通道。
 */
private const val NAV_FADE_MS = 240
private const val NAV_ENTER_MS = 280
private const val NAV_EXIT_MS = 200
private val NAV_EASING = FastOutSlowInEasing

/**
 * 「这一趟返回」的标志要活多久：转场 lambda 是在转场开始那一刻才求值的，判据必须活过整趟转场
 * （最长的是进页 280ms），否则它读到 null/false，这一趟又走回带滑动的常规规格 —— 用户看到的
 * 「点返回按钮时主页面还是在滑动」就是这个。400ms 比 280 长一截，又短到不会影响下一次导航。
 *
 * Long 而不是 Int：它唯一的用处是 `delay(...)`，而 delay 只收 Long/Duration（上面那三个
 * `NAV_*_MS` 是 Int，因为它们只喂给 `tween(...)`，那边收 Int）。
 */
private const val NAV_RETURN_FLAG_MS = 400L

/**
 * 横向位移的除数：屏宽 / 12 ≈ 8% 屏宽（1080p 面板上 ≈ 90px）。够看得见，又短到不会和
 * 抽屉那条 300dp 的左滑读成同一个手势 —— 抽屉是整块面板推入，页面只是「动了一下」。
 * 四条转场（进/出/返回进/返回出）都按它算，正负号就是方向。
 */
private const val NAV_SHIFT_DIVISOR = 12

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
    val route = backStackEntry?.destination?.route?.substringBefore('?') ?: "chat"
    val pending by Core.gate.pending.collectAsState()

    // Owned here rather than inside the chat screen: the drawer lists and switches the
    // very conversations the screen is showing.
    val chat: ChatViewModel = viewModel()
    val conversations by chat.conversations.collectAsState()
    // The one id alone, not the whole chat state: the shell must not recompose once per
    // streamed token just to keep one row highlighted.
    val activeConversationId by remember(chat) {
        chat.state.map { it.conversationId }.distinctUntilChanged()
    }.collectAsState(initial = "")
    // Which conversations have a run in flight, straight off the engine's own map: the
    // drawer's dots follow it without the view model narrating anyone else's run.
    val runningIds by remember {
        Core.engine.runsFlow.map { it.keys }.distinctUntilChanged()
    }.collectAsState(initial = emptySet<String>())
    // Same reason as the ids above: the picker needs one field, not the whole transcript.
    val assistantId by remember(chat) {
        chat.state.map { it.assistantId }.distinctUntilChanged()
    }.collectAsState(initial = "")

    // 抽屉本体交给 M3 的 ModalNavigationDrawer：手势（边缘拖动跟手）、遮罩、面板位移全归库。
    // 自绘那版只能整段出现/消失——边缘拖不动，用户点名的就是这条。
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    // 抽屉里那张半屏助手表：和聊天页那张是同一个 AssistantSheet，只是宿主换成了外壳。
    // remember 而不是 rememberSaveable（与聊天页那张表一致）：进程重启后它不该自己弹回来。
    var shellAssistants by remember { mutableStateOf(false) }
    val settings by Core.settings.flow.collectAsState(initial = AppSettings())
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
    val open: (String) -> Unit = { target ->
        // 离开抽屉这一趟**不播关闭动画**：用户两次抱怨的都是「能看见抽屉动」——先是「两层各动
        // 一半」，后来是「我已经完全看到他被关掉的残影」。所以这里直接 snapTo(Closed)：同帧、
        // 零动画，新页干净地入场。UNDISPATCHED 让状态写在导航之前当场落地，否则它会被排到下一个
        // 主线程消息、那一帧就晚了（与返回时 snapTo(Open) 同一套做法，两条路观感对称，也就不像
        // 「硬做」）。抽屉本来就关着时这一句是空操作（聊天页顶栏那个「打开设置」走的就是这条路）。
        // 用户**主动**开合（汉堡键 LocalOpenSidebar、手势拖动、遮罩点击）仍走库的动画。
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            drawerState.snapTo(DrawerValue.Closed)
        }
        navController.navigate(target) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    // A row inside the settings page pushes, so back returns to the list it came from.
    val push: (String) -> Unit = { target -> navController.navigate(target) }

    // 从抽屉里点走一行时，记下出发的是哪一页：用户按返回回到那一页，抽屉应该还是开着的。
    // 这件事用户改口过三次（「每次回来侧边栏都会被关掉」→「也不要重放抽屉的动画」→
    // 「退出页面的动画加回来」→ 最后一句「我跟你说了在侧边栏打开界面返回时，不要把侧边栏
    // 关闭」），最终口径就是最后那一句：**状态要恢复，动画不要**——所以恢复走 snapTo，
    // 那一段滑入一次都不演。只由抽屉自己的入口置位——聊天页那个「打开设置」不是从抽屉走的，
    // 不该把抽屉带回来。
    // remember 而不是 rememberSaveable：存下来的 "chat" 会在冷启动那一帧（起始页正是 chat）
    // 把抽屉弹开；rotation 不走这条路（manifest 里 configChanges 已含 orientation）。
    var drawerReturnTo by remember { mutableStateOf<String?>(null) }
    // 「这一趟就是那次返回」：从 route 变回来源页的那一帧起为真，并**活过整趟转场**。
    // 为什么不能直接用 drawerReturnTo 当判据、还要多这一位：转场 lambda 是在转场开始那一刻
    // 求值的，而摆位（snapTo）与清标志发生在同一帧的组合末尾 —— 两种时序都可能发生，所以两条
    // 判据取「或」：同一帧求值时有 drawerReturnTo，晚一帧求值时有这一位。只留一条就会漏，
    // 用户看到的「点返回按钮时主页面还是在滑动」正是漏掉的那半。
    var drawerReturning by remember { mutableStateOf(false) }
    // 用户自己收起抽屉（遮罩、返回键）：记下的出发页一起清掉，回来时不该再弹出来。
    val closeDrawer: () -> Unit = {
        scope.launch { drawerState.close() }
        drawerReturnTo = null
    }
    // 抽屉里的一行：比 open 多记一件事。目标是当前页时（点会话、点新对话）不记——那不是
    // 「去了别的页」，记下来会当场被判成「回到出发页」而把抽屉又弹开。
    val openFromDrawer: (String) -> Unit = { target ->
        if (target != route) drawerReturnTo = route
        open(target)
    }
    // 返回上一页：抽屉的恢复**不挂在这条路上**（见下面的 SideEffect）——顶栏按钮、系统返回键、
    // 手势返回、以及 NavHost 自己处理的 pop，四条路都从 route 变化上恢复，谁 pop 都一样。
    // 但「这一趟零动画」的标志在这里就先立起来（pop 之前），这样即使转场 lambda 在 pop 的同一帧
    // 就被求值，它也已经看得到 —— 系统返回那条路没有这个先手，靠下面的 SideEffect 补。
    val goBack: () -> Unit = {
        if (navController.previousBackStackEntry?.destination?.route == drawerReturnTo) {
            drawerReturning = true
        }
        navController.popBackStack()
    }
    // 回到「抽屉的来源页」的那一帧，把抽屉摆成开着：route 变回来源页 ⇒ 抽屉本来就该在那儿。
    //
    // 用 SideEffect 而不是 LaunchedEffect：SideEffect 在组合完成后、**布局之前**跑，同一帧画出来
    // 就是「目标页 + 已经开着的抽屉」；LaunchedEffect 要等下一帧，用户看到的就是「先闪一下主页面
    // 才出现侧边栏」（他两轮都在说这条）。snapTo 不播动画，正是他要的「已经在那儿」。
    // UNDISPATCHED：snapTo 是挂起函数，普通 launch 会把写入排到下一个主线程消息，那一帧就晚了。
    //
    // 为什么按 route 派生、而不是让每条 pop 路径自己先 snapTo 再 pop：早先那版就是后者
    // （goBack 里 snapTo + 一个 BackHandler 分支），结果系统返回走的是 NavHost 自己注册的回调
    // （它比外壳里的 BackHandler 后注册、优先级更高），根本不经过我们——于是只有点顶栏返回按钮
    // 才不闪，用户原话：「只有点返回按钮才有那套返回处理，系统返回（手势/返回键）没有」。
    // 从 route 派生之后，恢复与「谁触发的 pop」无关，四条路完全一致。
    if (route == drawerReturnTo) {
        SideEffect {
            drawerReturnTo = null
            drawerReturning = true
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                drawerState.snapTo(DrawerValue.Open)
            }
        }
    }
    // 转场跑完再把「这一趟」的标志放下：早于转场清掉，两条 pop 转场就会读到 false，这一趟又变成
    // 带滑动的常规规格。只由 drawerReturning 驱动，不挂 route —— 挂 route 会在每次导航都重置计时。
    LaunchedEffect(drawerReturning) {
        if (drawerReturning) {
            delay(NAV_RETURN_FLAG_MS)
            drawerReturning = false
        }
    }

    // The keyboard is a layout change, not a scroll, and the platform already animates the
    // IME inset frame by frame. The shell lays itself out by a *clamped* copy of that inset:
    // the platform's own animation ends with a rebound of a few pixels the other way
    // (measured on device: the composer overshot ~6px past its resting place and snapped
    // back, which on a 120Hz panel reads as a hitch at the end of every close), and an
    // opposite-direction change inside the clamp is that rebound and is dropped; real
    // transition frames are 30-80px and pass untouched.
    val rootDensity = LocalDensity.current
    val imeRawPx = WindowInsets.ime.getBottom(rootDensity)
    var heldImePx by remember { mutableIntStateOf(0) }
    var imeDir by remember { mutableIntStateOf(0) }
    LaunchedEffect(imeRawPx) {
        val delta = imeRawPx - heldImePx
        if (delta == 0) return@LaunchedEffect
        val dir = if (delta > 0) 1 else -1
        val rebound = with(rootDensity) { abs(delta).toDp() <= 24.dp }
        if (imeDir != 0 && dir != imeDir && rebound) return@LaunchedEffect
        imeDir = dir
        heldImePx = imeRawPx
    }
    LaunchedEffect(imeRawPx) {
        delay(300)
        imeDir = 0
    }
    CompositionLocalProvider(
        // 用户自己拉开抽屉（聊天页那个汉堡键，也是全仓唯一的正常开抽屉路径）：演库自己那
        // 一段滑入——用户反对的从来是「返回时把它重放一遍」，不是手动打开时的动画。
        LocalOpenSidebar provides { scope.launch { drawerState.open() } },
        // The clamped keyboard height, for children that have to subtract it from an inset of
        // their own (the composer's navigation-bar padding: while the keyboard covers the
        // gesture bar that padding is dead space, which is the band the user photographed).
        LocalHeldImePx provides heldImePx,
    ) {
        // 抽屉开着时 BACK 先关抽屉：ModalNavigationDrawer 只接管预测性返回的手势，普通 BACK
        // 仍会交给 Activity，不收这一句就会退出应用。助手那张半屏表自己吞 BACK，审批卡是
        // dialog 也吞，所以这里只剩抽屉这一种。
        // 「返回抽屉来源页」那一趟**不需要**在这里拦：抽屉的恢复是从 route 派生的一帧内动作
        // （见上面的 SideEffect），系统返回走 NavHost 自己的回调也一样会恢复——拦下来反而会
        // 把 NavHost 的预测性返回动画弄没。
        BackHandler(enabled = drawerState.isOpen) { closeDrawer() }
        // Painted on the window, not on the scaffold: the scaffold is inset by the keyboard, so
        // the strip behind the keyboard was left unpainted and the window's own white showed
        // through the keyboard's rounded corners as a pale seam. This box is not inset, so
        // everything the keyboard covers is already the app's own background.
        // The keyboard's rounded corners reveal whatever the WINDOW paints, and the window's
        // static background resource can never match a theme whose surface changes with palette
        // and dark mode — that mismatch was the pale seam. Sync the window to the live surface.
        val activity = (LocalContext.current as? Activity)
        val surfaceArgb = MaterialTheme.colorScheme.surface.toArgb()
        LaunchedEffect(surfaceArgb) {
            activity?.window?.setBackgroundDrawable(ColorDrawable(surfaceArgb))
        }
        // The strip behind the keyboard stays unpainted: other apps' rounded keyboard corners
        // show the wallpaper there, and ours showed our own canvas because the root painted the
        // whole window. Insetting the painted layer by the IME (and making the window background
        // transparent in MainActivity) is the adaptation they have and we were missing.
        // Two siblings, each inset once: the painted layer stops at the keyboard's top edge so
        // the strip behind it — and the keyboard's rounded corners — stay unpainted and show
        // whatever is behind the (transparent) window, which is the see-through the adapted
        // apps have. Painting the whole window instead put our gradient's own colour in those
        // corners, and a gradient can never match itself at two different heights, which is
        // why every full-window attempt read as "lighter but not the same".
        // Painted over the whole window, and the gradient's end is pinned to the canvas's own
        // bottom edge (window height minus the keyboard). Two gradients over two different
        // spans can never agree where they meet: with the root running 0..window and the canvas
        // 0..canvasBottom, the strip behind the keyboard showed the root at 62% of its ramp
        // while the canvas just above it sat at 100% — the colour mismatch at the keyboard's
        // rounded corners. Ending the root's ramp where the canvas ends (and clamping past it,
        // which is the ramp's own last colour) makes the corners exactly the canvas's bottom
        // colour, continuous with the pixel row above the keyboard.
        val gradientEndPx = with(rootDensity) {
            (LocalConfiguration.current.screenHeightDp.dp.toPx() - heldImePx).coerceAtLeast(1f)
        }
        Box(
            Modifier.fillMaxSize().background(
                if (LocalDarkTheme.current) {
                    SolidColor(MaterialTheme.colorScheme.surface)
                } else {
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                        ),
                        startY = 0f,
                        endY = gradientEndPx,
                    )
                },
            ),
        ) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                // 边缘拖动跟手（拖到一半松手会回弹）、遮罩与面板位移全归库——用户点名的「不要自绘」。
                gesturesEnabled = true,
                // 遮罩浓度照搬自绘那版：M3 默认 scrim 是 32%，这块面板从第一天起是 45% 的黑。
                scrimColor = Color.Black.copy(alpha = 0.45f),
                drawerContent = {
                    Drawer(
                        open = drawerState.isOpen,
                        route = route,
                        conversations = conversations,
                        activeId = activeConversationId,
                        runningId = runningIds,
                        onNew = {
                            chat.newConversation()
                            openFromDrawer("chat")
                        },
                        onOpenConversation = { conversation ->
                            chat.openConversation(conversation)
                            openFromDrawer("chat")
                        },
                        onDeleteConversation = { chat.deleteConversation(it) },
                        onNavigate = openFromDrawer,
                        onAssistants = {
                            // 半屏表挂在外壳上：抽屉先收，表从底下上来，不必再走一次页面切换。
                            closeDrawer()
                            shellAssistants = true
                        },
                        consoleOn = settings.floatingConsole,
                        onToggleConsole = {
                            if (settings.floatingConsole) {
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
                Scaffold(
                    modifier = Modifier.padding(bottom = with(rootDensity) { heldImePx.toDp() }),
                    // surface, not background: the scaffold covers the strip behind the keyboard, and
                    // its white container was exactly the pale seam the keyboard's rounded corners
                    // revealed against the chat canvas.
                    containerColor = MaterialTheme.colorScheme.surface,
                    // Screens own their top inset (their headers paint behind the status
                    // bar) and the composer owns the bottom one, so the scaffold reserves
                    // nothing for itself.
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                ) { padding ->
                    NavHost(
                        navController = navController,
                        startDestination = "chat",
                        modifier = Modifier.padding(padding),
                        // Every destination enters and leaves with the same three channels — a
                        // fade, a short horizontal push and a depth change — so a push and a pop
                        // read as one gesture played backwards. The push is deliberately short
                        // (1/12 screen width): the drawer already owns the long leftward slide,
                        // and a second full-width sweep under it would read as two panels
                        // fighting, while a small one only says "this page moved".
                        // 位移只有屏宽/12（NAV_SHIFT_DIVISOR）：新页从右侧挪一小步、旧页向左让
                        // 一小步，返回时这两步原样倒放。整幅横移那一版（新页整幅滑入、旧页 1/3
                        // 幅视差）在用户看过 ChatGPT 之后仍嫌「不够连贯」——小位移 + 淡入淡出
                        // 才是那一段观感的来源。抽屉仍是它自己的 300dp 左滑，与页面这一小步
                        // 方向不同，不会混。
                        // 四套转场全在这一个地方配：进/出/返回进/返回出都是同一组 NAV_* 常量、
                        // 同一个位移除数，方向互为镜像（见文件顶部那段）。返回的动画上一版被砍
                        // 成瞬时，用户否了：「退出页面的动画加回来吧……退出也有动画，连贯虽然不
                        // 易察觉」。唯一的例外在下面那两条 pop 转场里（回到抽屉来源页的那一趟），
                        // 除此之外没有任何按目的地分叉的条件。
                        // 进页**不淡入**：新页从第一帧就是不透明的，只做位移。用户原话「感觉会
                        // 残留上一页面的残影，才进入到」——新页 alpha 0→1 的这 240ms 里旧页正在
                        // 它后面淡出，旧页就"透"过来了。离场那页照旧淡出+滑出。
                        enterTransition = {
                            slideInHorizontally(tween(NAV_ENTER_MS, easing = NAV_EASING)) {
                                it / NAV_SHIFT_DIVISOR
                            }
                        },
                        exitTransition = {
                            fadeOut(tween(NAV_EXIT_MS, easing = NAV_EASING)) +
                                slideOutHorizontally(tween(NAV_EXIT_MS, easing = NAV_EASING)) {
                                    -it / NAV_SHIFT_DIVISOR
                                }
                        },
                        // 唯一的例外：这一趟 pop 的目的地正是「抽屉的来源页」——也就是抽屉被摆回
                        // 打开的那一次。用户对这条路径改口过三次（「主页面为什么也有动画」→「返回
                        // 时的动画可以轻一点的淡淡的」→ 看到实物后「返回时页面动就是不对」），最终
                        // 口径是**两侧都零动画**：页面直接是那一帧、抽屉已经在（恢复走 snapTo，不重放
                        // 滑入），屏幕上没有任何可见运动。其余每一次返回照常走上面那套统一规格；两边
                        // 共用同一个判断，只关一边会露出半截动画。
                        // 判据是「或」：drawerReturning 是这一趟的标志（活过整趟转场），
                        // drawerReturnTo 兜住"转场 lambda 在标志立起来之前就被求值"那种时序。
                        popEnterTransition = {
                            if (drawerReturning ||
                                targetState.destination.route == drawerReturnTo
                            ) {
                                EnterTransition.None
                            } else {
                                slideInHorizontally(tween(NAV_ENTER_MS, easing = NAV_EASING)) {
                                    -it / NAV_SHIFT_DIVISOR
                                }
                            }
                        },
                        popExitTransition = {
                            if (drawerReturning ||
                                targetState.destination.route == drawerReturnTo
                            ) {
                                ExitTransition.None
                            } else {
                                fadeOut(tween(NAV_EXIT_MS, easing = NAV_EASING)) +
                                    slideOutHorizontally(tween(NAV_EXIT_MS, easing = NAV_EASING)) {
                                        it / NAV_SHIFT_DIVISOR
                                    }
                            }
                        },
                    ) {
                        composable("chat") { ChatScreen(viewModel = chat, onOpenSettings = { open("settings") }) }
                        composable("files") { FilesScreen(onBack = goBack) }
                        composable("tasks") { TasksScreen(onBack = goBack) }
                        // 定时任务是「到点自己跑」的那一份，与 agent 的 todo 看板（"tasks"）是两件
                        // 事：抽屉那行说的「任务」一直指的是后者，用户以为是摆设，才有了这一页。
                        composable("schedules") { SchedulesScreen(onBack = goBack) }
                        composable("settings") {
                            SettingsScreen(
                                onOpen = push,
                                onBack = goBack,
                            )
                        }
                        // 设置里的六个子页各是一条真路由，而不是设置页内部的 when 分支：同一
                        // 个目的地里换内容，NavHost 不参与，于是进出完全没有转场——用户说的
                        // 「就是复用的，根本没有切换动画」正是这个。走 push 而不是 open：抽屉
                        // 是同级切换，设置子页是往下一层，返回要回到那份列表。
                        listOf(
                            "settings/model",
                            "settings/steer",
                            "settings/appearance",
                            "settings/network",
                            "settings/data",
                            "settings/usage",
                        ).forEach { route ->
                            composable(route) {
                                SettingsPageRoute(
                                    route = route,
                                    onOpen = push,
                                    onBack = goBack,
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
                            AssistantScreen(
                                assistants = settings.assistants,
                                activeId = assistantId.ifBlank { settings.activeAssistantId },
                                settings = settings,
                                onUse = { chat.useAssistant(it) },
                                onSetDefault = { chat.setDefaultAssistant(it) },
                                onSave = { chat.saveAssistant(it) },
                                onDelete = { chat.deleteAssistant(it) },
                                onBack = goBack,
                            )
                        }
                    }
                }
            }
        }
    }

    // Rendered outside the navigation host so a prompt cannot be missed while the
    // user is on another screen — the agent is blocked until it is answered.
    pending.entries.firstOrNull()?.let { (chatId, request) ->
        ApprovalDialog(
            chatId = chatId,
            toolName = request.toolName,
            args = request.args,
            // The card floats above whichever screen the user is on, so it has to name the
            // conversation it belongs to: with several runs in flight the prompt on screen
            // may well be another chat's, and only this line says whose it is.
            sourceTitle = conversations.firstOrNull { it.id == chatId }?.title,
            otherWaiting = pending.size - 1,
        )
    }

    // 抽屉里那张半屏表。挂在外壳、不在任何一页里：抽屉是外壳自己的面板，表跟着它走，
    // 与聊天页那张共用同一个 AssistantSheet（回调也同一套）。
    if (shellAssistants) {
        AssistantSheet(
            assistants = settings.assistants,
            activeId = assistantId.ifBlank { settings.activeAssistantId },
            settings = settings,
            onUse = { chat.useAssistant(it) },
            onSetDefault = { chat.setDefaultAssistant(it) },
            onSave = { chat.saveAssistant(it) },
            onDelete = { chat.deleteAssistant(it) },
            onDismiss = { shellAssistants = false },
        )
    }
}

/**
 * The left drawer: conversations on top, everything else at the bottom.
 *
 * 面板本体是 M3 的 [ModalDrawerSheet]（用户点名「不要自绘」）：拖动跟手、遮罩与位移都归库。
 * 圆角、底色、宽度、elevation 全部显式覆盖成自绘那版的值——换库不许换设计。
 */
@Composable
private fun Drawer(
    /** 抽屉是否开着（= drawerState.isOpen）：只用来在拉开时收起键盘。 */
    open: Boolean,
    route: String,
    conversations: List<Conversation>,
    activeId: String,
    runningId: Set<String>,
    onNew: () -> Unit,
    onOpenConversation: (Conversation) -> Unit,
    onDeleteConversation: (Conversation) -> Unit,
    consoleOn: Boolean,
    onToggleConsole: () -> Unit,
    onNavigate: (String) -> Unit,
    onAssistants: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // Every delete entry in the drawer funnels through this one confirmation: a swipe
    // of the thumb on a row must not be able to erase a conversation outright.
    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(open) {
        if (open) {
            // Five 56dp rows plus the head do not fit above the keyboard on a short
            // screen, and the list column can only shrink to zero — the bottom rows,
            // 设置 included, would sit off-screen with nothing able to scroll them.
            // Dropping the keyboard gives the panel the height it was laid out for.
            keyboard?.hide()
        }
    }

    // 面板：库负责拖动/回弹/遮罩/位移，视觉参数逐条覆盖回自绘那版——
    // 宽度 300dp（库的 sizeIn 区间是 240–360dp）、圆角只有右侧两角 30dp（库默认四角 16dp）、
    // 底色 surfaceContainerHigh（库默认 surfaceContainerLow）、elevation 0（库默认 16dp 的
    // tonal elevation，那会把底色再调亮一档，用户看了几个版本的颜色就变了）。
    // windowInsets 归零：状态栏/导航栏/键盘的内边距一直由下面这层 Column 自己管，库那层
    // 再补一次就是双重内边距。
    ModalDrawerSheet(
        modifier = Modifier
            .fillMaxHeight()
            .width(300.dp),
        drawerShape = RoundedCornerShape(topEnd = 30.dp, bottomEnd = 30.dp),
        drawerContainerColor = scheme.surfaceContainerHigh,
        drawerTonalElevation = 0.dp,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .statusBarsPadding()
                .windowInsetsPadding(WindowInsets.navigationBars.exclude(WindowInsets.ime))
                // The keyboard can be up when the drawer is opened mid-sentence: the
                // panel keeps clear of it the same way the shell does.
                .imePadding()
                .padding(vertical = 18.dp),
        ) {
            Row(
                modifier = Modifier.padding(start = 20.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SuixingMark(size = 30.dp)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("随行", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "跟着你走的手机智能体",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onNew, modifier = Modifier.size(38.dp)) {
                    // The same glyph the chat header's compose button draws, at the same
                    // 22dp: the drawer and the bar are two doors to one action, and they
                    // read as two until the drawing is shared.
                    Icon(
                        painter = painterResource(R.drawable.ic_compose),
                        contentDescription = "新对话",
                        tint = scheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = scheme.outlineVariant,
            )
            Spacer(Modifier.height(6.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
            ) {
                items(conversations, key = { it.id }) { conversation ->
                    ConversationRow(
                        conversation = conversation,
                        selected = conversation.id == activeId,
                        running = conversation.id in runningId,
                        onClick = { onOpenConversation(conversation) },
                        onDelete = { pendingDelete = conversation },
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = scheme.outlineVariant,
            )
            Spacer(Modifier.height(6.dp))
            // 助手这一行开的是半屏表，不是整页：在抽屉里挑助手是「挑完接着聊」，
            // 不该被推到一整页上去。整页仍是设置页「助手」那一行的目的地
            // （route = "assistants"），两个宿主共用同一份 AssistantListContent。
            DrawerAction(
                label = "助手",
                icon = painterResource(R.drawable.ic_bot),
                selected = route == "assistants",
                onClick = onAssistants,
            )
            DrawerAction(
                label = "定时任务",
                icon = painterResource(R.drawable.ic_clock),
                selected = route == "schedules",
                onClick = { onNavigate("schedules") },
            )
            DrawerAction(
                label = "文件",
                icon = painterResource(R.drawable.ic_folder_open),
                selected = route == "files",
                onClick = { onNavigate("files") },
            )
            // The console's switch moved out of the chat header into here: on
            // without the overlay grant would read ON with no ball, so the grant
            // page comes first and the switch flips when the grant lands.
            DrawerAction(
                label = "悬浮窗控制台",
                icon = painterResource(R.drawable.ic_picture_in_picture_2),
                selected = false,
                // The whole row toggles, both ways: this row is the app's only
                // console switch (the settings page is text only), so a row that
                // could only turn it on left the user tapping a dead row while the
                // ball was up. The Switch keeps its own handler; a child consumes
                // the tap, so the two never fire together.
                onClick = onToggleConsole,
                trailing = {
                    Switch(checked = consoleOn, onCheckedChange = { onToggleConsole() })
                },
            )
            DrawerAction(
                label = "设置",
                icon = painterResource(R.drawable.ic_settings),
                selected = route == "settings",
                onClick = { onNavigate("settings") },
            )
        }
    }
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除会话") },
            text = {
                Text(
                    text = target.title,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteConversation(target)
                    pendingDelete = null
                }) {
                    Text("删除", color = scheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    selected: Boolean,
    running: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) scheme.primary.copy(alpha = 0.16f) else Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (running) {
                // 运行中的会话可能在用户看不见的那一屏里跑，抽屉里得有个标记，
                // 否则「谁在占着那个 run」只能靠猜。标记本身在转：静止的点看不出
                // 「它正在动」，而这一行要回答的正是「现在有没有人在跑」。
                // 14dp 的环比原来的 7dp 点宽，间距收窄到 2dp，标题的左缘不动。
                RunningSpinner(Modifier.size(14.dp))
                Spacer(Modifier.width(2.dp))
            }
            Text(
                text = conversation.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) scheme.primary else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // A run owns its conversation and the view model refuses to delete it, so an
            // enabled button here was a tap that silently did nothing. The icon is dimmed
            // to Material's disabled alpha by hand because the explicit tint below would
            // otherwise keep it looking live.
            // The icon keeps its 18dp and the ripple its 36dp disc; the 48dp minimum sits
            // outside them, so only the touch area grows.
            IconButton(
                onClick = onDelete,
                enabled = !running,
                modifier = Modifier.minimumInteractiveComponentSize().size(36.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_trash),
                    contentDescription = "删除会话",
                    tint = if (running) {
                        scheme.onSurfaceVariant.copy(alpha = 0.38f)
                    } else {
                        scheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * 会话行左侧的运行标记：一段 270° 的弧在转，而不是一个静止的点。
 *
 * 自绘而不是用 Material 的 Autorenew/Sync：那两个图标画的是「刷新」语义，会被读成
 * 一个可以点的动作，而这里只是状态。只有运行中的行才建这段动画——整抽屉一起转比
 * 没有标记更糟。
 */
@Composable
private fun RunningSpinner(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "running")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
        ),
        label = "angle",
    )
    val arc = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = modifier.semantics { contentDescription = "正在运行" },
    ) {
        val stroke = 1.6.dp.toPx()
        val diameter = size.minDimension
        // 内缩半个线宽，圆头端点才不会在 14dp 的盒子里被切掉。
        drawArc(
            color = arc,
            startAngle = angle,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(diameter - stroke, diameter - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun DrawerAction(
    label: String,
    /** A Lucide vector from res/drawable: the drawer rows all draw from one icon set. */
    icon: Painter,
    selected: Boolean,
    onClick: () -> Unit,
    badge: String? = null,
    /** A control on the row's end instead of the chevron-less plain row. */
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) scheme.primary.copy(alpha = 0.16f) else Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = if (selected) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                color = if (selected) scheme.primary else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (badge != null) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.primary,
                )
            }
            trailing?.invoke()
        }
    }
}

@Composable
private fun ApprovalDialog(
    chatId: String,
    toolName: String,
    args: String,
    /** Title of the conversation whose run is asking; null once it is gone from the list. */
    sourceTitle: String?,
    /** How many other conversations are blocked on their own prompt right now. */
    otherWaiting: Int,
) {
    // A deleted or untitled conversation still has to be named: a blank line would leave
    // the user unable to tell the card apart from the chat they are looking at.
    val from = sourceTitle?.takeIf { it.isNotBlank() } ?: "另一个会话"
    AlertDialog(
        onDismissRequest = { Core.gate.resolve(chatId, false) },
        icon = { Icon(ImageVector.vectorResource(R.drawable.ic_shield_alert), contentDescription = null) },
        title = {
            Column {
                Text("需要你确认")
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "来自会话：$from",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Only worth a line when it is true: one blocked chat is the normal case.
                if (otherWaiting > 0) {
                    Text(
                        text = "还有 $otherWaiting 个会话在等待",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column {
                Text("随行想调用工具：$toolName")
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        text = args,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "拒绝后模型会收到失败结果，可以换个方式继续。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { Core.gate.resolve(chatId, true) }) { Text("允许一次") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { Core.gate.resolve(chatId, true, always = true) }) { Text("总是允许") }
                TextButton(onClick = { Core.gate.resolve(chatId, false) }) { Text("拒绝") }
            }
        },
    )
}
