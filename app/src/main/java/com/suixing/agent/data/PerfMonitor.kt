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
    /** 缓存命中率（0..100）；端点没报缓存时是 0。 */
    val cachePercent: Int get() = if (promptTokens <= 0) 0 else cachedTokens * 100 / promptTokens

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
    val avgCachePercent: Int = 0,
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
        records.toList().asReversed().take(limit.coerceAtLeast(0))
    }

    /** 顶部几个数字。没有任何记录时全 0，而不是编一个数。 */
    fun summary(limit: Int = 50): PerfSummary = synchronized(lock) {
        val window = records.toList().takeLast(limit.coerceAtLeast(1))
        if (window.isEmpty()) return PerfSummary()
        PerfSummary(
            latest = window.last(),
            count = window.size,
            avgTtftMs = window.filter { it.ttftMs > 0 }.map { it.ttftMs }.average().let {
                if (it.isNaN()) 0L else it.toLong()
            },
            avgTotalMs = window.filter { it.totalMs > 0 }.map { it.totalMs }.average().let {
                if (it.isNaN()) 0L else it.toLong()
            },
            avgCachePercent = window.filter { it.promptTokens > 0 }.map { it.cachePercent }.average().let {
                if (it.isNaN()) 0 else it.toInt()
            },
            avgTokensPerSecond = window.filter { it.tokensPerSecond > 0 }.map { it.tokensPerSecond }.average().let {
                if (it.isNaN()) 0.0 else it
            },
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
            tmp.writeText(json.encodeToString(PerfRecord.serializer().let { kotlinx.serialization.builtins.ListSerializer(it) }, records.toList()))
            if (file.exists() && !file.delete()) return@runCatching
            tmp.renameTo(file)
        }
    }
}
