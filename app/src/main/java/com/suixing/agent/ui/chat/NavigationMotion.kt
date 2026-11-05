package com.suixing.agent.ui.chat

import androidx.compose.foundation.lazy.LazyLayoutScrollScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutScrollScope as LazyLayoutScrollSession
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sign
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext

/** Scroll toward a stable target identity, using measured geometry for the final approach. */
internal class NavigationMotion {
    suspend fun navigate(list: LazyListState, index: Int, offset: Int): NavigationMotionResult {
        if (index !in 0 until list.layoutInfo.totalItemsCount) return NavigationMotionResult.TargetUnavailable

        val durationScale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
        if (durationScale == 0f) {
            // Compose animations finish on the next frame when motion is disabled. Preserve that
            // accessibility setting with the list's immediate scroll API and no spring frames.
            list.scrollToItem(index, offset)
            return NavigationMotionResult.Arrived
        }
        val effectiveScale = durationScale.takeIf { it.isFinite() && it > 0f } ?: 1f

        val alignTargetBottom = offset == TRANSCRIPT_END_OFFSET
        // The end sentinel is a placement request, not a pixel distance. Use a normal item-top
        // estimate while the target is offscreen, then track measured bottom error once visible.
        val targetOffset = navigationEstimateOffset(offset)
        val measuredDistance: ((LazyLayoutScrollSession) -> Int)? = if (alignTargetBottom) {
            { scrollScope ->
                val layout = list.layoutInfo
                val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
                if (item == null) scrollScope.calculateDistanceTo(index, targetOffset)
                else navigationEndAlignmentError(
                    item.offset,
                    item.size,
                    layout.afterContentPadding,
                    layout.viewportEndOffset,
                )
            }
        } else null

        var terminalResult = NavigationMotionResult.TimedOut
        val completed = withTimeoutOrNull(MAX_NAVIGATION_WALL_MILLIS) {
            list.scroll {
                val scope = LazyLayoutScrollScope(list, this)
                val landing = NavigationLandingController(index, targetOffset, measuredDistance)
                var previousFrameNanos: Long? = null
                var frameDeltaNanos = 0L

                while (true) {
                    // A zero-delta first pass still returns an already-aligned target at once.
                    // Later frames calculate geometry once, rather than repeating the same
                    // estimate in a zero-delta preflight and again after the frame clock.
                    val frame = landing.scrollFrame(
                        scope,
                        frameDeltaNanos = frameDeltaNanos,
                        viewportHeight = list.layoutInfo.viewportSize.height.coerceAtLeast(1),
                    )
                    when (frame) {
                        NavigationFrameResult.Arrived -> {
                            terminalResult = NavigationMotionResult.Arrived
                            return@scroll
                        }
                        NavigationFrameResult.ScrollClamped -> {
                            terminalResult = NavigationMotionResult.ScrollClamped
                            return@scroll
                        }
                        NavigationFrameResult.TargetUnavailable -> {
                            terminalResult = NavigationMotionResult.TargetUnavailable
                            return@scroll
                        }
                        NavigationFrameResult.Continue -> Unit
                    }

                    val frameNanos = withFrameNanos { it }
                    val rawDeltaNanos = previousFrameNanos?.let { frameNanos - it }
                        ?: DEFAULT_FRAME_DELTA_NANOS
                    previousFrameNanos = frameNanos
                    frameDeltaNanos = navigationScaledFrameDelta(rawDeltaNanos, effectiveScale)
                }
            }
            true
        } ?: false

        return if (completed) terminalResult else NavigationMotionResult.TimedOut
    }

    private companion object {
        const val DEFAULT_FRAME_DELTA_NANOS = 16_666_667L
        const val MAX_NAVIGATION_WALL_MILLIS = 15_000L
    }
}

internal enum class NavigationMotionResult {
    Arrived,
    ScrollClamped,
    TargetUnavailable,
    TimedOut,
}

internal enum class NavigationFrameResult {
    Continue,
    Arrived,
    ScrollClamped,
    TargetUnavailable,
}

/**
 * One continuously-running landing controller. Its velocity survives the transition from an
 * offscreen average-height estimate to measured item geometry, so measuring the target does not
 * stop and restart the motion.
 */
internal class NavigationLandingController(
    private val targetIndex: Int,
    private val targetOffset: Int,
    private val measuredDistance: ((LazyLayoutScrollSession) -> Int)? = null,
) {
    private var velocityPxPerSecond = 0f

    fun targetIsVisible(scope: LazyLayoutScrollSession): Boolean =
        targetIndex in scope.firstVisibleItemIndex..scope.lastVisibleItemIndex

    fun distanceToTarget(scope: LazyLayoutScrollSession): Int =
        if (targetIsVisible(scope)) {
            measuredDistance?.invoke(scope) ?: scope.calculateDistanceTo(targetIndex, targetOffset)
        } else {
            scope.calculateDistanceTo(targetIndex, targetOffset)
        }

    fun scrollFrame(
        scope: LazyLayoutScrollSession,
        frameDeltaNanos: Long,
        viewportHeight: Int,
    ): NavigationFrameResult {
        if (targetIndex !in 0 until scope.itemCount) return NavigationFrameResult.TargetUnavailable

        val targetVisible = targetIsVisible(scope)
        val distance = distanceToTarget(scope)
        if (targetVisible && distance == 0) return NavigationFrameResult.Arrived

        // LazyLayoutScrollScope only approximates the position of offscreen items. A degenerate
        // layout (for example, an empty or zero-sized viewport during a resize) can report zero
        // before the target is visible. Keep moving in the known item direction at the normal
        // speed limit until placement gives us measured geometry.
        var effectiveDistance = if (!targetVisible && distance == 0) {
            when {
                targetIndex > scope.lastVisibleItemIndex -> viewportHeight.coerceAtLeast(1)
                targetIndex < scope.firstVisibleItemIndex -> -viewportHeight.coerceAtLeast(1)
                else -> 0
            }
        } else {
            distance
        }

        if (distance < 0 && scope.firstVisibleItemIndex == 0) {
            // At the physical top, only the current first-item scroll offset is reachable. Ease
            // toward that boundary instead of driving into an impossible negative offset.
            effectiveDistance = maxOf(effectiveDistance, -scope.firstVisibleItemScrollOffset)
            if (effectiveDistance == 0) return NavigationFrameResult.ScrollClamped
        }

        val requested = nextScrollDelta(effectiveDistance.toFloat(), frameDeltaNanos, viewportHeight)
        if (requested == 0f) return NavigationFrameResult.Continue

        val consumed = scope.scrollBy(requested)
        if (targetIndex !in 0 until scope.itemCount) return NavigationFrameResult.TargetUnavailable
        if (abs(consumed - requested) > SCROLL_TOLERANCE_PX) return NavigationFrameResult.ScrollClamped

        if (targetIsVisible(scope) && distanceToTarget(scope) == 0) return NavigationFrameResult.Arrived
        return NavigationFrameResult.Continue
    }

    private fun nextScrollDelta(distancePx: Float, frameDeltaNanos: Long, viewportHeight: Int): Float {
        if (distancePx == 0f) return 0f
        val elapsedSeconds = frameDeltaNanos.coerceIn(0L, MAX_FRAME_DELTA_NANOS) / NANOS_PER_SECOND
        if (elapsedSeconds <= 0f) return 0f

        val direction = sign(distancePx)
        // Geometry can change while scrolling. If it moves the target across the viewport, do not
        // carry momentum away from its new position; otherwise preserve the current velocity.
        if (velocityPxPerSecond * direction < 0f) velocityPxPerSecond = 0f

        val maxSpeed = maximumSpeed(distancePx, viewportHeight)
        val omega = SPRING_ANGULAR_FREQUENCY
        val errorSpeedLimit = min(maxSpeed, omega * abs(distancePx))
        // An estimate-to-measure handoff can shrink the remaining error while velocity still
        // reflects the larger estimate. Project momentum to the critically damped envelope before
        // integration so the next step cannot consume the whole short residual.
        velocityPxPerSecond = velocityPxPerSecond.coerceIn(-errorSpeedLimit, errorSpeedLimit)
        val velocityTerm = omega * distancePx - velocityPxPerSecond
        val decay = exp((-omega * elapsedSeconds).toDouble()).toFloat()
        val nextError = (distancePx + velocityTerm * elapsedSeconds) * decay
        val analyticDelta = distancePx - nextError
        if (!analyticDelta.isFinite() || analyticDelta * direction <= 0f) {
            velocityPxPerSecond = 0f
            return 0f
        }

        val frameBudget = maxSpeed * elapsedSeconds
        val remaining = abs(distancePx)
        val deltaMagnitude = min(min(abs(analyticDelta), frameBudget), remaining)
        val requested = direction * deltaMagnitude

        val nextVelocity = (velocityPxPerSecond + omega * velocityTerm * elapsedSeconds) * decay
        val remainingAfterStep = (remaining - deltaMagnitude).coerceAtLeast(0f)
        val nextMaxSpeed = maximumSpeed(direction * remainingAfterStep, viewportHeight)
        val remainingErrorSpeedLimit = min(nextMaxSpeed, omega * remainingAfterStep)
        velocityPxPerSecond = when {
            deltaMagnitude >= remaining -> 0f
            nextVelocity * direction < 0f -> 0f
            else -> nextVelocity.coerceIn(-remainingErrorSpeedLimit, remainingErrorSpeedLimit)
        }
        return requested
    }

    private fun maximumSpeed(distancePx: Float, viewportHeight: Int): Float {
        val viewport = viewportHeight.coerceAtLeast(1).toFloat()
        val distanceInViewports = abs(distancePx) / viewport
        val viewportsPerSecond = when {
            distanceInViewports <= MAX_SPEED_PROFILE_VIEWPORTS -> {
                val progress = ((distanceInViewports - MIN_SPEED_PROFILE_VIEWPORTS) /
                    (MAX_SPEED_PROFILE_VIEWPORTS - MIN_SPEED_PROFILE_VIEWPORTS)).coerceIn(0f, 1f)
                val smoothstep = progress * progress * (3f - 2f * progress)
                MIN_VIEWPORTS_PER_SECOND +
                    (MAX_VIEWPORTS_PER_SECOND - MIN_VIEWPORTS_PER_SECOND) * smoothstep
            }
            distanceInViewports <= FAR_SPEED_PROFILE_START_VIEWPORTS -> {
                val progress = ((distanceInViewports - MAX_SPEED_PROFILE_VIEWPORTS) /
                    (FAR_SPEED_PROFILE_START_VIEWPORTS - MAX_SPEED_PROFILE_VIEWPORTS)).coerceIn(0f, 1f)
                val smoothstep = progress * progress * (3f - 2f * progress)
                MAX_VIEWPORTS_PER_SECOND +
                    (FAR_SPEED_PROFILE_START_VPS - MAX_VIEWPORTS_PER_SECOND) * smoothstep
            }
            else -> {
                // Keep the 12–18 viewport/s close profile untouched. Far jumps gain speed
                // continuously as distance grows; the same landing controller retains velocity
                // across this profile and the existing critically damped final approach.
                val progress = ((distanceInViewports - FAR_SPEED_PROFILE_START_VIEWPORTS) /
                    (FAR_SPEED_PROFILE_END_VIEWPORTS - FAR_SPEED_PROFILE_START_VIEWPORTS)).coerceIn(0f, 1f)
                val smoothstep = progress * progress * (3f - 2f * progress)
                FAR_SPEED_PROFILE_START_VPS +
                    (MAX_FAR_VIEWPORTS_PER_SECOND - FAR_SPEED_PROFILE_START_VPS) * smoothstep
            }
        }
        return viewport * viewportsPerSecond
    }

    private companion object {
        const val MAX_FRAME_DELTA_NANOS = 32_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val MIN_VIEWPORTS_PER_SECOND = 12f
        const val MAX_VIEWPORTS_PER_SECOND = 18f
        const val MIN_SPEED_PROFILE_VIEWPORTS = 2f
        const val MAX_SPEED_PROFILE_VIEWPORTS = 10f
        const val FAR_SPEED_PROFILE_START_VIEWPORTS = 50f
        const val FAR_SPEED_PROFILE_END_VIEWPORTS = 200f
        const val FAR_SPEED_PROFILE_START_VPS = 36f
        const val SPRING_ANGULAR_FREQUENCY = 14f
        const val MAX_FAR_VIEWPORTS_PER_SECOND = 72f
        const val SCROLL_TOLERANCE_PX = 0.5f
    }
}

internal fun navigationScaledFrameDelta(frameDeltaNanos: Long, durationScale: Float): Long {
    val safeScale = durationScale.takeIf { it.isFinite() && it > 0f } ?: 1f
    val nonNegativeDelta = frameDeltaNanos.coerceAtLeast(0L)
    return (nonNegativeDelta / safeScale).toLong().coerceAtMost(32_000_000L)
}

internal fun navigationEndAlignmentError(
    itemOffset: Int,
    itemSize: Int,
    afterContentPadding: Int,
    viewportEndOffset: Int,
): Int = (itemOffset.toLong() + itemSize + afterContentPadding - viewportEndOffset)
    .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

internal fun navigationEstimateOffset(offset: Int): Int =
    if (offset == TRANSCRIPT_END_OFFSET) 0 else offset

/** Resolve again after asynchronous work; a saved list index is not a message identity. */
internal fun navigationTargetIndex(
    key: String,
    rowIndices: Map<String, Int>,
    rowOffset: Int,
    liveKey: String?,
    lastIndex: Int,
): Int? = rowIndices[key]?.let { rowOffset + it }
    ?: lastIndex.takeIf { key == liveKey && it >= 0 }
