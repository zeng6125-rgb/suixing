package com.suixing.agent.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 局域网地址判定（Android 17 的 ACCESS_LOCAL_NETWORK 只对这类目标有意义）。
 *
 * 这些是纯字符串判定：不做 DNS、不碰 Context，所以普通 JVM 单测就能跑。
 */
class LocalNetworkAccessTest {

    @Test
    fun `private and loopback targets are local`() {
        listOf(
            "http://192.168.1.5:8080/v1",
            "http://10.0.0.2",
            "http://172.16.3.4:1234/v1/models",
            "http://127.0.0.1:8000",
            "http://localhost:11434/v1",
            "http://myserver:8080",
            "http://nas.local/models",
            "http://169.254.10.20",
        ).forEach { assertTrue(it, LocalNetworkAccess.isLocalTarget(it)) }
    }

    @Test
    fun `public targets never need the local network permission`() {
        listOf(
            "https://api.deepseek.com/v1",
            "https://api.openai.com/v1/models",
            "https://8.8.8.8/",
            "https://example.com",
            "http://172.32.0.1",
            "http://192.169.0.1",
        ).forEach { assertFalse(it, LocalNetworkAccess.isLocalTarget(it)) }
    }

    @Test
    fun `ipv6 loopback link local and unique local are local`() {
        listOf("http://[::1]:8080", "http://[fe80::1]", "http://[fd00::1234]", "http://[::ffff:192.168.1.5]")
            .forEach { assertTrue(it, LocalNetworkAccess.isLocalTarget(it)) }
        assertFalse(LocalNetworkAccess.isLocalTarget("https://[2001:4860:4860::8888]"))
    }

    @Test
    fun `host is taken from urls with and without scheme`() {
        assertTrue(LocalNetworkAccess.hostOf("http://192.168.1.5:8080/v1") == "192.168.1.5")
        assertTrue(LocalNetworkAccess.hostOf("192.168.1.5:8080/v1") == "192.168.1.5")
        assertTrue(LocalNetworkAccess.hostOf("https://user:pw@example.com/x") == "example.com")
        assertTrue(LocalNetworkAccess.hostOf("") == "")
    }
}
