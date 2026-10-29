package com.suixing.agent.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class StreamBoundsTest {
    @Test fun usageWithOnlyCacheHitCountIsRetained() {
        assertEquals(32, parseUsage(Json.parseToJsonElement("{\"prompt_cache_hit_tokens\":32}").jsonObject)?.cachedTokens)
    }

    @Test fun aggregateTurnSizeIsBoundedAcrossSmallFrames() {
        val stream = LlmClient.StreamTurn()
        val chunk = Json.parseToJsonElement("{\"choices\":[{\"delta\":{\"content\":\"${"x".repeat(64 * 1024)}\"}}]}").jsonObject
        val failure = runCatching { repeat(300) { stream.accept(chunk, {}, {}) } }.exceptionOrNull()
        assertTrue("oversized turn should fail explicitly: $failure", failure is LlmException)
    }
}
