package com.suixing.agent.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderPresentationTest {
    @Test fun `picker labels omit credentials paths and query tokens`() {
        assertEquals("gateway.example", providerEndpointLabel("https://user:secret@gateway.example/private/v1?key=secret#token"))
        assertEquals("api.deepseek.com", providerEndpointLabel("https://api.deepseek.com/v1"))
    }

    @Test fun `LAN addresses retain the distinguishing port and IPv6 brackets`() {
        assertEquals("192.168.0.50:1234", providerEndpointLabel("http://192.168.0.50:1234/v1"))
        assertEquals("[::1]:8080", providerEndpointLabel("http://[::1]:8080/v1"))
    }

    @Test fun `unfinished input uses a neutral label rather than leaking raw text`() {
        assertEquals("自定义接口", providerEndpointLabel(""))
        assertEquals("自定义接口", providerEndpointLabel("https://invalid host/secret"))
        assertEquals("自定义接口", providerEndpointLabel("not-yet-an-address"))
    }
}
