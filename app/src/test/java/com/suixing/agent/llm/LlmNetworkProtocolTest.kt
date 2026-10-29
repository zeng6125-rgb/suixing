package com.suixing.agent.llm

import com.suixing.agent.data.AppSettings
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
}
