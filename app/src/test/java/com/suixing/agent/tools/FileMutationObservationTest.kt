package com.suixing.agent.tools

import com.suixing.agent.data.FileActivityKind
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileMutationObservationTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `filesystem evidence captures script writes edits copies and deletes independently of output`() {
        val root = folder.newFolder("workspace")
        val edited = File(root, "edited.txt").apply { writeText("before\n") }
        val deleted = File(root, "deleted.txt").apply { writeText("remove\n") }
        val unchanged = File(root, "unchanged.txt").apply { writeText("same\n") }
        val observation = FileMutationObservation.begin(listOf(root))
        // No stdout is available to the observer. These stand in for arbitrary script side effects.
        edited.writeText("after\nextra\n")
        deleted.delete()
        File(root, "copy.txt").writeBytes(unchanged.readBytes())
        val result = observation.finish()
        assertEquals(setOf("edited.txt", "deleted.txt", "copy.txt"), result.activities.map { File(it.path).name }.toSet())
        assertEquals(FileActivityKind.MODIFIED, result.activities.single { it.path == edited.path }.kind)
        assertEquals(FileActivityKind.DELETED, result.activities.single { it.path == deleted.path }.kind)
        assertEquals(2, result.activities.single { it.path == edited.path }.addedLines)
        assertEquals(1, result.activities.single { it.path == edited.path }.removedLines)
        assertTrue(result.notice.isEmpty())
    }

    @Test fun `verified rename uses file identity while copy and delete remain separate when identity differs`() {
        val root = folder.newFolder("workspace")
        val source = File(root, "source.txt").apply { writeText("payload") }
        val observation = FileMutationObservation.begin(listOf(root))
        val destination = File(root, "destination.txt")
        assertTrue(source.renameTo(destination))
        val result = observation.finish().activities
        // Filesystems without a stable identity may honestly report create and delete instead.
        if (result.any { it.kind == FileActivityKind.MOVED }) {
            assertEquals(1, result.size)
            assertEquals(source.path, result.single().previousPath)
            assertEquals(destination.path, result.single().path)
        } else assertEquals(setOf(FileActivityKind.CREATED, FileActivityKind.DELETED), result.map { it.kind }.toSet())
    }

    @Test fun `bounded scan never turns skipped entries into false creations or deletions`() {
        val root = folder.newFolder("workspace")
        repeat(5) { File(root, "$it.txt").writeText("old") }
        val observation = FileMutationObservation.begin(listOf(root), maxEntries = 2)
        File(root, "added.txt").writeText("new")
        val result = observation.finish()
        assertTrue(result.activities.isEmpty())
        assertTrue(result.notice.isNotEmpty())
    }

    @Test fun `binary content changes have no invented line counts and identical writes have no activity`() {
        val root = folder.newFolder("workspace")
        val binary = File(root, "binary").apply { writeBytes(byteArrayOf(0, 1, 2)) }
        val same = File(root, "same").apply { writeText("same") }
        val observation = FileMutationObservation.begin(listOf(root))
        binary.writeBytes(byteArrayOf(0, 3, 2))
        same.writeText("same")
        val activity = observation.finish().activities.single()
        assertEquals(binary.path, activity.path)
        assertNull(activity.addedLines)
        assertNull(activity.removedLines)
    }

    @Test fun `overlapping observations do not assign shared mutations to two commands`() {
        val root = folder.newFolder("workspace")
        val one = FileMutationObservation.begin(listOf(root))
        val two = FileMutationObservation.begin(listOf(root))
        File(root, "shared").writeText("unknown author")
        assertTrue(one.finish().activities.isEmpty())
        assertTrue(two.finish().activities.isEmpty())
    }
}
