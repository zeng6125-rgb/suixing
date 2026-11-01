package com.suixing.agent.agent

import com.suixing.agent.data.StoredMessage

/**
 * 逐条 token 估算的有界缓存（issue #71 A3）。
 *
 * `estimateTokens` 对正文逐字符做 CJK 判定，而 `HistoryTrim.apply` 每次组请求都要把
 * **整个历史**倒着估一遍 —— 一条 3 万字符的回答在会话里会被重复估算几十次，
 * 复杂度是 O(轮数 × 历史总量)。消息是不可变的，估算值只取决于这条消息本身。
 *
 * 键是 `id + 长度`：id 全局唯一且不复用，长度再兜一层"同 id 内容被换掉"的情况
 * （编辑消息会换长度；同长度不同内容在极端情况下会命中旧值 —— 那只影响裁剪的
 * 估算值，不影响发出去的内容，比每次重算整个历史更划算）。
 */
internal object TokenEstimates {

    /** 上限：估算可重算，内存不涨。超出按最久未用淘汰。 */
    const val MAX_ENTRIES = 512

    private val entries = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean =
            size > MAX_ENTRIES
    }

    /**
     * 取这条消息的估算值，命中就复用。
     *
     * [estimator] 可注入只为可测：生产一律走 [estimateTokens]。
     */
    fun of(
        message: StoredMessage,
        estimator: (StoredMessage) -> Int = ::estimateTokens,
    ): Int {
        val key = message.id + ':' + message.text.length + ':' + message.toolOutput.orEmpty().length
        synchronized(entries) { entries[key] }?.let { return it }
        val tokens = estimator(message)
        synchronized(entries) { entries[key] = tokens }
        return tokens
    }

    /** 清空（仅测试用）。 */
    fun clear() = synchronized(entries) { entries.clear() }

    /** 当前条数（仅测试用）。 */
    fun size(): Int = synchronized(entries) { entries.size }
}
