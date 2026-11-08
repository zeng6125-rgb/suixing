package com.suixing.agent.ui.chat

import com.suixing.agent.data.ChatRepository
import com.suixing.agent.data.ChatTotals
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.Tps
import com.suixing.agent.data.totalsOfMessage

/** Whole-conversation statistics from persisted metadata, independently of the loaded window. */
data class SessionStats(
    val turns: Int,
    val steps: Int,
    val tokens: Int,
    val cachePercent: Double,
    val perSecond: Double,
    val contextTokens: Int,
    val contextWindow: Int,
    /** 三块花费（未缓存输入 / 缓存读取 / 输出），**整个会话**。 */
    val split: SessionSplit = SessionSplit(0, 0, 0),
    /** 整个会话的墙钟合计。 */
    val elapsedMs: Long = 0,
    /** 此快照所属会话；异步重读统计时，StateFlow 可能仍保留上个会话的值。 */
    val conversationId: String = "",
) {
    internal fun forConversation(chatId: String): SessionStats? =
        takeIf { conversationId == chatId }

    companion object {
        /**
         * 由**整个会话**的统计量构建（[ChatRepository.sessionTotals] 从 meta 读，O(1)）。
         *
         * **不能**再传界面上的消息列表：那只是 `readTail` 的 12 轮窗口，
         * 加出来的轮数/用量只是"已加载那部分"（用户实测：标题栏的会话统计少了一大截）。
         */
        /**
         * 从一段消息算统计 —— **只用于测试和"手里确实有完整列表"的调用方**。
         *
         * 界面上**不要**用这个重载：`ChatUiState.messages` 是 `readTail` 的窗口，
         * 加出来的只是"已加载那部分"。生产路径走 [of] 的 `ChatTotals` 版本。
         */
        fun of(messages: List<StoredMessage>, contextWindow: Int): SessionStats? =
            of(
                messages.fold(ChatTotals.EMPTY) { acc, message -> acc + totalsOfMessage(message) },
                contextWindow,
            )

        internal fun of(
            totals: ChatTotals,
            contextWindow: Int,
            conversationId: String = "",
        ): SessionStats? {
            val prompt = totals.promptTokens
            val completion = totals.completionTokens
            val cached = totals.cachedTokens
            // 官方聚合＝ΣdecodeTokens/ΣdecodeMs。逐轮 `elapsed - firstToken` 恒 ≥ 0，
            // 所以"先求和再相减"与逐轮求和等价。
            val decode = (totals.elapsedMs - totals.firstTokenMs).coerceAtLeast(0L)
            val tokens = prompt + completion
            if (tokens == 0 && totals.steps == 0) return null
            return SessionStats(
                turns = totals.turns,
                steps = totals.steps,
                tokens = tokens,
                // 比率在 Long 里算、不取整：`cached * 100 / prompt` 在 Int 上会在
                // cached 超过 21,474,837 时溢出，历史上那次就画成了"缓存命中整行消失"。
                cachePercent = if (prompt > 0) cached.toLong() * 100.0 / prompt else 0.0,
                perSecond = Tps.of(completion, decode),
                contextTokens = totals.contextTokens,
                contextWindow = contextWindow,
                split = SessionSplit(
                    uncached = (prompt.toLong() - cached.toLong()).coerceAtLeast(0L),
                    cached = cached.toLong(),
                    output = completion.toLong(),
                ),
                elapsedMs = totals.elapsedMs,
                conversationId = conversationId,
            )
        }
    }
}
