package com.suixing.agent.ui.components

/** Follows the platform animation toward its declared target without quantized pixel steps. */
internal class ImeInsetFilter {
    private var target = 0
    private var start = 0
    private var current = 0

    fun update(rawPx: Int, targetPx: Int, frozen: Boolean): Int {
        if (frozen) return current
        val nextTarget = targetPx.coerceAtLeast(0)
        if (nextTarget != target) {
            start = current
            target = nextTarget
        }
        val bounded = rawPx.coerceIn(minOf(start, target), maxOf(start, target))
        current = if (target >= start) maxOf(current, bounded) else minOf(current, bounded)
        return current
    }
}
