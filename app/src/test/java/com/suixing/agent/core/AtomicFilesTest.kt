package com.suixing.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** issue #69 P1-B：一份原子写实现，行为固定为「要么全成、要么保留旧文件」。 */
class AtomicFilesTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `writes content and leaves no temp file`() {
        val target = File(tmp.newFolder("a"), "x.json")
        AtomicFiles.writeText(target, "hello")
        assertEquals("hello", target.readText())
        assertFalse("成功后不该留下 .tmp", File(target.parentFile, "x.json.tmp").exists())
    }

    @Test fun `replaces existing content atomically`() {
        val dir = tmp.newFolder("b")
        val target = File(dir, "x.json").apply { writeText("old") }
        AtomicFiles.writeText(target, "new")
        assertEquals("new", target.readText())
    }

    @Test fun `creates parent directories`() {
        val target = File(tmp.newFolder("c"), "deep/nested/x.json")
        AtomicFiles.writeText(target, "v")
        assertTrue(target.exists())
        assertEquals("v", target.readText())
    }
}
