package com.suixing.agent.plugins

import com.suixing.agent.data.SecretBox
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Entire env.json must survive a partial failure to encrypt (#58). */
class PluginSecretWriteTest {
    @get:Rule val folder = TemporaryFolder()

    @Before fun setupCrypto() {
        SecretBox.crypto = SecretBox.IdentityCrypto
    }

    @After fun restoreCrypto() {
        SecretBox.crypto = SecretBox.KeystoreCrypto
    }

    @Test fun `editing several env vars is atomic when one cannot encrypt`() = runBlocking {
        val root = folder.newFolder("plugins")
        File(root, "test-plugin").mkdirs()
        val store = PluginStore(root)
        store.setEnvValues("test-plugin", mapOf("OLD" to "old-secret", "OTHER" to "old-value"))
        val file = File(root, "test-plugin/env.json")
        val before = file.readBytes()

        SecretBox.crypto = object : SecretBox.Crypto {
            override fun encrypt(plain: String): String? =
                if (plain == "cannot-encrypt") null else plain
            override fun decrypt(stored: String): String = stored
        }
        val result = runCatching {
            store.setEnvValues("test-plugin", mapOf(
                "OLD" to "new-secret",
                "OTHER" to "cannot-encrypt",
            ))
        }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertTrue(before.contentEquals(file.readBytes()))
        assertFalse(File(root, "test-plugin/env.json.tmp").exists())

        SecretBox.crypto = SecretBox.IdentityCrypto
        assertEquals("old-secret", store.env("test-plugin")["OLD"])
        assertEquals("old-value", store.env("test-plugin")["OTHER"])
    }
}
