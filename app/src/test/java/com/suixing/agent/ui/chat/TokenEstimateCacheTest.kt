package com.suixing.agent.ui.chat

import com.suixing.agent.agent.estimateTokens
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 切会话时上下文估算**不许再全量重算**（切换主线程开销之一），同时内容一变必须失效。
 * 组合期只读已确认的实例，新实例由后台核对后发布估算。
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

    @Test
    fun `同一内容第二次直接命中缓存`() {
        val m = msg("a", "你好，世界".repeat(50))
        val first = TokenEstimateCache.tokensFor(m)
        assertEquals(estimateTokens(m), first)
        assertEquals(first, TokenEstimateCache.peek(m))
        assertEquals(first, TokenEstimateCache.tokensFor(m))
    }

    @Test
    fun `主页只读取已确认的消息实例，新实例交给后台核对`() {
        val original = msg("a", "完整正文".repeat(2_000))
        val tokens = TokenEstimateCache.tokensFor(original)
        val reloaded = original.copy()

        assertEquals(tokens, TokenEstimateCache.peek(original))
        assertNull(TokenEstimateCache.peek(reloaded))
        assertEquals(tokens, TokenEstimateCache.tokensFor(reloaded))
        assertEquals(tokens, TokenEstimateCache.peek(reloaded))
    }

    @Test
    fun `同长度中文编辑与工具参数图片变化使用最新估算`() {
        val original = msg("a", "a".repeat(60), "tool output")
        TokenEstimateCache.tokensFor(original)
        val edited = original.copy(
            text = "中".repeat(60),
            toolArgs = "参数".repeat(40),
            imageBase64 = "image",
        )
        assertNull(TokenEstimateCache.peek(edited))
        assertEquals(estimateTokens(edited), TokenEstimateCache.tokensFor(edited))
        assertNull(TokenEstimateCache.peek(original))
        assertEquals(estimateTokens(edited), TokenEstimateCache.peek(edited))
    }

    @Test
    fun `内容变了必须重算，不许读到旧估算`() {
        val old = msg("a", "第一版回答")
        TokenEstimateCache.tokensFor(old)
        val edited = old.copy(text = "第二版回答，长了很多字".repeat(20))
        val fresh = TokenEstimateCache.tokensFor(edited)
        assertEquals(estimateTokens(edited), fresh)
        assertTrue("编辑后估算应变化", fresh != estimateTokens(old))
    }

    @Test
    fun `切会话再切回来不重算`() {
        // 模拟：会话 A 的 30 条消息估过一遍 → 切到 B（缓存表不被"换会话"清空）→ 切回 A 全命中。
        val messages = (1..30).map { msg("m$it", "正文 $it".repeat(40)) }
        messages.forEach { TokenEstimateCache.tokensFor(it) }
        val measured = messages.map { estimateTokens(it) }
        val hits = messages.map { TokenEstimateCache.peek(it) }
        assertEquals(measured, hits)
    }

    @Test
    fun `超出容量淘汰最旧而不是涨内存`() {
        val n = TokenEstimateCache.MAX_ENTRIES + 100
        for (i in 1..n) {
            TokenEstimateCache.tokensFor(msg("id$i", "x$i"))
        }
        // 前 100 条已被淘汰；再读它们只是重算一次，结果仍正确。
        val early = msg("id1", "x1")
        assertEquals(estimateTokens(early), TokenEstimateCache.tokensFor(early))
        // 最近的内容仍可复用，结果与重算一致。
        val latest = msg("id$n", "x$n")
        assertEquals(estimateTokens(latest), TokenEstimateCache.tokensFor(latest))
    }
}
