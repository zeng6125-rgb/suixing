package com.suixing.agent.ui.chat

/** Last measured row whose top is at or above the reading probe; NaN denotes an unfilled prefix. */
internal fun probeRowIndex(count: Int, probeY: Float, topAt: (Int) -> Float): Int {
    var low = 0
    var high = count - 1
    var found = -1
    while (low <= high) {
        val middle = (low + high).ushr(1)
        val top = topAt(middle)
        if (top.isNaN() || top <= probeY) {
            if (!top.isNaN()) found = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return found
}

/** reverseLayout: positive scrolling moves older rows down toward the probe. */
internal fun probeJumpDelta(rowTop: Float, probeY: Float): Float = probeY - rowTop
