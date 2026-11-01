package com.suixing.agent.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fail-closed TLS compatibility guard (#58). */
class LanHostTest {
    @Test fun `empty or malformed host never grants relaxed certificate checks`() {
        assertFalse(LlmClient.isLanHost(""))
        assertFalse(LlmClient.isLanHost("   "))
        assertFalse(LlmClient.isLanHost("example.com"))
        assertFalse(LlmClient.isLanHost("8.8.8.8"))
    }

    @Test fun `explicit loopback and private addresses remain supported`() {
        assertTrue(LlmClient.isLanHost("localhost"))
        assertTrue(LlmClient.isLanHost("127.0.0.1"))
        assertTrue(LlmClient.isLanHost("192.168.1.2"))
        assertTrue(LlmClient.isLanHost("10.0.0.1"))
        assertTrue(LlmClient.isLanHost("172.16.3.4"))
    }
}
