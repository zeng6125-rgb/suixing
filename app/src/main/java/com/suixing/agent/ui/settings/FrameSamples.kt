package com.suixing.agent.ui.settings

import kotlin.math.roundToInt

internal data class FrameStat(val fps: Int, val p90Ms: Int)

/** Primitive bounded storage covers a one-second 240Hz sample without per-frame boxing. */
internal class FrameSamples(capacity: Int = 240) {
    private val intervals = LongArray(capacity.also { require(it > 0) })
    private var count = 0
    private var next = 0

    fun add(intervalNanos: Long) {
        if (intervalNanos <= 0L) return
        intervals[next] = intervalNanos
        next = (next + 1) % intervals.size
        count = (count + 1).coerceAtMost(intervals.size)
    }

    fun summary(): FrameStat? {
        if (count < 10) return null
        val sorted = intervals.copyOf(count)
        var total = 0.0
        for (value in sorted) total += value
        sorted.sort()
        return FrameStat(
            fps = (1_000_000_000.0 * count / total).roundToInt(),
            p90Ms = (sorted[count * 9 / 10] / 1_000_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
    }
}
