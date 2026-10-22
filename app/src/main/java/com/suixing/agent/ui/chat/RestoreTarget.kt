package com.suixing.agent.ui.chat

/**
 * 「切回会话时恢复到哪个列表位置」的**纯决策**（issue #15/#16 的第三次反复）。
 *
 * 为什么单独抽出来：这条链路已经出过**三次**同一类事故，每次都表现为「位置没恢复，退回底部」，
 * 而且**不崩溃、不打日志**：
 *  1. `messageIds` 被 `LaunchedEffect(listState)` 一次性捕获（首帧集合是空的 ⇒ 锚点一次都写不上）；
 *  2. `itemCount` 是普通 `Int`，写进 `snapshotFlow` 就冻成常量（`first { it > 0 }` 永不完成）；
 *  3. `visible` / `feedPrefix` 同样是普通 `val`（冷路径那一帧 `visible` 是空表 ⇒ 找不到目标）。
 * 前两条修在"取最新值"上，但**决策本身**（该滚到第几项、什么时候放弃）一直没有断言守着。
 *
 * 这里的约定（与 `ChatScreen` 里的喂序换算一致）：
 * - `visible` 是**存储行**的列表，`visible[0]` 是最旧的一行（界面上在最上面）；
 * - 喂给 `LazyColumn` 的是它的**反转**（底锚 `reverseLayout`），`feedPrefix` 是插在前面那些
 *   临时行（流式行、状态行、错误行…）的条数；
 * - 所以 `visible[i]` 在喂序里的下标 = `feedPrefix + (visible.size - 1 - i)`。
 */
internal object RestoreTarget {

    /**
     * @return 该滚到的**喂序下标**；`null` 表示「这一帧不该滚」——
     *   调用方应按 issue #16 的既定策略只做精确校正（或退回底部），而不是滚到错误的位置。
     */
    fun fedIndex(visibleIds: List<String>, savedKey: String?, feedPrefix: Int): Int? {
        if (savedKey == null) return null
        // **空表就直接放弃**：冷路径第一帧就是这个形态，此时"找不到"不代表"这条消息没了"，
        // 硬滚会把人放到随机位置（历史事故的形态）。
        if (visibleIds.isEmpty()) return null
        val at = visibleIds.indexOf(savedKey)
        if (at < 0) return null
        val prefix = if (feedPrefix < 0) 0 else feedPrefix
        val fed = prefix + (visibleIds.size - 1 - at)
        return if (fed < 0) null else fed
    }
}
