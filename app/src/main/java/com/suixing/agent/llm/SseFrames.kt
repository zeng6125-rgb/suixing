package com.suixing.agent.llm

import okio.BufferedSource

// A UTF-16 code unit needs at most three UTF-8 bytes. Allow the data: prefix,
// then enforce the stricter character bound after decoding. No line can cause
// unbounded buffering before that check, even if a gateway never sends a newline.
internal const val MAX_SSE_LINE_BYTES = 3L * 1_048_576L + 16L

internal fun readSseLine(source: BufferedSource): String? {
    val newline = source.indexOf('\n'.code.toByte(), 0L, MAX_SSE_LINE_BYTES + 1L)
    if (newline >= 0L) return source.readUtf8LineStrict(MAX_SSE_LINE_BYTES)
    if (source.buffer.size > MAX_SSE_LINE_BYTES) {
        throw LlmException("流式响应无法解析：单行 SSE 数据过大，端点格式可能不兼容")
    }
    // A final event need not end in LF. indexOf has reached EOF here; preserve
    // readUtf8Line's final-line behavior instead of treating ordinary EOF as overflow.
    return if (source.buffer.size == 0L) null else source.readUtf8(source.buffer.size)
}

/** Bound malformed or oversized events before adding them to the pending buffer. */
internal fun checkSseFrameSize(pendingChars: Int, nextChars: Int) {
    if (pendingChars.toLong() + nextChars + (if (pendingChars > 0) 1 else 0) > 1_048_576L) {
        throw LlmException("流式响应无法解析：单个 SSE 事件超过 1 MiB 字符上限，端点格式可能不兼容")
    }
}

/**
 * SSE 帧的**括号配平** —— 判断「一个完整的 JSON 刚结束」。
 *
 * 为什么需要：网关有时把一个 JSON 拆成多行 `data:` 送达。原来的做法是每收到一行就把
 * 整个 pending 重新 parse 一遍试探完整性 —— 那是 O(行数2) 的字符解析
 * （一个 30KB 的 chunk 约等于 600 行 ⇒ 十几万字符被反复解析）。
 *
 * 括号配平是同一判定的廉价替代：一个 JSON 完整当且仅当括号净深度归零且不在字符串里。
 * 每行只扫本行，增量维护深度。配平通过后**仍会真正 parse 一次**（调用方的 `acceptPayload`），
 * 所以这不是「跳过校验」，只是把"试探"从每行一次降到完整时一次。
 */
internal object SseFrames {

    /** 配平时的跨行状态（一个 pending 缓冲区一个）。 */
    internal class State {
        /** 括号净深度（不含字符串内的）。 */
        var depth: Int = 0
        /** 是否在字符串字面量里。 */
        var inString: Boolean = false
        /** 前一个字符是否是转义符 `\`。 */
        var escaped: Boolean = false
        /**
         * 上一个事件已经闭合、正在等下一个。
         *
         * 没有这个标记就分不出「depth==0 是因为还没开始」和「depth==0 是因为刚闭合完」——
         * 后者会让后续每一行都被当成一个新事件（一次 SSE 连接被切成几十个空事件）。
         */
        var closed: Boolean = false

        /** 一个 JSON 完整了：括号归零且不在字符串里。 */
        fun isComplete(): Boolean = depth == 0 && !inString

        /**
         * 丢掉当前事件的全部配平状态。
         *
         * 缓冲区被**强制**清掉时必须一起调（空行 flush、流尾 flush）：否则残留的
         * `depth` 会算进下一个事件，那个事件就永远配不平、被当作"没解析"丢掉。
         */
        fun reset() {
            depth = 0
            inString = false
            escaped = false
            closed = false
        }
    }

    /**
     * 吃掉一段文本，返回**这一刻是否刚刚闭合了一个 JSON**。
     *
     * 只有「从未闭合/未闭合 → 闭合」那一次返回 true：
     *  - 第一个事件（之前什么都没收到）算；
     *  - 上一个事件闭合后又来内容 ⇒ 新的 `{`/`[` 会清零 [State.closed]，于是它能再算一次；
     *  - 上一个事件闭合后的空行/空白 ⇒ [State.closed] 仍为 true，不算。
     */
    fun feed(state: State, text: String): Boolean {
        for (ch in text) {
            when {
                state.escaped -> state.escaped = false
                ch == '\\' && state.inString -> state.escaped = true
                ch == '"' -> state.inString = !state.inString
                !state.inString && (ch == '{' || ch == '[') -> {
                    state.depth++
                    state.closed = false      // 新事件开始了
                }
                !state.inString && (ch == '}' || ch == ']') -> state.depth--
            }
        }
        if (!state.isComplete()) return false
        if (state.closed) return false        // 上一个事件闭合后一直没来新内容
        state.closed = true
        return true
    }
}
