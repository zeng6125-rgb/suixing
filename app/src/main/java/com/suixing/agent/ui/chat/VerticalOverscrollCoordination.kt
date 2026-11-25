package com.suixing.agent.ui.chat

import kotlin.math.abs
import kotlin.math.sign

internal const val MIUIX_FLING_VELOCITY_DIVISOR = 1.53333f
private const val MIN_BOUNDARY_FLING_LEFTOVER = 40f
private const val MIN_VISIBLE_FLING_TRIGGER = 110f
private const val MIN_SPRING_FLING_VELOCITY = 650f
private const val FLING_BOUNCE_GAIN = 1.6f
private const val MAX_SPRING_FLING_VELOCITY = 9_500f

/**
 * Miuix `applyToFling` divides unconsumed velocity by `1.53333f` and uses a critically damped
 * `response = 0.4f` spring whose `isAtEquilibrium()` stops the animation on step 1 if `|pos| < 1px`
 * (~220 px/s at 120Hz). For non-trivial boundary arrivals, compensate that divisor and map
 * leftover momentum into an explicit spring impulse while suppressing sub-threshold jitter.
 */
internal fun coordinateFlingLeftoverVelocityY(leftoverY: Float): Float {
    if (!leftoverY.isFinite()) return 0f
    val magnitude = abs(leftoverY)
    if (magnitude < MIN_BOUNDARY_FLING_LEFTOVER) return 0f
    val boosted = magnitude * FLING_BOUNCE_GAIN
    val effectiveMagnitude = if (magnitude >= MIN_VISIBLE_FLING_TRIGGER) {
        boosted.coerceAtLeast(MIN_SPRING_FLING_VELOCITY)
    } else {
        boosted
    }.coerceAtMost(MAX_SPRING_FLING_VELOCITY)
    return sign(leftoverY) * effectiveMagnitude * MIUIX_FLING_VELOCITY_DIVISOR
}

/**
 * Pure frame tracker for bridging the 1-2 frame delay between `MiuixOverscrollEffect.applyToFling`
 * launching its coroutine and `offsetY` crossing `offsetThreshold` (`1f`).
 */
internal class OverscrollFlingTracker {
    var pendingFrames: Int = 0
        private set

    fun armPendingFrames(frames: Int = DEFAULT_PENDING_FLING_FRAMES) {
        if (frames > pendingFrames) pendingFrames = frames
    }

    fun clearPending() {
        pendingFrames = 0
    }

    fun step(delegateInProgress: Boolean): Boolean {
        if (delegateInProgress) {
            pendingFrames = 0
            return true
        }
        if (pendingFrames > 0) {
            pendingFrames--
            return true
        }
        return false
    }

    private companion object {
        const val DEFAULT_PENDING_FLING_FRAMES = 4
    }
}
