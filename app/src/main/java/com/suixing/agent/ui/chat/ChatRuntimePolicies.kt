package com.suixing.agent.ui.chat

import kotlinx.coroutines.CancellationException

/** [runCatching] for suspend/flow boundaries: cancellation is never a recoverable failure. */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }

/** How many compressions of one conversation are kept; older ones are dropped. */
internal const val MAX_CHECKPOINTS = 10

/**
 * How often the answer being streamed is written to the conversation's draft file.
 *
 * A second, not every delta: each write is an encode plus a file write, and a long answer
 * arrives in thousands of deltas. It is the interval where a kill costs the last tick of the
 * answer rather than all of it.
 */
/**
 * 活跃输出的恢复副本多久落一次盘（R4）。
 *
 * 原文固定 1s：流式时每秒都要**组装整段正文 + 编码 + 写盘 + rename**，长回答下这笔开销
 * 与输出速度成正比地重复。这里按“上次落盘之后又长了多少字”分档：
 *  · 增量小（< 2k 字）：仍 1s —— 被杀最多丢 1s，代价最小；
 *  · 增量中（2k~8k）：2s —— 多丢 1s，换掉一半的组装/编码；
 *  · 增量大（≥ 8k）：3s —— 长回答每秒改变几千字，再密的副本也只是重复搬运同样的量。
 * 纯函数、有单测（LiveDraftIntervalTest）；要改手感只动这三个阈值。
 */
internal fun liveDraftIntervalMs(pendingChars: Int): Long = when {
    pendingChars >= 8_000 -> LIVE_DRAFT_INTERVAL_MS * 3
    pendingChars >= 2_000 -> LIVE_DRAFT_INTERVAL_MS * 2
    else -> LIVE_DRAFT_INTERVAL_MS
}

internal const val LIVE_DRAFT_INTERVAL_MS = 1000L

/**
 * How often buffered deltas are published to the screen.
 *
 * Under the frame time a reader can notice, and far under the rate a fast endpoint sends at:
 * the state stops changing hundreds of times a second, and with it the Markdown re-parse that
 * every one of those changes forced (the render cache is keyed by the text, so a text that
 * changes every frame is a miss every frame).
 *
 * Eighty rather than fifty is an inference, not a measurement: the user still saw ghosting
 * after the fifty-millisecond version, and the cost that scales with a fast endpoint is the
 * re-parse, so halving the publish rate halves it. Twelve and a half a second is still
 * smoother than reading speed. The measurement-backed fix — reusing the previous parse for
 * the unchanged prefix instead of re-parsing the whole document — needs `MarkdownText` to
 * report where each block starts, which it does not do yet.
 */
/**
 * 多久把攒着的 delta 发布一次。
 *
 * 0.8.35 从 50ms 提到 80ms 是为了少做 Markdown 重解析（代价随端点速度线性增长）。
 * 但 80ms 的攒批让**一次到达**变成几百字，打字机再快也只能"一块一块蹦"——
 * 用户连续反馈「瞬移」「不要一次到达」。
 *
 * 现在取 40ms（与打字机节拍同频，25 次/秒）：一次到达的量降到 1/2，
 * 每拍放的字数也随之变小，逐字感才回来。重解析次数翻倍仍在可接受范围
 * （0.8.382 实测：光标 480ms 步进后单核 42% → 这一项不是发热主因）。
 */
// 发布节流：**16ms ≈ 每帧一次**（用户：流式"一小段一小段出"、感觉不实时）。
// 原来 40ms ⇒ 每秒最多 25 次，配上网关按几十毫秒批量送 token，屏幕上一跳一跳地长。
// 正文与思考现在直接渲染 `part.text`（不做打字机），节流就是唯一的颗粒度来源，
// 所以收到就发。60 次/秒的 state 更新仍在预算内（一次发布只拼一小段字符串）。
internal const val DELTA_PUBLISH_MS = 16L

/**
 * How long to wait before trying a refused 排队 turn again.
 *
 * Only used for the refusal that has no other way back: the send was refused although nothing is
 * running, which means the engine is between publishing a run's terminal state and letting go of the
 * run. Long enough for that to finish, short enough that the user does not see a gap.
 */
internal const val RETRY_TURN_MS = 300L

/**
 * 重试行写在状态行上的开头。
 *
 * 写和清是同一件事的两端：`AgentEvent.Retrying` 把「连接失败，N 秒后重试（第 K 次）· 原因」
 * 放进状态行，内容一到就由 [clearRetryStatus] 抹掉 —— 重试已经成功了，这一行再挂着，读起来
 * 就像这一轮仍在出错（用户：「整轮对话是成功的话 就不要有 错误提示卡片存在」）。
 */
internal const val RETRY_STATUS_PREFIX = "连接失败"

/** 见 [RETRY_STATUS_PREFIX]：重试成功、内容已经到达，那一行就该让位给真正的回答。 */
internal fun RunSession.clearRetryStatus(): RunSession =
    if (status.startsWith(RETRY_STATUS_PREFIX)) copy(status = "") else this

/**
 * Where the retry diagnostic goes, and how it is kept from growing without bound.
 *
 * `files/retry-trace.log`, next to `fx-trace.log`: a user reporting 「自动重试真的重试了吗」 can be
 * answered from the file instead of from a claim. Rotated by truncating to the newest
 * [RETRY_TRACE_KEEP_CHARS] once it passes [RETRY_TRACE_MAX_BYTES] — retries are rare, so this is
 * history rather than a ring buffer.
 */
internal const val RETRY_TRACE_FILE = "retry-trace.log"
internal const val RETRY_TRACE_MAX_BYTES = 128 * 1024L
internal const val RETRY_TRACE_KEEP_CHARS = 64 * 1024
