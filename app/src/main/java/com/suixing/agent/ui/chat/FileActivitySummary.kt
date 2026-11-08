package com.suixing.agent.ui.chat

import com.suixing.agent.data.FileActivity
import com.suixing.agent.data.FileActivityKind
import com.suixing.agent.data.StoredMessage

/** Fold only tool-reported metadata. This never reads the filesystem or guesses from prose. */
internal fun fileActivitiesFor(
    parts: List<Part>,
    rounds: List<StoredMessage> = emptyList(),
): List<FileActivity> {
    val seen = HashSet<String>()
    val byPath = LinkedHashMap<String, FileActivity>()
    fun collect(message: StoredMessage) {
        if (message.fileActivities.isEmpty() || !seen.add(message.id)) return
        for (activity in message.fileActivities) {
            if (activity.path.isBlank()) continue
            val previous = byPath[activity.path]
            byPath[activity.path] = if (previous == null) activity else mergeFileActivity(previous, activity)
        }
    }
    rounds.forEach(::collect)
    parts.forEach { part ->
        if (part is Part.Tools) part.calls.forEach { it.message?.let(::collect) }
    }
    return byPath.values.toList()
}

internal enum class ReplyFooterSection { FILE_ACTIVITY, ACTIONS }

private val footerFiles = listOf(ReplyFooterSection.FILE_ACTIVITY)
private val footerActions = listOf(ReplyFooterSection.ACTIONS)
private val footerBoth = listOf(ReplyFooterSection.FILE_ACTIVITY, ReplyFooterSection.ACTIONS)

/** Shared by the rendered footer and its state/order regressions. */
internal fun replyFooterSections(streaming: Boolean, fileCount: Int, hasActions: Boolean): List<ReplyFooterSection> = when {
    streaming -> emptyList()
    fileCount > 0 && hasActions -> footerBoth
    fileCount > 0 -> footerFiles
    hasActions -> footerActions
    else -> emptyList()
}

internal fun fileActivityCanCollapse(fileCount: Int): Boolean = fileCount >= 2

internal fun fileActivityInitiallyExpanded(activities: List<FileActivity>): Boolean =
    activities.size == 1

private fun mergeFileActivity(previous: FileActivity, next: FileActivity): FileActivity {
    fun FileActivity.isChange() = kind == FileActivityKind.CREATED || kind == FileActivityKind.MODIFIED
    fun total(first: Int?, second: Int?): Int? =
        if (first == null || second == null) null else (first.toLong() + second).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val added = when {
        !next.isChange() -> previous.addedLines
        !previous.isChange() -> next.addedLines
        else -> total(previous.addedLines, next.addedLines)
    }
    val removed = when {
        !next.isChange() -> previous.removedLines
        !previous.isChange() -> next.removedLines
        else -> total(previous.removedLines, next.removedLines)
    }
    val kind = when {
        previous.kind == FileActivityKind.CREATED || next.kind == FileActivityKind.CREATED -> FileActivityKind.CREATED
        previous.isChange() || next.isChange() -> FileActivityKind.MODIFIED
        previous.kind == FileActivityKind.DOWNLOADED || next.kind == FileActivityKind.DOWNLOADED -> FileActivityKind.DOWNLOADED
        else -> FileActivityKind.DELIVERED
    }
    return previous.copy(kind = kind, addedLines = added, removedLines = removed,
        sizeBytes = next.sizeBytes ?: previous.sizeBytes,
        description = next.description.ifBlank { previous.description })
}
