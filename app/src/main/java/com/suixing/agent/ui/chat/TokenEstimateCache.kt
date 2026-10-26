package com.suixing.agent.ui.chat

import com.suixing.agent.agent.estimateTokens
import com.suixing.agent.data.StoredMessage

/**
 * 上下文圆环的逐条 token 估算缓存（进程级 LRU）。
 *
 * ## 为什么是全局的、而不是按会话 remember
 *
 * 调用方（`ChatScreen` 的 `transcriptEstimate`）原来拿 `remember(state.conversationId)`
 * 建一张 `HashMap` —— **每次切会话都换成空表**，于是整页正文又被逐字符重估一遍
 * （`estimateTokens` 做 CJK 脚本判定，一条长回答几千字符，一页 12 轮可达 5~20 万字符），
 * 而且就发生在**组合期、主线程**：切过去卡一下、切回来再卡一下。这是切换会话路径上
 * 一块确定性的主线程开销（注释里自己记过：「每次 revision 一次几 ms 的主线程尖峰」）。
 *
 * 估算的输入只有这条消息自己（正文/工具输出/图片数），与会话无关 ⇒ 缓存按
 * `消息 id + 内容指纹` 全局存，切会话时命中，来回切都不重估。
 *
 * ## 为什么不会读到脏值
 *
 * 每个条目都带 [stamp]（内容指纹，调用方用 `text.hashCode()*31 + 工具文本长度` 算，
 * `String.hashCode` 自带缓存所以指纹本身免费）。指纹对不上就重算 —— 编辑消息、
 * 流式把文字追加进已落盘的消息，都会换指纹。id 相同的不同内容不可能读到旧估算。
 *
 * ## 为什么是 LRU 而不是无限增长
 *
 * 消息 id 全局唯一、永不复用（新建消息都带新 id），只进不出会让表随使用时长无限涨。
 * 上限 [MAX_ENTRIES] 条：超出淘汰最久未用。被淘汰的条目下次自然重算，
 * 正确性不受影响，只是少一次命中。
 */
internal object TokenEstimateCache {

    /** 缓存条数上限。估算值可重算，内存不涨 —— 淘汰只影响命中率。 */
    const val MAX_ENTRIES = 4096

    /**
     * id →（内容指纹, 估算值）。`accessOrder=true` 的 [LinkedHashMap] 即 LRU：
     * 每次 `get`/`put` 把条目挪到链尾，超容量时 [removeEldestEntry] 摘链首。
     * 全部操作在同一把锁里 —— 调用方都在主线程，锁只是防将来有人后台调。
     */
    private val entries = object : LinkedHashMap<String, LongArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LongArray>?): Boolean =
            size > MAX_ENTRIES
    }

    /**
     * 返回这条消息的 token 估算：[stamp]（内容指纹）与缓存一致就复用，否则重算并写回。
     */
    fun tokensFor(message: StoredMessage, stamp: Long): Int = synchronized(entries) {
        val hit = entries[message.id]
        if (hit != null && hit[0] == stamp) {
            hit[1].toInt()
        } else {
            val tokens = estimateTokens(message)
            entries[message.id] = longArrayOf(stamp, tokens.toLong())
            tokens
        }
    }

    /** 清空（仅测试用：让每个用例从干净表开始）。 */
    fun clear() = synchronized(entries) { entries.clear() }
}
