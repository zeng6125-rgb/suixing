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

    private companion object {
        const val MB = 1024L * 1024L
    }

    private var lastWallMs = 0L
    private var lastProcessCpuMs = -1L
    private var lastSysTotal = 0L
    private var lastSysIdle = 0L

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
            pssMb = pssMb(),
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
    private fun systemStat(): Pair<Long, Long>? = runCatching {
        File("/proc/stat").useLines { lines -> lines.firstOrNull() }
    }.getOrNull()
        ?.takeIf { it.startsWith("cpu ") }
        ?.let { line ->
            val parts = line.trim().split(' ')
                .filter { it.isNotEmpty() }
                .drop(1)
                .mapNotNull { it.toLongOrNull() }
            if (parts.size < 5) null else parts.sum() to (parts[3] + parts[4])
        }

    @Suppress("DEPRECATION")
    private fun pssMb(): Int? = runCatching { (Debug.getPss() / 1024).toInt() }.getOrNull()

    /** /proc/self/status 的 Threads 是准数；读不到退回 Java 的近似值。 */
    private fun threadCount(): Int? = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("Threads:") }
        }?.substringAfter(':')?.trim()?.toIntOrNull()
    }.getOrNull() ?: Thread.activeCount()

    private fun gpuBusyPercent(): Int? =
        readFirstInt("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")?.coerceIn(0, 100)

    /** 频率单位统一按 Hz 处理，最后换 MHz；节点名各家不同，逐个试。 */
    private fun gpuFreqMhz(): Int? {
        val hz = readFirstInt("/sys/class/kgsl/kgsl-3d0/gpuclk")
            ?: readFirstInt("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq")
            ?: devfreqGpuFreq()
        return hz?.let { (it / 1_000_000).takeIf { mhz -> mhz > 0 } }
    }

    private fun devfreqGpuFreq(): Int? = runCatching {
        File("/sys/class/devfreq").listFiles()
            ?.firstOrNull { dir ->
                runCatching { File(dir, "name").readText() }.getOrDefault("")
                    .contains("gpu", ignoreCase = true)
            }
            ?.let { File(it, "cur_freq") }
            ?.takeIf { it.canRead() }
            ?.readText()?.trim()?.toLongOrNull()?.toInt()
    }.getOrNull()

    private fun readFirstInt(path: String): Int? = runCatching {
        File(path).takeIf { it.canRead() }?.readText()?.trim()
            ?.let { text -> Regex("-?\\d+").find(text)?.value?.toInt() }
    }.getOrNull()
}
