package com.suixing.agent.terminal

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class LinuxArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private data class Entry(val name: String, val text: String = "", val type: Char = '0', val link: String = "",
        val gnu: Boolean = false)
    private fun archive(vararg entries: Entry): ByteArray {
        val output = ByteArrayOutputStream()
        for (entry in entries) {
            val data = entry.text.toByteArray()
            val header = ByteArray(512)
            fun field(offset: Int, length: Int, text: String) {
                val bytes = text.toByteArray(Charsets.US_ASCII)
                bytes.copyInto(header, offset, 0, minOf(length, bytes.size))
            }
            fun octal(offset: Int, length: Int, number: Long) =
                field(offset, length, number.toString(8).padStart(length - 1, '0') + "\u0000")
            field(0, 100, entry.name)
            octal(100, 8, 420)
            octal(124, 12, data.size.toLong())
            header[156] = entry.type.code.toByte()
            field(157, 100, entry.link)
            field(257, 6, if (entry.gnu) "ustar " else "ustar")
            if (entry.gnu) field(345, 12, "12345670123")
            for (i in 148..155) header[i] = 32
            octal(148, 8, header.sumOf { it.toLong() and 255 })
            output.write(header); output.write(data)
            output.write(ByteArray((512 - data.size % 512) % 512))
        }
        output.write(ByteArray(1024))
        return output.toByteArray()
    }

    @Test fun extractsRootfsDataAndStripsOnlyTheDeclaredPrefix() {
        val root = temporary.newFolder("rootfs").toPath()
        LinuxArchive(root, "debian").extract(archive(
            Entry("debian", type = '5'), Entry("debian/etc/config", "中文 config"),
            Entry("debian/dev/null", type = '3'),
        ).inputStream())
        assertEquals("中文 config", Files.readString(root.resolve("etc/config")))
        assertFalse(Files.exists(root.resolve("dev/null")))
    }
    @Test fun traversalCannotOverwriteAnExistingFileOutsideRootfs() {
        val root = temporary.newFolder("rootfs").toPath()
        val outside = root.parent.resolve("keep.txt")
        Files.writeString(outside, "keep")
        assertThrows(IllegalArgumentException::class.java) {
            LinuxArchive(root).extract(archive(Entry("../keep.txt", "replaced")).inputStream())
        }
        assertEquals("keep", Files.readString(outside))
    }
    @Test fun escapingSymbolicLinkIsRejectedBeforeCreatingAnyLink() {
        val root = temporary.newFolder("rootfs").toPath()
        assertThrows(IllegalArgumentException::class.java) {
            LinuxArchive(root).extract(archive(Entry("escape", type = '2', link = "../../outside")).inputStream())
        }
        assertFalse(Files.exists(root.resolve("escape")))
    }
    @Test fun hardLinksOnlyReferenceARegularFileInsideTheStagingRoot() {
        val root = temporary.newFolder("rootfs").toPath()
        LinuxArchive(root).extract(archive(Entry("original", "payload"),
            Entry("linked", type = '1', link = "original")).inputStream())
        assertTrue(Files.isSameFile(root.resolve("original"), root.resolve("linked")))
    }
    @Test fun invalidHeaderAndOversizedArchiveDoNotGetExtracted() {
        val root = temporary.newFolder("rootfs").toPath()
        val damaged = archive(Entry("file", "data")).also { it[0] = 42 }
        assertThrows(IllegalArgumentException::class.java) { LinuxArchive(root).extract(damaged.inputStream()) }
        assertThrows(IllegalArgumentException::class.java) {
            LinuxArchive(root, maxBytes = 3).extract(archive(Entry("file", "data")).inputStream())
        }
        assertFalse(Files.exists(root.resolve("file")))
    }
    @Test fun gnuTimestampsAreNotTreatedAsDirectoryPrefixes() {
        val root = temporary.newFolder("rootfs").toPath()
        LinuxArchive(root).extract(archive(Entry("etc/config", "payload", gnu = true)).inputStream())
        assertEquals("payload", Files.readString(root.resolve("etc/config")))
    }
    @Test fun consecutiveGnuLongNameAndLongLinkHeadersPreserveBothValues() {
        val root = temporary.newFolder("rootfs").toPath()
        LinuxArchive(root).extract(archive(Entry("original", "payload"),
            Entry("././@LongLink", "nested/linked\u0000", type = 'L'),
            Entry("././@LongLink", "original\u0000", type = 'K'),
            Entry("short", type = '1', link = "missing")).inputStream())
        assertTrue(Files.isSameFile(root.resolve("original"), root.resolve("nested/linked")))
    }
}
