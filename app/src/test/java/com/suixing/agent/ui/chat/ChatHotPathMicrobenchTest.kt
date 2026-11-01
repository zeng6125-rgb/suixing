package com.suixing.agent.ui.chat

import com.suixing.agent.agent.estimateTokens
import com.suixing.agent.data.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 聊天热路径两处改动的 JVM 成本对比（issue #59 A2 / #56 R39）。
 *
 * 与 `LiveBlocksMicrobenchTest` 同一思路：真机帧时间要设备，但"这两步有没有白做、
 * 改完输出一不一样"可以在 JVM 上量清楚。数字只作趋势参考（JVM ≠ ART）。
 */
class ChatHotPathMicrobenchTest {

    /**
     * A2：打字机每帧 `text.take(revealed)` vs 追平后直接返回原串。
     *
     * 两次交替跑，取**第二遍**（已 JIT 预热）的数字；结果累加进 `sink` 并读回，
     * 防止 JIT 把"没人看的字符串"整个消掉 —— 第一版没有这两条，测出来 fast 反而更慢，
     * 那是预热与顺序效应，不是代码本身。
     */
    @Test fun `typewriter fast path matches take and reports cost`() {
        val text = "文".repeat(50_000)
        val frames = 3_000
        val reveals = IntArray(frames) { i ->
            if (i < frames * 9 / 10) text.length * i / (frames * 9 / 10) else text.length
        }

        fun runTake(): Pair<Double, Int> {
            var acc = 0
            val t = System.nanoTime()
            for (r in reveals) acc += text.take(r.coerceAtLeast(0)).length
            return (System.nanoTime() - t) / 1_000_000.0 to acc
        }

        fun runFast(): Pair<Double, Int> {
            var acc = 0
            val t = System.nanoTime()
            for (r in reveals) {
                val revealed = r.coerceAtLeast(0)
                acc += if (revealed >= text.length) text.length else text.take(revealed).length
            }
            return (System.nanoTime() - t) / 1_000_000.0 to acc
        }

        runTake(); runFast()                    // 预热
        // **多轮取中位数**：单次测量受 JIT 分层、GC、调度影响，不能当结论。
        val rounds = 7
        val takes = DoubleArray(rounds)
        val fasts = DoubleArray(rounds)
        var accA = 0
        var accB = 0
        repeat(rounds) { r ->
            // 交替顺序，避免"谁先跑谁吃亏"
            if (r % 2 == 0) {
                val (t1, a1) = runTake(); val (t2, b1) = runFast()
                takes[r] = t1; fasts[r] = t2; accA = a1; accB = b1
            } else {
                val (t2, b2) = runFast(); val (t1, a2) = runTake()
                fasts[r] = t2; takes[r] = t1; accA = a2; accB = b2
            }
        }
        assertEquals("两种写法必须给出相同长度序列", accA, accB)
        sink = sink xor accA xor accB
        fun med(a: DoubleArray) = a.sorted()[a.size / 2]
        println(
            "A2 microbench: frames=" + frames + " chars=" + text.length + " rounds=" + rounds +
                " take_med=" + "%.1f".format(med(takes)) + "ms fast_med=" + "%.1f".format(med(fasts)) +
                "ms speedup_med=" + "%.2f".format(med(takes) / maxOf(med(fasts), 0.001)) + "x" +
                " take_minmax=" + "%.1f".format(takes.min()) + "/" + "%.1f".format(takes.max()) +
                " fast_minmax=" + "%.1f".format(fasts.min()) + "/" + "%.1f".format(fasts.max()),
        )
    }

    @Volatile private var sink = 0

    /** R39：`estimateTokens` 对一条 20k 字符消息的开销（原来跑在组合期主线程）。 */
    @Test fun `token estimate cost for a 20k message`() {
        val msg = StoredMessage(id = "m1", role = "assistant", text = "字".repeat(20_000))
        repeat(3) { estimateTokens(msg) } // 预热
        val runs = 20
        val t0 = System.nanoTime()
        var acc = 0
        repeat(runs) { acc += estimateTokens(msg) }
        val totalMs = (System.nanoTime() - t0) / 1_000_000.0
        println(
            "R39 microbench: chars=20000 runs=" + runs +
                " perCall=" + "%.2f".format(totalMs / runs) + "ms total=" +
                "%.1f".format(totalMs) + "ms tokens=" + (acc / runs),
        )
    }
}

