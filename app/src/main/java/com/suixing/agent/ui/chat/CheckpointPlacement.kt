package com.suixing.agent.ui.chat

import com.suixing.agent.data.ContextCheckpoint

/**
 * 把上下文压缩记录**挂到它该出现的那一行**上（行 id → 该行下面要画的记录）。
 *
 * 抽成纯函数有两个原因：这段逻辑在流式期每次发布都会跑；而它的正确性肉眼很难验 —— 挂错一行
 * 就是「压缩记录时有时无、跑几轮就消失」。
 *
 * 定位规则（**与改前的内联实现逐条等价**，包括下面这些容易写错的边角）：
 * 1. **锚点优先**（[ContextCheckpoint.anchorId]），空串才回落到 [ContextCheckpoint.toMessageId]。
 *    锚点非空但**没命中**时**不再回退**到 `toMessageId`（改前就是直接当孤儿处理）。
 * 2. **消息 id 优先于轮次 id**：一行既有最新消息 id，也有它各轮次的 id。同一个字符串既像某行的
 *    消息 id、又像另一行的旧轮次 id 时，按**消息 id** 归属。旧实现是"两张表 + 先查消息表"，
 *    合并成一张表会把这条优先级弄反（对抗评审给出过反例）。
 * 3. **重复 id 取最后一次出现**（`associateBy` / `HashMap` 覆盖语义）：历史转录里存在重复
 *    message id，仓库明确按"两次独立出现"处理，不合并。
 * 4. 锚点落在当前页窗口之外 ⇒ 挂到**本页最旧一行**顶上，并排在该行已有记录之后。
 * 5. 两个不同行最终落到同一个 `message.id`（重复 id）时，后者覆盖前者 —— 与改前
 *    `groupBy().mapKeys{}` 的覆盖语义一致（那本身是个既有缺陷，本轮不顺手改，避免多出卡片）。
 *
 * @return 保持 [checkpoints] 原顺序；同一行下多条按输入顺序排列。
 */
internal fun placeCheckpoints(
    rows: List<ChatRow>,
    checkpoints: List<ContextCheckpoint>,
): Map<String, List<ContextCheckpoint>> {
    if (rows.isEmpty() || checkpoints.isEmpty()) return emptyMap()

    // 两张表、而不是一张：查出处的优先级是「消息 id 先、轮次 id 后」，合并会颠倒它。
    val messageOwner = HashMap<String, Int>(rows.size)
    val roundOwner = HashMap<String, Int>(rows.size)
    rows.forEachIndexed { index, row ->
        messageOwner[row.message.id] = index
        row.rounds.forEach { round -> roundOwner[round.id] = index }
    }

    // 先按**行下标**分组，再翻成 message id 作键：这样重复 message id 的"后者覆盖"与改前一致。
    val byIndex = LinkedHashMap<Int, MutableList<ContextCheckpoint>>()
    val orphans = mutableListOf<ContextCheckpoint>()
    checkpoints.forEach { checkpoint ->
        val anchor = checkpoint.anchorId.ifEmpty { checkpoint.toMessageId }
        val index = messageOwner[anchor] ?: roundOwner[anchor]
        if (index == null) orphans += checkpoint
        else byIndex.getOrPut(index) { mutableListOf() } += checkpoint
    }

    val placed = LinkedHashMap<String, List<ContextCheckpoint>>(byIndex.size + 1)
    byIndex.forEach { (index, list) -> placed[rows[index].message.id] = list }
    if (orphans.isNotEmpty()) {
        val top = rows.first().message.id
        placed[top] = placed[top].orEmpty() + orphans
    }
    return placed
}
