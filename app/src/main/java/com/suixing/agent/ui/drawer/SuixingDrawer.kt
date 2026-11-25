package com.suixing.agent.ui.drawer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.preferredFrameRate
import com.suixing.agent.ui.components.LocalDecorationMotion
import com.suixing.agent.ui.components.LocalDrawerBusy
import com.suixing.agent.core.NativeCore
import com.suixing.agent.core.NativeCoreJni
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** One pointer gesture's ownership token. A newer token permanently invalidates this one. */
internal class DrawerGestureSession internal constructor(internal val generation: Long)

internal class DrawerMotion(val target: DrawerValue, val spec: AnimationSpec<Float>)

private const val DRAG_INTERPOLATION_NANOS = 8_333_333L
private data class DrawerDragSample(val timeNanos: Long, val progress: Float)

/**
 * Discrete input-shield lifecycle for the home page. Only [Closed] may drop the shield; every
 * opening/closing frame, owned drag, and parked half-open drawer leaves home exposed.
 */
internal enum class DrawerHomeLifecycle {
    Closed,
    Opening,
    Open,
    Closing,
    Dragging,
    Exposed;

    val shieldsHome: Boolean get() = this != Closed
}

internal fun drawerMotionLifecycle(target: DrawerValue): DrawerHomeLifecycle =
    if (target == DrawerValue.Open) DrawerHomeLifecycle.Opening else DrawerHomeLifecycle.Closing

internal fun drawerSettledLifecycle(target: DrawerValue): DrawerHomeLifecycle =
    if (target == DrawerValue.Open) DrawerHomeLifecycle.Open else DrawerHomeLifecycle.Closed

/** No owner left: keep the shield when the panel is not fully closed. */
internal fun drawerIdleLifecycle(progress: Float): DrawerHomeLifecycle =
    if (progress > 0f) DrawerHomeLifecycle.Exposed else DrawerHomeLifecycle.Closed

/** The shield's hit region starts at the panel's right edge and must not cover drawer rows. */
internal fun drawerHomeShieldContains(xPx: Float, shieldStartPx: Float): Boolean =
    xPx >= shieldStartPx

/**
 * The panel's right edge while it moves: 0 when closed, the full width when open.
 *
 * The home layer is translated by the same amount, so this is exactly where the exposed
 * home begins. A fixed [DRAWER_SHEET_WIDTH] inset leaves a strip of the still-moving home
 * unshielded for the whole opening/closing animation (at half open: 150dp..300dp).
 */
internal fun drawerHomeShieldStartPx(panelWidthPx: Float, progress: Float): Float =
    panelWidthPx * progress.coerceIn(0f, 1f)

/** Ease the rounded outline into the full-screen rectangle over the final 30 dp of travel. */
internal fun drawerHomeCornerFraction(progress: Float): Float {
    val fraction = (progress / 0.10f).coerceIn(0f, 1f)
    return fraction * fraction * (3f - 2f * fraction)
}

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
 * App 改不了。这里改用 `Animatable` + 横向指针循环，
 * 回弹曲线由 [animateTo] 的调用方传入（与汉堡键共用临界阻尼弹簧 [DRAWER_SPEC]）。
 *
 * `isOpen` 表示已停靠端点；所有可见层与命中遮罩共用 [visualProgress]。
 */
@Stable
internal class SuixingDrawerState(initialValue: DrawerValue) {

    /** 0 = 关闭，1 = 完全打开。 */
    internal val progress = Animatable(if (initialValue == DrawerValue.Open) 1f else 0f).apply {
        updateBounds(0f, 1f)
    }
    private var dragProgress by mutableFloatStateOf(progress.value)
    private var directManipulation by mutableStateOf(false)
    private val dragSamples = ArrayDeque<DrawerDragSample>()
    private var dragFrameJob: Job? = null
    private var hasDragFrame = false

    /** One bounded progress for the panel, home transform, background and input shield. */
    val visualProgress: State<Float> = object : State<Float> {
        override val value: Float
            get() = (if (directManipulation) dragProgress else progress.value).coerceIn(0f, 1f)
    }
    // The backing color only changes at the closed boundary.
    val isHomeExposed: Boolean by derivedStateOf(structuralEqualityPolicy()) {
        visualProgress.value > 0f
    }

    /**
     * **只在动画结束时翻转**（不是每帧读 [progress]）。
     * 若按 `progress.value > 0.5f` 计算，动画期间会逐帧重组整棵抽屉 —— 正是
     * `SuixingApp.kt` 里那条「不要把状态读进外壳组合作用域」的坑。
     */
    private var settled by mutableStateOf(initialValue)
    val isOpen: Boolean get() = settled == DrawerValue.Open

    /** Discrete, so mounting the shield does not read [progress] during composition. */
    private var homeLifecycle by mutableStateOf(drawerSettledLifecycle(initialValue))
    private val moving = derivedStateOf(structuralEqualityPolicy()) {
        homeLifecycle == DrawerHomeLifecycle.Opening || homeLifecycle == DrawerHomeLifecycle.Closing ||
            homeLifecycle == DrawerHomeLifecycle.Dragging
    }
    val isMoving: Boolean get() = moving.value
    private val homeShield = derivedStateOf(structuralEqualityPolicy()) { homeLifecycle.shieldsHome }
    // Opening, dragging, exposed and closing all keep the same shield and drawer visibility.
    val homeShieldActive: Boolean get() = homeShield.value
    private var retainClosingFrame by mutableStateOf(false)

    /** A conversation switch must draw its target once before the close can freeze that page. */
    fun freezesChat(switchingConversation: Boolean): Boolean =
        if (homeShieldActive) {
            !switchingConversation || homeLifecycle == DrawerHomeLifecycle.Closing
        } else {
            retainClosingFrame
        }

    internal suspend fun observeContentUpdates(onAllowed: (Boolean) -> Unit) {
        try {
            snapshotFlow { isMoving to homeShieldActive }.collectLatest { (moving, exposed) ->
                if (exposed) {
                    retainClosingFrame = true
                } else {
                    // Present the closed geometry with the cached chat before recording live text.
                    withFrameNanos { }
                    if (retainClosingFrame) {
                        retainClosingFrame = false
                        // Give the live chat its own frame before refreshing hidden drawer rows.
                        withFrameNanos { }
                    }
                }
                onAllowed(!moving)
            }
        } finally {
            retainClosingFrame = false
        }
    }

    // Gesture ownership. All mutations below happen on the UI thread (pointer callbacks,
    // rememberCoroutineScope launches, and Compose effects). Do not call these from a background
    // thread: the generation/job fields are intentionally not synchronized. A generation bump is
    // enough to reject a late pointer callback or release from the previous owner.
    internal var gestureGeneration by mutableLongStateOf(0L)
        private set
    private var activeGesture: DrawerGestureSession? = null
    private var motionJob: Job? = null
    internal var motion: DrawerMotion? = null
        private set
    internal var priorityPointerId: PointerId? = null
    internal var priorityCanScroll: ((Float) -> Boolean)? = null
    internal var rejectedLeftSwipePointer: PointerId? = null

    /** Navigation observes ownership changes, not pointer positions. */
    internal var gestureSession by mutableStateOf<DrawerGestureSession?>(null)
        private set

    fun beginGesture(): DrawerGestureSession {
        val currentProgress = visualProgress.value
        cancelMotion()
        resetDragFrames()
        dragProgress = currentProgress
        directManipulation = true
        homeLifecycle = DrawerHomeLifecycle.Dragging
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
        resetDragFrames()
        cancelMotion()
        homeLifecycle = drawerIdleLifecycle(visualProgress.value)
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
        resetDragFrames()
        cancelMotion()
        homeLifecycle = drawerIdleLifecycle(visualProgress.value)
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
        initialVelocity: Float? = null,
    ) {
        // Revoking the previous motion cancels Animatable and clears its velocity. Sample it
        // first so a quick close/re-open continues from the moving panel without a dead stop.
        val startingProgress = visualProgress.value
        val startingVelocity = initialVelocity ?: if (directManipulation) 0f else progress.velocity
        revokeGestures()
        val job = currentCoroutineContext()[Job]
        motionJob = job
        motion = DrawerMotion(target, spec)
        homeLifecycle = drawerMotionLifecycle(target)
        var reached = false
        try {
            if (directManipulation) {
                progress.snapTo(startingProgress)
                currentCoroutineContext().ensureActive()
                if (motionJob !== job) return
                directManipulation = false
            }
            animateToAnchor(target, spec, startingVelocity)
            if (motionJob === job) {
                settled = target
                homeLifecycle = drawerSettledLifecycle(target)
                reached = true
            }
        } finally {
            if (motionJob === job) {
                motionJob = null
                motion = null
                if (!reached) homeLifecycle = drawerIdleLifecycle(visualProgress.value)
            }
        }
    }

    suspend fun snapTo(target: DrawerValue) {
        revokeGestures()
        progress.snapTo(if (target == DrawerValue.Open) 1f else 0f)
        directManipulation = false
        settled = target
        homeLifecycle = drawerSettledLifecycle(target)
    }

    /** Pointer movement is visible immediately, without waiting for the animation mutator. */
    fun applyGestureFrame(session: DrawerGestureSession, progressValue: Float) {
        if (!isGestureActive(session)) return
        dragProgress = progressValue.coerceIn(0f, 1f)
    }

    fun presentDragChange(
        session: DrawerGestureSession,
        accumulator: DrawerDragAccumulator,
        generation: Long,
        change: PointerInputChange,
        deltaPx: Float,
        scope: CoroutineScope,
    ) {
        val history = if (hasDragFrame) change.historical else emptyList()
        var previousX = change.previousPosition.x
        if (deltaPx == 0f && history.none { it.position.x != previousX }) return

        fun present(delta: Float, time: Long) {
            val next = accumulator.dragBy(generation, delta)
            if (!next.isNaN()) presentDragSample(session, next, time, scope)
        }

        // Once the first frame is visible, keep turns and pauses inside a delivered batch.
        var remaining = deltaPx
        for (sample in history) {
            val delta = sample.position.x - previousX
            present(delta, sample.uptimeMillis)
            previousX = sample.position.x
            remaining -= delta
        }
        present(remaining, change.uptimeMillis)
    }

    /** Keep the first response immediate. Once drawing, sample between real pointer positions. */
    fun presentDragSample(session: DrawerGestureSession, progress: Float, uptimeMillis: Long, scope: CoroutineScope) {
        if (!isGestureActive(session)) return
        val previous = dragSamples.lastOrNull()
        val time = maxOf(uptimeMillis * 1_000_000L, previous?.timeNanos ?: 0L)
        val position = progress.coerceIn(0f, 1f)
        if (!hasDragFrame) {
            dragSamples.clear()
            dragProgress = position
        } else if (previous?.timeNanos == time) {
            dragSamples.removeLast()
        }
        dragSamples.addLast(DrawerDragSample(time, position))
        if (dragFrameJob?.isActive == true) return
        dragFrameJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            do {
                withFrameNanos { frameTime ->
                    hasDragFrame = true
                    val drawTime = frameTime - DRAG_INTERPOLATION_NANOS
                    while (dragSamples.size > 1 && dragSamples[1].timeNanos <= drawTime) dragSamples.removeFirst()
                    val first = dragSamples.first()
                    val next = dragSamples.getOrNull(1)
                    dragProgress = if (next == null || drawTime <= first.timeNanos) first.progress else {
                        val fraction = (drawTime - first.timeNanos).toFloat() / (next.timeNanos - first.timeNanos)
                        first.progress + (next.progress - first.progress) * fraction
                    }
                }
            } while (dragSamples.size > 1)
        }
    }

    private fun resetDragFrames() {
        dragFrameJob?.cancel()
        dragFrameJob = null
        dragSamples.clear()
        hasDragFrame = false
    }

    /** Continue from the presented position with the finger's release velocity. */
    suspend fun settleGesture(
        session: DrawerGestureSession,
        target: DrawerValue,
        spec: AnimationSpec<Float>,
        initialVelocity: Float = 0f,
        releaseProgress: Float? = null,
    ) {
        if (!isGestureCurrent(session)) return
        val startingProgress = releaseProgress ?: visualProgress.value
        val job = currentCoroutineContext()[Job]
        motionJob = job
        motion = DrawerMotion(target, spec)
        homeLifecycle = drawerMotionLifecycle(target)
        var reached = false
        try {
            if (!isGestureCurrent(session)) return
            currentCoroutineContext().ensureActive()
            if (directManipulation || progress.value != startingProgress) {
                progress.snapTo(startingProgress)
                if (!isGestureCurrent(session)) return
            }
            currentCoroutineContext().ensureActive()
            directManipulation = false
            animateToAnchor(target, spec, initialVelocity)
            if (isGestureCurrent(session) && motionJob === job) {
                settled = target
                homeLifecycle = drawerSettledLifecycle(target)
                reached = true
            }
        } finally {
            if (motionJob === job) {
                motionJob = null
                motion = null
                if (!reached) homeLifecycle = drawerIdleLifecycle(visualProgress.value)
            }
        }
    }

    private suspend fun animateToAnchor(target: DrawerValue, spec: AnimationSpec<Float>, velocity: Float) {
        val anchor = if (target == DrawerValue.Open) 1f else 0f
        if (progress.value == anchor) return
        progress.animateTo(anchor, spec, velocity)
        // A programmatic reversal can briefly carry momentum toward the opposite bound.
        if (progress.value != anchor) progress.animateTo(anchor, spec)
    }

    private fun revokeGestures() {
        activeGesture = null
        gestureSession = null
        gestureGeneration++
        resetDragFrames()
        cancelMotion()
    }

    private fun cancelMotion() {
        motionJob?.cancel()
        motionJob = null
        motion = null
    }
}

internal val LocalDrawerGestureState = staticCompositionLocalOf<SuixingDrawerState?> { null }

private const val DRAWER_EDGE_EXCLUSION_DP = 32f
private const val MAX_DRAWER_RELEASE_SPEED = 7f // panel widths per second
private const val EARLY_DRAWER_DRAG_MS = 24L

/** Wait for a clear lead when the first movements are nearly diagonal. */
private fun drawerDragAxis(
    horizontal: Float,
    vertical: Float,
    slop: Float,
    allowEarlyHorizontal: Boolean,
): Orientation? {
    if (allowEarlyHorizontal && abs(horizontal) > maxOf(slop * (2f / 3f), abs(vertical) * 3f)) {
        return Orientation.Horizontal
    }
    if (NativeCore.available) {
        return when (NativeCoreJni.nativeDrawerAxis(horizontal, vertical, slop)) {
            1 -> Orientation.Horizontal
            2 -> Orientation.Vertical
            else -> null
        }
    }
    val x = abs(horizontal)
    val y = abs(vertical)
    val lead = slop / 2f
    return when {
        x > maxOf(slop, y + lead) -> Orientation.Horizontal
        y > maxOf(slop * 2f, x + lead) -> Orientation.Vertical
        else -> null
    }
}

private fun canStartDrawerDrag(progress: Float, delta: Float): Boolean =
    (delta > 0f && progress < 1f) || (delta < 0f && progress > 0f)

/** Horizontal content keeps a drag only when it can scroll in the finger's direction. */
@Composable
internal fun Modifier.drawerScrollPriority(canScroll: (Float) -> Boolean): Modifier {
    val state = LocalDrawerGestureState.current ?: return this
    val canScrollNow by rememberUpdatedState(canScroll)
    return pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            state.priorityPointerId = down.id
            state.priorityCanScroll = { delta -> canScrollNow(delta) }
            try {
                while (currentEvent.changes.any { it.pressed }) awaitPointerEvent(PointerEventPass.Final)
            } finally {
                if (state.priorityPointerId == down.id) {
                    state.priorityPointerId = null
                    state.priorityCanScroll = null
                }
            }
        }
    }
}

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

@Composable
internal fun rememberSuixingDrawerState(
    initialValue: DrawerValue = DrawerValue.Closed,
): SuixingDrawerState = remember { SuixingDrawerState(initialValue) }

/** Keep refresh-rate votes in a small scope and use Compose's platform-aware layer API. */
@Composable
private fun DrawerPanelFrameRateHost(
    state: SuixingDrawerState,
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    // A category lets Android choose an appropriate supported rate. Return to the default
    // as soon as motion ends, instead of holding the whole app at a fixed 90/120 Hz.
    val frameRateCategory = if (state.isMoving) FrameRateCategory.High else FrameRateCategory.Default
    Box(modifier.preferredFrameRate(frameRateCategory), content = content)
}

/** Keep the window exclusion update out of the drawer's gesture and content scope. */
@Composable
private fun BoxScope.DrawerEdgeGestureExclusion(state: SuixingDrawerState, widthPx: Float) {
    val bounds = Modifier.matchParentSize()
    Box(
        if (state.homeShieldActive) bounds else bounds.systemGestureExclusion { coordinates ->
            Rect(0f, 0f, widthPx, coordinates.size.height.toFloat())
        },
    )
}

/**
 * 抽屉宿主：侧边栏在下、主页在上（先画侧边栏，后画主页 ⇒ 主页的阴影能压上去）。
 *
 * ⚠️ 宿主与冻结层是**两套故意不同**的手势循环，不要"统一"成一份：
 *  · 宿主 = 横向判定后才消费事件，竖滑要原样放行
 *    给聊天列表滚动；
 *  · 冻结层 = `awaitEachGesture` 全消费：抽屉开着时竖滑也必须吞掉（挡列表滚动），
 *    轻点算"点遮罩"。消费语义相反，合并必然破坏其中一边。
 *
 * 抽屉面板和主页在绘制层读同一个 `state.visualProgress` 做变换。
 */
@Composable
internal fun SuixingDrawer(
    state: SuixingDrawerState,
    gesturesEnabled: Boolean,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val panelPx = with(density) { DRAWER_SHEET_WIDTH.dp.toPx() }
    val edgeExclusionPx = with(density) { DRAWER_EDGE_EXCLUSION_DP.dp.toPx() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val conversationSwitch = LocalDrawerBusy.current
    val parentDecorationMotion = LocalDecorationMotion.current
    val homeDecorationMotion = remember(state, parentDecorationMotion) {
        { parentDecorationMotion() && !state.homeShieldActive }
    }

    // Accumulate physical deltas independently of the release animation.
    val dragAccumulator = remember(state) { DrawerDragAccumulator() }
    val scope = rememberCoroutineScope()
    // **松手回弹用 spring 并吃进手指速度**（用户：「手势的收起动画好像有点违和」）。
    // tween 不认初速度 ⇒ 快划松手时动画会从 0 速度重新起步，看着像"顿一下再走"。
    // spring 带 initialVelocity 才能与手指的甩动连续。
    val settle: (DrawerGestureSession, DrawerValue, Float, DrawerDragRelease, AnimationSpec<Float>) -> Unit =
        remember(scope, state, dragAccumulator) {
            { session, target, progressVelocity, releaseProgress, spec ->
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    // A newer drag or external close invalidates this release.
                    if (!dragAccumulator.canSettle(releaseProgress) ||
                        !state.isGestureCurrent(session)
                    ) {
                        return@launch
                    }
                    state.settleGesture(
                        session,
                        target,
                        spec,
                        initialVelocity = progressVelocity,
                    )
                }
            }
        }
    val settleDrag: (DrawerGestureSession, DrawerDragRelease, Float) -> Unit = remember(density, panelPx, settle) {
        { session, release, velocity ->
            val target = drawerDragTarget(release.openByDistance, velocity / density.density)
            // Use the raw speed for the anchor. Bound the spring's speed after the finger lifts.
            val progressVelocity = velocity / panelPx
            val springVelocity = when (target) {
                DrawerValue.Open -> progressVelocity.coerceIn(0f, MAX_DRAWER_RELEASE_SPEED)
                DrawerValue.Closed -> progressVelocity.coerceIn(-MAX_DRAWER_RELEASE_SPEED, 0f)
            }
            val spec = if (target == DrawerValue.Closed) DRAWER_CLOSE_SPEC else DRAWER_OPEN_SPEC
            settle(session, target, springVelocity, release, spec)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(state, gesturesEnabled, panelPx) {
                if (!gesturesEnabled) return@pointerInput
                val tracker = DrawerPointerVelocity(viewConfiguration.touchSlop)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    state.rejectedLeftSwipePointer = null
                    // Let descendants register their DOWN before choosing the gesture owner.
                    awaitPointerEvent(PointerEventPass.Main)
                    if (state.priorityPointerId == down.id && state.priorityCanScroll == null)
                        return@awaitEachGesture
                    val interruptedMotion = state.motion
                    val interruptedVelocity = state.progress.velocity
                    var dismissKeyboardOnOpen = !state.homeShieldActive
                    var ownedSession = interruptedMotion?.let { state.beginGesture() }
                    var ownedGeneration = ownedSession?.let {
                        dragAccumulator.begin(state.visualProgress.value, panelPx)
                    }
                    tracker.trackDrawerPointer(down)
                    var pointerId = down.id
                    var horizontal = 0f
                    var vertical = 0f
                    var appliedHorizontal = 0f
                    var isDrag = false
                    var released = false
                    var childOwnsGesture = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            val childFirst = !isDrag && !childOwnsGesture &&
                                change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
                            if (childFirst) {
                                // Long-press selection may already own movement in a descendant.
                                awaitPointerEvent(PointerEventPass.Main)
                                if (change.isConsumed) {
                                    childOwnsGesture = true
                                    break
                                }
                            }
                            tracker.trackDrawerPointer(change)
                            val dx = change.position.x - change.previousPosition.x
                            horizontal += dx
                            vertical += change.position.y - change.previousPosition.y
                            if (!isDrag) {
                                val axis = drawerDragAxis(
                                    horizontal, vertical, viewConfiguration.touchSlop,
                                    allowEarlyHorizontal = ownedSession == null && !childOwnsGesture &&
                                        state.priorityPointerId != down.id &&
                                        change.pressed && horizontal > 0f &&
                                        change.uptimeMillis - down.uptimeMillis in 1L..EARLY_DRAWER_DRAG_MS &&
                                        canStartDrawerDrag(state.visualProgress.value, horizontal),
                                )
                                if (axis == Orientation.Vertical) {
                                    childOwnsGesture = true
                                    break
                                }
                                if (axis == Orientation.Horizontal) {
                                    val childCanScroll = state.priorityPointerId == down.id &&
                                        state.priorityCanScroll?.invoke(dx) == true
                                    if (childCanScroll) {
                                        childOwnsGesture = true
                                    } else if (!childOwnsGesture || dx != 0f) {
                                        val delta = if (childOwnsGesture) dx else horizontal
                                        if (ownedSession != null || canStartDrawerDrag(state.visualProgress.value, delta)) {
                                            if (ownedSession == null) {
                                                if (childOwnsGesture) {
                                                    appliedHorizontal = horizontal - dx
                                                    tracker.resetTracking(change)
                                                }
                                                ownedSession = state.beginGesture()
                                                ownedGeneration = dragAccumulator.begin(state.visualProgress.value, panelPx)
                                            }
                                            childOwnsGesture = false
                                            isDrag = true
                                        } else if (!childOwnsGesture) {
                                            // Keep a wrong-way swipe with the page until the finger lifts.
                                            if (delta < 0f) state.rejectedLeftSwipePointer = down.id
                                            break
                                        }
                                    }
                                }
                            }
                            if (childOwnsGesture && !isDrag) {
                                val session = ownedSession
                                val generation = ownedGeneration
                                ownedSession = null
                                ownedGeneration = null
                                if (session != null && generation != null) {
                                    val release = dragAccumulator.finish(generation)
                                    val stillOwned = state.finishGesture(session)
                                    if (release != null && stillOwned && interruptedMotion != null) {
                                        settle(session, interruptedMotion.target, interruptedVelocity,
                                            release, interruptedMotion.spec)
                                    }
                                }
                            }
                            val session = ownedSession
                            val generation = ownedGeneration
                            if (session != null && generation != null) {
                                state.presentDragChange(
                                    session, dragAccumulator, generation, change, horizontal - appliedHorizontal, scope,
                                )
                                appliedHorizontal = horizontal
                            }
                            if (isDrag) {
                                change.consume()
                                if (dismissKeyboardOnOpen && dx > 0f) {
                                    dismissKeyboardOnOpen = false
                                    keyboardController?.hide()
                                    focusManager.clearFocus(force = true)
                                }
                            }
                            if (change.changedToUpIgnoreConsumed()) {
                                val replacement = event.changes.firstOrNull { it.pressed }
                                if (replacement != null) {
                                    pointerId = replacement.id
                                    tracker.trackDrawerPointer(replacement)
                                    continue
                                }
                                // An ordinary row tap may trigger an external drawer action.
                                if (!isDrag && !childFirst) awaitPointerEvent(PointerEventPass.Main)
                                released = true
                                break
                            }
                            if (!isDrag) {
                                awaitPointerEvent(PointerEventPass.Final)
                                if (change.isConsumed) {
                                    childOwnsGesture = true
                                    // Respect an established vertical scroll while keeping diagonal starts eligible.
                                    if (state.priorityPointerId != down.id &&
                                        abs(vertical) > abs(horizontal) + viewConfiguration.touchSlop
                                    ) break
                                }
                            }
                        }
                        val session = ownedSession
                        val generation = ownedGeneration
                        if ((released || childOwnsGesture) && session != null && generation != null) {
                            val release = dragAccumulator.finish(generation)
                            val stillOwned = state.finishGesture(session)
                            ownedSession = null
                            ownedGeneration = null
                            if (release != null && stillOwned) {
                                if (isDrag && !childOwnsGesture) {
                                    val v = tracker.calculateVelocity(viewConfiguration.maximumFlingVelocity).x
                                    settleDrag(session, release, v)
                                } else if (interruptedMotion != null) {
                                    settle(session, interruptedMotion.target, interruptedVelocity,
                                        release, interruptedMotion.spec)
                                }
                            }
                        }
                    } finally {
                        cancelOwnedDrawerDrag(state, dragAccumulator, ownedSession, ownedGeneration)
                    }
                }
            },
    ) {
        // Move the whole panel as a layer. The progress read stays in the draw phase, so
        // opening/closing does not re-place the drawer's LazyColumn on every animation frame.
        // The panel's vote is enough for the window. Restrict propagation to this small
        // subtree instead of walking every Markdown/text layer in the home page.
        DrawerPanelFrameRateHost(
            state = state,
            modifier = Modifier
                .width(DRAWER_SHEET_WIDTH.dp)
                .fillMaxHeight()
                .graphicsLayer {
                    translationX = -panelPx * (1f - state.visualProgress.value)
                },
        ) {
            drawerContent()
        }
        // 主页：后画 ⇒ 在上层。
        CompositionLocalProvider(
            LocalDrawerGestureState provides state,
            LocalDecorationMotion provides homeDecorationMotion,
        ) {
            content()
        }
        if (gesturesEnabled) DrawerEdgeGestureExclusion(state, edgeExclusionPx)
        // Keep the shield ready while closed, outside the viewport. During motion its clipped
        // hit region starts at the panel edge, leaving drawer rows and their gestures exposed.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    clip = true
                    translationX = if (state.homeShieldActive || conversationSwitch.value) {
                        drawerHomeShieldStartPx(panelPx, state.visualProgress.value)
                    } else {
                        size.width
                    }
                }
                // **一个手势处理器，不要拆成两个 detector**：
                // `detectHorizontalDragGestures` 与 `detectTapGestures` 各自独立消费指针，
                // 轻点很可能先被拖动那边吃掉 ⇒ "点一下关闭"不稳定。
                // 这里用 `awaitEachGesture` 分流横拖、竖滑与轻点，并全程消费事件。
                .pointerInput(state, panelPx) {
                    val tracker = DrawerPointerVelocity(viewConfiguration.touchSlop)
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        state.priorityPointerId = down.id
                        state.priorityCanScroll = null
                        tracker.trackDrawerPointer(down)
                        var pointerId = down.id
                        var horizontal = 0f
                        var vertical = 0f
                        var isDrag = false
                        var ignoreSwipe = false
                        var movedPastSlop = false
                        var appliedHorizontal = 0f
                        val interruptedMotion = state.motion
                        val interruptedVelocity = state.progress.velocity
                        var ownedSession = interruptedMotion?.let { state.beginGesture() }
                        var ownedGeneration = ownedSession?.let {
                            dragAccumulator.begin(state.visualProgress.value, panelPx)
                        }
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                tracker.trackDrawerPointer(change)
                                horizontal += change.position.x - change.previousPosition.x
                                vertical += change.position.y - change.previousPosition.y
                                event.changes.forEach { it.consume() }
                                if (!isDrag && !ignoreSwipe) {
                                    val slop = viewConfiguration.touchSlop
                                    movedPastSlop = movedPastSlop || maxOf(abs(horizontal), abs(vertical)) > slop
                                    val axis = drawerDragAxis(
                                        horizontal, vertical, slop,
                                        allowEarlyHorizontal = ownedSession == null && change.pressed && horizontal < 0f &&
                                            change.uptimeMillis - down.uptimeMillis in 1L..EARLY_DRAWER_DRAG_MS &&
                                            canStartDrawerDrag(state.visualProgress.value, horizontal),
                                    )
                                    isDrag = axis == Orientation.Horizontal &&
                                        (ownedSession != null ||
                                            canStartDrawerDrag(state.visualProgress.value, horizontal))
                                    // A rejected or undecided swipe must not become a shield tap.
                                    ignoreSwipe = !isDrag && (axis != null ||
                                        (movedPastSlop && !change.pressed && event.changes.none { it.pressed }))
                                    if (isDrag && ownedSession == null) {
                                        ownedSession = state.beginGesture()
                                        ownedGeneration = dragAccumulator.begin(state.visualProgress.value, panelPx)
                                    } else if (ignoreSwipe) {
                                        val session = ownedSession
                                        val generation = ownedGeneration
                                        ownedSession = null
                                        ownedGeneration = null
                                        if (session != null && generation != null) {
                                            val release = dragAccumulator.finish(generation)
                                            val stillOwned = state.finishGesture(session)
                                            if (release != null && stillOwned && interruptedMotion != null) {
                                                settle(session, interruptedMotion.target,
                                                    interruptedVelocity, release, interruptedMotion.spec)
                                            }
                                        }
                                    }
                                }
                                val session = ownedSession
                                val generation = ownedGeneration
                                if (session != null && generation != null) {
                                    state.presentDragChange(
                                        session, dragAccumulator, generation, change, horizontal - appliedHorizontal, scope,
                                    )
                                    appliedHorizontal = horizontal
                                }
                                if (change.changedToUpIgnoreConsumed()) {
                                    val replacement = event.changes.firstOrNull { it.pressed }
                                    if (replacement != null) {
                                        pointerId = replacement.id
                                        tracker.trackDrawerPointer(replacement)
                                        continue
                                    }
                                    if (ignoreSwipe) break
                                    if (!isDrag) {
                                        // **轻点 = 点遮罩**：外部状态动作优先，作废旧拖拽。
                                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                            state.animateTo(DrawerValue.Closed, DRAWER_CLOSE_SPEC)
                                        }
                                    } else {
                                        ownedSession = null
                                        ownedGeneration = null
                                        val releaseProgress = generation?.let {
                                            dragAccumulator.finish(it)
                                        }
                                        val stillOwned = session != null &&
                                            state.finishGesture(session)
                                        if (releaseProgress != null && stillOwned) {
                                            val v = tracker.calculateVelocity(viewConfiguration.maximumFlingVelocity).x
                                            settleDrag(session, releaseProgress, v)
                                        }
                                    }
                                    break
                                }
                            }
                        } finally {
                            val session = ownedSession
                            val generation = ownedGeneration
                            ownedSession = null
                            ownedGeneration = null
                            if (state.priorityPointerId == down.id) {
                                state.priorityPointerId = null
                                state.priorityCanScroll = null
                            }
                            cancelOwnedDrawerDrag(state, dragAccumulator, session, generation)
                        }
                    }
                },
        )
    }
}
