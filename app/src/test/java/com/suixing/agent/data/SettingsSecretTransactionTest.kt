package com.suixing.agent.data

import org.junit.Assert.*
import org.junit.Test

class SettingsSecretTransactionTest {
    @Test fun migrationDetectsStandaloneSearchAndCustomHeaderSecrets() {
        assertTrue(hasPlainSecret("""{"searchApiKey":"search-only"}"""))
        assertTrue(hasPlainSecret("""{"requestHeaders":{"Authorization":"Bearer private"}}"""))
        assertFalse(hasPlainSecret("""{"searchApiKey":"enc1:already","requestHeaders":{"X-Key":"enc1:safe"}}"""))
    }

    @Test fun failedFieldAbortsInsteadOfReplacingCredentialsOrMixingProviders() {
        val previous = SecretBox.crypto
        try {
            SecretBox.crypto = object : SecretBox.Crypto {
                override fun encrypt(plain: String): String? = null
                override fun decrypt(stored: String): String = stored
            }
            val old = AppSettings(apiKey = "enc1:old")
            var persisted = old
            try {
                persisted = old.copy(searchApiKey = "new-secret").encryptedForStorage()
                fail("Encryption failure must abort the update")
            } catch (_: IllegalStateException) { }
            assertSame(old, persisted)
            assertEquals("enc1:old", persisted.apiKey)
        } finally {
            SecretBox.crypto = previous
        }
    }
}
