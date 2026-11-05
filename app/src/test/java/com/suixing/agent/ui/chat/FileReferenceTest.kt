package com.suixing.agent.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileReferenceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun draftAppendsQuotedReferenceWithoutReplacingTextOrAttachments() {
        val draft = Draft("请分析这份资料", "content://attachment", "报告.pdf", listOf("photo"))
        val path = "/sdcard/研究资料/报告 final.txt"
        val referenced = draft.withFileReference(path)
        assertTrue(referenced.text.startsWith(draft.text + " @"))
        assertEquals(listOf(path), fileReferences(referenced.text).map { it.path })
        assertEquals(draft.attachmentUri, referenced.attachmentUri)
        assertEquals(draft.attachmentName, referenced.attachmentName)
        assertEquals(draft.imageNames, referenced.imageNames)
    }

    @Test fun quotedPathsRoundTripSpacesQuotesBackslashesAndUnicode() {
        val paths = listOf("/data/中文 名字.txt", "/sdcard/a\"b.txt", "/sdcard/a\\b.txt")
        val text = paths.fold("对比") { draft, path -> appendFileReference(draft, path) }
        assertEquals(paths, fileReferences(text).map { it.path })
        assertEquals(emptyList<FileReference>(), fileReferences("mail@example.com @someone"))
    }

    @Test fun completionUsesCaretTokenAndLeavesSurroundingTextIntact() {
        val text = "请看 @rep 之后解释"
        val query = requireNotNull(activeFileReference(text, 7))
        assertEquals("rep", query.query)
        val picked = FileReferenceSuggestion("/workspace/report.txt", "report.txt", false)
        val (result, cursor) = replaceFileReference(text, query, picked)
        assertTrue(result.startsWith("请看 " + fileReferenceToken(picked.path)))
        assertTrue(result.endsWith(" 之后解释"))
        assertEquals(" ", result.substring(cursor - 1, cursor))
        assertNull(activeFileReference(result, cursor))
        assertNull(activeFileReference("mail@example.com", 16))
    }

    @Test fun incompleteQuotedDirectoryCanBeBrowsedWithoutLosingSpaces() {
        val first = requireNotNull(activeFileReference("文件 @", 4))
        val directory = FileReferenceSuggestion("/workspace/my folder", "my folder", true)
        val (text, cursor) = replaceFileReference("文件 @", first, directory)
        val nested = requireNotNull(activeFileReference(text, cursor))
        assertEquals("/workspace/my folder/", nested.query)
        val file = FileReferenceSuggestion("/workspace/my folder/test.md", "test.md", false)
        val (completed, end) = replaceFileReference(text, nested, file)
        assertEquals(listOf(file.path), fileReferences(completed).map { it.path })
        assertNull(activeFileReference(completed, end))
    }

    @Test fun directorySuggestionsFilterLocallyAndExposeFoldersForDrilling() {
        val root = temporary.newFolder("workspace")
        val nested = File(root, "reports").apply { mkdir() }
        File(root, "top.txt").writeText("top")
        File(nested, "daily report.txt").writeText("report")
        val initial = fileReferenceSuggestions(root, "")
        assertTrue(initial.first().directory)
        assertEquals(setOf("reports", "top.txt"), initial.map { it.name }.toSet())
        assertEquals(listOf("daily report.txt"), fileReferenceSuggestions(root, "reports/daily").map { it.name })
        assertFalse(initial.any { it.name == "daily report.txt" })
    }

    @Test fun visualLabelsKeepFullPathEndpointsAndMonotonicSelectionMappings() {
        val path = "/data/user/0/app/files/a very long 中文 😀 report name.txt"
        val prefix = "分析 "
        val token = fileReferenceToken(path)
        val original = prefix + token + " 后回答"
        val transformed = FileReferenceTransformation(Color.Green).filter(AnnotatedString(original))
        assertEquals(prefix + fileReferenceLabel(path) + " 后回答", transformed.text.text)
        val offsets = transformed.offsetMapping
        assertEquals(prefix.length, offsets.originalToTransformed(prefix.length))
        val labelEnd = prefix.length + fileReferenceLabel(path).length
        assertEquals(labelEnd, offsets.originalToTransformed(prefix.length + token.length))
        assertEquals(prefix.length + token.length, offsets.transformedToOriginal(labelEnd))
        assertEquals(original.length, offsets.transformedToOriginal(transformed.text.length))
        val forward = (0..original.length).map(offsets::originalToTransformed)
        val backward = (0..transformed.text.length).map(offsets::transformedToOriginal)
        assertTrue(forward.zipWithNext().all { (a, b) -> a <= b })
        assertTrue(backward.zipWithNext().all { (a, b) -> a <= b })
        assertTrue(forward.all { it in 0..transformed.text.length })
        assertTrue(backward.all { it in 0..original.length })
    }

    @Test fun longLabelsUseEllipsisWithoutSplittingEmojiAndPlainTextRemainsUnchanged() {
        val label = fileReferenceLabel("/workspace/" + "😀".repeat(40) + ".txt")
        assertEquals(32, label.codePointCount(0, label.length))
        assertTrue(label.endsWith("…"))
        assertFalse(label.dropLast(1).last().isHighSurrogate())
        val input = "hello 你好\n/goal finish"
        val transformed = FileReferenceTransformation(Color.Blue).filter(AnnotatedString(input))
        assertEquals(input, transformed.text.text)
        for (offset in 0..input.length) {
            assertEquals(offset, transformed.offsetMapping.originalToTransformed(offset))
            assertEquals(offset, transformed.offsetMapping.transformedToOriginal(offset))
        }
    }

    @Test fun slashCommandTintAndMultipleReferencesKeepTheirTextBoundaries() {
        val input = "/read_file " + fileReferenceToken("/workspace/a.txt") + " " +
            fileReferenceToken("/workspace/b.txt")
        val transformed = FileReferenceTransformation(Color.Green, 10).filter(AnnotatedString(input))
        assertEquals("/read_file @a.txt @b.txt", transformed.text.text)
        assertTrue(transformed.text.spanStyles.any { it.start == 0 && it.end == 10 })
        assertEquals(input.length, transformed.offsetMapping.transformedToOriginal(transformed.text.length))
    }
}
