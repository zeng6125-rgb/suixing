package com.suixing.agent.agent

import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage

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
