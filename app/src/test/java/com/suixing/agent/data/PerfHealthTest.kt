package com.suixing.agent.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 健康度这一组数字的意义全在"分位数怎么算"上：均值会把一轮慢轮摊薄，
 * 而页面要回答的是"用户会不会觉得卡"。这里钉住三件事：
 * 最近秩不插值、0 是"没采到"而不是"测得零"、抖动是尾部/中位。
 */
class PerfHealthTest {

    private fun record(ttft: Long, total: Long, prompt: Int = 100, completion: Int = 10) =
        PerfRecord(
            at = 0,
            ttftMs = ttft,
            totalMs = total,
            promptTokens = prompt,
            completionTokens = completion,
        )

    @Test
    fun `percentile is nearest rank and never interpolates`() {
        val values = listOf(10L, 20L, 30L, 40L)
        assertEquals(20L, PerfHealth.percentile(values, 0.50))
        assertEquals(40L, PerfHealth.percentile(values, 0.90))
    }

    @Test
    fun `a single sample is its own percentile`() {
        assertEquals(7L, PerfHealth.percentile(listOf(7L), 0.90))
    }

    @Test
    fun `no samples gives zero rather than an invented number`() {
        assertEquals(0L, PerfHealth.percentile(emptyList(), 0.90))
    }

    @Test
    fun `zero readings are missing data and stay out of the percentiles`() {
        val health = PerfHealth.of(listOf(record(0, 1000), record(500, 2000), record(0, 0)))
        assertEquals(3, health.runs)
        assertEquals(500L, health.ttftP50)
        assertEquals(500L, health.ttftP90)
        assertEquals(1000L, health.totalP50)
        assertEquals(2000L, health.totalP90)
    }

    @Test
    fun `jitter is the tail over the median`() {
        val health = PerfHealth.of(listOf(record(100, 1000), record(100, 4000)))
        assertEquals(4.0, health.jitter, 0.001)
    }

    /** 口径统一：比率在数据层不取整，两位小数只在显示层格式化（与聊天页的会话统计同一条规矩）。 */
    @Test
    fun `the cache ratio keeps its fraction until the screen formats it`() {
        val one = PerfRecord(promptTokens = 300, cachedTokens = 100)
        assertEquals(100.0 / 3.0, one.cachePercent, 1e-9)
        val health = PerfHealth.of(listOf(one, PerfRecord(promptTokens = 100, cachedTokens = 100)))
        assertEquals((100.0 / 3.0 + 100.0) / 2.0, health.cachePercent, 1e-9)
    }

    @Test
    fun `slow runs and missing usage are counted, not estimated`() {
        val health = PerfHealth.of(
            listOf(
                record(100, 1000, prompt = 100),
                record(100, 90_000, prompt = 0),
            ),
        )
        assertEquals(1, health.slowRuns)
        assertEquals(1, health.noUsageRuns)
    }

    @Test
    fun `an empty window reports nothing rather than zeros dressed as data`() {
        val health = PerfHealth.of(emptyList())
        assertEquals(0, health.runs)
        assertEquals(0.0, health.jitter, 0.001)
    }
}
