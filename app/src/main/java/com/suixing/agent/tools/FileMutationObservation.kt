package com.suixing.agent.tools

import com.suixing.agent.data.FileActivity
import com.suixing.agent.data.FileActivityKind
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

internal data class ObservedFileChanges(val activities: List<FileActivity>, val notice: String = "") {
    fun attach(result: ToolResult): ToolResult = result.copy(
        fileActivities = result.fileActivities + activities.filter { candidate -> result.fileActivities.none { it.path == candidate.path } },
        output = result.output + notice.takeIf { it.isNotEmpty() }?.let { "\n[$it]" }.orEmpty(),
    )
}

internal class ObservedFileCancellation(val changes: ObservedFileChanges, cause: kotlinx.coroutines.CancellationException) :
    kotlinx.coroutines.CancellationException(cause.message) {
    init { initCause(cause) }
}

/** Record before crossing a cancelled dispatcher boundary, which can discard thrown results. */
internal class FileCancellationSink(val record: (ObservedFileChanges) -> Unit) :
    kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<FileCancellationSink>
}

internal suspend fun observeFileChanges(roots: List<File>, block: suspend () -> ToolResult): ToolResult {
    val observation = FileMutationObservation.begin(roots)
    val result = try { block() } catch (cancelled: kotlinx.coroutines.CancellationException) {
        // Shell collection has stopped the owned process before returning cancellation.
        // The loop may persist these independently verified mutations while still publishing cancellation.
        val changes = observation.finish()
        if (changes.activities.isNotEmpty()) {
            val sink = kotlinx.coroutines.currentCoroutineContext()[FileCancellationSink]
            if (sink != null) sink.record(changes) else throw ObservedFileCancellation(changes, cancelled)
        }
        throw cancelled
    } catch (error: Exception) {
        ToolResult.failure("工具执行失败：${error.message ?: error.javaClass.simpleName}")
    } catch (error: Throwable) {
        observation.abandon()
        throw error
    }
    return observation.finish().attach(result)
}

/** Bounded filesystem evidence, never command output. Contents are transient and never persisted. */
internal class FileMutationObservation private constructor(
    private val roots: List<File>, private val maxEntries: Int,
) {
    private data class State(val size: Long, val digest: String?, val text: String?, val identity: String?)
    private data class Snapshot(val files: Map<String, State>, val completeRoots: Set<String>, val limited: Boolean)
    private val id = UUID.randomUUID().toString()
    @Volatile private var overlapping = false
    private var before: Snapshot = Snapshot(emptyMap(), emptySet(), true)

    private var finished: ObservedFileChanges? = null
    fun abandon() { synchronized(active) { active.remove(id) } }
    @Synchronized fun finish(): ObservedFileChanges {
        finished?.let { return it }
        val after = try { snapshot() } catch (_: Exception) { null }
            catch (error: Throwable) { abandon(); throw error }
        return synchronized(active) {
            val result = when {
                after == null -> ObservedFileChanges(emptyList(), "文件变更统计未知：无法完成文件观测")
                overlapping -> ObservedFileChanges(emptyList(), "文件变更归属未知：观测期间有其它文件操作")
                else -> changes(before, after)
            }
            active.remove(id)
            finished = result
            result
        }
    }

    private fun changes(old: Snapshot, new: Snapshot): ObservedFileChanges {
        val activities = ArrayList<FileActivity>()
        fun complete(path: String, snapshot: Snapshot) = roots.any { root ->
            root.path in snapshot.completeRoots && (path == root.path || path.startsWith(root.path + File.separator))
        }
        for (path in old.files.keys + new.files.keys) {
            val first = old.files[path]
            val last = new.files[path]
            val kind = when {
                first == null && last != null && complete(path, old) -> FileActivityKind.CREATED
                first != null && last == null && complete(path, new) -> FileActivityKind.DELETED
                first != null && last != null && (first.size != last.size ||
                    (first.digest != null && last.digest != null && first.digest != last.digest)) -> FileActivityKind.MODIFIED
                else -> null
            } ?: continue
            val counts = activityLineCounts(if (first == null) "" else first.text, if (last == null) "" else last.text)
            activities += FileActivity(path, kind, counts?.first, counts?.second, last?.size)
        }
        // Only a unique stable identity AND equal content proves a rename. Copy/delete remains two events.
        val deleted = activities.filter { it.kind == FileActivityKind.DELETED }
        val created = activities.filter { it.kind == FileActivityKind.CREATED }
        for (source in deleted) {
            val first = old.files.getValue(source.path)
            if (first.identity == null || first.digest == null) continue
            val candidates = created.filter { target -> new.files.getValue(target.path).let {
                it.identity == first.identity && it.digest == first.digest
            } }
            if (candidates.size != 1 || deleted.count { old.files.getValue(it.path).identity == first.identity } != 1) continue
            val target = candidates.single()
            if (source !in activities || target !in activities) continue
            activities.remove(source)
            activities.remove(target)
            activities += target.copy(kind = FileActivityKind.MOVED, previousPath = source.path, addedLines = null, removedLines = null)
        }
        return ObservedFileChanges(activities, if (old.limited || new.limited)
            "文件变更统计不完整：部分路径、内容或扫描范围无法验证" else "")
    }

    private fun snapshot(): Snapshot {
        val files = LinkedHashMap<String, State>()
        val visited = HashMap<String, Boolean>()
        val complete = HashSet<String>()
        var entries = 0
        var remainingHash = 16L * 1024 * 1024
        var remainingText = 2 * 1024 * 1024
        var limited = false
        val deadline = System.nanoTime() + 300_000_000L
        fun scan(file: File, depth: Int): Boolean {
            visited[file.path]?.let { return it }
            if (++entries > maxEntries || depth > 12 || System.nanoTime() > deadline) { limited = true; return false }
            val scanned = try {
                val path = file.toPath()
                val attrs = try { Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }
                    catch (_: NoSuchFileException) { return true }
                if (attrs.isSymbolicLink) { limited = true; return false }
                if (attrs.isDirectory) {
                    Files.newDirectoryStream(path).use { children ->
                        var all = true
                        for (child in children) {
                            if (entries >= maxEntries || System.nanoTime() > deadline) { limited = true; all = false; break }
                            if (!scan(child.toFile(), depth + 1)) all = false
                        }
                        all
                    }
                } else if (attrs.isRegularFile) {
                    var digest: String? = null
                    var text: String? = null
                    if (attrs.size() <= 4L * 1024 * 1024 && attrs.size() <= remainingHash) {
                        val hash = MessageDigest.getInstance("SHA-256")
                        val bytes = if (attrs.size() <= 128 * 1024 && attrs.size() <= remainingText)
                            java.io.ByteArrayOutputStream(attrs.size().toInt()) else null
                        var readTotal = 0L
                        file.inputStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                readTotal += count
                                if (readTotal > attrs.size() || readTotal > remainingHash || System.nanoTime() > deadline)
                                    throw java.io.IOException("file changed or observation budget reached")
                                hash.update(buffer, 0, count)
                                bytes?.write(buffer, 0, count)
                            }
                        }
                        remainingHash -= readTotal
                        val end = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        if (readTotal == attrs.size() && end.size() == attrs.size() && end.lastModifiedTime() == attrs.lastModifiedTime() && end.fileKey() == attrs.fileKey()) {
                            digest = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                            if (bytes != null) {
                                remainingText -= bytes.size()
                                text = runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
                                    .takeUnless { '\u0000' in it } }.getOrNull()
                            }
                        } else limited = true
                    } else limited = true
                    files[file.path] = State(attrs.size(), digest, text, attrs.fileKey()?.toString())
                    true
                } else true
            } catch (_: Exception) { limited = true; false }
            visited[file.path] = scanned
            return scanned
        }
        for (root in roots) if (scan(root, 0)) complete += root.path
        return Snapshot(files, complete, limited)
    }

    companion object {
        private val active = LinkedHashMap<String, FileMutationObservation>()
        /** Native file tools may run in another session while a shell observation is open. */
        fun noteIndependentMutation(file: File) {
            val path = runCatching { file.canonicalPath }.getOrNull() ?: return
            synchronized(active) { active.values.forEach { observation ->
                if (observation.roots.any { path == it.path || path.startsWith(it.path + File.separator) }) observation.overlapping = true
            } }
        }
        fun begin(roots: List<File>, maxEntries: Int = 512): FileMutationObservation {
            val requested = roots.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }.distinctBy { it.path }
            val canonical = requested
                .filterNot { it.path in setOf("/", "/sdcard", "/storage", "/storage/emulated", "/storage/emulated/0") }
                .sortedBy { it.isDirectory }
                .take(12)
            val observation = FileMutationObservation(canonical, maxEntries)
            synchronized(active) {
                active.values.forEach { other ->
                    if (canonical.any { one -> other.roots.any { two ->
                        one.path == two.path || one.path.startsWith(two.path + File.separator) || two.path.startsWith(one.path + File.separator)
                    } }) { observation.overlapping = true; other.overlapping = true }
                }
                active[observation.id] = observation
            }
            try {
                observation.before = observation.snapshot().let { snapshot ->
                    if (canonical.isEmpty() || requested.size != canonical.size) snapshot.copy(limited = true) else snapshot
                }
            } catch (error: Throwable) { observation.abandon(); throw error }
            return observation
        }
    }
}

/** Literals select observation scope only; they never count as evidence that a file changed. */
internal fun fileObservationRoots(workspace: File?, command: String, explicit: List<File> = emptyList(), cwd: File? = null): List<File> {
    val literals = Regex("(?:\"([^\"]+)\"|'([^']+)'|([^\\s;|&<>]+))").findAll(command.take(64_000)).mapNotNull { match ->
        val word = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty()
        word.takeIf { it.startsWith('/') && '$' !in it && '`' !in it && '*' !in it && '?' !in it && !it.startsWith("/proc/") && !it.startsWith("/sys/") && !it.startsWith("/dev/") }
            ?.let(::File)
    }.take(8).toList()
    val actualCwd = cwd ?: runCatching { File(".").canonicalFile }.getOrNull()
    val targets = (explicit.take(8) + literals).sortedByDescending { it.path.length }
    return targets + listOfNotNull(workspace, actualCwd?.takeUnless { it.absolutePath == "/" })
}
