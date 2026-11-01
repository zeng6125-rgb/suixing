package com.suixing.agent.ui.chat
import com.suixing.agent.data.StoredMessage

/**
 * 冷开读盘的**执行骨架**（issue #67 P1-3 的最后一块：真实延迟下的调度顺序）。
 *
 * 之前只把"判据"抽成了纯函数（`canApplyColdOpen` / `needsColdRead`），能测"该不该应用"，
 * 但测不到**读完那一刻世界变了**这类时序：读盘要几十到几百毫秒，期间用户可能已经切走。
 *
 * 这里把执行顺序固定成三步，且每一步都能被测试注入替身：
 *
 * 1. `read()` —— 真读盘（测试里可以 `delay`）；
 * 2. 读完先问 `stillWanted()` —— **过期的 I/O 不写任何共享状态**；
 * 3. 再问 `canApply()` —— 只在这一刻仍成立时才上屏，否则走 `onDiscard()`。
 *
 * 返回值 = 是否真的应用了结果，便于调用方与测试断言。
 */
internal suspend fun runColdOpen(
    read: suspend () -> List<StoredMessage>?,
    stillWanted: () -> Boolean,
    canApply: () -> Boolean,
    onApply: (List<StoredMessage>) -> Unit,
    onDiscard: () -> Unit = {},
): Boolean {
    val opened = read()
    if (!stillWanted()) {          // 代次已过期：连"读到的东西"都不该影响状态
        onDiscard()
        return false
    }
    if (opened == null || !canApply()) {
        onDiscard()
        return false
    }
    onApply(opened)
    return true
}
