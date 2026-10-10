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
