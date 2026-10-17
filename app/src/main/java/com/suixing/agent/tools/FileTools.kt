package com.suixing.agent.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File access for the agent. Relative paths land in the app's external files
 * directory, which needs no runtime permission; absolute paths are used as
 * given, so `/sdcard/...` starts working once all-files access is granted.
 */
class FileTools(private val context: Context) : ToolGroup() {

    private val workspace: File get() = context.getExternalFilesDir(null) ?: context.filesDir

    override val group: String = "文件"

    override val specs: List<ToolSpec> = listOf(
        ToolSpec(
            "read_file",
            "Read a text file (optionally a line range). Relative paths resolve against the working " +
                "directory; absolute paths are used as given.",
            Schema.objectOf(
                properties = listOf(
                    "path" to Schema.string("File path, e.g. note.txt or /sdcard/Download/a.md"),
                    "offset" to Schema.integer("First line to read, default 1", default = 1, minimum = 1),
                    "limit" to Schema.integer(
                        "Maximum lines to read, default 400",
                        default = 400,
                        minimum = 1,
                        maximum = 5000,
                    ),
                ),
                required = listOf("path"),
            ),
        ),
        ToolSpec(
            "write_file",
            "Write a text file (parent directories are created), optionally appending.",
            Schema.objectOf(
                properties = listOf(
                    "path" to Schema.string("File path"),
                    "content" to Schema.string("Text to write"),
                    "append" to Schema.boolean("Append to the end of the file instead of overwriting", default = false),
                ),
                required = listOf("path", "content"),
            ),
        ),
        ToolSpec(
            "edit_file",
            "Replace a span of a text file in place: the reliable way to change part of a file without " +
                "rewriting all of it. old_string must match the file character for character (spaces and " +
                "newlines included) and must be unique — when it occurs more than once the edit is refused " +
                "and the matching lines are reported, so nothing is changed blindly. Pass replace_all=true " +
                "to replace every occurrence.",
            Schema.objectOf(
                properties = listOf(
                    "path" to Schema.string("File path"),
                    "old_string" to Schema.string("Exact text to replace; include surrounding lines to make it unique"),
                    "new_string" to Schema.string("Replacement text"),
                    "replace_all" to Schema.boolean("Replace every occurrence, default false", default = false),
                ),
                required = listOf("path", "old_string", "new_string"),
            ),
        ),
        ToolSpec(
            "list_dir",
            "List a directory (directories first, with sizes).",
            Schema.objectOf(
                properties = listOf(
                    "path" to Schema.string("Directory path, default the working directory", default = "~"),
                    "limit" to Schema.integer("Maximum entries, default 200", default = 200, minimum = 1, maximum = 1000),
                ),
            ),
        ),
        ToolSpec(
            "make_dir",
            "Create a directory, including any missing parents.",
            Schema.objectOf(
                properties = listOf("path" to Schema.string("Directory path to create")),
                required = listOf("path"),
            ),
        ),
        ToolSpec(
            "stat_path",
            "Inspect a path: whether it exists, its type, size, modification time and read/write access.",
            Schema.objectOf(
                properties = listOf("path" to Schema.string("File or directory path")),
                required = listOf("path"),
            ),
        ),
        ToolSpec(
            "move_path",
            "Move or rename a file or directory.",
            Schema.objectOf(
                properties = listOf(
                    "from" to Schema.string("Source path"),
                    "to" to Schema.string("Destination path"),
                ),
                required = listOf("from", "to"),
            ),
        ),
        ToolSpec(
            "delete_path",
            "Delete a file or directory. Directories are not recursive by default: a non-empty " +
                "directory is refused, and deleting the whole tree needs recursive=true. This cannot be undone.",
            Schema.objectOf(
                properties = listOf(
                    "path" to Schema.string("Path to delete"),
                    "recursive" to Schema.boolean("Delete a directory tree recursively, default false", default = false),
                ),
                required = listOf("path"),
            ),
        ),
        ToolSpec(
            "find_files",
            "Find files by name anywhere under a directory tree.",
            Schema.objectOf(
                properties = listOf(
                    "root" to Schema.string("Starting directory, default the working directory", default = "~"),
                    "name" to Schema.string("Case-insensitive substring of the file name"),
                    "max_depth" to Schema.integer("Maximum search depth, default 8", default = 8, minimum = 1, maximum = 30),
                    "limit" to Schema.integer("Maximum results, default 60", default = 60, minimum = 1, maximum = 500),
                ),
                required = listOf("name"),
            ),
        ),
        ToolSpec(
            "search_in_files",
            "Search the text files under a directory and return matches as 「path:line: text」.",
            Schema.objectOf(
                properties = listOf(
                    "root" to Schema.string("Starting directory, default the working directory", default = "~"),
                    "query" to Schema.string("Text or regular expression to search for"),
                    "regex" to Schema.boolean("Treat query as a regular expression, default false", default = false),
                    "max_files" to Schema.integer(
                        "Maximum files to scan, default 400",
                        default = 400,
                        minimum = 1,
                        maximum = 5000,
                    ),
                    "limit" to Schema.integer("Maximum matches, default 60", default = 60, minimum = 1, maximum = 500),
                ),
                required = listOf("query"),
            ),
        ),
    )

    override suspend fun execute(name: String, args: JsonObject): ToolResult = when (name) {
        "read_file" -> readFile(args)
        "write_file" -> writeFile(args)
        "edit_file" -> editFile(args)
        "list_dir" -> listDir(args)
        "make_dir" -> makeDir(args)
        "stat_path" -> statPath(args)
        "move_path" -> movePath(args)
        "delete_path" -> deletePath(args)
        "find_files" -> findFiles(args)
        "search_in_files" -> searchInFiles(args)
        else -> ToolResult.failure("未知工具：$name")
    }

    private fun resolve(raw: String?): File = resolveUserPath(context, raw)

    private fun describe(file: File): String = when {
        file.isDirectory -> "[目录]"
        else -> "[文件 ${humanSize(file.length())}]"
    }

    private suspend fun readFile(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val file = resolve(args.str("path"))
            if (!file.exists()) return@withContext ToolResult.failure("文件不存在：${file.absolutePath}")
            if (file.isDirectory) return@withContext ToolResult.failure("${file.absolutePath} 是目录，请改用 list_dir")
            if (file.length() > MAX_TEXT_BYTES) {
                return@withContext ToolResult.failure(
                    "文件过大（${humanSize(file.length())}），超过 ${humanSize(MAX_TEXT_BYTES)} 上限；" +
                        "可以用 run_shell 配合 head/tail 分段读取",
                )
            }
            val bytes = file.readBytes()
            if (bytes.take(4096).any { it == 0.toByte() }) {
                return@withContext ToolResult.success("二进制文件：${file.absolutePath}（${humanSize(file.length())}），无法按文本读取")
            }
            val lines = bytes.toString(Charsets.UTF_8).lines()
            val offset = args.int("offset", 1).coerceAtLeast(1)
            val limit = args.int("limit", 400).coerceIn(1, 5000)
            if (offset > lines.size) {
                return@withContext ToolResult.failure("起始行 $offset 超出文件总行数 ${lines.size}")
            }
            val slice = lines.subList(offset - 1, minOf(lines.size, offset - 1 + limit))
            val header = "${file.absolutePath}（共 ${lines.size} 行，显示 $offset-${offset + slice.size - 1}）\n"
            ToolResult.success(header + slice.joinToString("\n"))
        } catch (error: Exception) {
            ToolResult.failure("读取失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private suspend fun writeFile(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val path = args.str("path")
            if (path.isNullOrBlank()) return@withContext ToolResult.failure("缺少参数 path")
            val file = resolve(path)
            if (file.isDirectory) return@withContext ToolResult.failure("${file.absolutePath} 是目录")
            file.parentFile?.mkdirs()
            val content = args.str("content").orEmpty()
            val append = args.bool("append", false)
            if (append) file.appendText(content) else file.writeText(content)
            ToolResult.success("已${if (append) "追加" else "写入"} ${content.length} 字 → ${file.absolutePath}")
        } catch (error: Exception) {
            ToolResult.failure("写入失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    /**
     * 按原文替换一段（用户：「不要整篇重写，只改那一处」）。三条防线，宁可拒绝也不盲改：
     * 找不到就说清楚要逐字符一致；多处命中就拒绝并报出行号；成功时给出改动的行预览。
     */
    private suspend fun editFile(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val path = args.str("path")
            if (path.isNullOrBlank()) return@withContext ToolResult.failure("缺少参数 path")
            val old = args.str("old_string").orEmpty()
            if (old.isEmpty()) return@withContext ToolResult.failure("缺少参数 old_string")
            val new = args.str("new_string").orEmpty()
            val replaceAll = args.bool("replace_all", false)
            val file = resolve(path)
            if (!file.exists()) return@withContext ToolResult.failure("文件不存在：${file.absolutePath}")
            if (file.isDirectory) return@withContext ToolResult.failure("${file.absolutePath} 是目录，请改用 list_dir")
            if (file.length() > MAX_TEXT_BYTES) {
                return@withContext ToolResult.failure("文件过大（${humanSize(file.length())}），请改用 read_file 分段处理后重写")
            }
            val text = file.readText()
            val hits = occurrences(text, old)
            if (hits.isEmpty()) {
                return@withContext ToolResult.failure(
                    "文件里找不到 old_string：${file.absolutePath}。它必须与文件内容**逐字符一致**" +
                        "（空格、缩进、换行都要一样）；先用 read_file 看一眼原文再改。",
                )
            }
            if (hits.size > 1 && !replaceAll) {
                val lines = hits.map { lineOf(text, it) }.joinToString("、")
                return@withContext ToolResult.failure(
                    "old_string 在文件里出现 ${hits.size} 次（第 $lines 行），拒绝盲改：" +
                        "把 old_string 写长一点（带上前后行）让它唯一，或传 replace_all=true 全部替换。",
                )
            }
            val changed = if (replaceAll) text.replace(old, new) else {
                text.substring(0, hits.first()) + new + text.substring(hits.first() + old.length)
            }
            file.writeText(changed)
            val preview = buildString {
                appendLine("- ${old.lines().first()}")
                appendLine("+ ${new.lines().first()}")
                if (old.lines().size > 1 || new.lines().size > 1) {
                    appendLine("（共替换 ${old.lines().size} 行 → ${new.lines().size} 行）")
                }
            }.trim()
            ToolResult.success(
                "已修改 ${file.absolutePath}（第 ${lineOf(text, hits.first())} 行，替换 ${hits.size} 处）：\n$preview",
            )
        } catch (error: Exception) {
            ToolResult.failure("修改失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    /** 非重叠命中位置；空 needle 不算命中。 */
    private fun occurrences(haystack: String, needle: String): List<Int> {
        if (needle.isEmpty()) return emptyList()
        val out = mutableListOf<Int>()
        var at = haystack.indexOf(needle)
        while (at >= 0) {
            out += at
            at = haystack.indexOf(needle, at + needle.length)
        }
        return out
    }

    /** 1-based 行号：该偏移之前有多少个换行。 */
    private fun lineOf(text: String, index: Int): Int = text.substring(0, index).count { it == '\n' } + 1

    private suspend fun listDir(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val dir = resolve(args.str("path") ?: "~")
            if (!dir.exists()) return@withContext ToolResult.failure("目录不存在：${dir.absolutePath}")
            if (!dir.isDirectory) return@withContext ToolResult.failure("${dir.absolutePath} 不是目录")
            val limit = args.int("limit", 200).coerceIn(1, 1000)
            val children = dir.listFiles()?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name })
                ?: emptyList()
            if (children.isEmpty()) return@withContext ToolResult.success("${dir.absolutePath} 是空目录")
            val body = children.take(limit).joinToString("\n") { "${describe(it)} ${it.name}" }
            val suffix = if (children.size > limit) "\n…共 ${children.size} 项，已截断" else ""
            ToolResult.success("${dir.absolutePath}（${children.size} 项）：\n$body$suffix")
        } catch (error: Exception) {
            ToolResult.failure("列出目录失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private suspend fun makeDir(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val dir = resolve(args.str("path"))
            if (dir.exists()) return@withContext ToolResult.success("目录已存在：${dir.absolutePath}")
            val created = dir.mkdirs()
            if (created) {
                ToolResult.success("已创建目录 ${dir.absolutePath}")
            } else {
                ToolResult.failure("创建目录失败：${dir.absolutePath}（可能没有权限）")
            }
        } catch (error: Exception) {
            ToolResult.failure("创建目录失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private suspend fun statPath(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val file = resolve(args.str("path"))
            if (!file.exists()) return@withContext ToolResult.failure("路径不存在：${file.absolutePath}")
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(file.lastModified()))
            buildString {
                appendLine("路径: ${file.absolutePath}")
                appendLine("类型: ${if (file.isDirectory) "目录" else "文件"}")
                if (file.isFile) appendLine("大小: ${humanSize(file.length())}")
                appendLine("修改时间: $time")
                appendLine("可读: ${file.canRead()}  可写: ${file.canWrite()}  可执行: ${file.canExecute()}")
                if (file.isDirectory) appendLine("子项数: ${file.list()?.size ?: 0}")
            }.trim().let { ToolResult.success(it) }
        } catch (error: Exception) {
            ToolResult.failure("读取路径信息失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private suspend fun movePath(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val from = resolve(args.str("from"))
            val to = resolve(args.str("to"))
            if (!from.exists()) return@withContext ToolResult.failure("源路径不存在：${from.absolutePath}")
            if (to.exists()) return@withContext ToolResult.failure("目标已存在：${to.absolutePath}")
            to.parentFile?.mkdirs()
            if (from.renameTo(to)) {
                ToolResult.success("已移动 ${from.absolutePath} → ${to.absolutePath}")
            } else {
                val copied = copyRecursive(from, to)
                if (!copied) return@withContext ToolResult.failure("移动失败（跨分区复制未成功）")
                deleteRecursive(from)
                ToolResult.success("已移动（复制后删除）${from.absolutePath} → ${to.absolutePath}")
            }
        } catch (error: Exception) {
            ToolResult.failure("移动失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private suspend fun deletePath(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val file = resolve(args.str("path"))
            if (!file.exists()) return@withContext ToolResult.failure("路径不存在：${file.absolutePath}")
            val canonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            if (canonical.length < 2 || canonical in PROTECTED_PATHS || canonical == workspace.canonicalPath) {
                return@withContext ToolResult.failure(
                    "拒绝删除受保护路径：$canonical（可以删除它下面的具体文件或子目录）",
                )
            }
            if (file.isDirectory && !args.bool("recursive", false)) {
                val empty = file.delete()
                return@withContext if (empty) {
                    ToolResult.success("已删除空目录 $canonical")
                } else {
                    ToolResult.failure("$canonical 是非空目录；确认要整树删除时请显式传 recursive=true")
                }
            }
            val removed = deleteRecursive(file)
            if (removed > 0) {
                ToolResult.success("已删除 $canonical（$removed 项）")
            } else {
                ToolResult.failure("删除失败：$canonical（可能没有权限）")
            }
        } catch (error: Exception) {
            ToolResult.failure("删除失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private suspend fun findFiles(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val root = resolve(args.str("root") ?: "~")
            val keyword = args.str("name")?.trim().orEmpty()
            if (keyword.isEmpty()) return@withContext ToolResult.failure("缺少参数 name")
            if (!root.isDirectory) return@withContext ToolResult.failure("起始目录无效：${root.absolutePath}")
            val maxDepth = args.int("max_depth", 8).coerceIn(1, 30)
            val limit = args.int("limit", 60).coerceIn(1, 500)
            val hits = ArrayList<String>()
            walk(root, 0, maxDepth, { hits.size >= limit }) { file ->
                if (file.name.contains(keyword, ignoreCase = true)) hits.add(file.absolutePath)
            }
            if (hits.isEmpty()) {
                ToolResult.success("没有找到名称包含「$keyword」的文件")
            } else {
                val more = if (hits.size >= limit) "（已达 limit 上限，可能还有更多）" else ""
                ToolResult.success("找到 ${hits.size} 个$more：\n" + hits.joinToString("\n"))
            }
        } catch (error: Exception) {
            ToolResult.failure("查找失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private suspend fun searchInFiles(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val root = resolve(args.str("root") ?: "~")
            val query = args.str("query").orEmpty()
            if (query.isEmpty()) return@withContext ToolResult.failure("缺少参数 query")
            if (!root.isDirectory) return@withContext ToolResult.failure("起始目录无效：${root.absolutePath}")
            val regex = if (args.bool("regex", false)) {
                runCatching { Regex(query) }.getOrElse {
                    return@withContext ToolResult.failure("正则表达式无效：${it.message}")
                }
            } else {
                null
            }
            val maxFiles = args.int("max_files", 400).coerceIn(1, 5000)
            val limit = args.int("limit", 60).coerceIn(1, 500)
            val hits = ArrayList<String>()
            var scanned = 0
            walk(root, 0, 12, { hits.size >= limit || scanned >= maxFiles }) { file ->
                if (hits.size >= limit || scanned >= maxFiles || !file.isFile) return@walk
                if (file.length() > MAX_SEARCH_BYTES) return@walk
                scanned++
                val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@walk
                if (bytes.take(2048).any { it == 0.toByte() }) return@walk
                val lines = bytes.toString(Charsets.UTF_8).lines()
                for ((index, line) in lines.withIndex()) {
                    val matched = regex?.containsMatchIn(line) ?: line.contains(query, ignoreCase = true)
                    if (matched) {
                        hits.add("${file.absolutePath}:${index + 1}: ${line.trim().take(200)}")
                        if (hits.size >= limit) return@walk
                    }
                }
            }
            if (hits.isEmpty()) {
                ToolResult.success("在 $scanned 个文件里没有找到「$query」")
            } else {
                ToolResult.success("命中 ${hits.size} 条（扫描 $scanned 个文件）：\n" + hits.joinToString("\n"))
            }
        } catch (error: Exception) {
            ToolResult.failure("搜索失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    /**
     * Depth-first walk that stops as soon as [stop] says so and cooperates with
     * coroutine cancellation, so a search over `/sdcard` cannot pin the IO
     * dispatcher after the user taps stop.
     */
    private suspend fun walk(
        root: File,
        depth: Int,
        maxDepth: Int,
        stop: () -> Boolean,
        action: (File) -> Unit,
    ) {
        if (depth > maxDepth || stop() || !currentCoroutineContext().isActive) return
        val children = root.listFiles() ?: return
        for (child in children) {
            if (stop() || !currentCoroutineContext().isActive) return
            action(child)
            if (child.isDirectory) walk(child, depth + 1, maxDepth, stop, action)
        }
    }

    private fun deleteRecursive(file: File): Int {
        var count = 0
        if (file.isDirectory) {
            file.listFiles()?.forEach { count += deleteRecursive(it) }
        }
        if (file.delete()) count++
        return count
    }

    private fun copyRecursive(from: File, to: File): Boolean = try {
        if (from.isDirectory) {
            if (!to.mkdirs() && !to.isDirectory) false
            else from.listFiles()?.all { copyRecursive(it, File(to, it.name)) } ?: true
        } else {
            to.parentFile?.mkdirs()
            from.inputStream().use { input -> to.outputStream().use { output -> input.copyTo(output) } }
            true
        }
    } catch (error: Exception) {
        false
    }

    private companion object {
        const val MAX_TEXT_BYTES = 4L * 1024 * 1024
        const val MAX_SEARCH_BYTES = 2L * 1024 * 1024

        /**
         * `delete_path` is the one tool that cannot be undone, so whole trees that
         * a single wrong argument would wipe out are refused outright.
         */
        val PROTECTED_PATHS = setOf(
            "/", "/sdcard", "/storage", "/storage/emulated", "/storage/emulated/0",
            "/system", "/system/bin", "/data", "/data/data", "/vendor", "/proc", "/sys", "/etc",
        )
    }
}

/**
 * The agent's default working directory. App-private external storage needs no
 * runtime permission, so relative paths work on a freshly installed phone.
 */
internal fun workspaceDir(context: Context): File =
    context.getExternalFilesDir(null) ?: context.filesDir

/** Shared by the file tools and the download tool so `~` means the same thing in both. */
internal fun resolveUserPath(context: Context, raw: String?): File {
    val workspace = workspaceDir(context)
    val path = raw?.trim().orEmpty()
    return when {
        path.isEmpty() || path == "~" -> workspace
        path.startsWith("~/") -> File(workspace, checkedRelative(path.removePrefix("~/")))
        File(path).isAbsolute -> File(path)
        else -> File(workspace, checkedRelative(path))
    }
}

/**
 * Refuses a relative path that climbs out of the workspace with `..`.
 *
 * Not a fix for an open hole: the workspace is app-private storage, so a `..` could only reach
 * directories the tools have no business in, and absolute paths stay allowed because the file
 * tools check those against `PROTECTED_PATHS` by canonical path. This is depth instead — a
 * relative path is documented as 「工作目录之内」, and quietly honouring `../..` is how a later
 * change to what the workspace is turns the promise into an escape.
 */
private fun checkedRelative(path: String): String {
    require(path.split('/', '\\').none { it == ".." }) {
        "路径不能包含 ..（相对路径不能跳出工作目录，要用别处请写绝对路径）"
    }
    return path
}

/** Suffix a download writes to before the finished file is renamed into place. */
internal const val PART_SUFFIX = ".part"

/** How old a `.part` file has to be before it counts as a leftover. */
private const val STALE_PART_MS = 24L * 60 * 60 * 1000

/** How deep the leftover sweep walks; the workspace is shallow, and a tidy-up must stay cheap. */
private const val STALE_PART_MAX_DEPTH = 6

/**
 * Deletes stale `*.part` leftovers under [root].
 *
 * A download that was killed mid-write leaves one, and it is unusable by construction — only a
 * complete file is ever renamed into place. The download tool already deletes the part file it is
 * about to write, so this is about the ones whose target is never tried again.
 *
 * Best effort on purpose: a tidy-up must never be a reason for a download to fail.
 */
internal fun sweepStaleParts(
    root: File,
    now: Long = System.currentTimeMillis(),
    olderThanMs: Long = STALE_PART_MS,
) {
    runCatching {
        root.walkTopDown()
            .maxDepth(STALE_PART_MAX_DEPTH)
            .filter { it.isFile && it.name.endsWith(PART_SUFFIX) && now - it.lastModified() > olderThanMs }
            .forEach { it.delete() }
    }
}

internal fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    // Locale.US on purpose: a size is a number, and a German or French device would otherwise
    // render it as "1,5 KB" while every other size in the app stays ASCII.
    bytes < 1024 * 1024 -> "%.1f KB".format(Locale.US, bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(Locale.US, bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(Locale.US, bytes / 1024.0 / 1024.0 / 1024.0)
}
