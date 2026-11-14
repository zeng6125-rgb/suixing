package com.suixing.agent.ui.drawer

/** The final progress for one drag; a later begin invalidates this release. */
internal data class DrawerDragRelease(val generation: Long, val progress: Float)

/**
 * Accumulates pointer deltas from the progress captured when a horizontal drag wins touch slop.
 * The release animation never supplies the next pointer position.
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

    fun begin(progress: Float, widthPx: Float): Long {
        require(widthPx > 0f)
        generation++
        active = true
        startProgress = progress.coerceIn(0f, 1f)
        panelWidthPx = widthPx
        minOffsetPx = -startProgress * panelWidthPx
        maxOffsetPx = (1f - startProgress) * panelWidthPx
        accumulatedPx = 0f
        latestProgress = startProgress
        return generation
    }

    /**
     * A callback may outlive its gesture while another detector already owns the accumulator.
     * A stale generation is a no-op rather than an exception or a delta on the newer drag.
     */
    fun dragBy(dragGeneration: Long, deltaPx: Float): Float? {
        if (!active || generation != dragGeneration) return null
        accumulatedPx = (accumulatedPx + deltaPx).coerceIn(minOffsetPx, maxOffsetPx)
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
        return DrawerDragRelease(generation, latestProgress)
    }
}
