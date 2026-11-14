package com.suixing.agent.ui.settings

import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ProviderConfig
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSettingsSecretsTest {
    private val stored = AppSettings(
        apiKey = "enc1:api",
        providers = listOf(ProviderConfig(id = "p", apiKey = "enc1:provider")),
        searchApiKey = "enc1:search",
        asrApiKey = "enc1:asr",
        requestHeaders = mapOf("X-Custom" to "enc1:header"),
    )
    private val decoded = stored.copy(
        apiKey = "decoded-api",
        providers = stored.providers.map { it.copy(apiKey = "decoded-provider") },
        searchApiKey = "decoded-search",
        asrApiKey = "decoded-asr",
        requestHeaders = mapOf("X-Custom" to "decoded-header"),
    )

    @Test fun `an unrelated settings update retains every decoded credential`() {
        val incoming = stored.copy(themeMode = 2)
        val reused = incoming.reuseDecodedSecrets(stored, decoded)
        assertTrue("unchanged credentials must use the decoded snapshot", reused != null)
        assertTrue("metadata edits must remain visible", reused?.themeMode == 2)
        assertTrue("provider credentials must remain decoded", reused?.apiKey == decoded.apiKey && reused?.providers == decoded.providers)
        assertTrue("search credentials must remain decoded", reused?.searchApiKey == decoded.searchApiKey)
        assertTrue("speech credentials must remain decoded", reused?.asrApiKey == decoded.asrApiKey)
        assertTrue("request headers must remain decoded", reused?.requestHeaders == decoded.requestHeaders)
    }

    @Test fun `changed search speech and header credentials reject cached decoding`() {
        val updates = listOf(
            stored.copy(searchApiKey = "enc1:search-new"),
            stored.copy(asrApiKey = "enc1:asr-new"),
            stored.copy(requestHeaders = mapOf("X-Custom" to "enc1:header-new")),
        )
        updates.forEachIndexed { index, incoming ->
            assertTrue("changed credential $index must be decoded again", incoming.reuseDecodedSecrets(stored, decoded) == null)
        }
    }

    @Test fun `changed provider credentials still reject cached decoding`() {
        val incoming = stored.copy(providers = stored.providers.map { it.copy(apiKey = "enc1:provider-new") })
        assertTrue("provider credential changes must be decoded again", incoming.reuseDecodedSecrets(stored, decoded) == null)
    }
}
