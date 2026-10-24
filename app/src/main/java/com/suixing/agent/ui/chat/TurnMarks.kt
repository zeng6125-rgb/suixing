package com.suixing.agent.ui.chat

/**
 * 导航条「当前是第几轮」的换算。
 *
 * 坐标两层：`visible[i]` 是存储行正序；喂序下标 = `feedPrefix + (size - 1 - i)`；
 * `turnIndices` 用"可见坐标 + rowOffset"。比较前必须先把 `firstVisible` 倒回可见坐标再补
 * `rowOffset` —— 漏前者跳到下一轮，漏后者偏一行，都不报错。
 */

internal object TurnMarks {

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
    ): Int {
        if (visibleSize <= 0 || turnIndices.isEmpty()) return 1
        val lastVisible = visibleSize - 1
        // 喂序 → 可见：反置。钳到合法范围（首可见项也可能是我们的临时行）。
        val visible = (lastVisible - (firstVisibleFed - feedPrefix)).coerceIn(0, lastVisible)
        val withOffset = visible + rowOffset
        return turnIndices.indexOfLast { it <= withOffset }.coerceAtLeast(0) + 1
    }

    /**
     * 视口顶部那一行在**喂序**里的下标 —— 单 item 转录专用。
     *
     * 整页装进一个 LazyColumn item 之后 `firstVisibleItemIndex` 恒为 0，没有 item 边界
     * 可读，只能按像素反查：从最新一端（底部）往上累加各行高度，第一个跨过
     * 「距底偏移 + 视口高」的行就是视口顶部那一行。
     *
     * @param offsetFromBottom `firstVisibleItemScrollOffset`（倒序布局里就是距最新端的像素）
     * @param viewport 视口高（像素）
     * @param heightsOldestFirst 各行高度（**可见正序、旧→新**，已含行间距）；0 = 还没量到
     * @return 喂序下标（0 = 最新）；没量到高度时返回当前近似值
     */
    fun topFedIndex(
        offsetFromBottom: Int,
        viewport: Int,
        heightsOldestFirst: IntArray,
    ): Int {
        val n = heightsOldestFirst.size
        if (n <= 0) return 0
        val target = offsetFromBottom + viewport
        var acc = 0
        for (r in 0 until n) {
            val h = heightsOldestFirst[n - 1 - r]
            if (h <= 0) return r
            if (acc + h > target) return r
            acc += h
        }
        return n - 1
    }
}
