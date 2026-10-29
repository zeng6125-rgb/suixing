package com.suixing.agent.ui.settings

import com.suixing.agent.data.ProviderConfig
import com.suixing.agent.data.AppSettings
import org.junit.Assert.*
import org.junit.Test

class ProviderTemplatesTest {
    @Test fun `saving an incomplete preset does not clear the current live endpoint`() {
        val current = AppSettings(baseUrl = "http://local/v1", apiKey = "existing", model = "current", protocol = "anthropic")
        val provider = providerTemplates.first().newProvider("p").copy(apiKey = "new-key")
        val draft = current.copy(baseUrl = provider.baseUrl, apiKey = provider.apiKey, model = "",
            protocol = provider.protocol, providers = listOf(provider))
        val saved = draft.forSaving(current)
        assertEquals(current.baseUrl, saved.baseUrl)
        assertEquals(current.apiKey, saved.apiKey)
        assertEquals(current.model, saved.model)
        assertEquals(current.protocol, saved.protocol)
        assertEquals(provider, saved.providers.single())
    }

    @Test fun `new supplier never copies credentials or enabled models`() {
        val provider = providerTemplates.first().newProvider("new")
        assertEquals("new", provider.id)
        assertTrue(provider.apiKey.isEmpty())
        assertTrue(provider.models.isEmpty())
        assertTrue(provider.enabled.isEmpty())
        val custom = (null as ProviderTemplate?).newProvider("custom")
        assertEquals("", custom.baseUrl)
        assertEquals("", custom.protocol)
    }

    @Test fun `enabling adds one id while preserving credentials aliases and protocol`() {
        val original = ProviderConfig("p", baseUrl = "http://gateway/v1", apiKey = "secret",
            aliases = mapOf("m" to "model"), protocol = "openai-responses")
        val enabled = original.withModelEnabled("m", true).withModelEnabled("m", true)
        assertEquals(listOf("m"), enabled.enabled)
        assertEquals(listOf("m"), enabled.models)
        assertEquals(original.copy(models = listOf("m"), enabled = listOf("m")), enabled)
        assertEquals(emptyList<String>(), enabled.withModelEnabled("m", false).enabled)
        assertEquals(listOf("m"), enabled.withModelEnabled("m", false).models)
    }
}
