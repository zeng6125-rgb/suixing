package com.suixing.agent.data

import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JobFileActivityTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `failed job reports verified output once only to its originating session`() = runBlocking {
        val workspace = folder.newFolder("workspace")
        val target = File(workspace, "partial-result.txt")
        val process = mockk<Process> {
            every { waitFor() } returns 1
            every { inputStream } returns ByteArrayInputStream(byteArrayOf())
            every { errorStream } returns ByteArrayInputStream(byteArrayOf())
            every { outputStream } returns ByteArrayOutputStream()
        }
        val store = JobStore(folder.newFolder("logs"), this,
            startProcess = { _, _ -> target.writeText("written before a later failure\n"); process },
            observationRoots = { listOf(workspace) })
        val job = store.start("arbitrary script", "test", ownerChatId = "child-session")
        withTimeout(5000) { while (store.find(job.id)?.exitCode == null) delay(10) }
        assertEquals(1, store.find(job.id)?.exitCode)
        assertTrue(store.takeFileChanges(listOf(job.id), "parent-session").activities.isEmpty())
        val changes = store.takeFileChanges(listOf(job.id), "child-session").activities
        assertEquals(target.canonicalPath, changes.single().path)
        assertEquals(FileActivityKind.CREATED, changes.single().kind)
        assertTrue(store.takeFileChanges(listOf(job.id), "child-session").activities.isEmpty())
    }
}
