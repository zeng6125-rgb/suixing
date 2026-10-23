package com.suixing.agent.agent

import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * token 估算的**取整规则**与**脚本系数**。
 *
 * 系数不是猜的：取设备上一个真实会话（927 条消息、425 条带端点回报的 usage），
 * 对 192 条"回答"样本做过原点最小二乘拟合 `tokens = a·CJK + b·其它`，得到
 * **CJK 1.10 token/字**、**其它 0.39 token/字**（旧实现中英同价 1/3）。
 * 见 `tokensFor` 的 KDoc。
 *
 * 这组断言锁的是"系数与取整的行为"，**不声称等于端点真实值** —— 真实值只有
 * `prompt_tokens` 是权威，系数是拟合出来的中位近似。
 */
class EstimateTokensTest {

    private fun msg(text: String) = StoredMessage(id = "m", role = Roles.USER, text = text)

    @Test
    fun `an empty message costs nothing`() {
        assertEquals(0, estimateTokens(msg("")))
        assertEquals(0, tokensFor(""))
    }

    @Test
    fun `a one or two character message is never free`() {
        // 旧实现 `len/3` 会把 1~2 字算成 0 token；那条是真 bug（预算被系统性低估）。
        assertTrue(estimateTokens(msg("嗯")) >= 1)
        assertTrue(estimateTokens(msg("嗯嗯")) >= 1)
    }

    @Test
    fun `latin text costs about 39 hundredths per character`() {
        // 'abc' 3 个字符 ⇒ 117/100 ⇒ 向上取整成 2。
        assertEquals(2, tokensFor("abc"))
        assertEquals(4, tokensFor("abcdefghi"))       // 9 × 0.39 = 3.51 ⇒ 向上取整 4
    }

    @Test
    fun `chinese costs about three times what latin does per character`() {
        // 中文 13 字 ⇒ 14.3 ⇒ 15；拉丁 43 字符 ⇒ 16.8 ⇒ 17。两者量级相近但**每字**差 2.8 倍。
        val cjk = tokensFor("这是一个用来测试的中文句子")
        val latin = tokensFor("this is a sentence used to test the tokenizer token")
        assertTrue("中文 13 字应在 13~16（实测 $cjk）", cjk in 13..16)
        // 非空白 42 字符 × 0.39 = 16.4 ⇒ 17（空白不计费）
        assertTrue("拉丁 42 个非空白字符应在 16~18（实测 $latin）", latin in 16..18)
        assertTrue("每字单价：中文应远贵于拉丁", cjk / 13.0 > latin / 51.0 * 2.5)
    }

    @Test
    fun `mixed text sums both scripts`() {
        val onlyCjk = tokensFor("测试测试测试测试")          // 8 字 ⇒ 8.8 ⇒ 9
        val onlyLatin = tokensFor("testtesttesttest")        // 16 字符 ⇒ 6.24 ⇒ 7
        val mixed = tokensFor("测试test测试test")            // 4 字 + 8 字符 ⇒ 4.4+3.1=7.5 ⇒ 8
        assertTrue("混排应介于两者之间（$mixed vs $onlyCjk/$onlyLatin）", mixed in onlyLatin..onlyCjk)
    }

    @Test
    fun `whitespace costs nothing`() {
        assertEquals(0, tokensFor(" "))
        assertEquals(tokensFor("abc"), tokensFor("  a b c  "))
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
        assertTrue("带图必须比不带图贵", estimateTokens(withImage) > estimateTokens(msg("")))
        assertTrue("带图必须算得出正数", estimateTokens(withImage) > 0)
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
    fun `a short message that used to be free is now charged`() {
        // 这条是 issue #37 的回归锁：`len/3` 让 1~2 字算 0，几十条短消息累积 ⇒ 预算被骗。
        assertEquals(0, tokensFor("") )
        assertTrue(tokensFor("x") >= 1)
        assertTrue(tokensFor("xy") >= 1)
    }
}
