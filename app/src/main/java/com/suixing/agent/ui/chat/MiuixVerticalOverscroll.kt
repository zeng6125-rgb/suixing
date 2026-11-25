package com.suixing.agent.ui.chat

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.isActive
import top.yukonga.miuix.kmp.utils.MiuixOverscrollFactory
import kotlin.math.abs

/**
 * 聊天列表与官方 Miuix (`MiuixOverscrollFactory`) 弹性过卷的融合适配器：
 * 1. **严格锁死纯纵向**：只把 `available.y` / `velocity.y` 交给 `MiuixOverscrollEffect`，
 *    横向分量直接透传给 `performScroll` / `performFling` 且不累积水平 `offsetX`，
 *    彻底消除斜滑/抽屉边沿手势引起的整屏横向漂移或手势被吞问题；
 * 2. **帧级精确跟踪弹性回弹状态 (`active`)**：Miuix 0.9.4 的 `startSpringAnimationY`
 *    收尾只调 `resetStateY()` 而不会重置 `OverScrollState`，且 `applyToFling` 启动协程后
 *    前两帧 `offsetY` 仍为 0；本宿主结合 [OverscrollFlingTracker] 与 `LaunchedEffect` 仅在过卷
 *    激活期盯帧，回弹归零当帧立刻释放 [active]，让 [TranscriptPosition] 和 [ChatScroll]
 *    在弹簧归零后再恢复文字锚定/追底计时，互不打架；
 * 3. **协调甩动边界初速**：Miuix 原生 `applyToFling` 在未过卷撞边时会把剩余速度除以
 *    `1.53333f` 并沿用严格均衡阈值，导致高刷屏快速甩底/甩顶回弹微弱甚至被吞；这里仅对
 *    `performFling` 未消费的撞边余速通过 [coordinateFlingLeftoverVelocityY] 补偿，使甩动回弹
 *    与跟手拖动阻尼手感保持一致。
 */
@Stable
internal class VerticalOverscrollHost(delegateFactory: OverscrollFactory) {
    private var tracked by mutableStateOf<List<OverscrollEffect>>(emptyList())
    private val flingTracker = OverscrollFlingTracker()
    var active by mutableStateOf(false)
        private set

    fun refreshActive(): Boolean {
        val now = tracked.any { it.isInProgress }
        if (now) flingTracker.clearPending()
        if (active != now) active = now
        return now
    }

    fun stepAnimationFrame(): Boolean {
        val now = flingTracker.step(tracked.any { it.isInProgress })
        if (active != now) active = now
        return now
    }

    fun notifyFlingSettling() {
        flingTracker.armPendingFrames()
        active = true
    }

    fun register(effect: OverscrollEffect) {
        if (tracked.none { it === effect }) {
            tracked = tracked + effect
        }
        val inProgress = tracked.any { it.isInProgress }
        if (inProgress || flingTracker.pendingFrames > 0) {
            active = true
        }
    }

    fun clear() {
        tracked = emptyList()
        flingTracker.clearPending()
        active = false
    }

    val factory: OverscrollFactory = VerticalOnlyOverscrollFactory(delegateFactory, this)
}

private class VerticalOnlyOverscrollFactory(
    private val delegateFactory: OverscrollFactory,
    private val host: VerticalOverscrollHost,
) : OverscrollFactory {
    override fun createOverscrollEffect(): OverscrollEffect =
        VerticalOnlyOverscrollEffect(delegateFactory.createOverscrollEffect(), host)

    override fun equals(other: Any?): Boolean =
        other is VerticalOnlyOverscrollFactory &&
            delegateFactory == other.delegateFactory &&
            host === other.host

    override fun hashCode(): Int = 31 * delegateFactory.hashCode() + System.identityHashCode(host)
}

private class VerticalOnlyOverscrollEffect(
    private val delegate: OverscrollEffect,
    private val host: VerticalOverscrollHost,
) : OverscrollEffect {
    override val isInProgress: Boolean
        get() = delegate.isInProgress

    override val node: DelegatableNode
        get() = delegate.node

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset {
        host.register(this)
        var consumedX = 0f
        val consumedY = delegate.applyToScroll(Offset(0f, delta.y), source) { remainingVertical ->
            val inner = performScroll(Offset(delta.x, remainingVertical.y))
            consumedX = inner.x
            Offset(0f, inner.y)
        }.y
        host.refreshActive()
        return Offset(consumedX, consumedY)
    }

    override suspend fun applyToFling(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity,
    ) {
        host.register(this)
        var triggeredFlingBounce = false
        try {
            val wasInProgress = delegate.isInProgress
            delegate.applyToFling(Velocity(0f, velocity.y)) { remainingVertical ->
                val inner = performFling(Velocity(velocity.x, remainingVertical.y))
                if (wasInProgress) {
                    host.refreshActive()
                    Velocity(0f, inner.y)
                } else {
                    val leftoverY = remainingVertical.y - inner.y
                    val compensated = coordinateFlingLeftoverVelocityY(leftoverY)
                    if (compensated != 0f) {
                        triggeredFlingBounce = true
                        host.notifyFlingSettling()
                    }
                    Velocity(0f, remainingVertical.y - compensated)
                }
            }
        } finally {
            if (triggeredFlingBounce) {
                host.notifyFlingSettling()
            } else {
                host.refreshActive()
            }
        }
    }
}

private fun miuixFactory(): OverscrollFactory = MiuixOverscrollFactory

@Composable
internal fun rememberVerticalMiuixOverscrollHost(enabled: Boolean): VerticalOverscrollHost? {
    if (!enabled) return null
    val baseFactory = miuixFactory()
    val host = remember(baseFactory) { VerticalOverscrollHost(baseFactory) }
    LaunchedEffect(host) {
        snapshotFlow { host.active }.collect { isActive ->
            if (!isActive) return@collect
            while (coroutineContext.isActive && host.stepAnimationFrame()) {
                withFrameNanos { }
            }
        }
    }
    DisposableEffect(host) {
        onDispose { host.clear() }
    }
    return host
}
