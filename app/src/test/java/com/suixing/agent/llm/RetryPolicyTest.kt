package com.suixing.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression net for the retry rule: [shouldRetry] and [parseRetryAfter].
 *
 * The rule is the whole feature — retrying the wrong failure is worse than not retrying at all,
 * because it either repeats an error the user has to read twice or resends a request the endpoint
 * already answered (and billed for). Both functions are pure, so the rule is pinned here instead of
 * against a live server.
 */
class RetryPolicyTest {

    /** The common shape: nothing received, not cancelled, one attempt already spent. */
    private fun retry(
        status: Int?,
        attempt: Int = 1,
        maxAttempts: Int = 2,
        receivedAnyOutput: Boolean = false,
        cancelled: Boolean = false,
    ): Boolean = shouldRetry(status, attempt, maxAttempts, receivedAnyOutput, cancelled)

    // ------------------------------------------------------------------ 可恢复

    @Test
    fun `a rate limit is worth another try`() {
        // 429 是「现在不行」，不是「这个请求不行」。
        assertTrue(retry(status = 429))
    }

    @Test
    fun `a server fault is worth another try`() {
        assertTrue(retry(status = 500))
        assertTrue(retry(status = 502))
        assertTrue(retry(status = 503))
        assertTrue(retry(status = 504))
        assertTrue(retry(status = 599))
    }

    @Test
    fun `a transport failure has no status and is worth another try`() {
        // 连接被 reset / DNS 失败 / 超时：都到不了「服务器答复」这一步，所以没有状态码。
        assertTrue(retry(status = null))
    }

    // ------------------------------------------------------------------ 不可恢复

    @Test
    fun `a bad request is not retried`() {
        // 400 说明请求本身就是错的，重发只会得到同一个 400。
        assertFalse(retry(status = 400))
    }

    @Test
    fun `an auth failure is not retried`() {
        // 401/403 是 key 的问题，重试不会让 key 变对。
        assertFalse(retry(status = 401))
        assertFalse(retry(status = 403))
    }

    @Test
    fun `other client errors are not retried`() {
        assertFalse(retry(status = 404))
        assertFalse(retry(status = 413))
        assertFalse(retry(status = 422))
    }

    @Test
    fun `a status just below the server range is not retried`() {
        // 边界：4xx 的末尾不是 5xx。
        assertFalse(retry(status = 499))
    }

    // ------------------------------------------------------------------ 三条护栏

    @Test
    fun `an attempt that already delivered text is never retried`() {
        // 最重要的一条：已经收到正文/思考再重发，用户会看到重复内容，而且那次请求已经计过费。
        assertFalse(retry(status = null, receivedAnyOutput = true))
        assertFalse(retry(status = 500, receivedAnyOutput = true))
        assertFalse(retry(status = 429, receivedAnyOutput = true))
    }

    @Test
    fun `a cancelled attempt is never retried`() {
        // 用户点了停止：那是命令，不是失败。
        assertFalse(retry(status = null, cancelled = true))
        assertFalse(retry(status = 500, cancelled = true))
        assertFalse(retry(status = 429, cancelled = true))
    }

    @Test
    fun `the retry budget is counted in retries, not in attempts`() {
        // maxAttempts = 失败后最多再试几次：第 1、2 次失败都还有额度，第 3 次就没有了。
        assertTrue(retry(status = 500, attempt = 1, maxAttempts = 2))
        assertTrue(retry(status = 500, attempt = 2, maxAttempts = 2))
        assertFalse(retry(status = 500, attempt = 3, maxAttempts = 2))
    }

    @Test
    fun `zero retries means the first failure is final`() {
        assertFalse(retry(status = 500, attempt = 1, maxAttempts = 0))
        assertFalse(retry(status = null, attempt = 1, maxAttempts = 0))
    }

    @Test
    fun `the guards are checked before the status`() {
        // 取消 + 已收到输出 同时成立时也不能重试（两者各自都足以否决）。
        assertFalse(retry(status = 500, receivedAnyOutput = true, cancelled = true))
    }

    // ------------------------------------------------------------------ 中途断流

    /**
     * 例外的那个口子：内容已经收到、连接断了（局域网重启、上游抖动、网关掐流）。
     *
     * 调用方会先把已经流出去的那一轮撤掉（`LlmClient` 的 `onRestart`），所以这里允许重试 ——
     * 否则用户看到的就是「停在中途那个节点，再也不动」。撤回这件事做不到时（`midStream=false`），
     * 规则仍然是"不重试"：同一段开头打印两遍比停住更糟。
     */
    @Test
    fun `a stream that breaks after output is retried only when the caller can discard it`() {
        assertTrue(
            shouldRetry(
                status = null,
                attempt = 1,
                maxAttempts = 2,
                receivedAnyOutput = true,
                cancelled = false,
                midStream = true,
            ),
        )
        assertFalse(
            shouldRetry(
                status = null,
                attempt = 1,
                maxAttempts = 2,
                receivedAnyOutput = true,
                cancelled = false,
            ),
        )
        // 预算用尽 / 用户停手，照旧不重试。
        assertFalse(
            shouldRetry(
                status = null,
                attempt = 3,
                maxAttempts = 2,
                receivedAnyOutput = true,
                cancelled = false,
                midStream = true,
            ),
        )
        assertFalse(
            shouldRetry(
                status = null,
                attempt = 1,
                maxAttempts = 2,
                receivedAnyOutput = true,
                cancelled = true,
                midStream = true,
            ),
        )
    }

    // ------------------------------------------------------------------ Retry-After

    @Test
    fun `retry-after is read as seconds`() {
        assertEquals(7, parseRetryAfter("7"))
        assertEquals(30, parseRetryAfter(" 30 "))
    }

    @Test
    fun `retry-after is clamped to something a person will wait`() {
        // 服务端要一小时：照做看起来就像卡死，所以夹到上限。
        assertEquals(MAX_RETRY_DELAY_SECONDS, parseRetryAfter("3600"))
        assertEquals(MIN_RETRY_DELAY_SECONDS, parseRetryAfter("0"))
    }

    @Test
    fun `an unreadable retry-after falls back to the setting`() {
        // HTTP-date 形式与乱码都返回 null，调用方就用设置里的秒数。
        assertNull(parseRetryAfter(null))
        assertNull(parseRetryAfter(""))
        assertNull(parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(parseRetryAfter("soon"))
        assertNull(parseRetryAfter("-5"))
    }
}
