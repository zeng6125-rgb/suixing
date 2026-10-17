package com.suixing.agent.data

import kotlin.math.roundToLong

/**
 * 输出速度（TPS）—— **DeepSeek 官方口径**，一手核对自本机安装的
 * `@deepseek-ai/dsh-client-ui-chat/lib/client.js`（2026-10-13 只读核对）：
 *
 * ```
 * ttftMs   = firstTokenTime - stepStartTime      // 首个 token（任意块，含推理块）
 * decodeMs = completedTime  - firstTokenTime     // 首 token 之后 → 结束（不含 TTFT 那段等待）
 * outputTokens = usage.outputTokens              // 该步全部输出 token（含推理，官方不减）
 * tps      = decodeTokens / (decodeMs / 1e3)     // 门禁 decodeMs > 0
 * format   = tps >= 10 ? round(tps) : round(tps*10)/10
 * ```
 *
 * 三条必须记住的官方语义（与 DSH 桌面端一致，别再"好心纠正"）：
 * 1. **分子是全部输出 token（含推理 token）**：随行的 `completionTokens` 本就把 reasoning 算在
 *    里面（见 [com.suixing.agent.llm.Usage]），官方不减它。
 * 2. **分母是「首 token 之后 → 结束」**（decodeMs），**不含**首字之前那段 TTFT 等待 —— 这正是
 *    随行旧代码算错的地方：它拿整轮墙钟 `elapsedMs` 当分母，把 TTFT 也算进去，系统性低估 TPS。
 * 3. **多轮聚合是 ΣdecodeTokens / ΣdecodeMs**（官方 client.js:8331-8333），不是各轮速率再平均。
 *
 * `firstTokenMs == 0` 的处理：官方 client.js:8273 在 firstTokenTime 缺失时**退回整轮墙钟**
 * （completedTime - stepStartTime）。随行沿用这条 fallback —— 老转录（firstTokenMs 未记录）与
 * 极快响应都落到这里，宁可给一个略低的数，也不整项变「—」。
 */
object Tps {

    /**
     * 生成阶段时长（decodeMs）：整轮墙钟减掉首 token 之前的等待。
     * [firstTokenMs] 未知（=0）时退回 [elapsedMs]（官方 fallback）；结果 clamp 到 ≥0。
     */
    fun decodeMs(elapsedMs: Long, firstTokenMs: Long): Long =
        (if (firstTokenMs > 0) elapsedMs - firstTokenMs else elapsedMs).coerceAtLeast(0L)

    /**
     * 官方 TPS 数值。[outputTokens] 含推理 token；[decodeMs] 是生成阶段时长。
     * 不可测（decodeMs ≤ 0 或没有输出）返回 0.0，调用方据此决定省略还是显示「—」。
     */
    fun of(outputTokens: Int, decodeMs: Long): Double =
        if (decodeMs <= 0L || outputTokens <= 0) 0.0 else outputTokens * 1000.0 / decodeMs

    /** 便捷入口：从整轮墙钟与首字时刻直接算 TPS（内部先求 decodeMs）。 */
    fun of(outputTokens: Int, elapsedMs: Long, firstTokenMs: Long): Double =
        of(outputTokens, decodeMs(elapsedMs, firstTokenMs))

    /**
     * 官方 `formatTokensPerSecond`：clamp 负值/非有限值到 0，再 **≥10 取整、<10 一位小数**。
     * 两个分支两套精度（不是统一的 toFixed）——大数不要小数位，小数才要。
     * 边界怪癖照抄官方：<10 分支先乘 10 再四舍五入，故 9.96 → "10"。
     */
    fun format(tps: Double): String {
        val clamped = if (tps.isFinite() && tps > 0.0) tps else 0.0
        return if (clamped >= 10.0) {
            clamped.roundToLong().toString()
        } else {
            val oneDecimal = (clamped * 10).roundToLong() / 10.0
            // 官方 String(73/10)="7.3"、String(100/10)="10"：整数不带 ".0"。
            if (oneDecimal == oneDecimal.toLong().toDouble()) oneDecimal.toLong().toString()
            else oneDecimal.toString()
        }
    }

    /** 带单位的一格显示（`274 tok/s`）；不可测返回 null，由调用方决定省略还是「—」。 */
    fun label(outputTokens: Int, elapsedMs: Long, firstTokenMs: Long): String? {
        val tps = of(outputTokens, elapsedMs, firstTokenMs)
        return if (tps <= 0.0) null else "${format(tps)} tok/s"
    }
}
