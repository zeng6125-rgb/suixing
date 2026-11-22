package com.suixing.agent.ui.chat

import java.io.File
import java.util.PriorityQueue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class FileReference(val start: Int, val end: Int, val path: String)
internal data class FileReferenceQuery(val start: Int, val end: Int, val query: String)
internal data class FileReferenceSuggestion(val path: String, val name: String, val directory: Boolean)

private data class FileReferenceCandidate(
    val file: File,
    val name: String,
    val directory: Boolean,
    val sortName: String,
    val order: Int,
)

/** Quotes keep spaces and punctuation inside one reference; the draft retains the full path. */
internal fun fileReferenceToken(path: String): String =
    "@\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

internal fun appendFileReference(text: String, path: String): String =
    text + (if (text.isNotEmpty() && !text.last().isWhitespace()) " " else "") +
        fileReferenceToken(path) + " "

private fun referenceStart(text: String, index: Int): Boolean {
    if (text[index] != '@') return false
    if (index == 0) return true
    val previous = text[index - 1]
    return previous !in 'a'..'z' && previous !in 'A'..'Z' && previous !in '0'..'9' &&
        previous != '_' && previous != '.' && previous != '-'
}

private data class ReferenceToken(val end: Int, val path: String, val closed: Boolean)

private fun referenceToken(text: String, start: Int): ReferenceToken {
    val quoted = text.getOrNull(start + 1) == '"'
    var index = start + if (quoted) 2 else 1
    val path = StringBuilder()
    while (index < text.length) {
        val char = text[index]
        if (quoted && char == '"') return ReferenceToken(index + 1, path.toString(), true)
        if (!quoted && char.isWhitespace()) break
        val next = text.getOrNull(index + 1)
        if (quoted && char == '\\' && (next == '\\' || next == '"')) {
            index++
        }
        path.append(text[index++])
    }
    return ReferenceToken(index, path.toString(), !quoted)
}

private fun absoluteReferencePath(path: String): Boolean = path.startsWith("/") ||
    (path.length > 2 && path[1] == ':' && (path[2] == '/' || path[2] == '\\'))

internal fun fileReferences(text: String): List<FileReference> {
    val references = ArrayList<FileReference>()
    var index = 0
    while (index < text.length) {
        if (!referenceStart(text, index)) {
            index++
            continue
        }
        val token = referenceToken(text, index)
        if (token.closed && absoluteReferencePath(token.path)) {
            references += FileReference(index, token.end, token.path)
        }
        index = token.end.coerceAtLeast(index + 1)
    }
    return references
}

/** Only the token containing the caret is completed; email addresses are ordinary text. */
internal fun activeFileReference(text: String, cursor: Int): FileReferenceQuery? {
    if (cursor !in 0..text.length) return null
    var index = 0
    while (index < text.length) {
        if (!referenceStart(text, index)) {
            index++
            continue
        }
        val token = referenceToken(text, index)
        val quoted = text.getOrNull(index + 1) == '"'
        if (cursor > index && cursor <= token.end &&
            !(quoted && token.closed && cursor == token.end)
        ) {
            val prefix = referenceToken(text.substring(0, cursor), index).path
            return FileReferenceQuery(index, token.end, prefix)
        }
        index = token.end.coerceAtLeast(index + 1)
    }
    return null
}

internal fun replaceFileReference(
    text: String,
    query: FileReferenceQuery,
    suggestion: FileReferenceSuggestion,
): Pair<String, Int> {
    val token = if (suggestion.directory) {
        // Keep the quote open while browsing the next directory's candidates.
        fileReferenceToken(suggestion.path.trimEnd('/', '\\') + "/").dropLast(1)
    } else fileReferenceToken(suggestion.path) + " "
    return text.replaceRange(query.start, query.end, token) to (query.start + token.length)
}

internal fun fileReferenceLabel(path: String, maxCodePoints: Int = 32): String {
    val directory = path.endsWith("/")
    val name = File(path.trimEnd('/', '\\')).name.ifEmpty { path }
    val label = "@" + name + if (directory) "/" else ""
    if (label.codePointCount(0, label.length) <= maxCodePoints) return label
    return label.substring(0, label.offsetByCodePoints(0, maxCodePoints - 1)) + "…"
}

/** Browse only the queried directory, on the caller's IO dispatcher, without a whole-disk scan. */
internal suspend fun fileReferenceSuggestions(root: File, query: String): List<FileReferenceSuggestion> {
    val slash = query.lastIndexOf('/')
    val directoryPath = if (slash >= 0) query.substring(0, slash + 1) else ""
    val name = if (slash >= 0) query.substring(slash + 1) else query
    val directory = when {
        directoryPath.isEmpty() -> root
        absoluteReferencePath(directoryPath) -> File(directoryPath)
        else -> File(root, directoryPath)
    }
    val ranking = compareBy<FileReferenceCandidate>({ !it.directory }, { it.sortName }, { it.order })
    val best = PriorityQueue(24, ranking.reversed())
    val context = currentCoroutineContext()
    for ((order, file) in directory.listFiles().orEmpty().withIndex()) {
        context.ensureActive()
        val fileName = file.name
        if (!fileName.contains(name, ignoreCase = true)) continue
        val sortName = fileName.lowercase()
        val last = if (best.size == 24) best.element() else null
        // With 24 directories selected, a later name cannot enter the result as either kind.
        if (last?.directory == true && sortName >= last.sortName) continue
        val isDirectory = file.isDirectory
        if (last != null) {
            val comparison = if (isDirectory != last.directory) {
                if (isDirectory) -1 else 1
            } else sortName.compareTo(last.sortName)
            if (comparison >= 0) continue
            best.remove()
        }
        best.add(FileReferenceCandidate(file, fileName, isDirectory, sortName, order))
    }
    return best.sortedWith(ranking).map { FileReferenceSuggestion(it.file.absolutePath, it.name, it.directory) }
}

internal fun Draft.withFileReference(path: String): Draft =
    copy(text = appendFileReference(text, path))
