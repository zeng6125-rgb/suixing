package com.suixing.agent.ui.chat

/**
 * 流式 delta 的**攒批缓冲**（每个会话一份），带发布节流。
 *
 * 为什么单独抽出来：这段代码负责三条**沉默失效**的规则，出问题时都不报错：
 *  1. **不丢**：`append` 收到的每个字符，最终必须被某次 `take` 取走（否则回答少一截）；
 *  2. **不漏**：会话已经结束（`sessions` 里没了）时，缓冲里的残字**必须丢弃** ——
 *     留着它会在同一个会话的**下一轮**被取走、拼到新内容前面（"新回答开头多出一段旧话"）；
 *  3. **节流**：`tickIntervalMs` 之内最多发布一次，但**时间到点必须放行**（否则一直攒着不动）。
 *
 * 三条都是"状态机正确性"，`ChatViewModel` 里那段内联代码只能靠人眼守。抽出来之后有断言。
 *
 * **锁的用法**：`handleEvent` 需要「append → 判断该不该发 → 取走」在一个临界区里完成
 * （分开做时另一线程的 `take` 会把刚 append 的文字顺手带走）。所以这里不暴露裸字段，
 * 而是让调用方用 [locked] 把三步包起来 —— 语义与原来的 `synchronized(deltaLock)` 完全一致。
 */
internal class DeltaBuffer {
    private val lock = Any()
    private val texts = HashMap<String, StringBuilder>()
    private val reasonings = HashMap<String, StringBuilder>()
    private val lastPublishAt = HashMap<String, Long>()

    /** 在内部锁里执行一段操作（append / mayPublish / take 三步必须同处一个临界区）。 */
    fun <T> locked(block: () -> T): T = synchronized(lock) { block() }

    /** 追加一段 delta。**调用方必须已在 [locked] 内。** */
    fun append(chatId: String, text: String?, reasoning: String?) {
        if (text != null && text.isNotEmpty()) {
            texts.getOrPut(chatId) { StringBuilder() }.append(text)
        }
        if (reasoning != null && reasoning.isNotEmpty()) {
            reasonings.getOrPut(chatId) { StringBuilder() }.append(reasoning)
        }
    }

    /** 到点了吗？到点顺便记账。**调用方必须已在 [locked] 内。** */
    fun mayPublish(chatId: String, nowMs: Long, intervalMs: Long): Boolean {
        val last = lastPublishAt[chatId] ?: 0L
        if (nowMs - last < intervalMs) return false
        lastPublishAt[chatId] = nowMs
        return true
    }

    /**
     * **只读**看一眼还没被取走的内容（不清空、不动节流记账）。
     *
     * 为什么需要：后台会话的 delta 只缓冲不发布（屏幕上看不到它，发布只是白做的 O(n) 拼接），
     * 而「live 草稿」（进程被杀后的恢复副本）读的是 `sessions` 里那份 ——
     * 于是后台长回答的草稿会是**空的**，进程一旦被杀就真丢了。草稿写出时把这段拼上去即可。
     *
     * **调用方必须已在 [locked] 内。**
     */
    fun peek(chatId: String): Pair<String, String> =
        (texts[chatId]?.toString().orEmpty()) to (reasonings[chatId]?.toString().orEmpty())

    /** Change detection without copying a potentially long unpublished tail. Caller holds [locked]. */
    fun sizes(chatId: String): Pair<Int, Int> =
        (texts[chatId]?.length ?: 0) to (reasonings[chatId]?.length ?: 0)

    /** 取走并清空这一份。**调用方必须已在 [locked] 内。** */
    fun take(chatId: String): Pair<String, String> {
        val text = texts.remove(chatId)?.toString().orEmpty()
        val reasoning = reasonings.remove(chatId)?.toString().orEmpty()
        return text to reasoning
    }

    /**
     * 会话已经不存在时的**丢弃**（规则 2）。
     *
     * 只丢内容、也清节流记账：下一轮是全新的一轮，不该继承上一轮的发布时刻。
     */
    fun drop(chatId: String) = locked {
        texts.remove(chatId)
        reasonings.remove(chatId)
        lastPublishAt.remove(chatId)
        Unit
    }
}
