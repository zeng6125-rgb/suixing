package com.suixing.agent.ui.chat

/**
 * 「这次切换要不要读盘」（issue #67 P1-3 的另一半）。
 *
 * 热 A→B→A 必须**不重读**同一份正文：A 的正文要么在 live 会话里，要么在 LRU 缓存里，
 * 两者都没有才该落到 `readTailWindow`。冷开与热开的差别就体现在这里 ——
 * 有缓存却仍去读盘，就是"每次切回都重新读一遍几十 MB 日志"。
 */
internal fun needsColdRead(hasLiveSession: Boolean, cachedIsPresent: Boolean): Boolean =
    !hasLiveSession && !cachedIsPresent
