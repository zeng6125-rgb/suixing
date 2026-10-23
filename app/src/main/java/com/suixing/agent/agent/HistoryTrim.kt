package com.suixing.agent.agent

import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage

/**
 * 裁完之后**必须仍有人问的那句话**。
 *
 * 为什么需要：`trim` 在"最新那条怎么都装不下"时只保留最新一条（这是对的 —— 宁可只发一条，
 * 也不要发一堆旧的）。但如果那条恰好是 `tool` 结果，而它的 `assistant`（带 tool_calls）已被裁掉，
 * `WireSanitize` 会把这条孤儿结果丢掉 ⇒ 请求里**一条 user 都没有**（只剩 checkpoint 摘要），
 * 模型就会答非所问。工具输出动辄几十万字符，这条链不是假想。
 *
 * 这里只在"wire 里没有任何 user 提问"时，把历史上**最后一句**用户消息补回来 —— 只补一条，
 * 代价上界是一条消息（且它不计入预算：问题消息是唯一无界项，宁可略超也不要不发）。
 *
 * **为什么追加到末尾而不是紧跟 system 之后**（评审 18 的 A1）：后缀里没有 user 时 `apply`
 * 会整段保留（见上面不变量 3 的说明），wire 尾部可能是一条 assistant；把问题插到前面会让请求
 * **以 assistant 结尾** ⇒ 模型会"续写"而不是回答。追加末尾让末尾永远是 user，两种尾都成立。
 */
internal fun ensureQuestionKept(
    wire: List<StoredMessage>,
    history: List<StoredMessage>,
): List<StoredMessage> {
    // **`steer` 不算"问题在 wire 里"**（评审 18 的 A3）：插话在请求里是「（用户在任务进行中说：…）」
    // 包装，不是用户提问；若后缀恰好从一条 steer 开始，真问题已被裁掉，这里必须把它补回来。
    if (wire.any { it.role == Roles.USER && !it.steer }) return wire
    val question = history.lastOrNull { it.role == Roles.USER && !it.steer } ?: return wire
    return wire + question
}

/**
 * 按 token 预算**从前面**裁历史。
 *
 * 为什么单独抽出来：它决定的正是"模型这一轮看得到什么"，出错不会崩、不会报错，
 * 只会**静默降低回答质量**（丢了最近几轮的上下文）或**把请求撑爆**（超预算）。
 * 三条不变量原来只写在 KDoc 里，没有一条测试守着：
 *
 *  1. `budget <= 0` ⇒ 原样返回（不裁）；
 *  2. **最新一条无论多大都要发**：只留一条也好过留一堆旧的；
 *  3. 裁完的起点必须是 `user`：以工具结果/助手开头的转录会被端点直接拒绝
 *     （没有问它的那句话），所以裁点要往后挪到第一个用户消息。
 *
 * ## 这个预算判断是**偏保守**的，而且偏差不小（issue #39 问题 4）
 *
 * `estimateTokens` 用的是 `(字符数 + 2) / 3`（向上取整）。相对旧的 `字符数 / 3`：
 * **每条消息最多多算 1 token**（长度是 3 的倍数时相同，否则 +1）—— 不是按比例膨胀。
 * 均匀长度分布下整体比值 ≈ 1.008（issue #39 曾说"整体 +33%"，实测不成立，已在 #39 更正）。
 *
 * 方向是安全的：估算偏大 ⇒ 裁得更早、请求更小，不会因为低估而超预算被拒。
 * 但**绝对量级仍不是真实 token**：中文实测约 0.9 token/字，而这里按 1/3 字算 —— 差异主要来自
 * 系数而不是取整。真实值只有端点回报的 `prompt_tokens` 才是权威。
 *
 * **不要把这个数当真实 token 用**；真实值只有端点回报的 `prompt_tokens` 才是权威。
 */
internal object HistoryTrim {

    fun apply(messages: List<StoredMessage>, budget: Int): List<StoredMessage> {
        if (budget <= 0 || messages.isEmpty()) return messages
        val kept = ArrayDeque<StoredMessage>()
        var used = 0
        for (message in messages.asReversed()) {
            val cost = estimateTokens(message)
            // 最新那条永远发：`kept` 还是空的时候不受预算限制。
            if (kept.isNotEmpty() && used + cost > budget) break
            kept.addFirst(message)
            used += cost
        }
        // 没裁掉任何东西就返回**原实例**（调用方可能靠这个省一次复制）。
        if (kept.size == messages.size) return messages
        val tail = kept.toList()
        val start = tail.indexOfFirst { it.role == Roles.USER }
        return if (start <= 0) tail else tail.subList(start, tail.size)
    }
}
