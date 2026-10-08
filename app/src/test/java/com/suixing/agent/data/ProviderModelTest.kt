package com.suixing.agent.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderModelTest {

    @Test
    fun `prefix becomes the group`() {
        assertEquals("deepseek", modelGroupOf("deepseek/deepseek-chat"))
    }

    @Test
    fun `bare id lands in the fallback group`() {
        assertEquals("其他", modelGroupOf("gpt-4o"))
    }

    @Test
    fun `blank id lands in the fallback group`() {
        assertEquals("其他", modelGroupOf(""))
    }

    @Test
    fun `groups keep the order ids arrived in`() {
        val groups = groupModels(listOf("b/2", "a/1", "b/1", "solo"))
        assertEquals(listOf("b", "a", "其他"), groups.map { it.first })
        assertEquals(listOf("b/2", "b/1"), groups[0].second)
        assertEquals(listOf("a/1"), groups[1].second)
        assertEquals(listOf("solo"), groups[2].second)
    }

    @Test
    fun `short label falls back to the host`() {
        val provider = ProviderConfig(id = "p", baseUrl = "https://192.168.0.50:8914/v1")
        assertEquals("192.168.0.50:8914", provider.shortLabel)
    }

    @Test
    fun `short label prefers an explicit name`() {
        val provider = ProviderConfig(id = "p", name = "本地反代", baseUrl = "https://x/v1")
        assertEquals("本地反代", provider.shortLabel)
    }

    @Test
    fun `enabledModels pairs each ticked id with its provider`() {
        val settings = AppSettings(
            providers = listOf(
                ProviderConfig(id = "a", baseUrl = "https://a/v1", enabled = listOf("m1")),
                ProviderConfig(id = "b", baseUrl = "https://b/v1", enabled = listOf("m2", "m3")),
            ),
        )
        assertEquals(listOf("m1", "m2", "m3"), settings.enabledModels().map { it.second })
        assertEquals(listOf("a", "b", "b"), settings.enabledModels().map { it.first.id })
    }

    @Test
    fun `activeProvider matches the address ignoring a trailing slash`() {
        val settings = AppSettings(
            baseUrl = "https://a/v1",
            providers = listOf(ProviderConfig(id = "a", baseUrl = "https://a/v1/")),
        )
        assertEquals("a", settings.activeProvider()?.id)
    }

    @Test
    fun `activeProvider is null when nothing matches`() {
        val settings = AppSettings(
            baseUrl = "https://elsewhere/v1",
            providers = listOf(ProviderConfig(id = "a", baseUrl = "https://a/v1")),
        )
        assertEquals(null, settings.activeProvider())
    }

    @Test
    fun `shortModel strips the provider prefix`() {
        assertEquals("deepseek-chat", AppSettings(model = "deepseek/deepseek-chat").shortModel)
        assertEquals("gpt-4o", AppSettings(model = "gpt-4o").shortModel)
    }
}
