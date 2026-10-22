package com.suixing.agent.ui.chat

/**
 * 底锚列表的**槽位账本**：喂序里到底有几项、临时行占了几项。
 *
 * 为什么单独抽出来：`feedPrefix`（喂序下标换算的前缀）与 `itemCount`（"有没有内容"与插入回底
 * 的判据）本来是**两段各写一遍的表达式**，而且它们已经**数得不一致**：
 *
 * ```kotlin
 * // 喂序（底锚倒序，视觉从下往上）：live? → compress-status? → error? → visible 倒序 → load-earlier?
 * val feedPrefix = live/running + (!running && compressing) + error
 * val itemCount  = visible + load-earlier + error + live/running      // ← 漏了 compress-status
 * ```
 *
 * 少算一项今天只影响 `itemCount <= 0` 这种判据（实际无害），但这类"两处各数一遍"的写法
 * 一旦哪天有人给列表加一行，另一处必然漏 —— 这条链路上已经出过三次同类事故。所以合成一个
 * 纯函数：**两个数从同一份输入算出来，物理上不可能再分叉**，并且可以被单测断言。
 *
 * @param hasLiveCard 有流式卡（正文/思考/工具任一生效），或这一轮已发出待回答（占位行）
 * @param runningHere 这一轮就在这里跑（压缩状态行只在**没在跑**时出现）
 * @param compressing 正在压缩上下文
 * @param hasErrorLine 底部有独立的错误行
 * @param visibleRows 存储行（消息）数量
 * @param hasLoadEarlier 顶部有「加载更早」一行
 */
internal data class ListSlots(
    /** 插在喂序最前面（视觉最底）的临时行数量：`live? + compress-status? + error?` */
    val feedPrefix: Int,
    /** 整个列表的项数（含所有临时行与「加载更早」）。 */
    val total: Int,
) {
    companion object {
        fun of(
            hasLiveCard: Boolean,
            runningHere: Boolean,
            compressing: Boolean,
            hasErrorLine: Boolean,
            visibleRows: Int,
            hasLoadEarlier: Boolean,
        ): ListSlots {
            val liveSlots = if (hasLiveCard) 1 else 0
            // 压缩状态行只在**没在跑**的时候出现：一边跑一边压缩不会同时画两行。
            val compressSlots = if (!runningHere && compressing) 1 else 0
            val errorSlots = if (hasErrorLine) 1 else 0
            val prefix = liveSlots + compressSlots + errorSlots
            val loadEarlier = if (hasLoadEarlier) 1 else 0
            val rows = if (visibleRows < 0) 0 else visibleRows
            return ListSlots(feedPrefix = prefix, total = prefix + rows + loadEarlier)
        }
    }
}
