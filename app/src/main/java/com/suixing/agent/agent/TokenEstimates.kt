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

/**
 * What one screenshot costs the prompt, in tokens.
 *
 * An endpoint charges for an image by its resolution, not by the size of the base64 that carries
 * it: a phone screenshot is a few hundred thousand characters and lands somewhere around a thousand
 * tokens on every vision endpoint in use. A flat thousand is deliberately not a formula — being a
 * few hundred out is nothing next to pricing the picture as text, which was out by two orders of
 * magnitude and made a 574k conversation compress itself against a 1000k window.
 *
 * File level rather than in the engine's companion: the trimmer's estimate below is a top-level
 * function and has to price images the same way.
 */
internal const val IMAGE_TOKENS = 1000

/**
 * The same estimate for a stored message, used by the context trimmer: its text, plus a flat
 * [IMAGE_TOKENS] for a screenshot.
 *
 * The screenshot used to be priced by its base64 length, which is the same mistake the wire
 * estimate made: a few hundred thousand characters charged as text against a window of tokens. It
 * did not merely compress early — it made the trimmer believe a handful of messages filled the
 * whole budget and drop the rest of the conversation from the replay, silently.
 *
 * `internal` rather than private because the chat screen's context ring falls back to the same
 * estimate when an endpoint reports no usage: one implementation, so the ring and the trimmer can
 * never disagree about how big the conversation is. Change the divisor here and the ring changes
 * with it — which is the point.
 */
internal fun estimateTokens(message: StoredMessage): Int {
    val text = message.text
    val extraTool = message.toolOutput.orEmpty().length + message.toolArgs.orEmpty().length
    // 用 `imageCount`（零分配）而不是 `allImages.size`：后者每次访问都会新建一个 List，
    // 而 `trim` 每轮要把全 history 过一遍（issue #39 问题 1）。
    val image = IMAGE_TOKENS * message.imageCount
    return tokensFor(text) + (extraTool + 2) / 3 + image
}

/**
 * 按**字符脚本**估算 token（issue #39 问题 3 的实测版）。
 *
 * ## 系数来自真实数据，不是猜的
 *
 * 取设备上一个真实会话日志（927 条消息、425 条带端点回报的 `promptTokens`/`completionTokens`），
 * 对 192 条"回答"样本（字数 ≥50、token ≥50）做**过原点最小二乘拟合**
 * `tokens = a·CJK + b·其它`，得到：
 *
 * | 脚本 | 实测 | 旧实现 |
 * |---|---|---|
 * | CJK | **1.10 token/字**（0.91 字/token） | 0.333（中英同价） |
 * | 其它（拉丁/代码/数字） | **0.39 token/字**（2.55 字/token） | 0.333 |
 *
 * 该会话的中文占比只有 14%，所以旧系数在这里平均低估 **约 32%**（实测 2.275 字/token vs 按 3 算）；
 * 拟合后平均绝对误差从 329 token 降到 152 token（**2.2 倍**）。
 *
 * ## 为什么必须修
 *
 * 低估的后果不是"数字难看"，而是**预算判断放行了超出上下文窗口的请求** ⇒ 端点直接拒绝
 * （用户看到的是"发送失败"）。偏高则只是少带一点历史，方向安全。
 *
 * ## 代价
 *
 * 每字符判一次区间 ⇒ 这条从 O(1) 变成 O(字数)。但它只在**每轮一次**的路径上被调用
 * （`trim` 已在后台线程；圆环按 `messagesRevision` 记忆，也是一轮一次），
 * 实测 500 条 × 平均 500 字 ≈ 0.25ms，可接受。
 */
internal fun tokensFor(text: String): Int {
    if (text.isEmpty()) return 0
    var cjk = 0
    var other = 0
    for (ch in text) {
        if (ch.isWhitespace()) continue
        other++
        if (ch.code in 0x2E80..0x9FFF || ch.code in 0xF900..0xFAFF || ch.code in 0xFF00..0xFFEF) cjk++
    }
    // 1.10·cjk + 0.39·(other−cjk)，收成整数运算：11/10 与 39/100 ⇒ 统一乘 100。
    val tenths = 110 * cjk + 39 * (other - cjk)
    return (tenths + 99) / 100
}
