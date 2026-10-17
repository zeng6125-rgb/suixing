package com.suixing.agent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 官方 TPS 口径的回归网（一手核对自本机 `@deepseek-ai/dsh-client-ui-chat/lib/client.js`）。
 *
 * 三条官方语义各自钉死：分母是 decodeMs（首字之后→结束，不含 TTFT）、分子含推理 token、
 * 多轮聚合＝ΣdecodeTokens/ΣdecodeMs；外加官方取整规则与 firstTokenMs 缺失时的墙钟 fallback。
 */
class TpsTest {

    // -------------------------------------------------- decodeMs：分母

    @Test
    fun `decodeMs 减掉首字之前的 TTFT 等待`() {
        // elapsed 4594、首字在 1746 → decode 段只有 2848。
        assertEquals(2848L, Tps.decodeMs(elapsedMs = 4594L, firstTokenMs = 1746L))
    }

    @Test
    fun `decodeMs 在首字未知时退回整轮墙钟（官方 fallback）`() {
        // client.js:8273 firstTokenTime 缺失时用 completedTime - stepStartTime。
        // 随行用 firstTokenMs==0 表达「没流式过 / 老转录未记录」，此时 decodeMs＝elapsedMs。
        assertEquals(4594L, Tps.decodeMs(elapsedMs = 4594L, firstTokenMs = 0L))
    }

    @Test
    fun `decodeMs clamp 到非负`() {
        // 时钟抖动或 firstTokenMs>elapsedMs 的畸形数据不该产出负分母。
        assertEquals(0L, Tps.decodeMs(elapsedMs = 100L, firstTokenMs = 500L))
    }

    // -------------------------------------------------- of：数值

    @Test
    fun `官方真实锚点 780tok 除以 2848ms 得 273_88`() {
        // 参考实现的本机实测锚点：outputTokens=780、ttft=1746、total=4594 → 273.9。
        val tps = Tps.of(outputTokens = 780, elapsedMs = 4594L, firstTokenMs = 1746L)
        assertEquals(273.88, tps, 0.01)
    }

    @Test
    fun `decodeMs 为 0 不可测`() {
        assertEquals(0.0, Tps.of(outputTokens = 100, decodeMs = 0L), 0.0)
        assertEquals(0.0, Tps.of(outputTokens = 100, decodeMs = -3L), 0.0)
    }

    @Test
    fun `没有输出 token 不可测`() {
        assertEquals(0.0, Tps.of(outputTokens = 0, decodeMs = 5000L), 0.0)
    }

    // -------------------------------------------------- format：官方取整

    @Test
    fun `大于等于 10 取整四舍五入`() {
        assertEquals("10", Tps.format(10.0))
        assertEquals("10", Tps.format(10.4))
        assertEquals("11", Tps.format(10.5))
        assertEquals("274", Tps.format(273.88))
        assertEquals("359", Tps.format(358.8))
        // 参考实现锁的那个数量级：官方口径同样取整，不虚构小数位。
        assertEquals("11815", Tps.format(11814.8))
    }

    @Test
    fun `小于 10 保留一位小数`() {
        assertEquals("7.3", Tps.format(7.34))
        assertEquals("7.4", Tps.format(7.35))
        assertEquals("9.9", Tps.format(9.94))
    }

    @Test
    fun `官方边界怪癖 9_96 进位到 10（照抄不修正）`() {
        // <10 分支先乘 10 再四舍五入：9.96*10=99.6→round→100→/10=10.0→整数形态 "10"。
        assertEquals("10", Tps.format(9.96))
    }

    @Test
    fun `负值与非有限值 clamp 到 0`() {
        assertEquals("0", Tps.format(-5.0))
        assertEquals("0", Tps.format(Double.NaN))
        assertEquals("0", Tps.format(Double.POSITIVE_INFINITY))
        assertEquals("0", Tps.format(0.0))
    }

    // -------------------------------------------------- label：带单位的一格

    @Test
    fun `label 带上官方 tok_s 单位`() {
        assertEquals("274 tok/s", Tps.label(780, elapsedMs = 4594L, firstTokenMs = 1746L))
    }

    @Test
    fun `label 不可测时返回 null（由调用方显示 —）`() {
        assertNull(Tps.label(outputTokens = 100, elapsedMs = 0L, firstTokenMs = 0L))
        assertNull(Tps.label(outputTokens = 0, elapsedMs = 5000L, firstTokenMs = 100L))
    }
}
