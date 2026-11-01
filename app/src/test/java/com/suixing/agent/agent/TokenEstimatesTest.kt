package com.suixing.agent.agent

import com.suixing.agent.data.StoredMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * issue #71 A3 的判据（可复现，不靠感觉）：同一批消息第二次组请求时**不再重算**。
 */
class TokenEstimatesTest {

    @After fun tearDown() = TokenEstimates.clear()

    @Test
    fun `the same message is estimated once no matter how often it is trimmed`() {
        var calls = 0
        val estimator: (StoredMessage) -> Int = { calls += 1; 100 }
        val message = StoredMessage(id = "a", role = "assistant", text = "答".repeat(30_000))
        repeat(30) { assertEquals(100, TokenEstimates.of(message, estimator)) }
        assertEquals("30 轮组请求只该算一次", 1, calls)
    }

    @Test
    fun `different messages are estimated separately`() {
        var calls = 0
        val estimator: (StoredMessage) -> Int = { calls += 1; 7 }
        TokenEstimates.of(StoredMessage(id = "a", role = "assistant"), estimator)
        TokenEstimates.of(StoredMessage(id = "b", role = "assistant"), estimator)
        assertEquals(2, calls)
    }

    @Test
    fun `the cache is bounded`() {
        repeat(TokenEstimates.MAX_ENTRIES + 40) { i ->
            TokenEstimates.of(StoredMessage(id = "m$i", role = "assistant")) { 1 }
        }
        assertEquals(TokenEstimates.MAX_ENTRIES, TokenEstimates.size())
    }
}
