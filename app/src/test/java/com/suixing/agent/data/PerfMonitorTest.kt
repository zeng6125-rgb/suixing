package com.suixing.agent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 性能记录：有界、可落盘、可重载；没数据时不编数字。
 */
class PerfMonitorTest {
    @Test fun `a record arriving before background load retains old records and late load is harmless`() {
        monitor().record(entry(1))
        val warm = monitor()
        warm.record(entry(2))
        warm.load()
        assertEquals(listOf(2L, 1L), warm.recent().map { it.at })
        assertEquals(listOf(2L, 1L), monitor().apply { load() }.recent().map { it.at })
    }

    @get:Rule
    val folder = TemporaryFolder()

    private fun monitor(): PerfMonitor = PerfMonitor(File(folder.root, "perf.json"))

    private fun entry(at: Long, ttft: Long = 100, total: Long = 1000, prompt: Int = 0, cached: Int = 0) =
        PerfRecord(
            at = at,
            conversationId = "c1",
            ttftMs = ttft,
            totalMs = total,
            promptTokens = prompt,
            completionTokens = 100,
            cachedTokens = cached,
        )

    @Test
    fun `records survive a reload`() {
        val first = monitor()
        first.record(entry(1))

        val second = monitor().apply { load() }

        assertEquals(1, second.recent().size)
        assertEquals(1L, second.recent().first().at)
    }

    @Test
    fun `only the newest MAX_RECORDS are kept`() {
        val monitor = monitor()
        repeat(PerfMonitor.MAX_RECORDS + 5) { monitor.record(entry(it.toLong())) }

        assertEquals(PerfMonitor.MAX_RECORDS, monitor.recent(limit = 1000).size)
        assertEquals("最新的一条要在最前", (PerfMonitor.MAX_RECORDS + 4).toLong(), monitor.recent().first().at)
    }

    @Test
    fun `the file is bounded too`() {
        val monitor = monitor()
        repeat(PerfMonitor.MAX_RECORDS + 5) { monitor.record(entry(it.toLong())) }

        val reloaded = monitor().apply { load() }

        assertEquals(PerfMonitor.MAX_RECORDS, reloaded.recent(limit = 1000).size)
    }

    @Test
    fun `an empty monitor reports zeros instead of inventing numbers`() {
        val summary = monitor().summary()

        assertEquals(0, summary.count)
        assertEquals(null, summary.latest)
        assertEquals(0L, summary.avgTtftMs)
        assertEquals(0.0, summary.avgTokensPerSecond, 0.0)
    }

    @Test
    fun `cache percent only counts when the endpoint reported prompt tokens`() {
        val monitor = monitor()
        monitor.record(entry(1, prompt = 0, cached = 500))
        monitor.record(entry(2, prompt = 1000, cached = 400))

        // 注意：这里必须是已记录过数据的 monitor 实例，不是工厂新建的空实例。
        val summary = monitor.summary()

        assertEquals("没报 prompt 的那条不能算成一个 0% 的样本", 40.0, summary.avgCachePercent, 1e-9)
    }

    @Test
    fun `clear removes both memory and file`() {
        val monitor = monitor()
        monitor.record(entry(1))
        monitor.clear()

        assertEquals(0, monitor.recent().size)
        assertTrue(!File(folder.root, "perf.json").exists() || monitor().apply { load() }.recent().isEmpty())
    }

    @Test
    fun `a corrupt file is treated as empty`() {
        File(folder.root, "perf.json").writeText("{ not json")

        val monitor = monitor().apply { load() }

        assertEquals(0, monitor.recent().size)
    }
}
