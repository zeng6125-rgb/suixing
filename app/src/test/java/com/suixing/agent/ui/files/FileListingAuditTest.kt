package com.suixing.agent.ui.files

import androidx.compose.ui.graphics.Color
import com.suixing.agent.tools.humanSize
import java.io.File
import java.nio.file.Files
import java.nio.file.FileSystemException
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileListingAuditTest {
    @get:Rule val temp = TemporaryFolder()

    private data class Row(
        val file: File,
        val name: String,
        val directory: Boolean,
        val size: String,
        val modified: String,
        val icon: Int,
        val tint: Color,
    )

    private fun previousListing(directory: File): List<Row>? {
        val files = directory.listFiles() ?: return null
        val timeFormat = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())
        return files.map { file ->
            val metadata = file.readListingMetadata()
            val (icon, tint) = iconFor(metadata.directory, file.extension)
            val row = Row(
                file,
                file.name,
                metadata.directory,
                if (metadata.directory) "文件夹" else humanSize(metadata.size),
                timeFormat.format(Date(metadata.modified)),
                icon,
                tint,
            )
            row to row.name.lowercase()
        }.sortedWith(compareBy({ !it.first.directory }, { it.second })).map { it.first }
    }

    private fun FileListing.row(entry: FileListingEntry): Row {
        val (icon, tint) = iconFor(entry.isDirectory, entry.file.extension)
        return Row(
            entry.file,
            entry.name,
            entry.isDirectory,
            entry.sizeLabel(),
            entry.modifiedLabel(timeFormat),
            icon,
            tint,
        )
    }

    private fun makeListing(parent: File, files: Int, directories: Int) {
        val suffixes = listOf("TXT", "jpg", "tar", "KT", "mp4", "mp3", "custom", "json")
        for (i in 0 until files) {
            val prefix = when (i % 3) {
                0 -> "Zebra"
                1 -> "alpha"
                else -> "报告"
            }
            File(parent, "$prefix-${files - i}.${suffixes[i % suffixes.size]}").apply {
                if (i % 11 == 0) writeBytes(ByteArray(3600 + i)) else createNewFile()
                setLastModified(1_695_000_000_000L + i * 62_000L)
            }
        }
        for (i in 0 until directories) File(parent, "Folder-${directories - i}").mkdir()
    }

    @Test fun compare_directory_metadata_concurrency() = runBlocking {
        val lanes = listOf(1, 2, 4, null)
        for (size in listOf(48, 600, 2100, 7000)) {
            val root = temp.newFolder("parallel-$size")
            makeListing(root, size, size / 10)
            val expected = checkNotNull(readFileListing(root))
            val expectedRows = expected.entries.map { expected.row(it) }
            val times = lanes.associateWith { ArrayList<Long>() }
            val dispatcher = Dispatchers.IO.limitedParallelism(4)

            for (round in 0 until 10) {
                for (shift in lanes.indices) {
                    val workers = lanes[(shift + round) % lanes.size]
                    val start = System.nanoTime()
                    val listing = if (workers == null) withContext(Dispatchers.IO) {
                        checkNotNull(readFileListing(root))
                    } else withContext(dispatcher) {
                        val files = checkNotNull(root.listFiles())
                        val entries = if (workers == 1) files.map {
                            FileListingEntry(it, it.readListingMetadata())
                        }.toMutableList() else coroutineScope {
                            List(workers) { worker ->
                                async {
                                    val first = files.size * worker / workers
                                    val end = files.size * (worker + 1) / workers
                                    (first until end).map { index ->
                                        val file = files[index]
                                        FileListingEntry(file, file.readListingMetadata())
                                    }
                                }
                            }.awaitAll().flatten().toMutableList()
                        }
                        entries.sortWith(compareBy({ !it.isDirectory }, { it.sortName }))
                        FileListing(entries, SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()))
                    }
                    val first = listing.entries.take(18).map { listing.row(it) }
                    val duration = System.nanoTime() - start
                    assertEquals(expectedRows.take(18), first)
                    if (round == 0) assertEquals(expectedRows, listing.entries.map { listing.row(it) })
                    if (round >= 3) times.getValue(workers).add(duration)
                }
            }
            val result = times.entries.joinToString { (workers, results) ->
                val median = results.sorted()[results.size / 2] / 1_000_000.0
                "workers=${workers ?: "file-page"} median_ms=$median"
            }
            println("entries=${expectedRows.size} $result")
        }
    }

    @Test fun leaving_a_large_directory_cancels_its_remaining_reads() = runBlocking {
        val realFile = temp.newFile("attribute-source")
        val reads = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val blockedFile = object : File(realFile.path) {
            override fun toPath(): Path {
                if (reads.incrementAndGet() == 4) started.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
                return realFile.toPath()
            }
        }
        val largeDirectory = object : File(temp.root.path) {
            override fun listFiles(): Array<File> = Array(700) { blockedFile }
        }
        val listing = async(Dispatchers.IO) { readFileListing(largeDirectory) }
        try {
            withTimeout(5_000) { started.await() }
            listing.cancel()
        } finally {
            release.countDown()
            listing.join()
        }
        assertTrue(listing.isCancelled)
        assertEquals(4, reads.get())
    }

    @Test fun listing_labels_and_order_match_the_current_screen() = runBlocking {
        val root = temp.newFolder("mixed")
        makeListing(root, 630, 27)
        val targetFile = File(root, "standalone.MD").apply { writeText("contents") }
        val targetFolder = File(root, "reference").apply { mkdir() }
        try {
            Files.createSymbolicLink(File(root, "synced.txt").toPath(), targetFile.toPath())
            Files.createSymbolicLink(File(root, "directory-link").toPath(), targetFolder.toPath())
            Files.createSymbolicLink(File(root, "disconnected").toPath(), File(root, "missing-target").toPath())
            println("symbolic_link_fixture=available")
        } catch (error: FileSystemException) {
            println("symbolic_link_fixture=unavailable (${error.reason})")
            if (System.getProperty("os.name").orEmpty().startsWith("Windows")) {
                val junction = File(root, "directory-link")
                if (!junction.exists()) {
                    val create = ProcessBuilder(
                        "cmd.exe", "/d", "/c", "mklink", "/J", junction.path, targetFolder.path,
                    ).redirectErrorStream(true).start()
                    val output = create.inputStream.bufferedReader().use { it.readText() }
                    assertEquals(output, 0, create.waitFor())
                }
                assertEquals(true, junction.readListingMetadata().directory)
                println("directory_junction_fixture=available")
            }
        } catch (_: UnsupportedOperationException) {
            println("symbolic_link_fixture=unavailable (filesystem)")
        }
        val candidate = checkNotNull(readFileListing(root))
        assertEquals(previousListing(root), candidate.entries.map { candidate.row(it) })
        assertEquals(null, readFileListing(targetFile))
        assertEquals(null, readFileListing(File(root, "unavailable")))
        val empty = temp.newFolder("empty")
        assertNotNull(readFileListing(empty))
        assertEquals(emptyList<FileListingEntry>(), readFileListing(empty)?.entries)
    }

    @Test fun directory_listing_workloads() = runBlocking {
        for (files in listOf(48, 7000)) {
            val root = temp.newFolder("load-$files")
            makeListing(root, files, files / 10)
            val previous = ArrayList<Long>()
            val candidate = ArrayList<Long>()
            var viewportLabels = 0
            for (round in 0 until 12) {
                for (old in if (round % 2 == 0) listOf(true, false) else listOf(false, true)) {
                    val start = System.nanoTime()
                    val first = if (old) checkNotNull(previousListing(root)).take(18) else {
                        val listing = checkNotNull(readFileListing(root))
                        listing.entries.take(18).map { listing.row(it) }
                    }
                    viewportLabels += first.sumOf { it.modified.length + it.size.length }
                    if (round >= 4) (if (old) previous else candidate).add(System.nanoTime() - start)
                }
            }
            check(viewportLabels > 0)
            val listing = checkNotNull(readFileListing(root))
            val projection = (0 until 200).map { index ->
                val start = System.nanoTime()
                val offset = index * 17 % (listing.entries.size - 18)
                val rows = listing.entries.subList(offset, offset + 18).map { listing.row(it) }
                check(rows.size == 18)
                System.nanoTime() - start
            }
            fun List<Long>.medianMs() = sorted()[size / 2] / 1_000_000.0
            println(
                "entries=${listing.entries.size} first18_previous_ms=${previous.medianMs()} " +
                    "first18_candidate_ms=${candidate.medianMs()} next18_candidate_ms=${projection.medianMs()}",
            )
        }
    }
}
