package com.suixing.agent.ui.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileListingMetadataTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `files and directories retain their displayed metadata`() {
        val file = temp.newFile("example.txt").apply { writeText("test") }
        val metadata = file.readListingMetadata()
        assertFalse(metadata.directory)
        assertEquals(file.length(), metadata.size)
        assertEquals(file.lastModified(), metadata.modified)

        val directory = temp.newFolder("folder")
        val folderMetadata = directory.readListingMetadata()
        assertTrue(folderMetadata.directory)
        assertEquals(directory.lastModified(), folderMetadata.modified)
    }

    @Test fun `a deleted entry retains the original empty metadata fallback`() {
        val gone = File(temp.root, "gone")
        assertEquals(FileListingMetadata(false, 0L, 0L), gone.readListingMetadata())
    }
}
