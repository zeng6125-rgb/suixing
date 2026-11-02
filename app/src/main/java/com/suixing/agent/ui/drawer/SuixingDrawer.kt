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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** One pointer gesture's ownership token. A newer token permanently invalidates this one. */
internal class DrawerGestureSession internal constructor(internal val generation: Long)

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

    // Gesture ownership. All mutations below happen on the UI thread (pointer callbacks,
    // rememberCoroutineScope launches, and Compose effects). Do not call these from a background
    // thread: the generation/job fields are intentionally not synchronized. A generation bump is
    // enough to make an already queued frame or release observe that it no longer owns the drawer.
    private var gestureGeneration = 0L
    private var activeGesture: DrawerGestureSession? = null
    private var motionJob: Job? = null

    /**
     * The session sampled by the frame pump. It is deliberately snapshot state rather than a
     * plain property: [collectLatest] cancels the previous pump (including a snapTo already
     * waiting inside Animatable's mutator) as soon as a newer owner replaces it.
     */
    internal var gestureSession by mutableStateOf<DrawerGestureSession?>(null)
        private set

    fun beginGesture(): DrawerGestureSession {
        // A fresh touch owns the drawer. Drop the previous gesture's settle/frame before it can
        // cancel this drag from Animatable's mutator.
        cancelMotion()
        gestureGeneration++
        val session = DrawerGestureSession(gestureGeneration)
        activeGesture = session
        gestureSession = session
        return session
    }

    fun isGestureActive(session: DrawerGestureSession): Boolean =
        activeGesture === session && session.generation == gestureGeneration

    fun isGestureCurrent(session: DrawerGestureSession): Boolean =
        session.generation == gestureGeneration

    /** Ends a live drag but keeps its generation valid for the release that follows. */
    fun finishGesture(session: DrawerGestureSession): Boolean {
        if (!isGestureActive(session)) return false
        activeGesture = null
        gestureSession = null
        cancelMotion()
        return true
    }

    /**
     * Pointer-input cancellation/restart. It only releases this session; it never chooses a
     * settle target, and it cannot touch a newer session that already began. Keeping the last
     * drag progress is deliberate: cancellation must not turn into a direction choice, and an
     * external close/new gesture owns the recovery if one follows.
     */
    fun cancelGesture(session: DrawerGestureSession): Boolean {
        if (!isGestureActive(session)) return false
        activeGesture = null
        gestureSession = null
        gestureGeneration++
        cancelMotion()
        return true
    }

    /**
     * External state action (system Back, route close, tap shield, hamburger). It revokes the
     * whole gesture epoch synchronously before the animation starts, so no live pointer can
     * later settle an old drag over this close.
     */
    suspend fun animateTo(
        target: DrawerValue,
        spec: AnimationSpec<Float>,
        initialVelocity: Float = 0f,
    ) {
        revokeGestures()
        val job = currentCoroutineContext()[Job]
        motionJob = job
        try {
            progress.animateTo(if (target == DrawerValue.Open) 1f else 0f, spec, initialVelocity)
            if (motionJob === job) settled = target
        } finally {
            if (motionJob === job) motionJob = null
        }
    }

    suspend fun snapTo(target: DrawerValue) {
        revokeGestures()
        progress.snapTo(if (target == DrawerValue.Open) 1f else 0f)
        settled = target
    }

    /**
     * Applies one pointer frame only while [session] still owns the drawer. Called from a
     * collectLatest block, so beginning another gesture cancels this call even if it is already
     * suspended acquiring Animatable's internal mutator.
     */
    suspend fun applyGestureFrame(session: DrawerGestureSession, progressValue: Float) {
        if (!isGestureActive(session)) return
        val job = currentCoroutineContext()[Job]
        motionJob = job
        try {
            if (!isGestureActive(session)) return
            currentCoroutineContext().ensureActive()
            progress.snapTo(progressValue)
        } finally {
            if (motionJob === job) motionJob = null
        }
    }

    /** Applies the final pointer position and then the velocity-carrying spring. */
    suspend fun settleGesture(
        session: DrawerGestureSession,
        target: DrawerValue,
        spec: AnimationSpec<Float>,
        initialVelocity: Float = 0f,
        releaseProgress: Float? = null,
    ) {
        if (!isGestureCurrent(session)) return
        val job = currentCoroutineContext()[Job]
        motionJob = job
        try {
            if (!isGestureCurrent(session)) return
            currentCoroutineContext().ensureActive()
            if (releaseProgress != null && progress.value != releaseProgress) {
                progress.snapTo(releaseProgress)
                if (!isGestureCurrent(session)) return
            }
            currentCoroutineContext().ensureActive()
            progress.animateTo(
                if (target == DrawerValue.Open) 1f else 0f,
                spec,
                initialVelocity,
            )
            if (isGestureCurrent(session) && motionJob === job) settled = target
        } finally {
            if (motionJob === job) motionJob = null
        }
    }

    private fun revokeGestures() {
        activeGesture = null
        gestureSession = null
        gestureGeneration++
        cancelMotion()
    }

    private fun cancelMotion() {
        motionJob?.cancel()
        motionJob = null
    }
}

/**
 * One frame consumer for the newest drag session. `collectLatest` gives the cancellation that
 * closes the narrow "checked generation, then suspended inside Animatable" window: a newer
 * session cancels the old block at whatever suspension point it has reached.
 */
/**
 * The shared cancellation path for both pointer loops. It is deliberately not a settle: a Job
 * restart or pointer loss only releases the state it still owns. A close/new drag that already
 * bumped the generation is left untouched.
 */
internal fun cancelOwnedDrawerDrag(
    state: SuixingDrawerState,
    dragAccumulator: DrawerDragAccumulator,
    session: DrawerGestureSession?,
    generation: Long?,
) {
    if (generation != null) dragAccumulator.cancel(generation)
    if (session != null) state.cancelGesture(session)
}

internal suspend fun runDrawerDragFrameLoop(
    sessions: Flow<DrawerGestureSession?>,
    dragAccumulator: DrawerDragAccumulator,
    isActive: (DrawerGestureSession) -> Boolean,
    applyFrame: suspend (DrawerGestureSession, Float) -> Unit,
    awaitFrame: suspend () -> Unit,
) {
    sessions.collectLatest { session ->
        if (session == null) return@collectLatest
        var firstSample = true
        while (isActive(session)) {
            if (!firstSample) awaitFrame()
            firstSample = false
            val frame = dragAccumulator.frame()
            if (frame == null || !dragAccumulator.canApply(frame)) {
                awaitFrame()
                continue
            }
            applyFrame(session, frame.progress)
        }
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
 * 抽屉面板在绘制层平移；主页自己读 `state.offset` 做变换。
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

    // Pointer callbacks only update this accumulator. One frame consumer samples its latest
    // target, so high-rate input cannot queue one Animatable mutation per event.
    val dragAccumulator = remember(state) { DrawerDragAccumulator() }
    LaunchedEffect(state) {
        // `snapshotFlow` only wakes the loop when ownership changes. It does not define the frame
        // rate: the explicit frame clock samples the newest pointer target at most once/frame.
        // collectLatest cancels an older session's snap even while Animatable holds it waiting.
        runDrawerDragFrameLoop(
            sessions = snapshotFlow { state.gestureSession },
            dragAccumulator = dragAccumulator,
            isActive = state::isGestureActive,
            applyFrame = state::applyGestureFrame,
            awaitFrame = { withFrameNanos { } },
        )
    }
    val scope = rememberCoroutineScope()
    // **松手回弹用 spring 并吃进手指速度**（用户：「手势的收起动画好像有点违和」）。
    // tween 不认初速度 ⇒ 快划松手时动画会从 0 速度重新起步，看着像"顿一下再走"。
    // spring 带 initialVelocity 才能与手指的甩动连续。
    val settle: (DrawerGestureSession, DrawerValue, Float, DrawerDragRelease) -> Unit =
        remember(scope, state, panelPx) {
            { session, target, velocityPx, releaseProgress ->
                scope.launch {
                    // The frame pump may not have consumed the final pointer update yet. The
                    // session check covers both that queued frame and a newer drag/external close.
                    if (!dragAccumulator.canSettle(releaseProgress) ||
                        !state.isGestureCurrent(session)
                    ) {
                        return@launch
                    }
                    state.settleGesture(
                        session,
                        target,
                        DRAWER_SPEC,
                        initialVelocity = velocityPx / panelPx,
                        releaseProgress = releaseProgress.progress,
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
                // detectHorizontalDragGestures does not promise onDragCancel when its Job is
                // cancelled for a key change. Keep the owner here and release only this drag in
                // finally; a normal release clears the owner first so finally cannot settle.
                var ownedSession: DrawerGestureSession? = null
                var ownedGeneration: Long? = null
                try {
                    // Callbacks only append deltas; the frame consumer owns snaps.
                    detectHorizontalDragGestures(
                        onDragStart = {
                            // 固定起点并累计 delta：不能每个 pointer event 都读 `progress.value`
                            // 再叠加，因为 frame consumer 可能还没应用上一批 delta。
                            val session = state.beginGesture()
                            ownedSession = session
                            ownedGeneration = dragAccumulator.begin(state.progress.value, panelPx)
                            tracker.resetTracking()
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            ownedGeneration?.let { dragAccumulator.dragBy(it, dragAmount) }
                            tracker.addPosition(change.uptimeMillis, change.position)
                        },
                        onDragEnd = {
                            val session = ownedSession ?: return@detectHorizontalDragGestures
                            val generation = ownedGeneration
                                ?: return@detectHorizontalDragGestures
                            ownedSession = null
                            ownedGeneration = null
                            // **带速度判断**（用户：「手势很大程度都滑不动，得滑很久」）。
                            // 快速滑动（|v| > 800px/s）按方向吸附；否则看 25% 位置。
                            val v = tracker.calculateVelocity().x
                            // 最终指针位置必须在帧泵关闸后单独交接。代际不符的旧 finish
                            // 不能停掉另一只手已开始的新拖动。
                            val releaseProgress = dragAccumulator.finish(generation)
                            val stillOwned = state.finishGesture(session)
                            if (releaseProgress == null || !stillOwned) {
                                return@detectHorizontalDragGestures
                            }
                            val open = if (abs(v) > 800f) v > 0f
                            else releaseProgress.progress > 0.25f
                            settle(
                                session,
                                if (open) DrawerValue.Open else DrawerValue.Closed,
                                v,
                                releaseProgress,
                            )
                        },
                        onDragCancel = {
                            val session = ownedSession ?: return@detectHorizontalDragGestures
                            val generation = ownedGeneration
                                ?: return@detectHorizontalDragGestures
                            ownedSession = null
                            ownedGeneration = null
                            val releaseProgress = dragAccumulator.finish(generation)
                            val stillOwned = state.finishGesture(session)
                            if (releaseProgress == null || !stillOwned) {
                                return@detectHorizontalDragGestures
                            }
                            settle(
                                session,
                                if (releaseProgress.progress > 0.25f) DrawerValue.Open
                                else DrawerValue.Closed,
                                0f,
                                releaseProgress,
                            )
                        },
                    )
                } finally {
                    val session = ownedSession
                    val generation = ownedGeneration
                    ownedSession = null
                    ownedGeneration = null
                    cancelOwnedDrawerDrag(state, dragAccumulator, session, generation)
                }
            },
    ) {
        // Move the whole panel as a layer. The progress read stays in the draw phase, so
        // opening/closing does not re-place the drawer's LazyColumn on every animation frame.
        Box(
            Modifier
                .width(DRAWER_SHEET_WIDTH.dp)
                .fillMaxHeight()
                .graphicsLayer {
                    translationX = -panelPx * (1f - state.progress.value)
                },
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
                            var horizontal = 0f
                            var isDrag = false
                            var appliedHorizontal = 0f
                            var ownedSession: DrawerGestureSession? = null
                            var ownedGeneration: Long? = null
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.changedToUp()) {
                                        change.consume()
                                        if (!isDrag) {
                                            // **轻点 = 点遮罩**：外部状态动作优先，作废旧拖拽。
                                            scope.launch {
                                                state.animateTo(DrawerValue.Closed, DRAWER_CLOSE_SPEC)
                                            }
                                        } else {
                                            val session = ownedSession
                                            val generation = ownedGeneration
                                            ownedSession = null
                                            ownedGeneration = null
                                            val releaseProgress = generation?.let {
                                                dragAccumulator.finish(it)
                                            }
                                            val stillOwned = session != null &&
                                                state.finishGesture(session)
                                            if (releaseProgress != null && stillOwned) {
                                                val v = tracker.calculateVelocity().x
                                                val open = if (abs(v) > 800f) v > 0f
                                                else releaseProgress.progress > 0.25f
                                                settle(
                                                    session,
                                                    if (open) DrawerValue.Open else DrawerValue.Closed,
                                                    v,
                                                    releaseProgress,
                                                )
                                            }
                                        }
                                        break
                                    }
                                    val dx = change.positionChange().x
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                    horizontal += dx
                                    change.consume()   // 纵向拖动也吃掉 ⇒ 露出的主页不能滚动
                                    if (!isDrag && abs(horizontal) > viewConfiguration.touchSlop) {
                                        isDrag = true
                                        val session = state.beginGesture()
                                        ownedSession = session
                                        // Claim from the current panel position, not from
                                        // pointer-down: an external close may have advanced it.
                                        ownedGeneration = dragAccumulator.begin(state.progress.value, panelPx)
                                    }
                                    if (isDrag) {
                                        // Include pre-slop motion once when the direction is claimed;
                                        // after that, append only this event's unconsumed horizontal delta.
                                        ownedGeneration?.let {
                                            dragAccumulator.dragBy(it, horizontal - appliedHorizontal)
                                        }
                                        appliedHorizontal = horizontal
                                    }
                                }
                            } finally {
                                val session = ownedSession
                                val generation = ownedGeneration
                                ownedSession = null
                                ownedGeneration = null
                                cancelOwnedDrawerDrag(state, dragAccumulator, session, generation)
                            }
                        }
                    },
            )
        }
    }
}
