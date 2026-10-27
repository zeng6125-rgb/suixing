package com.suixing.agent.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全站统一的进/退场动画（0.8.106，用户：「进新页的动画需要都统一」）。
 *
 * 与 NavHost 的页面转场**同一套数字、同一个来源**：进页＝从右滑入 1/12 屏宽、200ms；
 * 退场＝淡出 200ms（「蒙版由深到没有」）。覆盖层（助手编辑页/裁剪页）走 [FullScreenOverlay]，
 * 导航页走 SuixingApp 的 NavHost，两边都引这三个常量 —— 改一处全站跟着改，不会再出现
 * 「一会有一会没有」的第二套动画。
 */
const val NAV_ENTER_MS = 200
const val NAV_EXIT_MS = 200
const val NAV_SHIFT_DIVISOR = 12

/**
 * How a screen asks the shell to slide its sidebar out. Null outside the shell,
 * which is what keeps the button from appearing in a preview or a bare screen.
 */
val LocalOpenSidebar = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * 抽屉是否正在播放收起/打开动画（外壳写，屏幕读）。
 *
 * 为什么需要它：切会话时**抽屉动画和整页重组在抢同一份主线程**。用户的原话是
 * 「切会话这个抽屉的动画都被吞掉和不流畅」，而"内容出不出来是一回事" ——
 * 所以正确的顺序是：**先让抽屉的动画把帧用掉，再把转录的分批填充压上去**。
 * 屏幕只在 `LaunchedEffect` 里等它变回 false，不在组合期读，不让它引起重组。
 */
val LocalDrawerBusy = compositionLocalOf<androidx.compose.runtime.State<Boolean>> {
    androidx.compose.runtime.mutableStateOf(false)
}

/**
 * The keyboard height the shell has clamped to, in pixels.
 *
 * Children that carry an inset of their own have to subtract it: the composer's navigation-bar
 * padding is dead space while the keyboard covers the gesture bar, which is the band that
 * appeared between the field and the keys. Clamped rather than raw so the platform's
 * end-of-animation rebound does not flicker the band back for a frame.
 *
 * `compositionLocalOf`, not `staticCompositionLocalOf`: this value changes on every frame of a
 * keyboard transition, and the static variant has no read tracking — every change would
 * invalidate the whole provided subtree (the entire NavHost) instead of just its readers.
 */
val LocalHeldImePx = compositionLocalOf<androidx.compose.runtime.State<Int>> {
    androidx.compose.runtime.mutableStateOf(0)
}

/**
 * 按「夹过的键盘高度」加底部内边距，**读数在布局期完成**。
 *
 * 键盘淡入淡出的那 200~300ms 里，`heldImePx` 每一帧都在变。若在组合期读它（原来就是
 * `Modifier.padding(bottom = with(density){ heldImePx.toDp() })`），等于每一帧都让**整棵树**
 * 重新组合并重新布局 —— 用户报的「输入法抬起/收起时掉帧」就是这个。
 *
 * 改成 `layout {}` 里读：状态变化只让这个节点重新测量/放置，不触发任何重组。
 */
internal fun Modifier.imePaddingBottom(ime: androidx.compose.runtime.State<Int>): Modifier =
    bottomInsetPadding { ime.value }

/** Shrink both minimum and maximum height, including exact-height parent constraints. */
internal fun Modifier.bottomInsetPadding(insetPx: () -> Int): Modifier =
    this.layout { measurable, constraints ->
        val bottom = insetPx().coerceAtLeast(0)
        val placeable = measurable.measure(constraints.offset(vertical = -bottom))
        val height = constraints.constrainHeight((placeable.height.toLong() + bottom).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        layout(placeable.width, height) { placeable.place(0, 0) }
    }

/**
 * 外壳的 IME clamp 冻结开关（0.8.107）：聊天页在「按下到抬手后守护窗」期间置 true，
 * 外壳就不接受任何 IME inset 变化 —— 抬手时选择手柄/菜单把高度报进 inset、输入框底垫暴涨，
 * 正是用户拍到的「抬起时对话框与输入法高度变得很大」。shell 提供、ChatScreen 写。
 */
val LocalSetSelectionActive = compositionLocalOf<((Boolean) -> Unit)?> { null }

/**
 * 列表底部的手势条避让（issue #47 评论区：设置页「没有 edge to edge」「手势条没沉浸」）。
 *
 * 原来列表用 `Modifier.navigationBarsPadding()`：那块空间把内容**裁掉**了，于是内容从
 * 来不穿到手势条底下，滚到底时手势条区域是一条空的背景带 —— 与 MIUI 系统设置的观感不同。
 *
 * 现在内容画满到手势条下面（沉浸），只有**最后一行**通过 contentPadding 避让：滚动中内容
 * 从手势条底下穿过，停到底时末行浮在导航条上方。`Scaffold` 的
 * `contentWindowInsets = WindowInsets(0,0,0,0)` 已经把窗口让出来了，只差这一步。
 *
 * [extra] 是列表自己额外的呼吸位（原来那 12dp）。
 */
@Composable
internal fun navBarContentPadding(extra: Dp = 12.dp): PaddingValues {
    val density = LocalDensity.current
    val bottomPx = WindowInsets.navigationBars.getBottom(density)
    // **结果要 remember**（优化清单 ui-P1）：全 ui 有 14 个调用点，每次重组都为同一个
    // 底部留白新建一个 `PaddingValues`；而它只依赖手势条高度与 extra，几乎不变。
    val bottom: Dp = with(density) { bottomPx.toDp() } + extra
    return remember(bottom) { PaddingValues(bottom = bottom) }
}
