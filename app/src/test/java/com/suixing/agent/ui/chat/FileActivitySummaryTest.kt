package com.suixing.agent.ui.chat

import com.suixing.agent.data.FileActivity
import com.suixing.agent.data.FileActivityKind
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileActivitySummaryTest {
    private fun message(id: String, activity: FileActivity): StoredMessage = StoredMessage(
        id = id, role = Roles.TOOL, toolName = "file_tool", toolOk = true, fileActivities = listOf(activity),
    )

    @Test fun deliveringAModifiedFileKeepsItsChangesAndUsesTheLatestFileDetails() {
        val path = "/sdcard/report.md"
        val changed = message("edit", FileActivity(path, FileActivityKind.MODIFIED,
            addedLines = 3, removedLines = 1, sizeBytes = 20))
        val delivered = message("deliver", FileActivity(path, FileActivityKind.DELIVERED,
            sizeBytes = 28, description = "交付的报告"))

        val summary = fileActivitiesFor(emptyList(), listOf(changed, delivered)).single()

        assertEquals(path, summary.path)
        assertEquals(FileActivityKind.MODIFIED, summary.kind)
        assertEquals(3, summary.addedLines)
        assertEquals(1, summary.removedLines)
        assertEquals(28L, summary.sizeBytes)
        assertEquals("交付的报告", summary.description)
    }

    @Test fun duplicateMessagesAcrossRoundsAndToolPartsCountEachOperationOnce() {
        val path = "/sdcard/result.txt"
        val created = message("create", FileActivity(path, FileActivityKind.CREATED,
            addedLines = 2, removedLines = 0, sizeBytes = 10))
        val changed = message("edit", FileActivity(path, FileActivityKind.MODIFIED,
            addedLines = 1, removedLines = 1, sizeBytes = 12))
        val other = message("deliver", FileActivity("/sdcard/attachment.bin", FileActivityKind.DELIVERED,
            sizeBytes = 8))
        val parts = listOf(Part.Tools(listOf(ToolLine.of(created.copy()), ToolLine.of(changed), ToolLine.of(other))))

        val summaries = fileActivitiesFor(parts, listOf(created, changed))

        assertEquals(listOf(path, "/sdcard/attachment.bin"), summaries.map { it.path })
        val result = summaries.first()
        assertEquals(FileActivityKind.CREATED, result.kind)
        assertEquals(3, result.addedLines)
        assertEquals(1, result.removedLines)
        assertEquals(12L, result.sizeBytes)
        assertEquals(FileActivityKind.DELIVERED, summaries.last().kind)
    }

    @Test fun anUnknownModificationKeepsCumulativeCountsUnknownAfterDelivery() {
        val path = "/sdcard/large.txt"
        val known = message("first-edit", FileActivity(path, FileActivityKind.MODIFIED,
            addedLines = 2, removedLines = 1, sizeBytes = 100))
        val unknown = message("large-edit", FileActivity(path, FileActivityKind.MODIFIED, sizeBytes = 200_000))
        val delivered = message("deliver", FileActivity(path, FileActivityKind.DELIVERED, sizeBytes = 200_000))

        val summary = fileActivitiesFor(emptyList(), listOf(known, unknown, delivered)).single()

        assertEquals(FileActivityKind.MODIFIED, summary.kind)
        assertNull(summary.addedLines)
        assertNull(summary.removedLines)
        assertEquals(200_000L, summary.sizeBytes)
    }
}
