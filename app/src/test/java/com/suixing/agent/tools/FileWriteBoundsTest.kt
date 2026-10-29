package com.suixing.agent.tools

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class FileWriteBoundsTest {
    @get:Rule val folder = TemporaryFolder()
    private fun context(): Context = mockk<Context>().also {
        every { it.filesDir } returns File(folder.root, "private").apply { mkdirs() }
        every { it.getExternalFilesDir(null) } returns File(folder.root, "workspace").apply { mkdirs() }
    }
    private fun args(vararg values: Pair<String, String>) = JsonObject(values.associate { it.first to JsonPrimitive(it.second) })

    @Test fun writeAndAppendRejectUtf8SizeBeforeTouchingFile() = runBlocking {
        val context = context()
        val tools = FileTools(context)
        val target = File(workspaceDir(context), "text.txt").apply { writeText("original") }
        val result = tools.execute("write_file", args("path" to "text.txt", "content" to "中".repeat(1_500_000)))
        assertFalse(result.ok)
        assertEquals("original", target.readText())
        target.writeBytes(ByteArray(4 * 1024 * 1024))
        assertFalse(tools.execute("write_file", args("path" to "text.txt", "content" to "x", "append" to "true")).ok)
        assertEquals(4L * 1024 * 1024, target.length())
    }

    @Test fun protectsAppStateButAllowsUserWorkspace() = runBlocking {
        val context = context()
        val tools = FileTools(context)
        val target = File(context.filesDir, "chats/store/one.jsonl").apply { parentFile!!.mkdirs(); writeText("saved") }
        assertFalse(tools.execute("write_file", args("path" to target.absolutePath, "content" to "broken")).ok)
        assertFalse(tools.execute("edit_file", args("path" to target.absolutePath, "old_string" to "saved", "new_string" to "broken")).ok)
        assertEquals("saved", target.readText())
        assertTrue(tools.execute("write_file", args("path" to "notes.txt", "content" to "note")).ok)
    }

    @Test fun streamCopyNeverWritesBeyondLimitWithUnknownLength() {
        val output = ByteArrayOutputStream()
        assertNotNull(runCatching { copyDownloadCapped(ByteArrayInputStream(ByteArray(20)), output, 10) }.exceptionOrNull())
        assertTrue(output.size() <= 10)
        val exact = ByteArrayOutputStream()
        copyDownloadCapped(ByteArrayInputStream(ByteArray(10)), exact, 10)
        assertEquals(10, exact.size())
    }

    @Test fun missingSettingCannotBeReportedAsVerified() {
        assertFalse(settingWriteVerified("1", "null"))
        assertFalse(settingWriteVerified("null", "null"))
        assertTrue(settingWriteVerified("1", "1"))
    }
}
