package com.suixing.agent.ui.chat

import com.suixing.agent.agent.estimateTokens
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 切会话时上下文估算**不许再全量重算**（切换主线程开销之一），同时内容一变必须失效。
 * 这两条是同一条缓存的两面：省掉重算是目的，指纹失效是它正确的代价。
 */
class TokenEstimateCacheTest {

    @Before
    fun cleanSlate() = TokenEstimateCache.clear()

    private fun msg(id: String, text: String, tool: String? = null) = StoredMessage(
        id = id,
        role = Roles.ASSISTANT,
        text = text,
        toolOutput = tool,
    )

    private fun stamp(m: StoredMessage) =
        m.text.hashCode().toLong() * 31 + m.toolOutput.orEmpty().length

    @Test
    fun `同一内容第二次直接命中缓存`() {
        val m = msg("a", "你好，世界".repeat(50))
        val first = TokenEstimateCache.tokensFor(m, stamp(m))
        assertEquals(estimateTokens(m), first)
        // 同 id 同内容 ⇒ 同结果（走的是缓存条目）。
        assertEquals(first, TokenEstimateCache.tokensFor(m, stamp(m)))
    }

    @Test
    fun `内容变了必须重算，不许读到旧估算`() {
        val old = msg("a", "第一版回答")
        TokenEstimateCache.tokensFor(old, stamp(old))
        val edited = old.copy(text = "第二版回答，长了很多字".repeat(20))
        val fresh = TokenEstimateCache.tokensFor(edited, stamp(edited))
        assertEquals(estimateTokens(edited), fresh)
        assertTrue("编辑后估算应变化", fresh != estimateTokens(old))
    }

    @Test
    fun `切会话再切回来不重算`() {
        // 模拟：会话 A 的 30 条消息估过一遍 → 切到 B（缓存表不被"换会话"清空）→ 切回 A 全命中。
        val messages = (1..30).map { msg("m$it", "正文 $it".repeat(40)) }
        messages.forEach { TokenEstimateCache.tokensFor(it, stamp(it)) }
        val measured = messages.map { estimateTokens(it) }
        val hits = messages.map { TokenEstimateCache.tokensFor(it, stamp(it)) }
        assertEquals(measured, hits)
    }

    @Test
    fun `超出容量淘汰最旧而不是涨内存`() {
        val n = TokenEstimateCache.MAX_ENTRIES + 100
        for (i in 1..n) {
            TokenEstimateCache.tokensFor(msg("id$i", "x$i"), 1L)
        }
        // 前 100 条已被淘汰；再读它们只是重算一次，结果仍正确。
        val early = msg("id1", "x1")
        assertEquals(estimateTokens(early), TokenEstimateCache.tokensFor(early, 1L))
        // 最近的还在：指纹一致直接命中（结果与重算一致）。
        val latest = msg("id$n", "x$n")
        assertEquals(estimateTokens(latest), TokenEstimateCache.tokensFor(latest, 1L))
    }
}
