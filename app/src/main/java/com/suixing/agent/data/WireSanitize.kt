package com.suixing.agent.data

/**
 * 发请求前的**工具对清理**：确保 `assistant.toolCalls` 与 `tool` 结果成对。
 *
 * 为什么单独抽出来：这段逻辑守着**两种**会让每个 OpenAI 兼容端点直接回 400 的形状，
 * 而两种都是真实发生过的（不是假想）：
 *  1. 助手声明了 N 个 tool call，但结果只回来 M < N 个（并行调用里一半没回来就停了/被杀）；
 *  2. 工具结果还在，但它的助手（带着 toolCallId）已经不在了 —— 单独一条 `tool` 消息。
 * 两种都表现为用户侧「发送失败」，而且看不出原因。原来它是 `AgentEngine` 的私有方法，
 * 一条测试都没有；抽出来之后这两个方向都能被断言钉住。
 *
 * 语义与原来**逐字一致**（先按"结果是否齐全"筛助手，再按"是否有人问过"筛工具结果）：
 * 两轮筛选必须一起做 —— 只丢助手会留下一堆孤儿工具结果，只丢工具结果会留下没人应答的声明，
 * 两种都会被端点拒绝。
 */
internal object WireSanitize {

    fun apply(messages: List<StoredMessage>): List<StoredMessage> {
        // ① 哪些 toolCallId 真的拿到了结果。
        val answered = messages
            .filter { it.role == Roles.TOOL }
            .mapNotNull { it.toolCallId }
            .toSet()
        // ② 丢掉"有呼叫但结果不全"的助手（并行里缺一条就不行）。
        val assistants = messages.filterNot { message ->
            message.role == Roles.ASSISTANT && message.toolCalls.any { it.id !in answered }
        }
        // ③ 反过来：哪些 toolCallId 还有人问过。
        val asked = assistants
            .filter { it.role == Roles.ASSISTANT }
            .flatMap { it.toolCalls }
            .map { it.id }
            .toSet()
        // ④ 丢掉没人问过的工具结果（含 toolCallId 缺失的那种）。
        return assistants.filter { message ->
            if (message.role != Roles.TOOL) return@filter true
            val id = message.toolCallId ?: return@filter false
            id in asked
        }
    }
}
