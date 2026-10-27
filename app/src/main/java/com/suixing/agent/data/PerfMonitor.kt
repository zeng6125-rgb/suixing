package com.suixing.agent.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 一轮请求的实测记录。字段都是**已经发生的事实**，不是估算：
 * 首字延迟、整轮耗时、端点回报的 token 与缓存命中、工具轮数、请求体大小。
 */
@Serializable
data class PerfRecord(
    /** 记录时间（墙钟）。 */
    val at: Long = 0,
    val conversationId: String = "",
    /** 从发出请求到第一个字回来；0 表示这一轮没有可测的首字（例如纯工具轮）。 */
    val ttftMs: Long = 0,
    /** 整轮（含工具往返）耗时。 */
    val totalMs: Long = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    /** 这一轮里工具往返了几次。 */
    val toolRounds: Int = 0,
    /** 出站请求体的字符数（裁剪之后真正发出去的那一份）。 */
    val wireChars: Int = 0,
) {
    /**
     * 缓存命中率（0..100）；端点没报缓存时是 0。
     *
     * 不取整：与 [SessionStats.cachePercent][com.suixing.agent.ui.chat.SessionStats] 同一条规矩 ——
     * 精度在数据层留着，两位小数由显示层格式化（性能页的「缓存命中（均值）」与每轮明细）。
     * 用 Long 相乘，避开 cachedTokens 超过 21,474,837 时的 Int 溢出。
     */
    val cachePercent: Double
        get() = if (promptTokens <= 0) 0.0 else cachedTokens.toLong() * 100.0 / promptTokens

    /** 每秒输出 token；没有 completion 或没有耗时就是 0，不编数。 */
    val tokensPerSecond: Double
        get() = if (completionTokens <= 0 || totalMs <= 0) 0.0 else completionTokens * 1000.0 / totalMs
}

/** 监测页顶部的几个大数字：最近一轮 + 一段时间的均值。 */
data class PerfSummary(
    val latest: PerfRecord? = null,
    val count: Int = 0,
    val avgTtftMs: Long = 0,
    val avgTotalMs: Long = 0,
    val avgCachePercent: Double = 0.0,
    val avgTokensPerSecond: Double = 0.0,
)

/**
 * 性能记录的本地存储：**有界**（只留最近 [MAX_RECORDS] 条）并落盘，跨重启可看。
 *
 * 为什么要落盘：想回答「这次改动到底有没有变快」只能对比同一台设备上的前后两组数字，
 * 只存内存的话每次重启都要从零开始攒。
 *
 * 为什么有界：这些记录是给"最近发生了什么"用的，不是账本；无界增长会让一个跑了几千轮的
 * 会话把 JSON 堆成几十 MB，反过来又成了新的性能问题。
 */
class PerfMonitor(private val file: File) {

    companion object {
        /** 保留多少条最近记录。200 轮足够看出趋势，文件也就几十 KB。 */
        const val MAX_RECORDS = 200
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 串行化读写：记录来自 run 的协程，读取来自设置页。 */
    private val lock = Any()

    private val records = ArrayDeque<PerfRecord>()

    /** 从磁盘载入；坏文件当作空，不让监测本身把应用带崩。 */
    fun load() {
        synchronized(lock) {
            records.clear()
            if (!file.exists()) return
            val text = runCatching { file.readText() }.getOrNull() ?: return
            val list = runCatching {
                json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(PerfRecord.serializer()),
                    text,
                )
            }.getOrNull() ?: return
            records.addAll(list.takeLast(MAX_RECORDS))
        }
    }

    /** 记一轮，超过上限就丢最旧的。 */
    fun record(entry: PerfRecord) {
        synchronized(lock) {
            records.addLast(entry)
            while (records.size > MAX_RECORDS) records.removeFirst()
            persist()
        }
    }

    /** 最近的记录，最新在最前。 */
    fun recent(limit: Int = 50): List<PerfRecord> = synchronized(lock) {
        List(limit.coerceIn(0, records.size)) { index -> records[records.lastIndex - index] }
    }

    /** 顶部几个数字。没有任何记录时全 0，而不是编一个数。 */
    fun summary(limit: Int = 50): PerfSummary = synchronized(lock) {
        val count = limit.coerceAtLeast(1).coerceAtMost(records.size)
        val window = List(count) { index -> records[records.size - count + index] }
        if (window.isEmpty()) return PerfSummary()
        // **一个循环算四组平均**（优化清单 data-P2）：原来 4 条 `filter{}.map{}.average()`
        // 会建 8 个中间 List。分组条件与分母完全不变（各算各的样本数）。
        var ttftSum = 0L
        var ttftN = 0
        var totalSum = 0L
        var totalN = 0
        var cacheSum = 0.0
        var cacheN = 0
        var tpsSum = 0.0
        var tpsN = 0
        for (r in window) {
            if (r.ttftMs > 0) { ttftSum += r.ttftMs; ttftN++ }
            if (r.totalMs > 0) { totalSum += r.totalMs; totalN++ }
            if (r.promptTokens > 0) { cacheSum += r.cachePercent; cacheN++ }
            if (r.tokensPerSecond > 0) { tpsSum += r.tokensPerSecond; tpsN++ }
        }
        PerfSummary(
            latest = window.last(),
            count = window.size,
            avgTtftMs = if (ttftN == 0) 0L else (ttftSum.toDouble() / ttftN).toLong(),
            avgTotalMs = if (totalN == 0) 0L else (totalSum.toDouble() / totalN).toLong(),
            avgCachePercent = if (cacheN == 0) 0.0 else cacheSum / cacheN,
            avgTokensPerSecond = if (tpsN == 0) 0.0 else tpsSum / tpsN,
        )
    }

    fun clear() {
        synchronized(lock) {
            records.clear()
            runCatching { file.delete() }
        }
    }

    /** 先写临时文件再改名：写到一半被杀掉时，旧文件还完整。 */
    private fun persist() {
        runCatching {
            val tmp = File(file.absolutePath + ".tmp")
            tmp.parentFile?.mkdirs()
            val text = json.encodeToString(PerfRecord.serializer().let { kotlinx.serialization.builtins.ListSerializer(it) }, records.toList())
            java.io.FileOutputStream(tmp).use {
                it.write(text.toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/**
 * 一段记录的"健康度"：**分位数**，不是只有均值。
 *
 * 均值会被一轮 40s 的慢轮拉走，而"卡不卡"是尾部决定的 —— 用户感觉到的是 p90，不是平均。
 * 所以这一组数字里既有 p50/p90，也有 [jitter]（p90/p50）：1.0 说明这一段的耗时很平，
 * 2.0 说明有一半的轮次拖到了中位数的两倍。
 */
data class PerfHealth(
    val runs: Int = 0,
    val ttftP50: Long = 0,
    val ttftP90: Long = 0,
    val totalP50: Long = 0,
    val totalP90: Long = 0,
    val cachePercent: Double = 0.0,
    val tokensPerSecond: Double = 0.0,
    /** 端点没回报 usage 的轮数：这些轮拿不到 token 数，速度也算不出来。 */
    val noUsageRuns: Int = 0,
    /** 整轮超过 [SLOW_MS] 的轮数。 */
    val slowRuns: Int = 0,
) {
    /** 尾部/中位倍数；1.0 表示这一段很平。没有中位数时是 0（页面画成「—」）。 */
    val jitter: Double get() = if (totalP50 <= 0L) 0.0 else totalP90.toDouble() / totalP50

    companion object {
        /** 一轮超过这么久，人已经能明显感觉到"卡住了"。 */
        const val SLOW_MS = 60_000L

        fun of(records: List<PerfRecord>): PerfHealth {
            if (records.isEmpty()) return PerfHealth()
            // 0 在这里的含义是「没有采集到」（纯工具轮没有首字、没有 usage），不进分位数。
            val ttft = records.map { it.ttftMs }.filter { it > 0 }
            val total = records.map { it.totalMs }.filter { it > 0 }
            return PerfHealth(
                runs = records.size,
                ttftP50 = percentile(ttft, 0.50),
                ttftP90 = percentile(ttft, 0.90),
                totalP50 = percentile(total, 0.50),
                totalP90 = percentile(total, 0.90),
                cachePercent = records.filter { it.promptTokens > 0 }
                    .map { it.cachePercent }
                    .takeIf { it.isNotEmpty() }
                    ?.average() ?: 0.0,
                tokensPerSecond = records.map { it.tokensPerSecond }
                    .filter { it > 0.0 }
                    .takeIf { it.isNotEmpty() }
                    ?.average() ?: 0.0,
                noUsageRuns = records.count { it.promptTokens <= 0 },
                slowRuns = records.count { it.totalMs > SLOW_MS },
            )
        }

        /** 最近秩（nearest-rank）分位：样本少的时候也不插值编数。 */
        internal fun percentile(values: List<Long>, p: Double): Long {
            if (values.isEmpty()) return 0L
            val sorted = values.sorted()
            val index = kotlin.math.ceil(p * sorted.size).toInt().coerceIn(1, sorted.size) - 1
            return sorted[index]
        }
    }
}
