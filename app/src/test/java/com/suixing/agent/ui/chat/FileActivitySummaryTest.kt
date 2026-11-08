package com.suixing.agent.ui.chat

import com.suixing.agent.data.FileActivity
import com.suixing.agent.data.FileActivityKind
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileActivitySummaryTest {
    @Test fun deletionAfterAWriteKeepsTheLatestUnavailableStateAndCumulativeCounts() {
        val path = "/sdcard/temporary.txt"
        val created = message("create", FileActivity(path, FileActivityKind.CREATED, addedLines = 2, removedLines = 0))
        val deleted = message("delete", FileActivity(path, FileActivityKind.DELETED, addedLines = 0, removedLines = 2))
        val result = fileActivitiesFor(emptyList(), listOf(created, deleted)).single()
        assertEquals(FileActivityKind.DELETED, result.kind)
        assertEquals(2, result.addedLines)
        assertEquals(2, result.removedLines)
    }

    @Test fun moveRetainsTheOriginalPathAndDoesNotEraseEarlierReplacementCounts() {
        val path = "/sdcard/new.txt"
        val edit = message("edit", FileActivity(path, FileActivityKind.MODIFIED, addedLines = 2, removedLines = 1))
        val move = message("move", FileActivity(path, FileActivityKind.MOVED, previousPath = "/sdcard/old.txt"))
        val later = message("later", FileActivity(path, FileActivityKind.MODIFIED, addedLines = 3, removedLines = 1))
        val result = fileActivitiesFor(emptyList(), listOf(edit, move, later)).single()
        assertEquals("/sdcard/old.txt", result.previousPath)
        assertEquals(5, result.addedLines)
        assertEquals(2, result.removedLines)
    }

    @Test fun movingAPreviouslyCreatedFileRemovesItsStaleSourceCard() {
        val created = message("create", FileActivity("/sdcard/old.txt", FileActivityKind.CREATED, addedLines = 2, removedLines = 0))
        val move = message("move", FileActivity("/sdcard/new.txt", FileActivityKind.MOVED, previousPath = "/sdcard/old.txt"))
        val result = fileActivitiesFor(emptyList(), listOf(created, move)).single()
        assertEquals("/sdcard/new.txt", result.path)
        assertEquals("/sdcard/old.txt", result.previousPath)
        assertEquals(2, result.addedLines)
    }

    @Test fun aRunningReplyHasNoFileOrActionFooter() {
        assertEquals(emptyList<ReplyFooterSection>(), replyFooterSections(streaming = true, fileCount = 1, hasActions = true))
    }

    @Test fun completedFilesAreRenderedBeforeTheReplyActions() {
        assertEquals(listOf(ReplyFooterSection.FILE_ACTIVITY, ReplyFooterSection.ACTIONS),
            replyFooterSections(streaming = false, fileCount = 2, hasActions = true))
    }

    @Test fun fileChangesRemainVisibleWithoutProseOrActionButtons() {
        assertEquals(listOf(ReplyFooterSection.FILE_ACTIVITY),
            replyFooterSections(streaming = false, fileCount = 1, hasActions = false))
    }

    @Test fun repliesWithoutFilesKeepOnlyTheirExistingActions() {
        assertEquals(listOf(ReplyFooterSection.ACTIONS),
            replyFooterSections(streaming = false, fileCount = 0, hasActions = true))
        assertEquals(emptyList<ReplyFooterSection>(),
            replyFooterSections(streaming = false, fileCount = 0, hasActions = false))
    }

    @Test fun oneChangedFileAlsoStartsCollapsed() {
        // 单个文件同样是「收尾信息」：默认只显示一行摘要，点开才看文件与按钮。
        val files = listOf(FileActivity("/sdcard/one.txt", FileActivityKind.MODIFIED))
        assertTrue(fileActivityCanCollapse(files.size))
        assertFalse(fileActivityInitiallyExpanded(files))
    }

    @Test fun twoOrMoreFilesCanCollapseAndStartCollapsedEvenWhenDelivered() {
        val files = listOf(
            FileActivity("/sdcard/one.txt", FileActivityKind.DELIVERED),
            FileActivity("/sdcard/two.txt", FileActivityKind.DOWNLOADED),
        )
        assertTrue(fileActivityCanCollapse(files.size))
        assertFalse(fileActivityInitiallyExpanded(files))
        assertTrue(fileActivityCanCollapse(3))
    }

    @Test fun repeatedEditsOfOnePathAreNotTreatedAsMultipleFiles() {
        val one = message("first", FileActivity("/sdcard/one.txt", FileActivityKind.MODIFIED, addedLines = 1, removedLines = 0))
        val two = message("second", FileActivity("/sdcard/one.txt", FileActivityKind.MODIFIED, addedLines = 2, removedLines = 0))
        val files = fileActivitiesFor(emptyList(), listOf(one, two))
        assertEquals(1, files.size)
        assertTrue(fileActivityCanCollapse(files.size))
        assertEquals(3, files.single().addedLines)
    }

    @Test fun zeroOrUnknownLineCountsDoNotHideConfirmedFileChanges() {
        listOf(
            FileActivity("/sdcard/unknown.bin", FileActivityKind.MODIFIED),
            FileActivity("/sdcard/zero.txt", FileActivityKind.CREATED, addedLines = 0, removedLines = 0),
        ).forEach { file ->
            val files = fileActivitiesFor(emptyList(), listOf(message(file.path, file)))
            assertEquals(listOf(ReplyFooterSection.FILE_ACTIVITY),
                replyFooterSections(streaming = false, fileCount = files.size, hasActions = false))
        }
    }

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
