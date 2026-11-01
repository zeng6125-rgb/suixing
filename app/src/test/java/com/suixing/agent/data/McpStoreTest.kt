package com.suixing.agent.data

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The MCP server list is a JSON file holding the user's own definitions, and nothing tested its
 * round trip: a field dropped in encoding reads as a server that quietly stops working.
 */
class McpStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    /**
     * JVM 单测里没有 Android Keystore。**只在测试侧**把加解密换成恒等实现 ——
     * 生产路径不能因为「平台没能力」就退回明文（那样真机上 Keystore 坏掉时
     * MCP 的 token 会明文落盘，正是 issue #58 要修的事）。
     */
    @org.junit.Before
    fun useIdentityCrypto() {
        SecretBox.crypto = SecretBox.IdentityCrypto
    }

    @org.junit.After
    fun restoreCrypto() {
        SecretBox.crypto = SecretBox.KeystoreCrypto
    }

    private fun store(): McpStore {
        val context = mockk<Context>()
        every { context.filesDir } returns folder.root
        return McpStore(context)
    }

    private fun file(name: String) = File(folder.root, name)

    @Test
    fun `an http server survives the round trip with its headers`() = runBlocking {
        store().save(
            listOf(
                McpServerConfig(
                    name = "github",
                    transport = McpServerConfig.TRANSPORT_HTTP,
                    url = "https://api.example.com/mcp",
                    headers = "Authorization: Bearer t",
                ),
            ),
        )

        val read = store().load()

        assertEquals(1, read.size)
        assertEquals("github", read[0].name)
        assertEquals("https://api.example.com/mcp", read[0].url)
        assertEquals("Authorization: Bearer t", read[0].headers)
        assertTrue(read[0].enabled)
        assertFalse(read[0].isStdio)
    }

    @Test
    fun `a stdio server keeps its command and environment`() = runBlocking {
        store().save(
            listOf(
                McpServerConfig(
                    name = "local",
                    command = "npx -y server",
                    env = "TOKEN=abc",
                    enabled = false,
                ),
            ),
        )

        val read = store().load()

        assertEquals(1, read.size)
        assertEquals("npx -y server", read[0].command)
        assertEquals("TOKEN=abc", read[0].env)
        assertEquals(McpServerConfig.TRANSPORT_STDIO, read[0].transport)
        assertTrue(read[0].isStdio)
        assertFalse(read[0].enabled)
    }

    @Test
    fun `encryption failure does not overwrite existing MCP credentials`() = runBlocking {
        val original = McpServerConfig(name = "local", env = "TOKEN=existing", headers = "Authorization: old")
        store().save(listOf(original))
        val before = file("mcp.json").readBytes()
        SecretBox.crypto = object : SecretBox.Crypto {
            override fun encrypt(plain: String): String? = if (plain.contains("new")) null else plain
            override fun decrypt(stored: String): String = stored
        }
        try {
            val failed = runCatching { store().save(listOf(original.copy(env = "TOKEN=new"))) }
            assertTrue(failed.exceptionOrNull() is IllegalStateException)
            assertTrue(before.contentEquals(file("mcp.json").readBytes()))
            assertFalse(file("mcp.json.tmp").exists())
        } finally {
            SecretBox.crypto = SecretBox.IdentityCrypto
        }
        assertEquals(original, store().load().single())
    }

    @Test
    fun `a missing file reads as no servers`() = runBlocking {
        assertTrue(store().load().isEmpty())
    }

    @Test
    fun `a field this build does not know about does not break the file`() = runBlocking {
        file("mcp.json").writeText(
            """[{"name":"x","transport":"stdio","command":"npx","enabled":true,"future":"?"}]""",
        )

        val read = store().load()

        assertEquals(1, read.size)
        assertEquals("x", read[0].name)
    }

    /** Never drop the user's definitions silently: the bytes stay under a recoverable name. */
    @Test
    fun `an unreadable file is quarantined and reads as no servers`() = runBlocking {
        file("mcp.json").writeText("[ 这不是 json")

        val read = store().load()

        assertTrue(read.isEmpty())
        assertTrue(file("mcp.corrupt.json").isFile)
        assertFalse(file("mcp.json").exists())
    }
}
