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
}
