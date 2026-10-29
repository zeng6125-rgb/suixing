package com.suixing.agent.ui.chat

/**
 * 导航条「当前是第几轮」的换算。
 *
 * 坐标两层：`visible[i]` 是存储行正序；喂序下标 = `feedPrefix + (size - 1 - i)`；
 * `turnIndices` 用"可见坐标 + rowOffset"。比较前必须先把 `firstVisible` 倒回可见坐标再补
 * `rowOffset` —— 漏前者跳到下一轮，漏后者偏一行，都不报错。
 */

internal object TurnMarks {

    fun probeOffset(viewportStart: Int, viewportEnd: Int): Float =
        viewportStart + (viewportEnd - viewportStart) * PROBE_FRACTION

    /** Native normal-list offsets: a negative scroll offset places the item below its start. */
    fun navigationScrollOffset(viewportStart: Int, viewportEnd: Int): Int =
        -kotlin.math.floor(probeOffset(viewportStart, viewportEnd).toDouble()).toInt()

    /** Main list indices are chronological and include the load-earlier row. */
    fun currentAtRow(
        physicalIndex: Int,
        turnIndices: List<Int>,
        drawnTurns: Int,
        totalTurns: Int,
    ): Int {
        val newest = totalTurns.coerceAtLeast(1)
        if (turnIndices.isEmpty() || drawnTurns <= 0) return newest
        val inWindow = turnIndices.indexOfLast { it <= physicalIndex }.coerceAtLeast(0)
        val oldest = (totalTurns - drawnTurns + 1).coerceAtLeast(1)
        return (oldest + inWindow).coerceIn(1, newest)
    }

    /**
     * @param firstVisibleFed `/…/` 喂序里的首可见项下标（0 = 最底 = 最新）
     * @param feedPrefix 喂序前缀（临时行数）
     * @param visibleSize 存储行数
     * @param rowOffset 「加载更早」行存在时 1，否则 0
     * @param turnIndices 轮首下标（**已含 rowOffset**）
     * @return 1-based 的轮次；空会话返回 1
     */
    fun current(
        firstVisibleFed: Int,
        feedPrefix: Int,
        visibleSize: Int,
        rowOffset: Int,
        turnIndices: List<Int>,
        /**
         * **已加载的轮数**（`page.drawn`）与**会话总轮数**（`page.turns`）。
         *
         * 导航条统一用**全局轮号**（1 基，最新那轮 = totalTurns）。加载的是最后 [drawnTurns] 轮，
         * 所以「窗口内第 k 轮」的全局号 = `totalTurns - drawnTurns + k`。
         */
        drawnTurns: Int,
        totalTurns: Int,
    ): Int {
        val newest = totalTurns.coerceAtLeast(1)
        if (visibleSize <= 0 || turnIndices.isEmpty()) return newest
        val lastVisible = visibleSize - 1
        // 喂序 → 可见：反置。钳到合法范围（首可见项也可能是我们的临时行）。
        val visible = (lastVisible - (firstVisibleFed - feedPrefix)).coerceIn(0, lastVisible)
        val withOffset = visible + rowOffset
        // 窗口内第几轮（1 基）
        val inWindow = turnIndices.indexOfLast { it <= withOffset }.coerceAtLeast(0) + 1
        // 窗口内 → 全局：加载的是最后 drawnTurns 轮，最旧那轮是 totalTurns - drawnTurns + 1。
        val oldestLoaded = (totalTurns - drawnTurns + 1).coerceAtLeast(1)
        return (oldestLoaded + inWindow - 1).coerceIn(1, newest)
    }

    /**
     * 视口内 [PROBE_FRACTION] 位置那一行在**喂序**里的下标 —— 这才是"当前读到哪一轮"。
     *
     * **口径取自 Aether**（`shared/.../ConversationTimeline.kt` 的 `currentTimelineIndex`）：
     * ```kotlin
     * val probe = layout.viewportStartOffset + (layout.viewportEndOffset - layout.viewportStartOffset) * 0.28f
     * timelineTargets.indexOfLast { … it.offset <= probe … }
     * ```
     * 即：取视口**靠上 28%** 处的那一轮当"当前"，而不是顶部、也不是底部。
     * 之前用顶部算当前轮，导航条的高亮与条数全都对不上。
     *
     * 随行是单 item 化（整页一个 item），没有 item 边界可读，只能按像素反查：从最新端
     * （底部）往上累加各行高度，第一个跨过「距底偏移 + 视口高 × 0.28」的行就是它。
     */
    fun probeFedIndex(
        offsetFromBottom: Int,
        viewport: Int,
        heightsOldestFirst: IntArray,
    ): Int {
        val n = heightsOldestFirst.size
        if (n <= 0) return 0
        // **方向**：底锚下 `firstVisibleItemScrollOffset` 是「距最新端（底部）的像素」，
        // 往**上**（朝更旧的内容）走是加法。视口的 28% 处（靠上）距底是 72% 视口高 ——
        // Aether 那边是顶锚，`offset <= probe` 取的是探针**之上**最后一项，等价于取探针所在行。
        val target = offsetFromBottom + (viewport * (1f - PROBE_FRACTION)).toInt()
        // **内容总高**：底锚下内容贴着底部排，若探针线落在**内容上方**（内容不足一屏、
        // 或刚刚切会话还没填满），那个位置根本没有行 —— 此时"正在读的"就是最新那一轮。
        // 原来这种情况循环跑完返回 `n - 1`（**最旧**那一行），于是导航条把最初一轮当成当前轮：
        // 高亮错位、条数错、点条跳错。单测 `TurnMarksRealListTest` 里
        // 「内容不满一屏时当前轮必须是最新那轮」原先失败（expected 2, was 1）就是它。
        var total = 0
        for (i in 0 until n) {
            val h = heightsOldestFirst[i]
            if (h > 0) total += h
        }
        if (target >= total) return 0
        var acc = 0
        for (r in 0 until n) {
            val h = heightsOldestFirst[n - 1 - r]
            if (h <= 0) return r
            if (acc + h > target) return r
            acc += h
        }
        return n - 1
    }

    /** 视口内取多靠上的位置算"当前轮"（Aether 用 0.28）。 */
    const val PROBE_FRACTION = 0.28f
}
