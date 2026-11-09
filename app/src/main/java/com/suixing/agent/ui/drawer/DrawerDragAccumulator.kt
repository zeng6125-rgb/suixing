package com.suixing.agent.ui.drawer

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/** A snapshot a frame consumer may apply only while this exact drag is still active. */
internal data class DrawerDragFrame(val generation: Long, val progress: Float)

/** The final progress for one drag; a later begin invalidates this release. */
internal data class DrawerDragRelease(val generation: Long, val progress: Float)

/**
 * Accumulates pointer deltas from the progress captured when a horizontal drag wins touch slop.
 * It does not read the animated value on each move, which may still be one display frame behind.
 */
internal class DrawerDragAccumulator {
    private var generation = 0L
    private var active = false
    private var startProgress = 0f
    private var panelWidthPx = 1f
    private var minOffsetPx = 0f
    private var maxOffsetPx = 1f
    private var accumulatedPx = 0f
    private var latestProgress = 0f
    private var cachedFrame: DrawerDragFrame? = null
    private var updates: Channel<Unit>? = null

    fun begin(progress: Float, widthPx: Float): Long {
        require(widthPx > 0f)
        updates?.close()
        updates = Channel<Unit>(Channel.CONFLATED).also { it.trySend(Unit) }
        generation++
        active = true
        startProgress = progress.coerceIn(0f, 1f)
        panelWidthPx = widthPx
        minOffsetPx = -startProgress * panelWidthPx
        maxOffsetPx = (1f - startProgress) * panelWidthPx
        accumulatedPx = 0f
        latestProgress = startProgress
        cachedFrame = null
        return generation
    }

    /**
     * A callback may outlive its gesture while another detector already owns the accumulator.
     * A stale generation is a no-op rather than an exception or a delta on the newer drag.
     */
    fun dragBy(dragGeneration: Long, deltaPx: Float): Float? {
        if (!active || generation != dragGeneration) return null
        val previous = latestProgress
        accumulatedPx = (accumulatedPx + deltaPx).coerceIn(minOffsetPx, maxOffsetPx)
        latestProgress = startProgress + accumulatedPx / panelWidthPx
        if (latestProgress != previous) updates?.trySend(Unit)
        return latestProgress
    }

    /** A pump retains this drag's channel; an old receiver cannot consume a newer drag's update. */
    fun frameUpdates(): ReceiveChannel<Unit>? = updates

    fun frame(): DrawerDragFrame? {
        if (!active) return null
        val frame = cachedFrame
        // Holding still can be sampled for many display frames without a new pointer position.
        if (frame != null && frame.generation == generation &&
            frame.progress.toRawBits() == latestProgress.toRawBits()
        ) return frame
        return DrawerDragFrame(generation, latestProgress).also { cachedFrame = it }
    }

    fun canApply(frame: DrawerDragFrame): Boolean = active && frame.generation == generation

    fun canSettle(release: DrawerDragRelease): Boolean =
        !active && release.generation == generation

    /** Cancels only the drag that owns [dragGeneration], leaving any newer drag untouched. */
    fun cancel(dragGeneration: Long): Boolean {
        if (!active || generation != dragGeneration) return false
        active = false
        generation++
        updates?.close()
        cachedFrame = null
        return true
    }

    /**
     * Invalidates any frame already waiting on the animation mutation lock. A stale pointer's
     * finish must not deactivate a newer drag, so the generation is checked here too.
     */
    fun finish(dragGeneration: Long): DrawerDragRelease? {
        if (!active || generation != dragGeneration) return null
        active = false
        generation++
        updates?.close()
        cachedFrame = null
        return DrawerDragRelease(generation, latestProgress)
    }
}
