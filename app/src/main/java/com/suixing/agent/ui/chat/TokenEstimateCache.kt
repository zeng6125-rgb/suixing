package com.suixing.agent.ui.chat

import com.suixing.agent.agent.estimateTokens
import com.suixing.agent.data.StoredMessage
import java.lang.ref.WeakReference

/**
 * 上下文圆环的进程级 LRU。按消息 id 查找，指纹只做快速筛选，
 * 正文、工具字段及图片数全部一致才命中；同长度编辑和哈希碰撞均会重算。
 * 弱引用不延长大消息正文的生命周期，回收后安全重算。
 * 调用方在组合期只 peek，未命中估算在 Dispatchers.Default 上执行。
 */
internal object TokenEstimateCache {
    const val MAX_ENTRIES = 4096

    private data class Estimate(
        val message: WeakReference<StoredMessage>,
        val stamp: Long,
        val imageCount: Int,
        val tokens: Int,
    ) {
        fun matches(current: StoredMessage, currentStamp: Long): Boolean {
            if (stamp != currentStamp || imageCount != current.imageCount) return false
            val old = message.get() ?: return false
            return old.text == current.text && old.toolOutput == current.toolOutput &&
                old.toolArgs == current.toolArgs
        }
    }

    private val entries = object : LinkedHashMap<String, Estimate>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Estimate>?): Boolean =
            size > MAX_ENTRIES
    }

    fun tokensFor(message: StoredMessage, stamp: Long): Int {
        peek(message, stamp)?.let { return it }
        val imageCount = message.imageCount
        val tokens = estimateTokens(message)
        synchronized(entries) {
            entries[message.id] = Estimate(WeakReference(message), stamp, imageCount, tokens)
        }
        return tokens
    }

    /** 只查不算；比较在锁外进行，长字段不阻塞其它会话的查表。 */
    fun peek(message: StoredMessage, stamp: Long): Int? {
        val entry = synchronized(entries) { entries[message.id] } ?: return null
        return entry.tokens.takeIf { entry.matches(message, stamp) }
    }

    /** 清空（仅测试用）。 */
    fun clear() = synchronized(entries) { entries.clear() }
}
