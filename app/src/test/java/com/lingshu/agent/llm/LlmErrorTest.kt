package com.lingshu.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException

class LlmErrorTest {

    @Test
    fun `reads ids out of an openai model list`() {
        val body = """{"object":"list","data":[
            {"id":"deepseek-chat","object":"model"},
            {"id":"deepseek-reasoner","object":"model"}]}"""
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), parseModels(body))
    }

    @Test
    fun `accepts a bare array and a models key`() {
        assertEquals(listOf("a", "b"), parseModels("""["a","b"]"""))
        assertEquals(listOf("x"), parseModels("""{"models":[{"name":"x"}]}"""))
    }

    @Test
    fun `drops duplicates and null ids`() {
        assertEquals(listOf("a"), parseModels("""{"data":[{"id":"a"},{"id":"a"},{"id":null}]}"""))
    }

    @Test(expected = LlmException::class)
    fun `rejects a response with no list at all`() {
        parseModels("""{"object":"list"}""")
    }

    @Test(expected = LlmException::class)
    fun `rejects a non json body`() {
        parseModels("<html>502 Bad Gateway</html>")
    }

    @Test
    fun `pulls the message out of an error envelope`() {
        assertEquals(
            "Incorrect API key provided",
            extractApiError("""{"error":{"message":"Incorrect API key provided","type":"invalid_request_error"}}"""),
        )
        assertEquals("rate limited", extractApiError("""{"message":"rate limited"}"""))
        assertEquals("upstream down", extractApiError("""{"error":"upstream down"}"""))
    }

    @Test
    fun `falls back to raw text when the body is not json`() {
        assertTrue(extractApiError("<html>\n  502 Bad Gateway  </html>").contains("502"))
    }

    @Test
    fun `http statuses carry advice instead of a bare number`() {
        assertTrue(friendlyHttp(401, "").contains("API Key"))
        assertTrue(friendlyHttp(404, "").contains("/v1"))
        assertTrue(friendlyHttp(429, """{"error":{"message":"quota exceeded"}}""").contains("quota exceeded"))
        assertTrue(friendlyHttp(503, "").contains("服务器"))
    }

    @Test
    fun `an untrusted certificate points at the switch`() {
        val cause = CertPathValidatorException("Trust anchor for certification path not found.")
        val error = SSLHandshakeException("Chain validation failed").apply { initCause(cause) }
        val text = describeError(error)
        assertTrue(text, text.contains("自签名"))
    }

    @Test
    fun `an unknown host is explained`() {
        assertTrue(describeError(UnknownHostException("api.example.invalid")).contains("域名"))
    }

    @Test
    fun `our own llm messages pass through untouched`() {
        assertEquals(
            "API Key 不对或已失效（HTTP 401）",
            describeError(LlmException("API Key 不对或已失效（HTTP 401）")),
        )
    }
}
