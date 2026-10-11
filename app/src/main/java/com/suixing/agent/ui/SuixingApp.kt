@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.suixing.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import android.app.Activity
import android.graphics.drawable.ColorDrawable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
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
 * 全站唯一的转场规格：**旧页瞬消 + 新页带着位移滑入**，屏幕上任何时刻只有一个页面。
 *
 * - 出（exit）与返回出（popExit）是 [ExitTransition.None]：旧页当帧让位，**不与新页叠加**；
 * - 进（enter）与返回进（popEnter）是 [androidx.compose.animation.slideInHorizontally]，
 *   只走位移、**没有淡入** ⇒ 新页从第一帧就是**不透明**的。
 *
 * 为什么不能用淡化：任何淡化都必然有「两页同时可见」的中间帧 —— 新页 alpha 从 0 涨到 1 的那段
 * 时间里旧页还在它后面，叠出来就是用户说的**残影**（他原话：「这动画，我不要残影的动画」）。
 * 位移不会：新页一出现就是满的，只是还没滑到位。
 *
 * 为什么是 200ms / 屏宽÷12：用户给的 ChatGPT 录屏实测是「切换 1–2 帧完成 + 收尾约 150ms」
 * ⇒ 用「瞬消 + 200ms 位移」逼近那个手感；幅度小（约 8% 屏宽）是为了让这 200ms 读成「到位」，
 * 而不是「从屏幕外飞进来」。
 *
 * 历史（别再加回来）：交叉淡化 180/150、快速淡化 120/80、整幅横移、`scaleIn(0.96)`（会在页边
 * 露缝，露出来的是外壳 Scaffold 的 containerColor = surface，与页面底色同色，等于没缩还多一条缝）
 * —— 淡化那几版都是被「残影」否掉的。
 *
 * 抽屉不在此列：点行时它 `snapTo(Closed)`、返回来源页时 `snapTo(Open)`（同样瞬时、无叠加），
 * 用户自己开合（汉堡键 / 左缘拖动 / 点遮罩 / 返回键）仍走库的滑入滑出动画。
 */
private const val NAV_SLIDE_MS = 200

/** 新页滑入的幅度：屏宽 ÷ 这个数（12 ⇒ 约 8% 屏宽）。见上面那段规格。 */
private const val NAV_SHIFT_DIVISOR = 12

/**
 * 从抽屉进入的四个顶层目的地。
 *
 * 它们之间是**同级切换**（抽屉是开关，不是栈），所以返回时不该有位移 —— 有位移会让「返回」
 * 看起来像「又进了一页」。见 [NAV_FADE_MS]。
 */
private val DRAWER_ROUTES = setOf("files", "tasks", "schedules", "settings")

/**
 * 抽屉路线上的淡入淡出时长。用户拿 GPT 录屏对比的原话是「有一点点动画，但是很淡，就是相当于
 * 蒙版由深到没有」⇒ 只有透明度、没有位移，90–120ms 之间取 110。
 *
 * 淡入**不会**露出下面的聊天页：覆盖层自己那层不透明底（见 NavHost 的 background）挡着，
 * 淡出来的是底色而不是 chat —— 否则这就会变成用户最讨厌的那种残影。
 */
private const val NAV_FADE_MS = 110

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
    //
    // 点抽屉里的一行：抽屉**瞬间消失**，不播滑出（用户原话：「GPT 的抽屉根本就不动」——他要的是
    // "它不在那儿了"，不是"它滑走了"），页面上只发生一次淡入（见文件顶部那组 NAV_FADE_*）。
    // snapTo 而不是 close()：close 是库那条 ~256ms 的左滑；UNDISPATCHED 让这次状态写入不排到
    // 下一个主线程消息，与导航同一帧落地，否则会先看见抽屉还盖着、页面已经在后面淡入。
    // 点**当前页那一行**同样只收抽屉、不导航：`popUpTo` 会把这一页弹掉再建一份，等于整页重建。
    //
    // 同时记下"从哪一页走的、去了哪一页"：用户要**保留离开时的状态**——从抽屉点走的那一页，
    // 返回落到它时抽屉要**还是开着的**（见下面的结算块）。用户对这件事改口过很多次，最终两句
    // 合起来才是完整规格：点行时抽屉不动地消失（这句）、返回时它又在那儿（那句）。
    var drawerReturnTo by remember { mutableStateOf<String?>(null) }
    // 抽屉里点走的那一页：标记只在「还停在这一页」的期间有效。没有它，一个陈旧的名字会留到
    // 下一次无关的导航上——从覆盖层页开抽屉去别页、返回落到 chat 之后，再从聊天页顶栏点设置，
    // route 恰好等于那个陈旧名字，抽屉就会自己弹出来。
    var drawerTarget by remember { mutableStateOf<String?>(null) }
    val open: (String) -> Unit = { target ->
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            drawerState.snapTo(DrawerValue.Closed)
        }
        if (target != route) {
            drawerReturnTo = route
            drawerTarget = target
            navController.navigate(target) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }
    // A row inside the settings page pushes, so back returns to the list it came from.
    val push: (String) -> Unit = { target -> navController.navigate(target) }
    // chat 常驻（见下面 NavHost 那段），所以"回聊天"不是导航到某个目的地，而是**把覆盖层收掉**：
    // pop 到透明的 "empty" 就等于"什么都没有在盖着 chat"。
    val showChat: () -> Unit = { navController.popBackStack("empty", inclusive = false) }

    // 用户自己收起抽屉（遮罩、返回键、左缘拖动）：这是用户自己的动作，走库的动画。同时清掉
    // 出发页标记——用户手动把抽屉收了，返回时就不该再自己弹出来（"状态"是他刚亲手关掉的这个）。
    val closeDrawer: () -> Unit = {
        scope.launch { drawerState.close() }
        drawerReturnTo = null
        drawerTarget = null
    }
    // 点会话 / 新对话：与点页面行不同——那是"换我在读的那一份内容"，覆盖层收掉、抽屉也收起来，
    // 用户直接落在会话里（ChatGPT 的抽屉在这里同样会关）。这里一并清掉出发页标记。
    val chatFromDrawer: () -> Unit = {
        drawerReturnTo = null
        drawerTarget = null
        // snapTo 而不是 close()：close 是库那条 ~256ms 的左滑，那 256ms 里抽屉与遮罩正盖在刚
        // 显示的聊天页上 —— 用户报的「残影」里就有这一帧（点页面行那条路早就是 snapTo，只有
        // 这里漏了）。UNDISPATCHED 让这次状态写入与下面的导航落在同一帧；手感不变：抽屉本来
        // 就是「不在了」，不是「滑走了」。
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            drawerState.snapTo(DrawerValue.Closed)
        }
        showChat()
    }
    // 返回上一页：顶栏按钮与系统返回都走这里。
    val goBack: () -> Unit = { navController.popBackStack() }

    // 结算：离开抽屉点走的那一页时清标记；如果落点正是出发页，把抽屉**瞬时**摆回开着
    // （snapTo，不播滑入——用户要的是"状态被保留"，不是"再看一遍它滑进来"）。
    // 用 SideEffect 而不是 LaunchedEffect：它在组合完成后、布局之前跑，抽屉与目标页同一帧就位。
    // 从 route 派生（而不是让每条 pop 路径自己摆）是为了让系统返回键/手势与顶栏按钮完全一致。
    if (drawerTarget != null && route != drawerTarget) {
        val origin = drawerReturnTo
        SideEffect {
            drawerReturnTo = null
            drawerTarget = null
            if (route == origin) {
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    drawerState.snapTo(DrawerValue.Open)
                }
            }
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
        // 只在会话页可用（抽屉只属于会话页，见 ModalNavigationDrawer）：子页没有汉堡入口，
        // 这一层判断是防止以后有人把汉堡画到子页上时它还能把抽屉拉出来。
        LocalOpenSidebar provides {
            if (route == "empty") scope.launch { drawerState.open() }
        },
        // The clamped keyboard height, for children that have to subtract it from an inset of
        // their own (the composer's navigation-bar padding: while the keyboard covers the
        // gesture bar that padding is dead space, which is the band the user photographed).
        LocalHeldImePx provides heldImePx,
    ) {
        // 用户明确要求保留这一段（2026-10-11：先被自审删掉，用户看到键盘后面那块颜色变了，要求回退）。
        // 把窗口底色刷成主题 surface：键盘圆角/键盘后面那条缝露的就是这个颜色；删掉之后窗口是透明的，
        // 缝里露的是壁纸 —— 用户不要那个效果。
        val activity = LocalContext.current as? Activity
        val surfaceArgb = MaterialTheme.colorScheme.surface.toArgb()
        LaunchedEffect(surfaceArgb) {
            activity?.window?.setBackgroundDrawable(ColorDrawable(surfaceArgb))
        }
        // Painted on the window, not on the scaffold: the scaffold is inset by the keyboard, so
        // the strip behind the keyboard was left unpainted and the window's own white showed
        // through the keyboard's rounded corners as a pale seam. This box is not inset, so
        // everything the keyboard covers is already the app's own background.
        // 窗口底色不在这里管：MainActivity 明确把它设成透明（MainActivity.kt:94-100，键盘圆角要
        // 露后面那层，不该由窗口去画），这里再按 surface 画一遍就是把那条接缝换了个颜色而已。
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
                // 但**只在会话页开着**：子页（设置主页 + 六个设置子页 + 会话管理 + 权限/工具/
                // 文件/任务/定时任务/用量/关于/扩展/画像/助手）都是 push 上去的独立页面，各有返回
                // 箭头，从屏幕左缘还能拉出抽屉是错的——用户原话「设置页到底是不是单独页？为什么在
                // 设置页里面还能侧滑出侧边栏？」。只有 chat 那一层（route == "empty"）为 true。
                gesturesEnabled = route == "empty",
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
                            chatFromDrawer()
                        },
                        onOpenConversation = { conversation ->
                            chat.openConversation(conversation)
                            chatFromDrawer()
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
                        onNavigate = open,
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
                    // chat 常驻：NavHost 在离开一个目的地之后会把它**销毁**，pop 回来时是从零
                    // 重新组合的（长列表重建、markdown 重解析）——那正是用户说的「返回像重新加载」，
                    // 三处 rememberSaveable 只救了状态、救不了重建。所以 chat 移出 NavHost、永远
                    // 留在组合里，其余页面改成**盖在它上面的覆盖层**：进设置是往覆盖层 push，
                    // 返回是把覆盖层 pop 掉 ⇒ 揭开的就是原来那份 chat，零重建、零重载。
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        ChatScreen(viewModel = chat, onOpenSettings = { open("settings") })
                        NavHost(
                            navController = navController,
                            // 覆盖层的栈底：什么都不画（透明），所以"当前是 empty"就等于"chat 露着"。
                            startDestination = "empty",
                            modifier = Modifier
                                .fillMaxSize()
                                // 覆盖层自己不透明：NavHost 是透明的，而「新页还没画出第一帧」
                                // （新页是一次全新组合，重页面尤其明显）或「状态栏/导航栏那一圈
                                // 没被页面画满」时，露出来的就是下面那份常驻的 chat —— 用户报的
                                // 「残影」就是这层露底，不是交叉淡化。这一层保证「要么是 chat、
                                // 要么是新页」，不存在中间态；route == "empty"（没有覆盖层）时
                                // 保持透明，chat 照旧可见。
                                .background(
                                    if (route == "empty") {
                                        Color.Transparent
                                    } else {
                                        MaterialTheme.colorScheme.background
                                    },
                                )
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
                            // 旧页瞬消 + 新页位移滑入（详见文件顶部那段规格）：exit 是 None ⇒ 旧页
                            // 当帧让位、不与新页叠加；enter 只走位移、没有淡入 ⇒ 新页从第一帧就是
                            // 不透明的，所以任何一帧都只有一个页面，结构上不可能有残影。
                            enterTransition = {
                                if (targetState.destination.route in DRAWER_ROUTES) {
                                    // 抽屉 → 页面：同级切换，只淡不滑。
                                    fadeIn(tween(NAV_FADE_MS))
                                } else {
                                    slideInHorizontally(
                                        animationSpec = tween(
                                            durationMillis = NAV_SLIDE_MS,
                                            easing = FastOutSlowInEasing,
                                        ),
                                    ) { fullWidth -> fullWidth / NAV_SHIFT_DIVISOR }
                                }
                            },
                            exitTransition = { ExitTransition.None },
                            popEnterTransition = {
                                // 判据是**被弹掉的那一页**（initialState）：弹掉的是抽屉路线（设置 →
                                // chat）就只淡不滑；弹掉的是设置子页（模型 → 设置）就走位移 —— 用户
                                // 要的「各页返回要有动画」正是后者，之前 popEnter 与 enter 同向，
                                // 返回看起来只是「又进了一页」。
                                if (initialState.destination.route in DRAWER_ROUTES) {
                                    fadeIn(tween(NAV_FADE_MS))
                                } else {
                                    slideInHorizontally(
                                        animationSpec = tween(
                                            durationMillis = NAV_SLIDE_MS,
                                            easing = FastOutSlowInEasing,
                                        ),
                                    ) { fullWidth -> -fullWidth / NAV_SHIFT_DIVISOR }
                                }
                            },
                            popExitTransition = {
                                // 返回抽屉路线（设置 → chat）：让**离开的那一页淡出**，而不是当帧消失。
                                // 用户要的「蒙版由深到没有」就是这一条 —— 原来是 None ⇒ 硬切，读起来
                                // 就是「返回时闪一下」（与「开闭动画生硬」是同一个病）。
                                //
                                // 为什么**不动**那层底色（它在 pop 期间本来就是透明的）：返回的目标是
                                // 聊天页。底色若在转场期间保持不透明，淡出时露出来的是**底色**而不是
                                // chat，等底色终于变透明时 chat 再「啪」地出现 —— 那是另一种闪。
                                // 现在这样：设置页在半透明状态里溶进聊天页，全程没有一帧是纯底色。
                                if (initialState.destination.route in DRAWER_ROUTES) {
                                    fadeOut(tween(NAV_FADE_MS))
                                } else {
                                    // 子页返回（模型 → 设置）：父页从左侧滑回来盖住它，不需要淡出。
                                    ExitTransition.None
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
                            ).forEach { route ->
                                composable(route) {
                                    SettingsPageRoute(
                                        route = route,
                                        onOpen = push,
                                        onBack = goBack,
                                        // 「打开」一个会话 = 切到它 + 收掉覆盖层，与抽屉里点会话
                                        // 完全同一条路（chat 常驻，所以这不是一次导航）。
                                        onOpenConversation = { id ->
                                            conversations.firstOrNull { it.id == id }
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
                        // 抽屉开着时 BACK 先关抽屉：ModalNavigationDrawer 只接管预测性返回的手势，
                        // 普通 BACK 会交给 Activity，不收这一句就会退出应用。位置必须在 NavHost
                        // **之后**：OnBackPressedDispatcher 是后注册者优先（LIFO），注册在 NavHost
                        // 之前时，覆盖层页上按 BACK 会先被 NavHost 的回调 pop 掉、抽屉反而留着——
                        // 而这里的意图是"抽屉先关"。助手那张半屏表自己吞 BACK，审批卡是 dialog 也吞，
                        // 所以只剩抽屉这一种。chat 上（route == "empty"）NavHost 的回调不消费，
                        // BACK 仍落到 Activity ⇒ 退出应用，不会多出一层空页。
                        BackHandler(enabled = drawerState.isOpen) { closeDrawer() }
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
    onRenameConversation: (Conversation, String) -> Unit,
    onTogglePin: (Conversation) -> Unit,
    consoleOn: Boolean,
    onToggleConsole: () -> Unit,
    onNavigate: (String) -> Unit,
    onAssistants: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Every delete entry in the drawer funnels through this one confirmation: a swipe
    // of the thumb on a row must not be able to erase a conversation outright.
    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }
    var pendingRename by remember { mutableStateOf<Conversation?>(null) }
    // 长按出来的菜单：只记「哪一行的菜单开着」，于是抽屉里同时最多只有一个菜单。
    var menuFor by remember { mutableStateOf<String?>(null) }
    // 导出与「设置 → 会话」页同一套：系统选择器（SAF），不碰 FileProvider、不改 manifest。
    var pendingExport by remember { mutableStateOf<Conversation?>(null) }
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
            // 抽屉里没有 SnackbarHost（它是一块面板，不是一个 Scaffold），所以这里用 Toast：
            // 「复制/导出到底成没成」必须当场看见，否则用户只能靠猜。
            Toast.makeText(
                context,
                if (ok) "已导出「${chat.title}」" else "导出失败，换个位置再试",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
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
                // 置顶只在抽屉里排：仓库那份列表仍是时间序（会话管理页与「上次打开」都吃它），
                // 这里换的是展示顺序，不是数据的顺序。
                val ordered = conversations.sortedWith(
                    compareByDescending<Conversation> { it.pinned }
                        .thenByDescending { it.updatedAt },
                )
                items(ordered, key = { it.id }) { conversation ->
                    ConversationRow(
                        conversation = conversation,
                        selected = conversation.id == activeId,
                        running = conversation.id in runningId,
                        menuOpen = menuFor == conversation.id,
                        onClick = { onOpenConversation(conversation) },
                        onLongClick = { menuFor = conversation.id },
                        onMenuDismiss = { menuFor = null },
                        onRename = {
                            menuFor = null
                            pendingRename = conversation
                        },
                        onDelete = {
                            menuFor = null
                            pendingDelete = conversation
                        },
                        onTogglePin = {
                            menuFor = null
                            onTogglePin(conversation)
                        },
                        onCopyTitle = {
                            menuFor = null
                            copyTitle(context, conversation.title)
                        },
                        onExport = {
                            menuFor = null
                            pendingExport = conversation
                            exporter.launch(exportName(conversation.title))
                        },
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
    // 改名：空标题或取消 ⇒ 一个字都不改（这里没有「清空标题」这种需求，标题是行的身份）。
    pendingRename?.let { target ->
        var draft by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("标题") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRename = null
                        onRenameConversation(target, draft)
                    },
                    enabled = draft.isNotBlank(),
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { pendingRename = null }) { Text("取消") } },
        )
    }
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            // 与「设置 → 会话」页同一句话：说清删掉的是什么、有多少、能不能撤销。
            title = { Text("删除这个会话？") },
            text = {
                Text(
                    text = "「${target.title}」的聊天记录（${target.messages.size} 条消息）会被永久删除，" +
                        "不能撤销。工作区里的文件不受影响。",
                    maxLines = 6,
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

/**
 * 复制标题到剪贴板。抽屉里没有 SnackbarHost（它是一块面板，不是 Scaffold），所以用 Toast
 * 报结果 —— 与导出同一条理由：这类动作「成没成」必须当场看得见。
 */
private fun copyTitle(context: android.content.Context, title: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    // 服务拿不到时不能报「已复制」：这条提示只有真的写进去了才该出现。
    val ok = clipboard != null &&
        runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("Suixing", title)) }.isSuccess
    Toast.makeText(
        context,
        if (ok) "已复制标题" else "复制失败",
        Toast.LENGTH_SHORT,
    ).show()
}

/**
 * 导出文件名。与「设置 → 会话」页那个同名函数同一形状（标题 + .json），只是它是 private 的，
 * 这里用不了；两处都只做一件事：把标题里能当路径用的字符换掉。
 */
private fun exportName(title: String): String {
    val clean = title.replace('/', '／').replace('\\', '＼').trim()
    return (clean.ifEmpty { "会话" }) + ".json"
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    selected: Boolean,
    running: Boolean,
    menuOpen: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenuDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    onCopyTitle: () -> Unit,
    onExport: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // 短按打开、长按出菜单：一行上只挂这一个手势。菜单里的每一项都走抽屉里已经有的那几条路
    // （删除仍旧进 pendingDelete 那个二次确认）。Surface(onClick) 不支持长按，所以点击交给
    // combinedClickable，Surface 只负责形状与底色；clip 是为了让水波纹跟着圆角走。
    Box {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .clip(MaterialTheme.shapes.medium)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
                // 置顶标记：仓库里**没有** ic_pin，按约定复用既有的 ic_star（不新造 drawable）。
                // 放标题**左边**：右边是删除按钮，夹在中间会被读成「点了会取消置顶」。
                if (conversation.pinned) {
                    Icon(
                        painter = painterResource(R.drawable.ic_star),
                        contentDescription = "已置顶",
                        tint = scheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
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
        // 长按出来的菜单。锚在这一行上（Box 内），所以它贴着被长按的那一条弹出；
        // 删除排在最后，与「设置 → 会话」页那两个动作的顺序一致。
        DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuDismiss) {
            DropdownMenuItem(text = { Text("改名") }, onClick = onRename)
            DropdownMenuItem(
                text = { Text(if (conversation.pinned) "取消置顶" else "置顶") },
                onClick = onTogglePin,
            )
            DropdownMenuItem(text = { Text("复制标题") }, onClick = onCopyTitle)
            DropdownMenuItem(text = { Text("导出") }, onClick = onExport)
            DropdownMenuItem(text = { Text("删除") }, onClick = onDelete)
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
