package com.suixing.agent.ui.drawer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 自建抽屉状态。
 *
 * **为什么要自建**：`material3 1.4.0` 的 `DrawerState` 内部控制松手回弹曲线的三个字段
 * 全是 `internal`，公开构造与 `rememberDrawerState` 都没有 anim spec 参数：
 * ```
 * NavigationDrawer.kt:127  class DrawerState(initialValue, internal val confirmStateChange)
 * NavigationDrawer.kt:132  internal var anchoredDraggableMotionSpec = …
 * NavigationDrawer.kt:253  internal var openDrawerMotionSpec  = snap()
 * NavigationDrawer.kt:255  internal var closeDrawerMotionSpec = snap()
 * NavigationDrawer.kt:303  fun rememberDrawerState(initialValue, confirmStateChange)
 * ```
 * ⇒ 手势松手后的回弹速度只能走库的 token（开 `spring(0.9, 700)` / 关 `spring(1.0, 3800)`），
 * App 改不了。这里改用 `Animatable` + `detectHorizontalDragGestures`，
 * 回弹曲线由 [animateTo] 的调用方传入（与汉堡键同一条 `DRAWER_*_SPEC`，210ms）。
 *
 * 语义与 `DrawerState` 对齐的部分：`offset`（关闭 = −面板宽，打开 = 0）与 `isOpen`。
 */
@Stable
internal class SuixingDrawerState(initialValue: DrawerValue) {

    /** 0 = 关闭，1 = 完全打开。 */
    internal val progress = Animatable(if (initialValue == DrawerValue.Open) 1f else 0f)

    /** 面板宽度（px），由 [SuixingDrawer] 在每帧同步。 */
    internal var panelWidthPx by mutableFloatStateOf(1f)

    /** 与 `DrawerState.offset` 同义：关闭 = −面板宽，打开 = 0。布局/绘制期读它不会触发重组。 */
    val offset: State<Float> = derivedStateOf { -panelWidthPx * (1f - progress.value) }

    /**
     * **只在动画结束时翻转**（不是每帧读 [progress]）。
     * 若按 `progress.value > 0.5f` 计算，动画期间会逐帧重组整棵抽屉 —— 正是
     * `SuixingApp.kt` 里那条「不要把状态读进外壳组合作用域」的坑。
     */
    private var settled by mutableStateOf(initialValue)
    val isOpen: Boolean get() = settled == DrawerValue.Open

    suspend fun animateTo(
        target: DrawerValue,
        spec: AnimationSpec<Float>,
        initialVelocity: Float = 0f,
    ) {
        val to = if (target == DrawerValue.Open) 1f else 0f
        progress.animateTo(to, spec, initialVelocity)
        settled = target
    }

    suspend fun snapTo(target: DrawerValue) {
        progress.snapTo(if (target == DrawerValue.Open) 1f else 0f)
        settled = target
    }
}

@Composable
internal fun rememberSuixingDrawerState(
    initialValue: DrawerValue = DrawerValue.Closed,
): SuixingDrawerState = remember { SuixingDrawerState(initialValue) }

/**
 * 抽屉宿主：侧边栏在下、主页在上（先画侧边栏，后画主页 ⇒ 主页的阴影能压上去）。
 *
 * ⚠️ 宿主与冻结层是**两套故意不同**的手势循环，不要"统一"成一份：
 *  · 宿主 = `detectHorizontalDragGestures`：横向 slop 之后才消费事件，竖滑要原样放行
 *    给聊天列表滚动；
 *  · 冻结层 = `awaitEachGesture` 全消费：抽屉开着时竖滑也必须吞掉（挡列表滚动），
 *    轻点算"点遮罩"。消费语义相反，合并必然破坏其中一边。
 *
 * 位移不用调用方再算：[offset] 由本组件写进 `offset{}`（布局期），主页那一层自己读
 * `state.offset` 做缩放/圆角/阴影。
 */
@Composable
internal fun SuixingDrawer(
    state: SuixingDrawerState,
    gesturesEnabled: Boolean,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val panelPx = with(LocalDensity.current) { DRAWER_SHEET_WIDTH.dp.toPx() }
    // 等值守卫：SideEffect 每次重组都跑，值没变就别碰状态（FloatState 同值写入无开销，
    // 但省掉这次调用让重组路径上少一次分支）。
    SideEffect { if (state.panelWidthPx != panelPx) state.panelWidthPx = panelPx }

    // **拖拽帧的唯一消费通道**：宿主与冻结层的手势回调只写 [dragTarget]，这里每帧
    // 合并成一次 `snapTo`。为什么不在回调里 `scope.launch { snapTo }`：拖拽回调每帧都跑
    //（120Hz），每帧一个协程是纯分配，且 launch 的执行顺序不保证 FIFO，乱序 snap 会让
    // 面板抖动。snapshotFlow 的合帧语义正好 —— 只看最新值，一帧最多一次 snap。
    // 初始值取当前进度（不是 0）：避免消费者启动时把开着的抽屉拽回 0。
    val dragTarget = remember(state) { mutableFloatStateOf(state.progress.value) }
    // **拖拽态闸门**：只有手指真的在拖时才把 [dragTarget] 应用到进度上。
    //
    // 为什么必须有（回归 0.8.611「抽屉停在中间」）：松手后 `settle` 启动 `animateTo`，
    // 而最后一帧的 `dragTarget` 快照可能还没被消费；它一旦在动画启动后才被消费，
    // `snapTo` 会**取消正在跑的动画**，抽屉就冻在半路，且 `settled` 不更新
    // （`isOpen` 仍为 false，冻结层消失，底下主页又能点）。加闸门后，
    // 松手时先把闸门关掉，残留快照直接丢弃。
    val dragging = remember(state) { mutableStateOf(false) }
    LaunchedEffect(state) {
        snapshotFlow { dragging.value to dragTarget.floatValue }.collect { (isDragging, target) ->
            if (isDragging && state.progress.value != target) state.progress.snapTo(target)
        }
    }
    val scope = rememberCoroutineScope()
    // **松手回弹用 spring 并吃进手指速度**（用户：「手势的收起动画好像有点违和」）。
    // tween 不认初速度 ⇒ 快划松手时动画会从 0 速度重新起步，看着像"顿一下再走"。
    // spring 带 initialVelocity 才能与手指的甩动连续。
    val settle: (DrawerValue, Float) -> Unit = remember(scope, panelPx) {
        { target, velocityPx ->
            scope.launch {
                state.animateTo(
                    target,
                    DRAWER_SPEC,
                    initialVelocity = velocityPx / panelPx,
                )
            }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(gesturesEnabled, panelPx) {
                if (!gesturesEnabled) return@pointerInput
                val tracker = VelocityTracker()
                var startProgress = 0f
                var accumulated = 0f
                // 拖拽帧只写 [dragTarget]（消费者在组合体里）：这里不是组合上下文，
                // 不能放 LaunchedEffect；也不能直接 snapTo（detectHorizontalDragGestures
                // 的回调不是挂起的）。写值 → snapshotFlow 每帧合并一次 snapTo。
                detectHorizontalDragGestures(
                    onDragStart = {
                        // 记下起点进度并累加位移：不能每帧读 `progress.value` 再叠加，
                        // 因为 snapTo 是异步消费的，读到的是上一帧的旧值 ⇒ 拖动会"吃掉"位移。
                        startProgress = state.progress.value
                        accumulated = 0f
                        dragTarget.floatValue = startProgress
                        dragging.value = true      // 开闸：拖拽帧开始生效
                        tracker.resetTracking()
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        accumulated += dragAmount
                        tracker.addPosition(change.uptimeMillis, change.position)
                        dragTarget.floatValue = (startProgress + accumulated / panelPx).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        // **带速度判断**（用户：「手势很大程度都滑不动，得滑很久」）。
                        // 原来只看位置 50% 阈值 ⇒ 短距离快滑也会弹回去，必须拖满 150dp 才开。
                        // 现在：快速滑动（|v| > 800px/s）直接按方向吸附；否则看 25% 位置。
                        val v = tracker.calculateVelocity().x
                        // 关闸要在 settle 之前：残留的最后一帧快照若在动画启动后被消费，
                        // snapTo 会取消动画 ⇒ 停在中间。
                        dragging.value = false
                        // 判据用 dragTarget（手指最终位置），不用 progress.value：
                        // 后者可能还差一帧没跟上。
                        val open = if (abs(v) > 800f) v > 0f else dragTarget.floatValue > 0.25f
                        settle(if (open) DrawerValue.Open else DrawerValue.Closed, v)
                    },
                    onDragCancel = {
                        dragging.value = false
                        settle(
                            if (dragTarget.floatValue > 0.25f) DrawerValue.Open else DrawerValue.Closed,
                            0f,
                        )
                    },
                )
            },
    ) {
        // 侧边栏：布局期平移到面板位置（不重组）。
        Box(
            Modifier
                .fillMaxSize()
                .offset { IntOffset((-panelPx * (1f - state.progress.value)).roundToInt(), 0) },
        ) {
            drawerContent()
        }
        // 主页：后画 ⇒ 在上层。
        content()
        // **抽屉打开时冻结露出的那一条主页**（用户需求）。
        //
        // 为什么需要：主页被推到右边后，露出的那 85dp 仍然是**活的**——可以点按钮、
        // 可以上下滚动列表。抽屉开着时这些操作都是误触。
        //
        // 做法：在这块区域上盖一个透明手势层（只有它在最上层，页面收不到任何事件）：
        //  · 点一下 → 等价于点遮罩，关闭抽屉；
        //  · 横向拖动 → 仍然可以关/开（用户：「不要改变其滑动可关闭的形态」）；
        //  · 纵向滑动 → 被吃掉，不做任何事（挡住列表滚动）。
        //
        // 只覆盖 `x ≥ 面板宽` 那一片（`padding(start = …)`）：左侧 300dp 是侧边栏本体，
        // 盖上去会让抽屉里的按钮点不动。
        if (state.isOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(start = DRAWER_SHEET_WIDTH.dp)
                    // **一个手势处理器，不要拆成两个 detector**：
                    // `detectHorizontalDragGestures` 与 `detectTapGestures` 各自独立消费指针，
                    // 轻点很可能先被拖动那边吃掉 ⇒ "点一下关闭"不稳定。
                    // 这里用 `awaitEachGesture` 自己分流：横向超阈值算拖动，否则抬起算轻点。
                    .pointerInput(panelPx) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            val tracker = VelocityTracker()
                            tracker.addPosition(down.uptimeMillis, down.position)
                            val startProgress = state.progress.value
                            var horizontal = 0f
                            var isDrag = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUp()) {
                                    change.consume()
                                    dragging.value = false   // 先关闸，再吸附（否则残留快照会取消动画）
                                    if (!isDrag) {
                                        // **轻点 = 点遮罩**：直接关。
                                        settle(DrawerValue.Closed, 0f)
                                    } else {
                                        val v = tracker.calculateVelocity().x
                                        val open = if (abs(v) > 800f) v > 0f
                                        else dragTarget.floatValue > 0.25f
                                        settle(if (open) DrawerValue.Open else DrawerValue.Closed, v)
                                    }
                                    break
                                }
                                val dx = change.positionChange().x
                                tracker.addPosition(change.uptimeMillis, change.position)
                                horizontal += dx
                                change.consume()   // 纵向拖动也吃掉 ⇒ 露出的主页不能滚动
                                if (!isDrag && abs(horizontal) > viewConfiguration.touchSlop) {
                                    isDrag = true
                                    dragging.value = true    // 开闸：拖拽帧开始生效
                                }
                                if (isDrag) {
                                    // awaitPointerEventScope 是受限挂起作用域，不能直接调
                                    // Animatable.snapTo（普通 suspend）⇒ 写 [dragTarget]，
                                    // 由组合体里的 snapshotFlow 消费者代为执行。
                                    dragTarget.floatValue =
                                        (startProgress + horizontal / panelPx).coerceIn(0f, 1f)
                                }
                            }
                        }
                    },
            )
        }
    }
}
