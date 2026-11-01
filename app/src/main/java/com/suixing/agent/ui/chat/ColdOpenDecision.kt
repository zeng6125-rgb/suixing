package com.suixing.agent.ui.chat

/**
 * 「这次冷读的结果还能不能上屏」（issue #67 P1-3）。
 *
 * 冷开要读磁盘，读完时用户可能已经切走、甚至选了另一个会话。判据有三条，缺一不可：
 *
 * 1. 代次仍是最新 —— 没有更新的用户意图插进来；
 * 2. 目标会话仍是屏幕上那个 —— 不把 A 的正文贴到 B 的页面上；
 * 3. 页面还没有正文 —— 有内容就不覆盖（热缓存已在用，冷读只是补位）。
 *
 * 抽成纯函数是为了能离线钉住这三条；`ChatViewModel` 里那段 if 是它的直接调用。
 */
internal fun canApplyColdOpen(
    targetChatId: String,
    currentChatId: String,
    intentCurrent: Boolean,
    currentIsEmpty: Boolean,
): Boolean = intentCurrent && targetChatId == currentChatId && currentIsEmpty

/**
 * 「这次切换要不要读盘」（issue #67 P1-3 的另一半）。
 *
 * 热 A→B→A 必须**不重读**同一份正文：A 的正文要么在 live 会话里，要么在 LRU 缓存里，
 * 两者都没有才该落到 `readTailWindow`。冷开与热开的差别就体现在这里 ——
 * 有缓存却仍去读盘，就是"每次切回都重新读一遍几十 MB 日志"。
 */
internal fun needsColdRead(hasLiveSession: Boolean, cachedIsPresent: Boolean): Boolean =
    !hasLiveSession && !cachedIsPresent
