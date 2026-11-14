package com.suixing.agent.ui.chat

import com.suixing.agent.agent.estimateTokens
import com.suixing.agent.data.StoredMessage
import java.lang.ref.WeakReference

/**
 * 上下文圆环的进程级 LRU。组合期只查已确认的消息实例，
 * 新实例的正文、工具字段及图片数在 Dispatchers.Default 上核对。
 * 弱引用不延长大消息正文的生命周期。
 */
internal object TokenEstimateCache {
    const val MAX_ENTRIES = 4096

    private data class Estimate(
        val message: WeakReference<StoredMessage>,
        val imageCount: Int,
        val tokens: Int,
    ) {
        fun matches(current: StoredMessage): Boolean {
            if (imageCount != current.imageCount) return false
            val old = message.get() ?: return false
            return old.text == current.text && old.toolOutput == current.toolOutput &&
                old.toolArgs == current.toolArgs
        }
    }

    private val entries = object : LinkedHashMap<String, Estimate>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Estimate>?): Boolean =
            size > MAX_ENTRIES
    }

    fun tokensFor(message: StoredMessage): Int {
        val cached = synchronized(entries) { entries[message.id] }
        if (cached != null && cached.message.get() === message) return cached.tokens
        val imageCount = message.imageCount
        val tokens = if (cached?.matches(message) == true) cached.tokens else estimateTokens(message)
        synchronized(entries) {
            entries[message.id] = Estimate(WeakReference(message), imageCount, tokens)
        }
        return tokens
    }

    /** 只比实例，不扫描正文；新实例交给后台核对后再缓存。 */
    fun peek(message: StoredMessage): Int? {
        val entry = synchronized(entries) { entries[message.id] } ?: return null
        return entry.tokens.takeIf { entry.message.get() === message }
    }

    /** 清空（仅测试用）。 */
    fun clear() = synchronized(entries) { entries.clear() }
}
