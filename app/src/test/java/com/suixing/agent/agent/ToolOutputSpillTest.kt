package com.suixing.agent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 工具输出超长时不能无声截断：完整输出落盘，wire 里留下路径与读取方法。
 * 落盘失败时也必须说清"被截断了、而且没存下来"，不能假装看全了。
 */
class ToolOutputSpillTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun dir(): File = File(folder.root, "toolout")

    @Test
    fun `short output is left alone`() {
        assertNull(spillToolOutput(dir(), "ui_dump", "short", maxKept = 100))
    }

    @Test
    fun `long output is kept in full on disk and announced in the text`() {
        val full = "行".repeat(500)

        val spilled = spillToolOutput(dir(), "ui_dump", full, maxKept = 100)!!

        assertEquals("完整内容要一字不少地落盘", full, File(spilled.path).readText())
        assertTrue("wire 里要说明被截断了", spilled.kept.contains("已截断"))
        assertTrue("要给出可回读的路径", spilled.kept.contains(spilled.path))
        assertTrue("要告诉模型别重跑", spilled.kept.contains("不要重跑"))
        assertTrue("头部内容要保留", spilled.kept.startsWith("行".repeat(100)))
    }

    @Test
    fun `a failed write still reports the truncation honestly`() {
        // 目录位置被一个普通文件占住，mkdirs 与写入都会失败。
        val blocked = File(folder.root, "blocked")
        blocked.writeText("x")

        val spilled = spillToolOutput(File(blocked, "toolout"), "run_shell", "y".repeat(300), maxKept = 50)!!

        assertTrue(spilled.kept.contains("已截断"))
        assertTrue("不能编一个不存在的文件", !spilled.kept.contains("已存 "))
        assertTrue(spilled.path.isEmpty())
    }

    @Test
    fun `only the newest spills are kept`() {
        val dir = dir().apply { mkdirs() }
        repeat(6) { index ->
            File(dir, "old-$index.txt").apply { writeText("x"); setLastModified(1_000L + index) }
        }

        pruneOldSpills(dir, keep = 2)

        assertEquals(2, dir.listFiles()!!.size)
    }

    @Test
    fun `tool names are made filesystem safe`() {
        val spilled = spillToolOutput(dir(), "weird/name:*?", "z".repeat(200), maxKept = 10)!!

        assertTrue(File(spilled.path).name.matches(Regex("[A-Za-z0-9_.-]+-[0-9]+\\.txt")))
    }
}
