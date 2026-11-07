package com.suixing.agent.tools

import android.content.Context
import com.suixing.agent.data.FileActivityKind
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileActivityTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun tools(): FileTools {
        val workspace = File(temporary.root, "workspace").apply { mkdirs() }
        val privateFiles = File(temporary.root, "private").apply { mkdirs() }
        val context = mockk<Context>()
        every { context.getExternalFilesDir(null) } returns workspace
        every { context.filesDir } returns privateFiles
        return FileTools(context)
    }

    private fun file(name: String): File = File(temporary.root, "workspace/$name")

    private fun args(vararg values: Pair<String, String>): JsonObject =
        JsonObject(values.associate { (key, value) -> key to JsonPrimitive(value) })

    private fun assertActivity(result: ToolResult, file: File, kind: FileActivityKind, added: Int?, removed: Int?) {
        assertTrue(result.output, result.ok)
        val activity = result.fileActivities.single()
        assertEquals(file.absolutePath, activity.path)
        assertTrue(File(activity.path).isAbsolute)
        assertEquals(kind, activity.kind)
        assertEquals(added, activity.addedLines)
        assertEquals(removed, activity.removedLines)
        assertEquals(file.length(), activity.sizeBytes)
    }

    @Test fun createEditAndAppendReportTheFilesActuallyWritten() = runBlocking {
        val tools = tools()
        val target = file("notes/结果.txt")
        val initial = "开头\r\n旧内容\r\n结尾\r\n"
        val created = tools.execute("write_file", args("path" to "notes/结果.txt", "content" to initial))
        assertEquals(initial, target.readText())
        assertActivity(created, target, FileActivityKind.CREATED, 3, 0)

        val edited = tools.execute("edit_file", args("path" to "notes/结果.txt",
            "old_string" to "旧内容\r\n", "new_string" to "新内容\r\n新增行\r\n"))
        val changed = "开头\r\n新内容\r\n新增行\r\n结尾\r\n"
        assertEquals(changed, target.readText())
        assertActivity(edited, target, FileActivityKind.MODIFIED, 2, 1)

        val appended = tools.execute("write_file", args("path" to "notes/结果.txt",
            "content" to "补充\r\n", "append" to "true"))
        assertEquals(changed + "补充\r\n", target.readText())
        assertActivity(appended, target, FileActivityKind.MODIFIED, 1, 0)
    }

    @Test fun refusedOperationsLeaveContentAndFileMetadataUntouched() = runBlocking {
        val tools = tools()
        val target = file("existing.txt").apply { writeText("原内容\n") }
        val read = tools.execute("read_file", args("path" to "existing.txt"))
        assertTrue(read.output, read.ok)
        val revision = read.output.substringAfter("revision=").substringBefore('\n')
        assertTrue(revision.isNotBlank())
        target.writeText("外部更新\n")

        val conflict = tools.execute("edit_file", args("path" to "existing.txt",
            "old_string" to "外部更新", "new_string" to "替换", "expected_revision" to revision))
        assertFalse(conflict.ok)
        assertTrue(conflict.fileActivities.isEmpty())
        assertEquals("外部更新\n", target.readText())

        val directory = file("directory").apply { mkdir() }
        val refusedWrite = tools.execute("write_file", args("path" to "directory", "content" to "写入"))
        assertFalse(refusedWrite.ok)
        assertTrue(refusedWrite.fileActivities.isEmpty())
        assertTrue(directory.isDirectory)
    }

    @Test fun deliveryVerifiesAnExistingFileWithoutChangingIt() = runBlocking {
        val tools = tools()
        val target = file("artifact.bin").apply { writeBytes(byteArrayOf(0, 1, 2, 3, -1)) }
        val before = target.readBytes()
        val delivered = tools.execute("deliver_file", args("path" to target.absolutePath,
            "description" to "生成的附件"))

        assertTrue(before.contentEquals(target.readBytes()))
        assertActivity(delivered, target, FileActivityKind.DELIVERED, null, null)
        assertEquals("生成的附件", delivered.fileActivities.single().description)

        val missing = tools.execute("deliver_file", args("path" to "missing.bin"))
        assertFalse(missing.ok)
        assertTrue(missing.fileActivities.isEmpty())
        val directory = file("folder").apply { mkdir() }
        val nonFile = tools.execute("deliver_file", args("path" to directory.absolutePath))
        assertFalse(nonFile.ok)
        assertTrue(nonFile.fileActivities.isEmpty())
    }

    @Test fun replacingALargeFileSucceedsWithoutInventingUnknownLineCounts() = runBlocking {
        val tools = tools()
        val target = file("large.txt").apply { writeText("x".repeat(256 * 1024)) }
        val result = tools.execute("write_file", args("path" to "large.txt", "content" to "短内容\n"))

        assertEquals("短内容\n", target.readText())
        assertActivity(result, target, FileActivityKind.MODIFIED, null, null)
    }

    @Test fun rewritingIdenticalTextKeepsTheFileEntryWithZeroChanges() = runBlocking {
        val tools = tools()
        val text = "第一行\n第二行\n"
        val target = file("same.txt").apply { writeText(text) }
        val result = tools.execute("write_file", args("path" to "same.txt", "content" to text))

        assertEquals(text, target.readText())
        assertActivity(result, target, FileActivityKind.MODIFIED, 0, 0)
    }
}
