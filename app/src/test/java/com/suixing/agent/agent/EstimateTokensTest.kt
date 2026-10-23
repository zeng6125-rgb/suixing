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
    fun `a length divisible by three is the only case identical to the old floor`() {
        // **注意**（issue #39 问题 4）：`(n+2)/3` 是向上取整，不是"只把 0 补成 1"。
        // 只有 n 是 3 的倍数时与旧的 `n/3` 相同（约占 1/3 的情形）；其余全部上调。
        assertEquals(100, estimateTokens(msg("字".repeat(300))))
    }

    @Test
    fun `the ceiling costs at most one token per message, not a third`() {
        // **更正 issue #39 的「+33% 全局膨胀」**：`(n+2)/3` 相对旧的 `n/3`，
        // 每条消息**最多多算 1 token**（n % 3 == 0 时相同，否则 +1），不是按比例膨胀。
        // 均匀长度分布下整体比值 ≈ 1.008；只有极短消息（1~2 字）从 0 变 1 的相对涨幅看起来大。
        for (n in 0..2_000) {
            val delta = estimateTokens(msg("x".repeat(n))) -
                (n / 3)
            assertTrue("n=$n 的差值只能是 0 或 1（实测 $delta）", delta == 0 || delta == 1)
        }
        var floorTotal = 0
        var ceilTotal = 0
        for (n in 1..500) {
            floorTotal += n / 3
            ceilTotal += estimateTokens(msg("x".repeat(n)))
        }
        val ratio = ceilTotal.toDouble() / floorTotal
        assertTrue("整体比值接近 1（实测 $ratio）", ratio in 1.00..1.02)
    }

    @Test
    fun `an image costs the same whether it comes from the list or the single field`() {
        // `imageCount` 是 `allImages.size` 的零分配替代，两者必须给出同一个数。
        val list = StoredMessage(id = "a", role = Roles.USER, text = "x", imagesBase64 = listOf("1", "2"))
        val single = StoredMessage(id = "b", role = Roles.USER, text = "x", imageBase64 = "1")
        val none = StoredMessage(id = "c", role = Roles.USER, text = "x")
        assertEquals(2, list.imageCount)
        assertEquals(1, single.imageCount)
        assertEquals(0, none.imageCount)
        assertEquals(list.allImages.size, list.imageCount)
        assertEquals(single.allImages.size, single.imageCount)
        assertEquals(none.allImages.size, none.imageCount)
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
