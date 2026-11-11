package com.suixing.agent.llm

import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.PROTOCOL_RESPONSES
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class LlmNetworkProtocolTest {
    private fun rejected(body: String) = runBlocking {
        val attempts = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            attempts.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        try {
            val failure = runCatching {
                LlmClient(http).complete(AppSettings(baseUrl = "https://example.test/v1", model = "test",
                    retryOnFailure = true, retryMaxAttempts = 3, retryDelaySeconds = 1), emptyList(), emptyList())
            }.exceptionOrNull()
            assertTrue("expected explicit protocol failure, got $failure", failure is LlmException)
            assertEquals("protocol errors must not be billed again", 1, attempts.get())
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test fun explicitEndpointErrorIsNotRetried() = rejected("data: {\"error\":{\"message\":\"quota exceeded\"}}\n\n")
    @Test fun incompatibleSseIsNotRetried() = rejected("data: not-json\n\n")

    @Test fun http400IncludesModelProtocolAndUpstreamParameterWithoutRetry(): Unit = runBlocking {
        val attempts = AtomicInteger()
        val settings = AppSettings(baseUrl = "https://private-endpoint.test/v1", apiKey = "private-fixture-key",
            model = "diagnostic-model", protocol = PROTOCOL_RESPONSES,
            retryOnFailure = true, retryMaxAttempts = 3, retryDelaySeconds = 1)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            attempts.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(400).message("Bad Request")
                .body("""{"error":{"message":"Invalid request parameters","param":"reasoning_effort","code":"unsupported_parameter"}}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            val failure = runCatching { LlmClient(http).complete(settings, emptyList(), emptyList()) }.exceptionOrNull()
            assertTrue("expected HTTP failure, got $failure", failure is LlmException)
            assertEquals("a rejected request must not be sent unchanged again", 1, attempts.get())
            val text = failure!!.message.orEmpty()
            assertTrue(text, text.contains("HTTP 400"))
            assertTrue(text, text.contains("Invalid request parameters"))
            assertTrue(text, text.contains(settings.model))
            assertTrue(text, text.contains(settings.protocol))
            assertTrue(text, text.contains("reasoning_effort"))
            assertTrue(text, text.contains("unsupported_parameter"))
            assertFalse(text, text.contains(settings.apiKey))
            assertFalse(text, text.contains(settings.baseUrl))
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}
