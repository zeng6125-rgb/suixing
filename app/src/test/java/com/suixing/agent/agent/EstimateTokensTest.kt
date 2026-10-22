package com.suixing.agent.agent

import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * token 估算的**取整规则**（issue #37 第二条）。
 *
 * 原来 `text / 3` 的整数除法让 **1~2 个字符的消息算 0 token**：几十条短消息累积起来，
 * 预算判断就会系统性偏低 ⇒ 发出超预算的请求 ⇒ 端点拒绝。
 *
 * 这条测试锁的是「**非空就至少算 1，且向上取整**」这个规则本身，不声称估算准确
 * （真实值以端点回报的 `prompt_tokens` 为准）。
 */
class EstimateTokensTest {

    private fun msg(text: String) = StoredMessage(id = "m", role = Roles.USER, text = text)

    @Test
    fun `an empty message costs nothing`() {
        assertEquals(0, estimateTokens(msg("")))
    }

    @Test
    fun `a one or two character message is never free`() {
        // 这就是原来算成 0 的情形。
        assertEquals("1 个字符也必须 ≥ 1", 1, estimateTokens(msg("嗯")))
        assertEquals(1, estimateTokens(msg("嗯嗯")))
    }

    @Test
    fun `rounding is upward`() {
        assertEquals(1, estimateTokens(msg("abc")))       // 3/3
        assertEquals(2, estimateTokens(msg("abcd")))      // 4/3 → 上取整
        assertEquals(2, estimateTokens(msg("abcde")))     // 5/3 → 上取整
        assertEquals(2, estimateTokens(msg("abcdef")))    // 6/3
    }

    @Test
    fun `a long message still divides as before`() {
        // 300 能被 3 整除 ⇒ 与旧实现一致（既有断言不受影响）。
        assertEquals(100, estimateTokens(msg("字".repeat(300))))
    }

    @Test
    fun `estimate never shrinks as text grows`() {
        var previous = -1
        for (length in 0..60) {
            val cost = estimateTokens(msg("x".repeat(length)))
            assertTrue("长度 $length 的估算不应小于更短的那条", cost >= previous)
            previous = cost
        }
    }

    @Test
    fun `image cost is added on top and unaffected`() {
        val withImage = StoredMessage(
            id = "m", role = Roles.USER, text = "", imagesBase64 = listOf("abc"),
        )
        val noImage = estimateTokens(msg(""))
        assertTrue("带图必须比不带图贵", estimateTokens(withImage) > noImage)
    }
}
