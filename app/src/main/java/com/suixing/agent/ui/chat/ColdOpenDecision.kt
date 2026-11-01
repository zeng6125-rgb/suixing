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
