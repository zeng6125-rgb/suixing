package com.suixing.agent.llm

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class EndpointSecurityTest {
    @Test fun privateLookingDomainsNeverGrantTlsExceptions() {
        listOf("10.attacker.com", "127.evil.org", "192.168.evil.org", "10.999.0.1", "172.16.1", "").forEach {
            assertFalse(it, isPrivateEndpointHost(it))
        }
        listOf("10.1.2.3", "192.168.1.2", "172.31.1.2", "localhost", "proxy.local", "::1", "fd00::1").forEach {
            assertTrue(it, isPrivateEndpointHost(it))
        }
    }

    @Test fun publicCleartextCredentialIsBlockedBeforeTransmission() {
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns Request.Builder().url("http://example.com/v1")
            .header("x-api-key", "private").build()
        try { PublicCredentialGuard().intercept(chain); fail("Must block credential transmission") }
        catch (expected: IOException) { assertFalse(expected.message.orEmpty().contains("private")) }
        verify(exactly = 0) { chain.proceed(any()) }
    }

    @Test fun localCredentialAndPublicHttpsRemainUsable() {
        for (url in listOf("http://192.168.1.2:8080/v1", "https://example.com/v1")) {
            val request = Request.Builder().url(url).header("Authorization", "Bearer token").build()
            val response = mockk<Response>()
            val chain = mockk<Interceptor.Chain>()
            every { chain.request() } returns request
            every { chain.proceed(request) } returns response
            assertSame(response, PublicCredentialGuard().intercept(chain))
        }
    }
}
