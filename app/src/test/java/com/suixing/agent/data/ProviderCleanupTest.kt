package com.suixing.agent.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ProviderCleanupTest {
    private fun supplier(id: String = "p") = ProviderConfig(id = id, name = "本地服务", baseUrl = "http://lan:8000/v1")

    @Test fun `missing identity name placeholder name or address is removed`() {
        val valid = supplier()
        val invalid = listOf(valid.copy(id = ""), valid.copy(id = "a", name = "  "),
            valid.copy(id = "b", name = "未命名"), valid.copy(id = "c", name = "未命名供应商"),
            valid.copy(id = "d", baseUrl = "  "))
        assertEquals(listOf(valid), AppSettings(providers = invalid + valid).withoutEmptyProviders().providers)
    }

    @Test fun `a keyless named endpoint without fetched models is a valid saved supplier`() {
        val settings = AppSettings(providers = listOf(supplier()))
        assertSame(settings, settings.withoutEmptyProviders())
        assertTrue(supplier().hasProviderDetails())
    }

    @Test fun `cleanup preserves all surviving connection and advanced fields`() {
        val valid = supplier().copy(apiKey = "enc1:fixture", models = listOf("x"), enabled = listOf("x"),
            ownSampling = true, temperature = 0.3, aliases = mapOf("x" to "显示名称"))
        val settings = AppSettings(providers = listOf(valid, supplier("bad").copy(name = "")),
            baseUrl = "http://live/v1", apiKey = "enc1:live", model = "x", modelContexts = mapOf("x" to 12345))
        val result = settings.withoutEmptyProviders()
        assertEquals(listOf(valid), result.providers)
        assertEquals(settings.copy(providers = listOf(valid), providersInitialized = true), result)
    }

    @Test fun `clearing the last invalid supplier survives serialization and legacy migration`() {
        val settings = AppSettings(providers = listOf(supplier().copy(name = "")))
        val cleaned = settings.withoutEmptyProviders()
        assertTrue(cleaned.providersInitialized)
        val reloaded = Json.decodeFromString<AppSettings>(Json.encodeToString(cleaned)).withProviders()
        assertTrue(reloaded.providers.isEmpty())
        assertEquals(settings.baseUrl, reloaded.baseUrl)
    }

    @Test fun `legacy settings without a provider list still get their original endpoint migrated`() {
        val legacy = AppSettings(baseUrl = "http://legacy/v1", apiKey = "fixture", model = "old")
        val migrated = legacy.withoutEmptyProviders().withProviders()
        assertEquals(1, migrated.providers.size)
        assertEquals(legacy.baseUrl, migrated.providers.single().baseUrl)
        assertEquals(legacy.apiKey, migrated.providers.single().apiKey)
        assertEquals(listOf("old"), migrated.providers.single().enabled)
    }

    @Test fun `an intentionally empty list remains empty without inventing a supplier`() {
        val cleared = AppSettings(providersInitialized = true)
        assertSame(cleared, cleared.withoutEmptyProviders().withProviders())
    }
}
