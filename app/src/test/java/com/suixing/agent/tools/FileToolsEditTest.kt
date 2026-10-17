package com.suixing.agent.tools

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `edit_file` 的三条防线（见 FileTools.editFile 的注释）：找不到就说清楚、多处命中就拒绝、
 * 成功要能看出改了哪几行。这几条都是"宁可拒绝也不盲改"的行为，回归成本极低但价值很高。
 */
class FileToolsEditTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun tools(): FileTools {
        val context = mockk<Context>()
        every { context.getExternalFilesDir(null) } returns folder.root
        every { context.filesDir } returns folder.root
        return FileTools(context)
    }

    private fun args(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    private fun write(name: String, text: String): File =
        File(folder.root, name).apply { parentFile?.mkdirs(); writeText(text) }

    @Test
    fun `replaces a unique span and reports what changed`() = runBlocking {
        val file = write("a.txt", "第一行\n第二行\n第三行\n")

        val result = tools().execute(
            "edit_file",
            args("path" to "a.txt", "old_string" to "第二行", "new_string" to "第二行（改过）"),
        )

        assertTrue("应成功：$result", result.ok)
        assertEquals("第一行\n第二行（改过）\n第三行\n", file.readText())
        assertTrue("返回里要有 diff 预览：$result", result.output.contains("+ "))
    }

    @Test
    fun `refuses an ambiguous match and names the lines`() = runBlocking {
        write("b.txt", "重复\n中间\n重复\n")

        val result = tools().execute(
            "edit_file",
            args("path" to "b.txt", "old_string" to "重复", "new_string" to "x"),
        )

        assertTrue("多处命中必须拒绝：$result", !result.ok)
        assertTrue("要说清出现几次：$result", result.output.contains("2 次"))
        assertTrue("要给出行号：$result", result.output.contains("第 1、3 行"))
        assertEquals("拒绝时文件不能被改", "重复\n中间\n重复\n", File(folder.root, "b.txt").readText())
    }

    @Test
    fun `replace_all changes every occurrence`() = runBlocking {
        val file = write("c.txt", "重复\n中间\n重复\n")

        val result = tools().execute(
            "edit_file",
            args("path" to "c.txt", "old_string" to "重复", "new_string" to "x", "replace_all" to "true"),
        )

        assertTrue("replace_all 应成功：$result", result.ok)
        assertEquals("x\n中间\nx\n", file.readText())
    }

    @Test
    fun `missing text is refused with the reason`() = runBlocking {
        write("d.txt", "内容\n")

        val result = tools().execute(
            "edit_file",
            args("path" to "d.txt", "old_string" to "不存在", "new_string" to "x"),
        )

        assertTrue("找不到必须拒绝：$result", !result.ok)
        assertTrue("要说清逐字符一致：$result", result.output.contains("逐字符一致"))
    }
}

