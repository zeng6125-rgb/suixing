package com.suixing.agent.ui.chat

import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The conversation's tally, and the one number inside it that is not a sum.
 *
 * `contextTokens` is the *newest* row that carries usage, not the total: it answers 「上下文用了
 * 多少」 for the round that last ran, which is what the ring divides by the window. The rest of
 * the struct is the conversation's own cumulative spend, and the two must not be confused — that
 * is what this file pins down. It is also the memo's contract: the tally only moves when a row
 * lands, which is why the view model caches it on the row count and the last row's counts.
 */
class SessionStatsTest {

    private fun user(id: String) = StoredMessage(id = id, role = Roles.USER, text = "问 $id")

    private fun answer(
        id: String,
        prompt: Int,
        completion: Int,
        cached: Int = 0,
        elapsed: Long = 0,
        firstToken: Long = 0,
    ) =
        StoredMessage(
            id = id,
            role = Roles.ASSISTANT,
            text = "答 $id",
            promptTokens = prompt,
            completionTokens = completion,
            cachedTokens = cached,
            elapsedMs = elapsed,
            firstTokenMs = firstToken,
        )

    @Test
    fun `the newest usage row is the context reading, not the sum`() {
        val messages = listOf(
            user("u1"),
            answer("a1", prompt = 1_000, completion = 200),
            user("u2"),
            answer("a2", prompt = 5_000, completion = 300),
        )
        val stats = SessionStats.of(messages, contextWindow = 200_000)!!
        assertEquals("the ring's numerator is the last round's prompt", 5_000, stats.contextTokens)
        assertEquals("the tally is still the whole conversation", 6_500, stats.tokens)
        assertEquals(2, stats.turns)
    }

    /** A trailing round with no usage must not erase the reading from the one before it. */
    @Test
    fun `a row without usage does not hide the newest one that has it`() {
        val messages = listOf(
            answer("a1", prompt = 4_000, completion = 100),
            StoredMessage(id = "t1", role = Roles.TOOL, text = "结果"),
            user("u2"),
        )
        val stats = SessionStats.of(messages, contextWindow = 128_000)!!
        assertEquals(4_000, stats.contextTokens)
    }

    @Test
    fun `a conversation with nothing priced has no tally`() {
        assertNull(SessionStats.of(listOf(user("u1")), contextWindow = 200_000))
        assertNull(SessionStats.of(emptyList(), contextWindow = 200_000))
    }

    /** The hit rate is worked out in Long: the Int multiplication overflows past ~21.4M cached. */
    @Test
    fun `the cache ratio survives a large conversation`() {
        val messages = listOf(
            answer("a1", prompt = 40_000_000, completion = 1_000, cached = 30_000_000),
        )
        val stats = SessionStats.of(messages, contextWindow = 200_000)!!
        assertEquals(75.0, stats.cachePercent, 0.0001)
    }

    @Test
    fun `the speed is the output over the time actually spent`() {
        val messages = listOf(
            answer("a1", prompt = 1_000, completion = 500, elapsed = 2_000),
            answer("a2", prompt = 1_000, completion = 500, elapsed = 2_000),
        )
        val stats = SessionStats.of(messages, contextWindow = 200_000)!!
        assertEquals("1000 tokens over 4s", 250.0, stats.perSecond, 0.001)
    }

    @Test
    fun `the speed excludes the wait before the first token`() {
        // 官方口径：分母是 decodeMs（首字之后→结束），不含 TTFT 等待。两轮各 elapsed=2000、
        // 首字在 500 → 每轮 decode=1500、Σ=3000；1000 tok / 3s = 333.3，而非按墙钟的 250。
        val messages = listOf(
            answer("a1", prompt = 1_000, completion = 500, elapsed = 2_000, firstToken = 500),
            answer("a2", prompt = 1_000, completion = 500, elapsed = 2_000, firstToken = 500),
        )
        val stats = SessionStats.of(messages, contextWindow = 200_000)!!
        assertEquals(333.33, stats.perSecond, 0.01)
    }

    @Test
    fun `tool messages do not pollute the speed denominator`() {
        // TOOL 消息 elapsedMs=0，不进 decodeMs 的和；一轮 assistant（decode=1500）配一条工具，
        // 速率仍只按那 1500ms 算。
        val messages = listOf(
            answer("a1", prompt = 1_000, completion = 500, elapsed = 2_000, firstToken = 500),
            StoredMessage(id = "t1", role = Roles.TOOL, text = "结果"),
        )
        val stats = SessionStats.of(messages, contextWindow = 200_000)!!
        assertEquals(333.33, stats.perSecond, 0.01)
    }

    @Test
    fun `the window is carried through as given`() {
        val stats = SessionStats.of(listOf(answer("a1", 10, 10)), contextWindow = 64_000)!!
        assertEquals(64_000, stats.contextWindow)
    }
}
