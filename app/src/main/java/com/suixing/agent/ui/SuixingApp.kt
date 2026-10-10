package com.suixing.agent.ui

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import androidx.compose.ui.graphics.toArgb
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.delay
import kotlin.math.abs
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
 * 覆盖。用户的原话是「动画开闭的尽量各个页面都统一，主要还是统一」。
 *
 * 主通道是淡入淡出，位移只有屏宽/12。用户看过 ChatGPT 之后要的是「退出也有动画，连贯虽然
 * 不易察觉」：进页从右侧挪一小步、旧页向左让一小步，返回把这两步原样倒放（回来的那页从左
 * 侧挪回原位）—— 来回是同一段动效的正反两面，不是两套手调的参数。
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

    var drawerOpen by rememberSaveable { mutableStateOf(false) }
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
        drawerOpen = false
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
    // 关闭」），最终口径就是最后那一句：**状态要恢复，动画不要**。所以恢复时置 drawerInstant。
    // 只由抽屉自己的入口置位——聊天页那个「打开设置」不是从抽屉走的，不该把抽屉带回来。
    // remember 而不是 rememberSaveable：存下来的 "chat" 会在冷启动那一帧（起始页正是 chat）
    // 把抽屉弹开；rotation 不走这条路（manifest 里 configChanges 已含 orientation）。
    var drawerReturnTo by remember { mutableStateOf<String?>(null) }
    // 恢复只恢复状态，不重放动画：用户明确反对过「回来的时候那个侧边栏的动画再来一遍」，
    // 他要的是「已经在那儿」。这一位只由下面的恢复路径置起，由「用户自己拉开抽屉」那条唯一
    // 路径清掉——清的那一刻抽屉是关着的，换 spec 不会打断任何正在跑的动画。于是恢复那一次
    // 是瞬时出现，之后每次手动打开照常演 240ms 左滑。
    var drawerInstant by remember { mutableStateOf(false) }
    // 用户自己收起抽屉（遮罩、返回键）：记下的出发页一起清掉，回来时不该再弹出来。
    val closeDrawer: () -> Unit = {
        drawerOpen = false
        drawerReturnTo = null
    }
    // 抽屉里的一行：比 open 多记一件事。目标是当前页时（点会话、点新对话）不记——那不是
    // 「去了别的页」，记下来会当场被判成「回到出发页」而把抽屉又弹开。
    val openFromDrawer: (String) -> Unit = { target ->
        if (target != route) drawerReturnTo = route
        open(target)
    }
    // 回到出发的那一页就把抽屉放回去：状态要，动画不要（见 drawerInstant）。这个 effect 只挂
    // 在 route 上、不挂 drawerReturnTo：点走的那一帧，抽屉的关闭和导航都还没生效，两个键都挂
    // 着会在那一帧就把抽屉判成「该开着」又弹回来；只认路由变化，就只在真的回到那一页时触发。
    LaunchedEffect(route) {
        val from = drawerReturnTo
        if (from != null && route == from) {
            drawerReturnTo = null
            drawerInstant = true
            drawerOpen = true
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
        // 用户自己拉开抽屉（聊天页那个汉堡键，也是全仓唯一的正常开抽屉路径）：把「恢复不重放
        // 动画」那一位还回去，下一次开合照常演左滑。
        LocalOpenSidebar provides {
            drawerInstant = false
            drawerOpen = true
        },
        // The clamped keyboard height, for children that have to subtract it from an inset of
        // their own (the composer's navigation-bar padding: while the keyboard covers the
        // gesture bar that padding is dead space, which is the band the user photographed).
        LocalHeldImePx provides heldImePx,
    ) {
        // The drawer is the shell's own panel rather than a ModalNavigationDrawer, so
        // nothing else consumes BACK: without this, BACK with the drawer open finished
        // the activity and dropped the user on the home screen. 助手 opens a half-screen
        // sheet of its own again and a sheet swallows BACK first, so the drawer is the
        // only case left here — the approval card is a dialog and swallows BACK too.
        BackHandler(enabled = drawerOpen) { closeDrawer() }
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
            Box(Modifier.fillMaxSize())
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
                    // 易察觉」。这里不再有任何按目的地分叉的条件。
                    enterTransition = {
                        fadeIn(tween(NAV_FADE_MS, easing = NAV_EASING)) +
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
                    popEnterTransition = {
                        fadeIn(tween(NAV_FADE_MS, easing = NAV_EASING)) +
                            slideInHorizontally(tween(NAV_ENTER_MS, easing = NAV_EASING)) {
                                -it / NAV_SHIFT_DIVISOR
                            }
                    },
                    popExitTransition = {
                        fadeOut(tween(NAV_EXIT_MS, easing = NAV_EASING)) +
                            slideOutHorizontally(tween(NAV_EXIT_MS, easing = NAV_EASING)) {
                                it / NAV_SHIFT_DIVISOR
                            }
                    },
                ) {
                    composable("chat") { ChatScreen(viewModel = chat, onOpenSettings = { open("settings") }) }
                    composable("files") { FilesScreen(onBack = { navController.popBackStack() }) }
                    composable("tasks") { TasksScreen(onBack = { navController.popBackStack() }) }
                    // 定时任务是「到点自己跑」的那一份，与 agent 的 todo 看板（"tasks"）是两件
                    // 事：抽屉那行说的「任务」一直指的是后者，用户以为是摆设，才有了这一页。
                    composable("schedules") { SchedulesScreen(onBack = { navController.popBackStack() }) }
                    composable("settings") {
                        SettingsScreen(
                            onOpen = push,
                            onBack = { navController.popBackStack() },
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
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                    composable("capability") { CapabilityScreen(onBack = { navController.popBackStack() }) }
                    composable("tools") { ToolsScreen(onBack = { navController.popBackStack() }) }
                    composable("about") { AboutScreen(onBack = { navController.popBackStack() }) }
                    // 记忆：路由名仍是 extension，页面只剩记忆（MCP 与技能搬去了工具页）。
                    composable("extension") {
                        ExtensionScreen(onBack = { navController.popBackStack() })
                    }
                    // 用户画像：一份纯文本自述（filesDir/profile.md），随每次请求注入提示词。
                    composable("profile") {
                        ProfileScreen(onBack = { navController.popBackStack() })
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
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
            Drawer(
                open = drawerOpen,
                // 恢复那一次瞬时出现（见 drawerInstant），不重放滑入。
                instant = drawerInstant,
                route = route,
                conversations = conversations,
                activeId = activeConversationId,
                runningId = runningIds,
                onClose = closeDrawer,
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
                    drawerOpen = false
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
 */
@Composable
private fun BoxScope.Drawer(
    open: Boolean,
    /** 恢复（回到出发页）那一次：立即出现，不重放滑入动画——用户明确要的。 */
    instant: Boolean,
    route: String,
    conversations: List<Conversation>,
    activeId: String,
    runningId: Set<String>,
    onClose: () -> Unit,
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

    // 遮罩与面板同一条出场时长（120ms），而且刻意短于路由转场：抽屉里点一行时，导航是
    // 当场发出的，面板若还按 200/160ms 慢慢退，就会和新页面的入场（280ms）同帧叠着演，
    // 屏幕上同时有两条滑动（用户说「动画有点奇怪」）。缩到 120ms 让面板先退干净、页面再
    // 到位；入场保持原样（240/180），那是用户已经接受的一半。
    // instant（从别的页面返回、抽屉本来就该在那儿）连这一半都不演：遮罩与面板走同一条路，
    // 否则会出现「面板已经在了、遮罩还在淡入」的错位。
    AnimatedVisibility(
        visible = open,
        enter = if (instant) EnterTransition.None else fadeIn(tween(180)),
        exit = fadeOut(tween(120)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
        )
    }

    AnimatedVisibility(
        visible = open,
        modifier = Modifier.align(Alignment.CenterStart),
        enter = if (instant) EnterTransition.None else slideInHorizontally(tween(240)) { -it },
        exit = slideOutHorizontally(tween(120)) { -it },
    ) {
        Surface(
            modifier = Modifier
                .fillMaxHeight()
                .width(300.dp),
            shape = RoundedCornerShape(topEnd = 30.dp, bottomEnd = 30.dp),
            color = scheme.surfaceContainerHigh,
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
