package com.suixing.agent.ui.chat

/**
 * `ChatViewModel` 里的纯判据（issue #69 P2-C：先抽可测逻辑，不动物理行为）。
 *
 * 都是"会丢用户数据"或"会截断历史"的决策点。放在这里而不是 3000 行的 ViewModel 中间，
 * 是为了让它们各自可被单测直接调用，也让人一眼看出哪些是纯函数。
 */

/**
 * 合并草稿时，**base 为空**要不要放弃这一次（先不动磁盘，把草稿留到下次）。
 *
 * 为什么需要这条：`shown` 在"合并读盘期间用户切走"时会变 null，而 `stored.messages` 恒为空
 * （#30 之后会话列表只带 meta）⇒ 手里只剩那条恢复消息。若照旧写盘，会把一份**截短的历史**
 * 写进缓存并标"已落盘"，切回来命中缓存、不再读盘 ⇒ 屏幕只显示那几条。
 *
 * **但有一个例外必须放过**：`baseFromDisk == true`（尾部确实从盘上读到了）且盘上**本来就没有
 * 任何消息**（`listMessageCount == 0`）—— 这时把恢复消息当整份历史写回不可能截断任何东西，
 * 而它正是"新建会话首发后约 1 秒内被杀"那条**本来能自愈**的路径。
 *
 * @param baseEmpty 手里的 base 是否为空
 * @param baseFromDisk base 是否确实来自磁盘（`readTail` 成功，且调用点 gate 在 `opened != null`）
 * @param listMessageCount 会话列表里那份 meta 的条数（**不是磁盘字节数**；未知传 null）。
 *   在 store 的自有不变式下 `count == 0 ⟺ logBytes == 0`（count 与 logBytes 同一个 list 写出），
 *   而真正的字节级把关仍在 `ChatRepository.save` 的 `diskHasLog = logBytes > 0`。
 */
internal fun shouldSkipEmptBaseMerge(
    baseEmpty: Boolean,
    baseFromDisk: Boolean,
    listMessageCount: Int?,
): Boolean {
    if (!baseEmpty) return false
    // 只有"确实从盘读到 + 盘上本来就一条没有"才放过；**拿不准就保守跳过**。
    val diskIsTrulyEmpty = baseFromDisk && listMessageCount == 0
    return !diskIsTrulyEmpty
}

/**
 * 有没有必要删掉一个**空会话**（放顶层是为了能直接单测这条会丢用户数据的判据）。
 *
 * 四条都要满足才删：盘上没消息、内存里也没消息、没有正在跑的 run、**没有草稿**。
 * 删错了是丢用户数据，比留个空壳严重得多。
 */
internal fun shouldDiscardEmptyChat(
    storedCount: Int,
    inMemoryCount: Int,
    running: Boolean,
    hasDraft: Boolean,
): Boolean = storedCount == 0 && inMemoryCount == 0 && !running && !hasDraft
