package com.suixing.agent.data

import android.os.Debug
import android.os.Process
import java.io.File
import kotlin.math.roundToInt

/**
 * 设备实时指标的一次采样。**读不到就是 null**，页面画成「—」。
 *
 * 读不到通常不是代码错：GPU 利用率在 Android 上没有公开 API，各芯片的 sysfs 节点名字还不一样
 * （Adreno 在 /sys/class/kgsl 下面，Mali 在 /sys/class/devfreq 下面），系统 CPU 的 /proc/stat 在部分
 * ROM 上被 SELinux 挡住。显示 0 会被读成"测到零"，那是另一种撒谎 —— 和性能页里首字延迟的
 * 「—」是同一条规矩。
 */
data class LiveMetrics(
    /** 本进程 CPU（% of one core；多线程忙起来可以超过 100）。 */
    val processCpuPercent: Int? = null,
    /** 整机 CPU 忙时百分比（0..100）。 */
    val systemCpuPercent: Int? = null,
    val javaHeapMb: Int? = null,
    val javaHeapMaxMb: Int? = null,
    /** 本进程 PSS（MB）。 */
    val pssMb: Int? = null,
    val threads: Int? = null,
    /** GPU 忙时百分比；只有部分机型暴露这个节点。 */
    val gpuPercent: Int? = null,
    /** GPU 当前频率（MHz）。 */
    val gpuFreqMhz: Int? = null,
)

/**
 * 采样器：**自己记住上一次的读数**，百分比都是两次采样之间的增量算出来的，
 * 所以第一次 [sample] 给不出 CPU（没有上一帧可比），页面会先显示「—」，一秒后才有数。
 *
 * 调用方负责节奏（页面里 1s 一次）；这里不持有线程，也没有后台协程。
 */
class LiveSampler {

    companion object {
        /**
         * PSS 的采样间隔（秒）。**公开**：性能页那行标签直接读它，改这里界面文案跟着变，
         * 不会出现"代码 5 秒、界面写着 3 秒"这种对不上的承诺。
         */
        const val PSS_INTERVAL_SECONDS = 5

        private const val MB = 1024L * 1024L

        /**
         * PSS 每隔 [PSS_INTERVAL_SECONDS] 秒量一次，中间沿用上次的数。
         *
         * `Debug.getPss()` 要读整份 `/proc/self/smaps`，是这一组读数里最贵的一个；而 PSS 本来
         * 也不会在一秒里明显变化。每秒量一次等于把"看性能"变成"制造性能问题"。
         */
        private val PSS_INTERVAL_MS = PSS_INTERVAL_SECONDS * 1000L

        /** GPU 利用率/频率的候选节点（各家芯片不同）。 */
        private val GPU_BUSY_PATHS = listOf("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")
        private val GPU_FREQ_PATHS = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
        )
        private val FIRST_INTEGER = Regex("-?\\d+")
    }

    private var lastWallMs = 0L
    private var lastProcessCpuMs = -1L
    private var lastSysTotal = 0L
    private var lastSysIdle = 0L
    private val systemStatFile = File("/proc/stat")
    private var systemStatReadable: Boolean? = null

    /** PSS 的节流状态。 */
    private var lastPssAt = 0L
    private var lastPssMb: Int? = null

    /**
     * GPU 节点**只探一次**。
     *
     * 探测本身要 `canRead()` 几个固定路径，读不到时还要 `listFiles()` 扫 `/sys/class/devfreq`。
     * 一台不暴露 GPU 节点的机器上，每秒重做一遍就是每秒一次无用 IO —— 探到没有就记住没有。
     */
    private var gpuProbed = false
    private var gpuBusyPath: String? = null
    private var gpuFreqPath: String? = null

    fun sample(): LiveMetrics {
        val wall = System.nanoTime() / 1_000_000L
        val processCpu = runCatching { Process.getElapsedCpuTime() }.getOrDefault(-1L)
        val stat = systemStat()
        val runtime = Runtime.getRuntime()

        val metrics = LiveMetrics(
            processCpuPercent = percentBetween(lastProcessCpuMs, processCpu, lastWallMs, wall),
            systemCpuPercent = stat?.let { (total, idle) ->
                if (lastSysTotal <= 0L || total <= lastSysTotal) {
                    null
                } else {
                    val dTotal = total - lastSysTotal
                    val dIdle = (idle - lastSysIdle).coerceAtLeast(0L)
                    (((dTotal - dIdle) * 100.0) / dTotal).roundToInt().coerceIn(0, 100)
                }
            },
            javaHeapMb = ((runtime.totalMemory() - runtime.freeMemory()) / MB).toInt(),
            javaHeapMaxMb = (runtime.maxMemory() / MB).toInt(),
            pssMb = pssMb(wall),
            threads = threadCount(),
            gpuPercent = gpuBusyPercent(),
            gpuFreqMhz = gpuFreqMhz(),
        )

        lastWallMs = wall
        lastProcessCpuMs = processCpu
        stat?.let { (total, idle) ->
            lastSysTotal = total
            lastSysIdle = idle
        }
        return metrics
    }

    private fun percentBetween(prevCpu: Long, cpu: Long, prevWall: Long, wall: Long): Int? {
        if (prevWall <= 0L || prevCpu < 0L || cpu < 0L || wall <= prevWall) return null
        val dCpu = cpu - prevCpu
        val dWall = wall - prevWall
        if (dCpu < 0L || dWall <= 0L) return null
        return ((dCpu * 100.0) / dWall).roundToInt()
    }

    /** /proc/stat 第一行：cpu user nice system idle iowait irq softirq steal ... ⇒ (总, 闲)。 */
    private fun systemStat(): Pair<Long, Long>? {
        // Like GPU probing, a denied node stays unavailable for this page instance.
        // Re-entering creates a fresh sampler, so changed system availability can be retried.
        if (systemStatReadable == null) systemStatReadable = runCatching { systemStatFile.canRead() }.getOrDefault(false)
        if (systemStatReadable != true) return null
        return runCatching {
            systemStatFile.useLines { lines -> lines.firstOrNull() }
        }.getOrElse { systemStatReadable = false; null }
        ?.takeIf { it.startsWith("cpu ") }
        ?.let { line ->
            val parts = line.trim().split(' ')
                .filter { it.isNotEmpty() }
                .drop(1)
                .mapNotNull { it.toLongOrNull() }
            if (parts.size < 5) null else parts.sum() to (parts[3] + parts[4])
        }
    }

    /** 见 [PSS_INTERVAL_SECONDS]：贵的那个按这个间隔量，中间沿用上次的数。 */
    private fun pssMb(wall: Long): Int? {
        if (lastPssAt > 0L && wall - lastPssAt < PSS_INTERVAL_MS) return lastPssMb
        lastPssAt = wall
        lastPssMb = runCatching { (Debug.getPss() / 1024).toInt() }.getOrNull()
        return lastPssMb
    }

    /** /proc/self/status 的 Threads 是准数；读不到退回 Java 的近似值。 */
    private fun threadCount(): Int? = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("Threads:") }
        }?.substringAfter(':')?.trim()?.toIntOrNull()
    }.getOrNull() ?: Thread.activeCount()

    /** 见 [gpuProbed]：只探一次，之后只读已确认可读的那个节点。 */
    private fun probeGpu() {
        if (gpuProbed) return
        gpuProbed = true
        gpuBusyPath = GPU_BUSY_PATHS.firstOrNull { runCatching { File(it).canRead() }.getOrDefault(false) }
        gpuFreqPath = GPU_FREQ_PATHS.firstOrNull { runCatching { File(it).canRead() }.getOrDefault(false) }
            ?: devfreqGpuFreqPath()
    }

    private fun gpuBusyPercent(): Int? {
        probeGpu()
        val path = gpuBusyPath ?: return null
        return readFirstInt(path)?.coerceIn(0, 100)
    }

    /** 频率单位统一按 Hz 处理，最后换 MHz。 */
    private fun gpuFreqMhz(): Int? {
        probeGpu()
        val hz = gpuFreqPath?.let { readFirstInt(it) } ?: return null
        return (hz / 1_000_000).takeIf { mhz -> mhz > 0 }
    }

    /**
     * devfreq 下按 `name` 找 GPU 的 `cur_freq`。
     *
     * 只在探测时调用一次：`listFiles()` 扫一个动态目录比读一个固定文件贵得多，而节点路径
     * 在进程生命周期里不会变。
     */
    private fun devfreqGpuFreqPath(): String? = runCatching {
        File("/sys/class/devfreq").listFiles()
            ?.firstOrNull { dir ->
                runCatching { File(dir, "name").readText() }.getOrDefault("")
                    .contains("gpu", ignoreCase = true)
            }
            ?.let { File(it, "cur_freq") }
            ?.takeIf { it.canRead() }
            ?.absolutePath
    }.getOrNull()

    private fun readFirstInt(path: String): Int? = runCatching {
        File(path).takeIf { it.canRead() }?.readText()?.trim()
            ?.let { text -> FIRST_INTEGER.find(text)?.value?.toInt() }
    }.getOrNull()
}
