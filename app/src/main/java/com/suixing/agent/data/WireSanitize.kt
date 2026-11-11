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
 * 每条工具声明只匹配它之后、下一条工具声明之前的结果，避免不同轮复用 call id 时
 * 借用其它轮的结果。缺结果仍丢弃整批；同批重复结果只保留首次，普通消息保持原序。
 */
internal object WireSanitize {

    fun apply(messages: List<StoredMessage>): List<StoredMessage> {
        val kept = BooleanArray(messages.size) { true }
        var assistantAt = -1
        var callIds: Set<String> = emptySet()
        val results = HashMap<String, Int>()
        fun finishBatch() {
            if (assistantAt >= 0 && results.size < messages[assistantAt].toolCalls.size) {
                kept[assistantAt] = false
                for (at in results.values) kept[at] = false
            }
            results.clear()
        }
        for ((at, message) in messages.withIndex()) {
            if (message.role == Roles.ASSISTANT && message.toolCalls.isNotEmpty()) {
                finishBatch()
                assistantAt = at
                callIds = message.toolCalls.mapTo(HashSet()) { it.id }
            } else if (message.role == Roles.TOOL) {
                val id = message.toolCallId
                if (id == null || id !in callIds || results.putIfAbsent(id, at) != null) {
                    kept[at] = false
                }
            }
        }
        finishBatch()
        return messages.filterIndexed { at, _ -> kept[at] }
    }
}
