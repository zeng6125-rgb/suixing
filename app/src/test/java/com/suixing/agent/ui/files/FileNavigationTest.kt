package com.suixing.agent.ui.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileNavigationTest {
    @Test fun nestedBackStopsAtSelectedFirstLevel() {
        val root = File("workspace").absoluteFile
        assertEquals(File(root, "one"), parentFileDirectory(root, File(root, "one/two")))
        assertEquals(root, parentFileDirectory(root, File(root, "one")))
        assertNull(parentFileDirectory(root, root))
    }

    @Test fun eachRootDefinesItsOwnExitBoundary() {
        for (name in listOf("workspace", "storage", "plugins")) {
            val root = File(name).absoluteFile
            assertEquals(root, parentFileDirectory(root, File(root, "folder")))
            assertNull(parentFileDirectory(root, root))
        }
    }

    @Test fun siblingPrefixAndOutsidePathsCannotBecomeParents() {
        val root = File("workspace").absoluteFile
        assertNull(parentFileDirectory(root, File(root.parentFile, "workspace-other/child")))
        assertNull(parentFileDirectory(root, File(root, "../outside")))
        assertNull(parentFileDirectory(root, File(root, "folder/..")))
    }
}
