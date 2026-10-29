package com.suixing.agent.tools

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileStreamingTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `streaming windows keep original line endings unicode and edit revision`() {
        for (text in listOf("", "\n", "a\r\nb\rc\n", "中文😀\r\n末行", "x".repeat(20_000) + "\r\nlast\n")) {
            for (offset in 1..text.lines().size + 1) {
                val result = scanTextWindow(text.reader(), offset, 2)
                assertEquals(text.lines().size, result.totalLines)
                assertEquals(text.lines().drop(offset - 1).take(2), result.lines)
                assertEquals(revisionOf(text), result.revision)
            }
        }
    }

    @Test fun `read_file revision matches edit_file even with CRLF and a trailing empty line`() = runBlocking {
        val dir = tmp.newFolder()
        val text = "首行\r\nneedle\r尾行\n"
        File(dir, "a.txt").writeText(text)
        val context = mockk<Context>(relaxed = true)
        every { context.getExternalFilesDir(null) } returns dir
        every { context.filesDir } returns dir
        val tools = FileTools(context)
        val result = tools.runForTest("read_file", buildJsonObject {
            put("path", JsonPrimitive("a.txt")); put("offset", JsonPrimitive(2)); put("limit", JsonPrimitive(2))
        })
        assertTrue(result.output, result.ok)
        assertTrue(result.output.contains("共 4 行，显示 2-3"))
        assertTrue(result.output.contains("revision=${revisionOf(text)}"))
        assertTrue(result.output.endsWith("needle\n尾行"))
    }

    @Test fun `streamed grep merges overlapping contexts without duplicate lines and respects output limit`() = runBlocking {
        val dir = tmp.newFolder()
        File(dir, "a.txt").writeText("before\nMATCH\nMATCH\nafter\nend\n")
        val context = mockk<Context>(relaxed = true)
        every { context.getExternalFilesDir(null) } returns dir
        every { context.filesDir } returns dir
        val result = FileTools(context).runForTest("grep_files", buildJsonObject {
            put("root", JsonPrimitive(dir.path)); put("query", JsonPrimitive("MATCH"))
            put("context", JsonPrimitive(1)); put("limit", JsonPrimitive(3))
        })
        assertTrue(result.output, result.ok)
        val hits = result.output.lines().filter { it.contains("a.txt:") }
        assertEquals(3, hits.size)
        assertTrue(hits[0].endsWith(":1- before"))
        assertTrue(hits[1].endsWith(":2: MATCH"))
        assertTrue(hits[2].endsWith(":3: MATCH"))
    }
}
