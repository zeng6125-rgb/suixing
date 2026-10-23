package com.suixing.agent.agent

import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage

/** 裁完之后仍要有人问的那句话：裁到只剩一条工具结果时，请求会连一个问题都没有。 */
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
 * 按 token 预算从前面裁历史。出错不崩不报错，只会静默丢上下文或把请求撑爆。
 *
 * 三条不变量：`budget <= 0` 原样返回；最新一条无论多大都发；裁完的起点必须是 user
 * （以工具结果开头的转录会被端点拒绝）。
 *
 * 估算偏大 ~1 token/条（方向安全），但绝对量级不是真实 token —— 中文实测 ≈0.9 token/字。
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
