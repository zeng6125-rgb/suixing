package com.suixing.agent.ui.drawer

import com.suixing.agent.core.NativeCore
import com.suixing.agent.core.NativeCoreJni
import java.nio.ByteBuffer
import kotlin.math.abs

/** The final progress for one drag; a later begin invalidates this release. */
internal data class DrawerDragRelease(val generation: Long, val progress: Float, val openByDistance: Boolean)

/**
 * Accumulates pointer deltas from the progress captured when a gesture takes ownership.
 * The release animation never supplies the next pointer position.
 */
internal class DrawerDragAccumulator {
    private val nativeDrag = if (NativeCore.available) ByteBuffer.allocateDirect(7 * Float.SIZE_BYTES) else null
    private var generation = 0L
    private var active = false
    private var startProgress = 0f
    private var panelWidthPx = 1f
    private var minOffsetPx = 0f
    private var maxOffsetPx = 1f
    private var accumulatedPx = 0f
    private var strokeStartPx = 0f
    private var furthestTravelPx = 0f
    private var latestProgress = 0f

    fun begin(progress: Float, widthPx: Float): Long {
        require(widthPx > 0f)
        generation++
        active = true
        latestProgress = progress.coerceIn(0f, 1f)
        if (nativeDrag != null) {
            NativeCoreJni.nativeDrawerDragBegin(nativeDrag, latestProgress, widthPx)
            return generation
        }
        startProgress = latestProgress
        panelWidthPx = widthPx
        minOffsetPx = -startProgress * panelWidthPx
        maxOffsetPx = (1f - startProgress) * panelWidthPx
        accumulatedPx = 0f
        strokeStartPx = 0f
        furthestTravelPx = 0f
        return generation
    }

    /**
     * A callback may outlive its gesture while another detector already owns the accumulator.
     * Returns NaN for a stale generation without changing the active drag.
     */
    fun dragBy(dragGeneration: Long, deltaPx: Float): Float {
        if (!active || generation != dragGeneration) return Float.NaN
        if (deltaPx == 0f) return latestProgress
        if (nativeDrag != null) {
            latestProgress = NativeCoreJni.nativeDrawerDragAdvance(nativeDrag, deltaPx)
            return latestProgress
        }
        accumulatedPx = (accumulatedPx + deltaPx).coerceIn(minOffsetPx, maxOffsetPx)
        if (accumulatedPx == minOffsetPx || accumulatedPx == maxOffsetPx) {
            strokeStartPx = accumulatedPx
            furthestTravelPx = 0f
        } else {
            furthestTravelPx = maxOf(furthestTravelPx, abs(accumulatedPx - strokeStartPx))
        }
        latestProgress = startProgress + accumulatedPx / panelWidthPx
        return latestProgress
    }

    fun canSettle(release: DrawerDragRelease): Boolean =
        !active && release.generation == generation

    /** Cancels only the drag that owns [dragGeneration], leaving any newer drag untouched. */
    fun cancel(dragGeneration: Long): Boolean {
        if (!active || generation != dragGeneration) return false
        active = false
        generation++
        return true
    }

    /**
     * A stale pointer's finish must not deactivate a newer drag.
     */
    fun finish(dragGeneration: Long): DrawerDragRelease? {
        if (!active || generation != dragGeneration) return null
        active = false
        generation++
        val openByDistance = if (nativeDrag != null) {
            NativeCoreJni.nativeDrawerDragOpens(nativeDrag)
        } else {
            opensByDistance()
        }
        return DrawerDragRelease(generation, latestProgress, openByDistance)
    }

    private fun opensByDistance(): Boolean {
        val travel = abs(accumulatedPx - strokeStartPx)
        val quarterWidth = panelWidthPx * 0.25f
        // A short stroke can leave an endpoint. A deliberate pullback restores midpoint settling.
        val shortSwipe = travel > quarterWidth && furthestTravelPx - travel < quarterWidth
        return when {
            shortSwipe && strokeStartPx == minOffsetPx -> true
            shortSwipe && strokeStartPx == maxOffsetPx -> false
            else -> latestProgress > 0.5f
        }
    }
}
