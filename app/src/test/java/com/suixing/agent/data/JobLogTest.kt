package com.suixing.agent.data

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JobLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun log(vararg lines: String): File =
        File(folder.root, "job.log").apply { writeText(lines.joinToString("\n", postfix = "\n")) }

    @Test
    fun `reads the last lines`() {
        val file = log("one", "two", "three")
        assertEquals("two\nthree", readJobLog(file, tail = 2, since = 0))
    }

    @Test
    fun `a tail larger than the log gives the whole log`() {
        val file = log("one", "two")
        assertEquals("one\ntwo", readJobLog(file, tail = 50, since = 0))
    }

    /** A log with no trailing newline is what a still-running process usually looks like. */
    @Test
    fun `a last line without a newline is still a line`() {
        val file = File(folder.root, "partial.log").apply { writeText("one\ntwo") }
        assertEquals("two", readJobLog(file, tail = 1, since = 0))
    }

    @Test
    fun `an offset gives only what was appended since`() {
        val file = log("one", "two")
        val seen = file.length()

        file.appendText("three\n")

        assertEquals("three\n", readJobLog(file, tail = 50, since = seen))
    }

    @Test
    fun `an offset at the end gives nothing`() {
        val file = log("one", "two")
        assertEquals("", readJobLog(file, tail = 50, since = file.length()))
    }

    @Test
    fun `an offset past the end gives nothing`() {
        val file = log("one")
        assertEquals("", readJobLog(file, tail = 50, since = 9_999L))
    }

    @Test
    fun `a log that was never written reads as empty`() {
        assertEquals("", readJobLog(File(folder.root, "absent.log"), tail = 50, since = 0))
    }

    /** Polling a job reads it the same way twice: the second call sees only the new bytes. */
    @Test
    fun `polling twice returns each chunk once`() {
        val file = log("start")
        val first = readJobLog(file, tail = 50, since = 0)
        var offset = file.length()

        file.appendText("working\n")
        val second = readJobLog(file, tail = 50, since = offset)
        offset = file.length()

        file.appendText("done\n")
        val third = readJobLog(file, tail = 50, since = offset)

        assertEquals("start", first)
        assertEquals("working\n", second)
        assertEquals("done\n", third)
    }
}
