package com.suixing.agent.ui.chat

import android.content.Context
import android.view.Choreographer
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 常驻帧诊断：只记录**掉帧**的帧（帧间隔超过 120Hz 的预算），写到 `filesDir/frame.log`。
 *
 * 为什么写文件：这台机器的 `logd` 不可用（`Log.*` 与 `logcat` 都收不到任何东西），
 * 证据只能落盘读 —— 与 `pin.log` 同一套思路。
 *
 * 为什么不用 adb 采样：`atrace` 对 release 包收不到应用内 trace 区段（实测 321MB 里
 * 没有任何应用级数据），`simpleperf` 需要长时间采样窗口。应用内自我记录不需要任何
 * 配合 —— 装上一次，之后随便什么时候滚都能拿到证据。
 *
 * 开销：正常帧（<12ms）只做一次减法；只有异常帧才进队列，攒 40 条或每 3 秒刷一次盘。
 */
internal object FrameTrace {

    /** 掉帧门槛（ms）：120Hz 的预算是 8.3ms，留一档余量，避免把正常抖动也记下来。 */
    private const val STALL_MS = 12L

    /** 攒够多少条异常帧刷一次盘（正常滚动不产生 IO）。 */
    private const val FLUSH_EVERY = 40

    /** 心跳间隔：哪怕一条掉帧都没有，也定期写一行，便于确认采样确实在跑。 */
    private const val HEARTBEAT_FRAMES = 600

    private val installed = AtomicBoolean(false)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "frame-trace") }
    private val pending = ArrayList<String>(64)

    private var file: File? = null
    private var probe: (() -> String)? = null
    private var lastNanos = 0L
    private var frames = 0L
    private var stalls = 0L
    private var scrollFrames = 0L
    private var worstMs = 0L

    fun install(context: Context, probe: () -> String) {
        if (!installed.compareAndSet(false, true)) return
        this.file = File(context.filesDir, "frame.log")
        this.probe = probe
        // 每进程一份，旧的先清掉（只在启动时发生一次）。
        runCatching { file?.writeText("") }
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                step(frameTimeNanos)
                Choreographer.getInstance().postFrameCallback(this)
            }
        })
    }

    private fun step(now: Long) {
        val prev = lastNanos
        lastNanos = now
        frames++
        if (prev == 0L) return
        val dtMs = (now - prev) / 1_000_000L
        val info = probe?.invoke().orEmpty()
        val scrolling = info.startsWith("scroll=true")
        if (scrolling) scrollFrames++
        if (dtMs < STALL_MS) {
            if (frames % HEARTBEAT_FRAMES == 0L) heartbeat()
            return
        }
        stalls++
        if (dtMs > worstMs) worstMs = dtMs
        pending += "stall dt=${dtMs}ms frames=${frames} $info"
        if (pending.size >= FLUSH_EVERY) flush()
    }

    private fun heartbeat() {
        pending += "beat frames=$frames scroll=$scrollFrames stalls=$stalls worst=${worstMs}ms"
        flush()
    }

    private fun flush() {
        if (pending.isEmpty()) return
        val batch = pending.joinToString(separator = "\n", postfix = "\n")
        pending.clear()
        val target = file ?: return
        io.execute { runCatching { target.appendText(batch) } }
    }
}
