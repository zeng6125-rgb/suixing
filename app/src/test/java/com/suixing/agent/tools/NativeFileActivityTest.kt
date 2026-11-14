package com.suixing.agent.tools

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeFileActivityTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun nativeUnknownCountsAreUsedWithoutRescanningTheEditedText() = runBlocking {
        editWithNativeResult("old\n" + "same\n".repeat(4_000), "old", "new", null)
    }

    @Test fun nativeKnownAndZeroCountsAreUsedWithoutRescanningTheEditedText() = runBlocking {
        editWithNativeResult("start\r\nold\r\nend\r\n", "old", "new\r\nmore", 2 to 1)
        editWithNativeResult("old\n" + "same\n".repeat(4_000), "old", "old", 0 to 0)
    }

    private suspend fun editWithNativeResult(before: String, old: String, replacement: String, counts: Pair<Int, Int>?) {
        val context = mockk<Context>()
        every { context.getExternalFilesDir(null) } returns folder.root
        every { context.filesDir } returns folder.root
        val after = before.replaceFirst(old, replacement)
        val target = File(folder.root, "native.txt").apply { writeText(before) }
        val native = NativeFileEditResult(1, listOf(1), after, false, revisionOf(after), counts)
        mockkObject(NativeFileEdits)
        mockkStatic("com.suixing.agent.tools.FileToolsKt")
        coEvery { NativeFileEdits.apply(any(), any(), any(), any(), any()) } returns native
        every { activityLineCounts(any(), any()) } throws AssertionError("native edits must not rescan activity lines in Kotlin")
        try {
            val result = FileTools(context).execute("edit_file", JsonObject(mapOf(
                "path" to JsonPrimitive("native.txt"),
                "old_string" to JsonPrimitive(old),
                "new_string" to JsonPrimitive(replacement),
            )))
            assertTrue(result.output, result.ok)
            assertEquals(after, target.readText())
            val activity = result.fileActivities.single()
            assertEquals(counts?.first, activity.addedLines)
            assertEquals(counts?.second, activity.removedLines)
        } finally {
            unmockkStatic("com.suixing.agent.tools.FileToolsKt")
            unmockkObject(NativeFileEdits)
        }
    }
}
