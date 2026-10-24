@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.suixing.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.OutlinedTextField
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.ripple
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
import kotlin.math.roundToInt
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.IntOffset
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
import com.suixing.agent.data.Assistant
import com.suixing.agent.ui.assistant.AssistantEditor
import com.suixing.agent.ui.assistant.AssistantSheet
import com.suixing.agent.ui.components.FullScreenOverlay
import com.suixing.agent.ui.components.NAV_ENTER_MS
import com.suixing.agent.ui.components.NAV_EXIT_MS
import com.suixing.agent.ui.components.NAV_SHIFT_DIVISOR
import com.suixing.agent.ui.capability.CapabilityScreen
import com.suixing.agent.ui.chat.ChatScreen
import com.suixing.agent.ui.chat.ChatViewModel
import com.suixing.agent.ui.chat.MenuCard
import com.suixing.agent.ui.components.LocalHeldImePx
import com.suixing.agent.ui.components.LocalSetSelectionActive
import com.suixing.agent.ui.components.LocalOpenSidebar
import com.suixing.agent.ui.components.SuixingMark
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
    val baseShift by animateFloatAsState(
        targetValue = if (route == "empty") 0f else 1f,
        animationSpec = tween(NAV_PUSH_MS, easing = FastOutSlowInEasing),
        label = "baseShift",
    )
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
    // 抽屉里点走的那一页：标记只在「还停在这一页」的期间有效。没有它，一个陈旧的名字会留到
    // 下一次无关的导航上——从覆盖层页开抽屉去别页、返回落到 chat 之后，再从聊天页顶栏点设置，
    // route 恰好等于那个陈旧名字，返回动画的判据就会错判。
    var drawerTarget by remember { mutableStateOf<String?>(null) }
    val open: (String) -> Unit = { target ->
        // **不关抽屉**（用户：「不用关掉侧边栏」「新页不应该在页面都最上面移动的吗？」）：
        // 覆盖层现在画在 ModalNavigationDrawer 之上（见下面 Scaffold/Box 那段），所以新页是
        // 压着抽屉滑进来的，抽屉保持打开、原样留在下面 —— 返回时揭开的就是它，状态天然保留。
        if (target != route) {
            // drawerTarget 只用来决定"返回时要不要播动画"（见 popExitTransition）：从抽屉点进的
            // 那一页，返回要瞬时揭开出发页。
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
        drawerTarget = null
    }
    // 点会话 / 新对话：与点页面行不同——那是"换我在读的那一份内容"，覆盖层收掉、抽屉也收起来，
    // 用户直接落在会话里（ChatGPT 的抽屉在这里同样会关）。这里一并清掉出发页标记。
    val chatFromDrawer: () -> Unit = {
        drawerTarget = null
        // 0.8.91 原样：与 open() 同一条（snapTo + UNDISPATCHED）。
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            drawerState.snapTo(DrawerValue.Closed)
        }
        showChat()
    }
    // 返回上一页：顶栏按钮与系统返回都走这里。
    val goBack: () -> Unit = { navController.popBackStack() }

    // 结算：离开抽屉点走的那一页时清标记。抽屉不再需要"摆回开着"——它从头到尾没关过
    // （见 open()），返回时揭开的就是原来那份。
    if (drawerTarget != null && route != drawerTarget) {
        SideEffect { drawerTarget = null }
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
    // 长按/选择期间冻结 clamp（用户：「抬起时对话框与输入法的高度有时变得很大」）：抬手那一刻
    // 选择手柄/菜单出现，这台 ROM 把它的高度报进 IME inset，clamp 一接受、输入框底垫就暴涨。
    // 守护窗（按下到抬手后 400ms）里不接受任何 inset 变化；窗口外照常。
    var selectionActive by remember { mutableStateOf(false) }
    LaunchedEffect(imeRawPx, selectionActive) {
        if (selectionActive) return@LaunchedEffect
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
        LocalSetSelectionActive provides { active -> selectionActive = active },
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
            Box(
                Modifier
                    .matchParentSize()
                    .padding(bottom = with(rootDensity) { heldImePx.toDp() })
                    .background(MaterialTheme.colorScheme.surface),
            )
            // 推进时下面那层（抽屉 + 聊天）跟着往左让 10%：**两层一起动**才读成"推上来"，
            // 只让新页动会像贴上去。只用位移、不做透明度 —— 淡化会露出下面一层，就是残影。
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = -size.width * 0.10f * baseShift },
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
                // chat 常驻：NavHost 在离开一个目的地之后会把它**销毁**，pop 回来时是从零
                // 重新组合的（长列表重建、markdown 重解析）——那正是用户说的「返回像重新加载」，
                // 三处 rememberSaveable 只救了状态、救不了重建。所以 chat 移出 NavHost、永远
                // 留在组合里，其余页面改成**盖在它上面的覆盖层**：进设置是往覆盖层 push，
                // 返回是把覆盖层 pop 掉 ⇒ 揭开的就是原来那份 chat，零重建、零重载。
                // 键盘让位只作用在聊天内容上（抽屉不再被顶起，见上面 Scaffold 的说明）。
                Box(Modifier.padding(bottom = with(rootDensity) { heldImePx.toDp() })) {
                    // **首启的引导还没走完时，先别组合 chat**（用户：「第一次出现引导页感觉卡卡的」）。
                    // 冷启动时 chat 要做 turnPage、receipts/transcript 统计、每条消息的 Markdown
                    // 渲染（ChatScreen.kt:465/512/560/621、ChatBlocks.kt:895/1519），
                    // 与引导层 200ms 的入场动画抢同一条主线程 ⇒ 首帧掉帧。引导是全屏覆盖层，
                    // 底下的 chat 这时看不见 ⇒ 不组合它没有代价。
                    //
                    // **只在首启时这样**：设置里「重新看一遍引导」时 chat 已经在组合里，
                    // 盖一层引导不能把常驻的 chat 拆了。
                    if (!(showOnboarding && onboardingIsFirstRun)) {
                        ChatScreen(viewModel = chat, onOpenSettings = { open("settings") })
                    }
                }
            }
            }
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
                                // 设置里的子页（push 进的）返回走推入的反向。判据用 drawerTarget：
                                // open() 置位、结算块在 route 离开它时清掉，所以 pop 那一刻它仍指向被退的页。
                                // 这条 lambda 是接收者形式（AnimatedContentTransitionScope），被退的
                                // 目的地在 initialState 上，不是 lambda 参数。
                                if (drawerTarget != null &&
                                    initialState.destination.route == drawerTarget
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
                        // **只在会话页**接管 BACK：抽屉现在不随导航关闭（见 open()），覆盖层页
                        // 盖在它上面时抽屉可能还开着 —— 那种情况下 BACK 应该先退页，不是去关一个
                        // 看不见的抽屉。route != "empty" 时这里让位，交给 NavHost 自己的返回。
                        BackHandler(enabled = drawerState.isOpen && route == "empty") { closeDrawer() }
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

    // 首次启动引导：与助手编辑器同一个位置——盖在导航宿主与抽屉之上的一切。
    // AnimatedVisibility 只在 visible 为真（或退场那 200ms）时组合内容，所以引导页
    // 不显示的时候它一行代码都不跑。
    FullScreenOverlay(visible = showOnboarding) {
        OnboardingHost(onClose = { showOnboarding = false })
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
            val servers by Core.mcp.servers.collectAsState()
            val toolNames = remember(servers) { Core.tools.names() }
            val target = settings.assistants.firstOrNull { it.id == id }
                ?: Assistant(id = id, avatar = "🧭")
            AssistantEditor(
                initial = target,
                settings = settings,
                toolNames = toolNames,
                backEnabled = editorAssistantId != null,
                onSave = {
                    chat.saveAssistant(it)
                    editorAssistantId = null
                },
                onDelete = if (settings.assistants.size > 1) {
                    {
                        chat.deleteAssistant(target)
                        editorAssistantId = null
                    }
                } else {
                    null
                },
                onDismiss = { editorAssistantId = null },
            )
        }
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
    onToggleArchive: (Conversation) -> Unit,
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
    // 抽屉的手动顺序：置顶区/普通区各一张 id 表（见 DrawerOrderStore 的注释）。
    // 它是查看偏好而不是会话数据，所以既不挂在 Conversation 上，也不进存储格式。
    val orderStore = remember(context) { DrawerOrderStore(context) }
    val orderState = remember { mutableStateOf(DrawerOrder()) }
    // 排序模式：抽屉头部那枚把手开关。开着时行尾的垃圾桶换成拖拽把手 ——
    // 长按行仍然是菜单，两个手势各占一处，不互相抢。
    var sortMode by remember { mutableStateOf(false) }
    val draggingState = remember { mutableStateOf<String?>(null) }
    val dragTracker = remember { DrawerDragTracker() }
    val listState = rememberLazyListState()
    // 每行上次看到的 updatedAt：数值变大 = 那条会话来了新消息 ⇒ 把它从普通表里摘掉，
    // 它就按 updatedAt 冒回普通区顶部（用户：「新会话照常出现在普通会话上面」）。
    val lastSeen = remember { HashMap<String, Long>() }
    var orderDirty by remember { mutableStateOf(false) }
    // 手势回调里要读最新的会话列表与分区，但 pointerInput 的 block 只能建一次
    // （中途换实例会把进行中的手势取消），所以这两份最新值走 rememberUpdatedState 转发。
    val conversationsNow = rememberUpdatedState(conversations)
    // **先 remember 再转发**（性能审计 #5）：原来把 `drawerSections(...)` 写在
    // `rememberUpdatedState` 的参数位 —— 那只包住了「转发最新值」，没包住计算，
    // 于是外壳每次重组都白算一遍 filter + associateBy + 2×sortedBy（O(n log n)）。
    // 同文件 :1001 已是正确写法。
    val sections = remember(conversations, orderState.value) {
        drawerSections(conversations, orderState.value)
    }
    val sectionsNow = rememberUpdatedState(sections)
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
                    // **从 store 导出**（#40 之后磁盘上已经没有 `<id>.json`）：原来这里直接读旧文件，
                    // 旧格式删掉后它必然不存在 ⇒ 每次导出都失败。`exportJson` 会拼出完整 JSON。
                    val text = Core.chats.exportJson(chat.id) ?: error("读不到会话内容")
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray())
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
        } else {
            // 抽屉一关，排序模式就失效：它是一次性模式，不该跨次拉开残留
            // （用户：「进行了长按等其它操作（例如关闭侧边栏），自动退出拖动模式」）。
            sortMode = false
            draggingState.value = null
            // 抽屉一关，长按菜单也不该留在屏幕上（它是 focusable = false 的弹窗，不会自己消失）。
            menuFor = null
        }
    }

    // 上次手动排的顺序。读失败 = 空表 = 退回时间序：偏好文件坏了不该拦着人看会话列表。
    LaunchedEffect(Unit) { orderState.value = orderStore.load() }

    // 落盘分两个入口：拖动松手、切换置顶是明确操作，立刻写；「新消息回顶」只挪基线，走 debounce。
    val persistOrder: () -> Unit = remember {
        {
            val snapshot = orderState.value.prunedTo(conversationsNow.value.map { it.id }.toSet())
            scope.launch { orderStore.save(snapshot) }
        }
    }

    // 会话列表一变就做两件事：剔除已删除的 id（残留 id 会在列表里占位，看着像排序没生效），
    // 以及把「updatedAt 变大」的那几行从普通表里摘掉。
    LaunchedEffect(conversations) {
        if (conversations.isEmpty()) return@LaunchedEffect
        val known = conversations.map { it.id }.toHashSet()
        val current = orderState.value
        var next = current.prunedTo(known)
        val bumped = conversations
            .filter { chat -> lastSeen[chat.id]?.let { chat.updatedAt > it } == true }
            .map { it.id }
        conversations.forEach { lastSeen[it.id] = it.updatedAt }
        val dropped = bumped.filter { it in next.normal }
        if (dropped.isNotEmpty()) next = next.copy(normal = next.normal.filterNot { it in dropped })
        if (next != current) orderState.value = next
        if (dropped.isNotEmpty()) orderDirty = true
    }

    // 「新消息回顶」攒 1.5s 落一次盘：应用被杀最多丢这一次位置修正；用户拖出来的顺序是
    // 松手即写，不会丢。
    LaunchedEffect(orderDirty) {
        if (!orderDirty) return@LaunchedEffect
        delay(1500)
        orderStore.save(orderState.value.prunedTo(conversationsNow.value.map { it.id }.toSet()))
        orderDirty = false
    }

    // 「排序模式是一次性模式」：任何非拖动的交互都从这一个口子关掉它，
    // 免得一半路径忘了关、模式残留到下一次拉开抽屉。
    val exitSortMode: () -> Unit = {
        sortMode = false
        draggingState.value = null
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
                // **不要 imePadding()**（issue #1：长按改名弹窗拉起输入法时，背后侧边栏变空）：
                // manifest 是 adjustNothing（窗口不随键盘缩放），但 IME inset 仍会报给 Activity，
                // `imePadding()` 于是给这个 fillMaxHeight 的抽屉底部垫上整块键盘高度
                // （约 1000px）⇒ 内容区被压到接近 0 ⇒ 列表看起来被清空。
                // 抽屉是整高的侧边面板，键盘盖住底部时列表本身可以滚，不需要让位。
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
                // 排序开关：把手图标 = 「拖」这件事本身。开着时行尾的垃圾桶换把手，
                // 于是「拖」与「长按菜单」各占一处，不用在同一个手势里二选一。
                IconButton(
                    onClick = { sortMode = !sortMode },
                    modifier = Modifier.size(38.dp),
                ) {
                    DrawerGripIcon(
                        tint = if (sortMode) scheme.primary else scheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
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

            // 置顶区在上、普通区在下（用户：「置顶的就是最上面的呀」）。顺序来自两张 id 表：
            // 置顶区只在置顶表里挪；普通区「新会话/新消息在前 + 手动基线」；跨区拖动在
            // reorderWithinSection 里被直接拒掉，所以怎么拖都越不过置顶那条线。
            // 排序要 filter + associateBy + 两次 sortedBy：不该每次重组都重算。
            val sections = remember(conversations, orderState.value) {
                drawerSections(conversations, orderState.value)
            }
            // 两个区块共用同一行（只差顺序，不差样子）：抽成局部 lambda，免得把二十来行
            // 回调抄两遍。animateItem 只在 item 作用域里拿得到，所以由调用处传进来。
            val drawerRow: @Composable (Conversation, Modifier) -> Unit = { conversation, anim ->
                // 把手的拖动手势建一次就固定住：手势进行中如果 block 换实例，Compose 会把
                // 这次拖动取消，于是「拖到一半断了」。
                val dragHandle = remember(conversation.id) {
                    Modifier.pointerInput(conversation.id) {
                        detectDragGestures(
                            onDragStart = {
                                val row = listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.key == conversation.id }
                                if (row != null) {
                                    draggingState.value = conversation.id
                                    dragTracker.pointerY = row.offset + row.size / 2f
                                }
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                val dragging = draggingState.value
                                if (dragging != null) {
                                    dragTracker.pointerY += amount.y
                                    val hit = listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull {
                                            dragTracker.pointerY >= it.offset &&
                                                dragTracker.pointerY <= it.offset + it.size
                                        }
                                        ?.key as? String
                                    if (hit != null) {
                                        val reordered = reorderWithinSection(
                                            order = orderState.value,
                                            sections = sectionsNow.value,
                                            dragging = dragging,
                                            target = hit,
                                        )
                                        if (reordered != null) orderState.value = reordered
                                    }
                                }
                            },
                            onDragEnd = {
                                if (draggingState.value != null) persistOrder()
                                draggingState.value = null
                            },
                            onDragCancel = { draggingState.value = null },
                        )
                    }
                }
                ConversationRow(
                    conversation = conversation,
                    selected = conversation.id == activeId,
                    running = conversation.id in runningId,
                    menuOpen = menuFor == conversation.id,
                    sortMode = sortMode,
                    dragging = draggingState.value == conversation.id,
                    dragHandleModifier = dragHandle,
                    modifier = anim,
                    // 点行、长按、以及菜单里的每一个动作都先退出排序模式：用户要的是
                    // 「做了别的事就别再停在拖动态」。
                    onClick = {
                        exitSortMode()
                        onOpenConversation(conversation)
                    },
                    onLongClick = {
                        exitSortMode()
                        menuFor = conversation.id
                    },
                    onMenuDismiss = {
                        exitSortMode()
                        menuFor = null
                    },
                    onRename = {
                        exitSortMode()
                        menuFor = null
                        pendingRename = conversation
                    },
                    onDelete = {
                        exitSortMode()
                        menuFor = null
                        pendingDelete = conversation
                    },
                    onTogglePin = {
                        exitSortMode()
                        menuFor = null
                        // 置顶就追加到置顶表末尾（置顶区有自己的顺序）；取消置顶只从置顶表
                        // 摘掉，于是它按 updatedAt 回到普通区该在的位置。
                        orderState.value = pinToggled(orderState.value, conversation)
                        persistOrder()
                        onTogglePin(conversation)
                    },
                    onToggleArchive = {
                        exitSortMode()
                        menuFor = null
                        onToggleArchive(conversation)
                    },
                    onCopyTitle = {
                        exitSortMode()
                        menuFor = null
                        copyTitle(context, conversation.title)
                    },
                    onExport = {
                        exitSortMode()
                        menuFor = null
                        pendingExport = conversation
                        exporter.launch(exportName(conversation.title))
                    },
                )
            }
            // 置顶区空着时连标签都不出现：空标签只会让人以为还有东西没加载出来。
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
            ) {
                if (sections.pinned.isNotEmpty()) {
                    item(key = "drawer-pinned-label") {
                        Text(
                            text = "置顶",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                        )
                    }
                    items(sections.pinned, key = { it.id }) { // 只保留位移动画：默认外观动画会让**滚进视口的每一行**都跑一段淡入，滚动时每行都带一个动画（子代理审计 B2）。
drawerRow(
                        it,
                        Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                    ) }
                    if (sections.normal.isNotEmpty()) {
                        item(key = "drawer-section-divider") {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                                color = scheme.outlineVariant,
                            )
                        }
                    }
                }
                items(sections.normal, key = { it.id }) { // 只保留位移动画：默认外观动画会让**滚进视口的每一行**都跑一段淡入，滚动时每行都带一个动画（子代理审计 B2）。
drawerRow(
                        it,
                        Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                    ) }
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
                    // **用 meta 里的条数**：列表只带 meta，`messages` 恒为空（#30）⇒ 原来这里永远显示 0 条。
                    text = "「${target.title}」的聊天记录（${target.messageCount} 条消息）会被永久删除，" +
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
    onToggleArchive: () -> Unit,
    onCopyTitle: () -> Unit,
    onExport: () -> Unit,
    /** 排序模式：行尾的垃圾桶换成拖拽把手（两个手势各占一处，长按行仍是菜单）。 */
    sortMode: Boolean = false,
    /** 正在被拖动的那一行：给一层浅底，让「拖住了」看得见。 */
    dragging: Boolean = false,
    dragHandleModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    // 菜单贴哪边、朝上还是朝下，都要先知道这一行在窗口里的位置：
    // · 水平——Panel 的 Popup 以父节点为锚，父节点就是下面那个「与面板同宽、贴住行右下角」的
    //   空盒子，于是面板左缘落在行右缘（用户：「在哪里长按，弹窗都靠近左侧」）；
    // · 垂直——下面是列表底部时朝上翻，翻多少取决于这一行有多高。
    val density = LocalDensity.current
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val gapPx = with(density) { 4.dp.toPx() }
    val menuHeightPx = with(density) { DrawerMenuHeight.toPx() }
    // 导航栏与安全区不落在 screenHeightDp 里，判断「下面还放得下吗」时留一截余量。
    val bottomInsetPx = with(density) { 72.dp.toPx() }
    var rowBottomPx by remember { mutableStateOf(0f) }
    var rowHeightPx by remember { mutableStateOf(0f) }
    val openUp = rowBottomPx + menuHeightPx + gapPx > screenHeightPx - bottomInsetPx
    // 短按打开、长按出菜单：一行上只挂这一个手势。菜单里的每一项都走抽屉里已经有的那几条路
    // （删除仍旧进 pendingDelete 那个二次确认）。Surface(onClick) 不支持长按，所以点击交给
    // combinedClickable，Surface 只负责形状与底色；clip 是为了让水波纹跟着圆角走。
    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            rowBottomPx = coordinates.boundsInWindow().bottom
            rowHeightPx = coordinates.size.height.toFloat()
        },
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .clip(MaterialTheme.shapes.medium)
                // 排序模式下这一行没有长按：把手上的拖动和行上的长按是同一只手指上的两个
                // handler，按住把手超过 500ms 会先被判成长按（用户：「拖把手容易误触菜单」）。
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (sortMode) null else onLongClick,
                ),
            shape = MaterialTheme.shapes.medium,
            // 拖动中被拎起来的那一行给一层浅底：否则「行在跟手」只能靠相邻行换位去猜。
            color = when {
                selected -> scheme.primary.copy(alpha = 0.16f)
                dragging -> scheme.surfaceContainerHighest
                else -> Color.Transparent
            },
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
                // 排序模式下，行尾换成拖拽把手：两枚控件同尺寸同位置，切换时列表不会跳。
                if (sortMode) {
                    Box(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(36.dp)
                            // 把手上的短按不打开会话：它是拖动的起手处，轻轻一点不该跳进会话。
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {},
                            )
                            .then(dragHandleModifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        DrawerGripIcon(
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    DrawerDeleteButton(running = running, onDelete = onDelete)
                }
            }
        }
        // 点面板外面关闭、按返回关闭。
        // MenuCard 的弹窗是 focusable = false（它自己的注释写了原因：抢焦点的弹窗会把输入法从
        // 输入框上拿走），这样的窗口既看不到「点在外面」也看不到 BACK —— 两件事都由调用方接，
        // 写法照 ChatTopBar 那张用量卡：遮罩是另一个弹窗，且**先**组合（弹窗按组合顺序叠）。
        if (menuOpen) {
            Popup(properties = PopupProperties(focusable = false)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onMenuDismiss() },
                )
            }
        }
        BackHandler(enabled = menuOpen) { onMenuDismiss() }

        // 面板就是输入框旁「添加」那个弹层本身：`ui/chat/ChatComposer.kt:307-362` 的 MenuCard
        // （surface 底 + 20dp 圆角 + 8dp 的 shadow(ambient/spot 25% 黑) + 160ms 缩放淡入）。
        // 它已经是 `internal` 的共用组件，这里直接调用 —— 不是照着参数再画一遍，所以底色、圆角、
        // 阴影质感、内边距、进场动画不可能再出现第二个版本（用户：「阴影质感都很高级」）。
        // 锚点仍是「与面板同宽、0 高、贴住行的右下角」的空盒子；MenuCard 的 Popup 以父节点为锚，
        // 面板左缘于是落在行右缘。
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .width(DrawerMenuWidth)
                .height(0.dp),
        ) {
            MenuCard(
                visible = menuOpen,
                onDismiss = onMenuDismiss,
                // 下面放得下：TopStart，面板左上角贴住锚点（= 行的右下角）朝下长；
                // 放不下：BottomStart，面板左下角贴住锚点，再往上让出这一行的高度，朝上长。
                alignment = if (openUp) Alignment.BottomStart else Alignment.TopStart,
                offset = if (openUp) {
                    IntOffset(0, -(rowHeightPx + gapPx).roundToInt())
                } else {
                    IntOffset(0, gapPx.roundToInt())
                },
                modifier = Modifier.width(DrawerMenuWidth),
                focusable = false,
            ) {
                DrawerMenuAction("改名", onClick = onRename)
                DrawerMenuAction(
                    if (conversation.pinned) "取消置顶" else "置顶",
                    onClick = onTogglePin,
                )
                // 归档：从抽屉列表里收起来，数据留着；取消归档在「设置 → 会话管理」那一页。
                DrawerMenuAction(
                    if (conversation.archived) "取消归档" else "归档",
                    onClick = onToggleArchive,
                )
                DrawerMenuAction("复制标题", onClick = onCopyTitle)
                DrawerMenuAction("导出", onClick = onExport)
                DrawerMenuAction("删除", destructive = true, onClick = onDelete)
            }
        }
    }
}

/** 抽屉长按菜单的面板宽度：锚点与面板同宽，菜单才贴着行的右缘弹出（见 ConversationRow）。 */
private val DrawerMenuWidth = 200.dp

/**
 * 面板高度：6 个 40dp 的项 + MenuCard 的上下各 14dp 内边距（项之间不再有外层 padding）。
 * 只用来判断「下面还放得下吗」，放不下就朝上翻；项数改了这里要跟着改。
 */
private val DrawerMenuHeight = 40.dp * 6 + 28.dp

/**
 * 抽屉长按菜单里的一行：尺寸与反馈都照对话框里那颗 `TextButton` 抄。
 *
 * 为什么不用 `DropdownMenuItem`：它自带 M3 的列表语言（17sp Regular、48dp 行高、整行方角
 * 涟漪、onSurface 文字色），和对话框按钮（14sp Medium、40dp、胶囊涟漪、主色）摆在一起就是
 * 两套东西 —— 用户点名的正是这个不一致。尺寸集中在这个函数里，将来对话框若改，两处一起改。
 *
 * 字色：常规项用 onSurface（就是那个弹层的正文色；用户看过主色那版说「为什么是绿色的字」），
 * 破坏性项仍用 error（用户明确说「删除的红色没毛病」）。
 */
@Composable
private fun DrawerMenuAction(
    text: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // 缩进与「添加」弹层里那些行是**同一组数字**（`ui/chat/ChatComposer.kt:420-455` 的 MenuItemRow）：
    // 行自己只留 4dp 左右内边距，其余空白全部由 MenuCard 自带的 18dp 提供。
    // 左边距因此是 18 + 4 = 22dp。改之前那版是给 DropdownMenu 用的（它自带 12dp 留白）：
    // 外层 8dp + 内层 16dp 叠成 42dp，用户看到的就是「项离左边太远」。外层 padding 已整层删掉。
    // clip 与触感也照 MenuItemRow：shapes.small + ripple()（不再是整行胶囊），涟漪正好铺满可见形状。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            )
            .height(40.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (destructive) scheme.error else scheme.onSurface,
            maxLines = 1,
        )
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
    // 尺寸一组（用户：「会话列表下方的五个按钮，整体小一点」）：行高 46dp、图标 20dp、
    // 字号 14sp、行内左右 14dp、行间 1dp —— 四处同比缩 18% 左右，46dp 是可点高度的底线。
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) scheme.primary.copy(alpha = 0.16f) else Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = if (selected) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp),
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

/**
 * 把 [dragging] 挪到 [target] 的槽位上，只在**同一区块内**生效。
 *
 * 置顶会话与普通会话之间换位一律返回 null —— 用户原话：「怎么拖都不能越过置顶」。
 * 另一条路（跨区即隐式置顶/取消置顶）看着更顺手，但一次误拖就会改掉置顶状态，不做。
 */
private fun reorderWithinSection(
    order: DrawerOrder,
    sections: DrawerSections,
    dragging: String,
    target: String,
): DrawerOrder? {
    val pinnedIds = sections.pinned.map { it.id }
    val normalIds = sections.normal.map { it.id }
    val updated = when {
        dragging in pinnedIds && target in pinnedIds -> order.copy(
            pinned = moved(pinnedIds, dragging, target),
            normal = normalIds,
        )
        dragging in normalIds && target in normalIds -> order.copy(
            pinned = pinnedIds,
            normal = moved(normalIds, dragging, target),
        )
        // 跨区，或者指针落在「置顶」标签/分隔线上：什么都不改。
        else -> return null
    }
    return if (updated == order) null else updated
}

/** 把 [item] 从表里取出、插到 [target] 原来的槽位（相邻行的一次次换位就是它重复的结果）。 */
private fun moved(list: List<String>, item: String, target: String): List<String> {
    val from = list.indexOf(item)
    val to = list.indexOf(target)
    if (from < 0 || to < 0 || from == to) return list
    return list.toMutableList().apply {
        removeAt(from)
        add(to.coerceIn(0, size), item)
    }
}

/**
 * 置顶开关对两张表的改动：置顶就追加到置顶表**末尾**（置顶区有它自己的顺序，不抢第一），
 * 同时从普通表里摘掉；取消置顶只从置顶表摘掉 —— 于是它按 updatedAt 回到普通区该在的位置，
 * 而不是回到普通表里那个旧槽位（那个槽位代表「手动基线」，刚取消置顶时按时间排更符合预期）。
 */
private fun pinToggled(order: DrawerOrder, chat: Conversation): DrawerOrder =
    if (chat.pinned) {
        order.copy(pinned = order.pinned.filterNot { it == chat.id })
    } else {
        order.copy(
            pinned = order.pinned.filterNot { it == chat.id } + chat.id,
            normal = order.normal.filterNot { it == chat.id },
        )
    }

/** 拖动时指针落在列表坐标系里的哪个 y（不参与重组，省掉每帧一次的重绘）。 */
private class DrawerDragTracker {
    var pointerY: Float = 0f
}

/** 行尾的删除按钮（排序模式下被拖拽把手顶替）。 */
@Composable
private fun DrawerDeleteButton(running: Boolean, onDelete: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
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

/**
 * 拖拽把手：三根短线。仓库里没有 grip 图标，按既有约定（抽屉的置顶标记也是复用 ic_star）
 * 不新造 drawable，用一个 16dp 的 Canvas 画出来。
 */
@Composable
private fun DrawerGripIcon(tint: Color, modifier: Modifier = Modifier.size(16.dp)) {
    Canvas(modifier) {
        val stroke = 1.5.dp.toPx()
        val start = size.width * 0.16f
        val end = size.width * 0.84f
        listOf(0.3f, 0.5f, 0.7f).forEach { fraction ->
            val y = size.height * fraction
            drawLine(
                color = tint,
                start = Offset(start, y),
                end = Offset(end, y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}
