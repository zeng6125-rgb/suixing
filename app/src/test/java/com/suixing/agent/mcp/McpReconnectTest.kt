package com.suixing.agent.mcp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The auto-reconnect schedule is the part of the retry loop worth pinning down on
 * its own: 2s doubling to a 30s cap, and a non-positive attempt meaning "no wait".
 * [backoffDelayMs] is pure, so this needs neither a device nor a live server.
 */
class McpReconnectTest {

    @Test
    fun `backoff doubles from two seconds`() {
        assertEquals(2_000L, backoffDelayMs(1))
        assertEquals(4_000L, backoffDelayMs(2))
        assertEquals(8_000L, backoffDelayMs(3))
        assertEquals(16_000L, backoffDelayMs(4))
    }

    @Test
    fun `backoff is capped at thirty seconds`() {
        // 5th retry would be 32s uncapped; it must clamp to the 30s ceiling.
        assertEquals(30_000L, backoffDelayMs(5))
        assertEquals(30_000L, backoffDelayMs(6))
        assertEquals(30_000L, backoffDelayMs(100))
    }

    @Test
    fun `a non-positive attempt waits zero`() {
        assertEquals(0L, backoffDelayMs(0))
        assertEquals(0L, backoffDelayMs(-1))
    }

    @Test
    fun `the retry budget is five attempts`() {
        // Guards the constant the statusLine text and the FAILED transition rely on.
        assertEquals(5, MCP_MAX_RECONNECT_ATTEMPTS)
    }
}
