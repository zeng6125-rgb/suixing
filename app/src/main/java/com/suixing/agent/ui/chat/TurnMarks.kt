package com.suixing.agent.ui.chat

/**
 * 导航条「当前是第几轮」的换算。
 *
 * 坐标空间有两层，历史上多次搞混（#16 的"偏一两项"就是它）：
 * - `visible[i]`：存储行的**正序**下标（0 = 最旧）；
 * - 喂序：底锚倒序，`visible[i]` 的喂序下标 = `feedPrefix + (size - 1 - i)`；
 * - `turnIndices`：轮首在**可见坐标 + rowOffset（「加载更早」那一行）**下的下标。
 *
 * 所以比较时必须把 `firstVisible` 先倒回可见坐标，**再补上 rowOffset** —— 漏了倒置会跳到下一轮，
 * 漏了 rowOffset 会在一屏内偏一行。两种错都只表现为"高亮位置不对"，不报错。
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
}
