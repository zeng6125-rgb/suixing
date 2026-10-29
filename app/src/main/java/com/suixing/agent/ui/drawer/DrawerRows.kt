package com.suixing.agent.ui.drawer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.suixing.agent.R
import com.suixing.agent.data.Conversation
import com.suixing.agent.ui.components.MenuCard
import com.suixing.agent.ui.components.CompletionDot
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
/**
 * 抽屉的返回键处理：**单独成一个组合单元**，只为把 `drawerState.isOpen` 的读取关在小作用域里。
 *
 * 若把 `drawerState.isOpen` 直接写进 `SuixingApp` 的函数体（原来是 `BackHandler(enabled =
 * drawerState.isOpen && …)`），那么抽屉动画**结束那一帧**状态翻转会连带重组整个外壳 ——
 * 包括常驻的 `ChatScreen`（参数含每次重组新建的 lambda，跳不过去）⇒ 聊天页整段重跑，
 * 主线程卡住，这一瞬间点到遮罩/按钮的反应就会被推迟。用户实测的现象正是
 * 「动画期间点遮罩立刻能关、动画刚播完那一下反而要等一会」。
 * 挪到本函数后，只有这一个只含 BackHandler 的作用域跟着翻转重组。
 */
@Composable
internal fun DrawerBackHandler(
    drawerState: androidx.compose.material3.DrawerState,
    route: String,
    onClose: () -> Unit,
) {
    BackHandler(enabled = drawerState.isOpen && route == "empty") { onClose() }
}

/**
 * 抽屉里那一行的点击：**不因节点移动而丢**。
 *
 * 为什么不用 `combinedClickable`：它（和 `detectTapGestures`）在抬手时用
 * `waitForUpOrCancellation` 判定"抬起点是否还在**节点当前**边界内"。抽屉面板在开/合动画里
 * 每帧都在位移（`ModalDrawerSheet` 的 `offset{}`），而手指是静止的 ⇒ 在**节点本地坐标**里
 * 手指一直往边界外跑 ⇒ 这一指被判成取消，`onClick` 根本不触发。
 * 这就是「进入抽屉后元素无法第一时间点击」：不是被谁挡住，是**点击被判定为取消**。
 *
 * 这里自己跟指针：只按"位移距离"判点按，不看节点边界；超过 `touchSlop` 就交还给列表/抽屉拖动
 * （不消费事件），长按到点先出菜单。涟漪复用 `LocalIndication` + 手工发 Press/Release。
 */
@Composable
private fun Modifier.drawerRowTap(
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
): Modifier {
    // **水波纹照旧**：press/release 的发射不能写在 `AwaitPointerEventScope` 里（那个作用域是
    // 受限的，调用 `emit` 编译不过 —— 上一版就是因此丢了涟漪）。这里拿一个普通的
    // `rememberCoroutineScope()`，把手势里的事件通过它转手发出去：视觉与以前一致。
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val indication = androidx.compose.foundation.LocalIndication.current
    val rippleScope = rememberCoroutineScope()
    val latestClick by androidx.compose.runtime.rememberUpdatedState(onClick)
    val latestLongClick by androidx.compose.runtime.rememberUpdatedState(onLongClick)
    return this
        .indication(interaction, indication)
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            val slop = viewConfiguration.touchSlop
            val longMs = viewConfiguration.longPressTimeoutMillis
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val press = androidx.compose.foundation.interaction.PressInteraction.Press(down.position)
                rippleScope.launch { interaction.emit(press) }
                var travel = 0f
                var longFired = false
                var tapped = false
                var cancelled = false
                while (true) {
                    val event = withTimeoutOrNull(if (longFired) Long.MAX_VALUE else longMs) {
                        awaitPointerEvent()
                    }
                    if (event == null) {
                        // 按住到点：长按生效，并继续保持跟手（抬手后不再触发点击）。
                        longFired = true
                        latestLongClick?.invoke()
                        continue
                    }
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    travel += (change.position - change.previousPosition).getDistance()
                    if (!change.pressed) {
                        tapped = travel <= slop
                        break
                    }
                    if (travel > slop) {
                        // 拖动：不消费，交给 LazyColumn 滚动 / 抽屉边缘拖动。
                        cancelled = true
                        break
                    }
                }
                rippleScope.launch {
                    interaction.emit(
                        androidx.compose.foundation.interaction.PressInteraction.Release(press),
                    )
                }
                if (!cancelled && tapped && !longFired) latestClick()
            }
        }
}

@Composable
internal fun ConversationRow(
    conversation: Conversation,
    selected: Boolean,
    running: Boolean,
    completedUnread: Boolean,
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
        modifier = if (menuOpen) modifier.onGloballyPositioned { coordinates ->
            rowBottomPx = coordinates.boundsInWindow().bottom
            rowHeightPx = coordinates.size.height.toFloat()
        } else modifier,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .clip(MaterialTheme.shapes.medium)
                // 排序模式下这一行没有长按：把手上的拖动和行上的长按是同一只手指上的两个
                // handler，按住把手超过 500ms 会先被判成长按（用户：「拖把手容易误触菜单」）。
                // 见 `drawerRowTap` 的说明：不能用 `combinedClickable`（抬手时会因节点位移
                // 把这一指判成取消，抽屉动画期间的第一指就是这么丢的）。
                .drawerRowTap(
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
                } else if (completedUnread) {
                    CompletionDot()
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
                } else if (selected) {
                    // 只有**高亮选中的那一条**行尾放三点：常驻删除图标已按用户要求去掉，
                    // 删除走长按菜单；选中行点三点直接开那张菜单。
                    Box(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .size(36.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onLongClick,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        DrawerMoreIcon(tint = scheme.onSurfaceVariant)
                    }
                }
            }
        }
        // 点面板外面关闭、按返回关闭。
        // MenuCard 的弹窗是 focusable = false（它自己的注释写了原因：抢焦点的弹窗会把输入法从
        // 输入框上拿走），这样的窗口既看不到「点在外面」也看不到 BACK —— 两件事都由调用方接，
        // 写法照 ChatTopBar 那张用量卡：遮罩是另一个弹窗，且**先**组合（弹窗按组合顺序叠）。
        //
        // 纯遮罩：面板本身是 [MenuCard]，进出场已经在它身上；这一层什么都不画、也不加动画修饰符。
        // `keeping` 只做一件事：面板退场那段让遮罩仍留在组合里，否则「点面板外面」会在面板还看得见
        // 的时候漏到抽屉下面的列表上。
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
                // 与模型选择浮层同一底色（用户：把会话列表改成和模型列表一致）。
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                DrawerMenuAction("改名", iconRes = R.drawable.ic_square_pen, onClick = onRename)
                DrawerMenuAction(
                    if (conversation.pinned) "取消置顶" else "置顶",
                    iconRes = R.drawable.ic_star,
                    onClick = onTogglePin,
                )
                // 归档：从抽屉列表里收起来，数据留着；取消归档在「设置 → 会话管理」那一页。
                DrawerMenuAction(
                    if (conversation.archived) "取消归档" else "归档",
                    iconRes = R.drawable.ic_file_archive,
                    onClick = onToggleArchive,
                )
                DrawerMenuAction("复制标题", iconRes = R.drawable.ic_copy, onClick = onCopyTitle)
                DrawerMenuAction("导出", iconRes = R.drawable.ic_download, onClick = onExport)
                DrawerMenuAction("删除", destructive = true, iconRes = R.drawable.ic_trash, onClick = onDelete)
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
