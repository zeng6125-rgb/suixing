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
                    // **乐观锁**（issue #44 第八项）：带上 read_file 返回的 revision，
                    // 文件在你读完之后被改过就拒绝，避免改到别人刚写的内容上。
                    "expected_revision" to Schema.string(
                        "Optional: the revision read_file reported for this file. If the file changed " +
                            "since then the edit is refused instead of writing over it.",
                    ),
                ),
                required = listOf("path", "old_string", "new_string"),
            ),
        ),
        ToolSpec(
            "list_dir",
            "List a directory (directories first, with sizes). Runs in-process: no shell is spawned. " +
                "Use pattern to filter inside big directories instead of raising limit.",
            Schema.objectOf(
                properties = listOf(
                    "path" to Schema.string("Directory path, default the working directory", default = "~"),
                    "pattern" to Schema.string("Glob filter on the entry name (*.kt, build*), optional"),
                    "limit" to Schema.integer("Maximum entries, default 200", default = 200, minimum = 1, maximum = 1000),
                    "all" to Schema.boolean("Include dot-files, default false", default = false),
                ),
            ),
        ),
        ToolSpec(
            "find_files",
            "Find files by name anywhere under a directory tree. Runs in-process: no shell is spawned. " +
                "Use pattern for globs (*.kt, **/test/*.kt); name is a plain case-insensitive substring.",
            Schema.objectOf(
                properties = listOf(
                    "root" to Schema.string("Starting directory, default the working directory", default = "~"),
                    "name" to Schema.string("Case-insensitive substring of the file name"),
                    "pattern" to Schema.string(
                        "Glob on the file name (*.kt) or path (**/test/*.kt); takes precedence over name",
                    ),
                    "exclude" to Schema.string("Skip paths containing this substring, e.g. build or /test/"),
                    "type" to Schema.string(
                        "What to match: file | dir | any, default file",
                        enumValues = listOf("file", "dir", "any"),
                        default = "file",
                    ),
                    "max_depth" to Schema.integer("Maximum search depth, default 8", default = 8, minimum = 1, maximum = 30),
                    "limit" to Schema.integer("Maximum results, default 60", default = 60, minimum = 1, maximum = 500),
                ),
            ),
        ),
        ToolSpec(
            "grep_files",
            "Search the text files under a directory and return matches as 「path:line: text」, " +
                "with optional surrounding context lines (format matches grep -C: ':' marks the match, " +
                "'-' marks context). Runs in-process: no shell is spawned.",
            Schema.objectOf(
                properties = listOf(
                    "root" to Schema.string("Starting directory, default the working directory", default = "~"),
                    "query" to Schema.string("Text or regular expression to search for"),
                    "regex" to Schema.boolean("Treat query as a regular expression, default false", default = false),
                    "exclude" to Schema.string("Skip paths containing this substring, e.g. node_modules or .git"),
                    "files_only" to Schema.boolean("List matching file paths instead of lines, default false", default = false),
                    "context" to Schema.integer(
                        "Lines before and after each match, default 0 (like grep -C)",
                        default = 0,
                        minimum = 0,
                        maximum = 5,
                    ),
                    "case_sensitive" to Schema.boolean("Match case, default false", default = false),
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
        "find_files" -> findFiles(args)
        "grep_files" -> searchInFiles(args)
        else -> ToolResult.failure("未知工具：$name")
    }

    /** 单测入口：直接跑一个工具名（`execute` 是 ToolGroup 的 override，测试里够用）。 */
    internal suspend fun runForTest(name: String, args: JsonObject): ToolResult = execute(name, args)

    private fun resolve(raw: String?): File = resolveUserPath(context, raw)

    private fun describe(file: File): String = when {
        file.isDirectory -> "[目录]"
        else -> "[文件 ${humanSize(file.length())}]"
    }

    /** 前 [limit] 字节里有没有 NUL（判定二进制），不装箱、不复制。 */
    private fun hasNulByte(bytes: ByteArray, limit: Int): Boolean {
        val end = minOf(bytes.size, limit)
        for (i in 0 until end) if (bytes[i] == 0.toByte()) return true
        return false
    }

    private suspend fun readFile(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val file = resolve(args.str("path"))
            if (!file.exists()) return@withContext ToolResult.failure("文件不存在：${file.absolutePath}")
            if (file.isDirectory) return@withContext ToolResult.failure("${file.absolutePath} 是目录，请改用 run_shell 的 ls")
            if (file.length() > MAX_TEXT_BYTES) {
                return@withContext ToolResult.failure(
                    "文件过大（${humanSize(file.length())}），超过 ${humanSize(MAX_TEXT_BYTES)} 上限；" +
                        "可以用 run_shell 配合 head/tail 分段读取",
                )
            }
            val bytes = file.readBytes()
            // 只看前 4096 字节，且**不装箱**：`bytes.take(4096)` 会把每个 Byte 装成 List<Byte>。
            if (hasNulByte(bytes, 4096)) {
                return@withContext ToolResult.success("二进制文件：${file.absolutePath}（${humanSize(file.length())}），无法按文本读取")
            }
            // **整份字节只解码一次**（原来 toString 两次：一次得 lines、一次算 revision，
            // ≤4MB 的文件会有两份大 String 同时存活）。
            val text = bytes.toString(Charsets.UTF_8)
            val lines = text.lines()
            val offset = args.int("offset", 1).coerceAtLeast(1)
            val limit = args.int("limit", 400).coerceIn(1, 5000)
            if (offset > lines.size) {
                return@withContext ToolResult.failure("起始行 $offset 超出文件总行数 ${lines.size}")
            }
            val slice = lines.subList(offset - 1, minOf(lines.size, offset - 1 + limit))
            val header = "${file.absolutePath}（共 ${lines.size} 行，显示 $offset-${offset + slice.size - 1}）\n" +
                // **revision 交给模型**（issue #44 第八项）：edit_file 带上它就能做乐观锁，
                // 读到与改写之间文件被改过会被拒绝，而不是静默覆盖。
                "revision=${revisionOf(text)}\n"
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
            if (file.isDirectory) return@withContext ToolResult.failure("${file.absolutePath} 是目录，请改用 run_shell 的 ls")
            if (file.length() > MAX_TEXT_BYTES) {
                return@withContext ToolResult.failure("文件过大（${humanSize(file.length())}），请改用 read_file 分段处理后重写")
            }
            val text = file.readText()
            // **乐观锁校验**（issue #44 第八项）：给了 expected_revision 就比对当前内容摘要，
            // 不一致直接拒绝。模型常常先 read_file 再 edit_file，中间这个文件可能被用户、
            // 被别的工具、或被上一轮 run 改过 —— 覆盖掉就是静默丢失。
            val expected = args.str("expected_revision")
            // **只在真的要做乐观锁时才算哈希**：revisionOf 要遍历整份文本，多数编辑不带它。
            val current = if (expected.isNullOrBlank()) "" else revisionOf(text)
            if (!expected.isNullOrBlank() && expected != current) {
                return@withContext ToolResult.failure(
                    "FILE_CHANGED：${file.absolutePath} 在你读取之后已被修改" +
                        "（expected_revision=$expected，当前=$current）。" +
                        "请重新 read_file 拿到最新内容与 revision 后再改。",
                )
            }
            val hits = occurrences(text, old)
            if (hits.isEmpty()) {
                return@withContext ToolResult.failure(
                    "文件里找不到 old_string：${file.absolutePath}。它必须与文件内容**逐字符一致**" +
                        "（空格、缩进、换行都要一样）；先用 read_file 看一眼原文再改。",
                )
            }
            if (hits.size > 1 && !replaceAll) {
                // 一次扫描算出全部命中行号，避免每个命中各做一次 substring + count（issue #54 B-1）。
                val lineMap = lineNumbers(text, hits)
                val lines = hits.joinToString("、") { lineMap[it].toString() }
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
                "已修改 ${file.absolutePath}（第 ${lineOf(text, hits.first())} 行，替换 ${hits.size} 处）：\n$preview" +
                    "\nrevision=${revisionOf(changed)}",
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


    /** 多个偏移共用一次扫描：O(n + k log k)，替代每个命中一次 `lineOf` 的 O(n·k)。 */
    private fun lineNumbers(text: String, offsets: List<Int>): Map<Int, Int> {
        val out = HashMap<Int, Int>(offsets.size)
        var line = 1
        var cursor = 0
        for (target in offsets.sorted()) {
            while (cursor < target && cursor < text.length) {
                if (text[cursor].code == 10) line++
                cursor++
            }
            out[target] = line
        }
        return out
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

    // ─── 原生目录/查找/搜索（不 fork 子进程）──────────────────────────────────
    //
    // 这三个留在应用内是有意的（v0.8.322 复盘）：`run_shell` 每调一次就
    // `ProcessBuilder.start()` 起一个进程（Shell.kt:251），Android 上一次 20~100ms；
    // 而日常"看看目录、找个文件、搜段文字"是最频繁的动作，靠 fork 去做纯属浪费。
    // 功能边界写进各自的 description 了：花哨的 flag 交给 run_shell 的 grep/find。

    // ─── 原生目录/查找/搜索（不 fork 子进程）──────────────────────────────────
    //
    // 这三个留在应用内是有意的：`run_shell` 每调一次就 ProcessBuilder.start() 起一个进程
    // （Shell.kt:251），Android 上一次 20~100ms；而「看看目录 / 找个文件 / 搜段文字」是最频繁
    // 的动作，靠 fork 去做纯属浪费。功能边界写进各自的 description 了：花哨的 flag
    //（正则上下文、排除 glob、按时间/大小筛选）交给 run_shell 的 grep/find。

    private suspend fun listDir(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val dir = resolve(args.str("path") ?: "~")
            if (!dir.exists()) return@withContext ToolResult.failure("目录不存在：" + dir.absolutePath)
            if (!dir.isDirectory) return@withContext ToolResult.failure(dir.absolutePath + " 不是目录")
            val limit = args.int("limit", 200).coerceIn(1, 1000)
            val all = args.bool("all", false)
            val pattern = args.str("pattern")?.takeIf { it.isNotBlank() }
            val children = dir.listFiles()
                ?.filter { all || !it.name.startsWith(".") }
                // 目录里东西多时，光靠 limit 会把想要的截掉；pattern 在**截断之前**过滤。
                ?.filter { pattern == null || Glob.nameMatches(pattern, it.name) }
                ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name })
                ?: emptyList()
            if (children.isEmpty()) return@withContext ToolResult.success(dir.absolutePath + " 是空目录")
            val body = children.take(limit).joinToString("\n") { describe(it) + " " + it.name }
            val suffix = if (children.size > limit) "\n" + "…共 " + children.size + " 项，已截断" else ""
            ToolResult.success(dir.absolutePath + "（" + children.size + " 项）：" + "\n" + body + suffix)
        } catch (error: Exception) {
            ToolResult.failure("列出目录失败：" + (error.message ?: error.javaClass.simpleName))
        }
    }

    private suspend fun findFiles(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val root = resolve(args.str("root") ?: "~")
            val name = args.str("name").orEmpty()
            val pattern = args.str("pattern")?.takeIf { it.isNotBlank() }
            // 两者都没给就没法筛：报清楚的错，而不是把整棵树列出来。
            if (name.isEmpty() && pattern == null) {
                return@withContext ToolResult.failure("缺少参数：至少给 name（子串）或 pattern（glob，如 *.kt）")
            }
            if (!root.isDirectory) return@withContext ToolResult.failure("起始目录无效：" + root.absolutePath)
            val exclude = args.str("exclude")?.takeIf { it.isNotBlank() }
            val kind = args.str("type")?.lowercase() ?: "file"
            val maxDepth = args.int("max_depth", 8).coerceIn(1, 30)
            val limit = args.int("limit", 60).coerceIn(1, 500)
            val hits = ArrayList<String>()
            walk(root, 0, maxDepth, { hits.size >= limit }) { file ->
                if (hits.size >= limit || file == root) return@walk
                val kindOk = when (kind) {
                    "dir" -> file.isDirectory
                    "any" -> true
                    else -> file.isFile
                }
                if (!kindOk) return@walk
                if (exclude != null && file.absolutePath.contains(exclude)) return@walk
                // pattern 优先：它能表达「所有 .kt」这种最常见的查询，比子串精确。
                val matched = if (pattern != null) Glob.pathMatches(pattern, file, root)
                else file.name.contains(name, ignoreCase = true)
                if (matched) hits.add(file.absolutePath)
            }
            val label = pattern ?: name
            if (hits.isEmpty()) {
                ToolResult.success("没有找到匹配「" + label + "」的" + if (kind == "dir") "目录" else "文件")
            } else {
                val more = if (hits.size >= limit) "（已达 limit 上限，可能还有更多）" else ""
                ToolResult.success("找到 " + hits.size + " 个" + more + "：" + "\n" + hits.joinToString("\n"))
            }
        } catch (error: Exception) {
            ToolResult.failure("查找失败：" + (error.message ?: error.javaClass.simpleName))
        }
    }

    private suspend fun searchInFiles(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val root = resolve(args.str("root") ?: "~")
            val query = args.str("query").orEmpty()
            if (query.isEmpty()) return@withContext ToolResult.failure("缺少参数 query")
            if (!root.isDirectory) return@withContext ToolResult.failure("起始目录无效：" + root.absolutePath)
            val caseSensitive = args.bool("case_sensitive", false)
            val regex = if (args.bool("regex", false)) {
                runCatching { searchPattern(query, caseSensitive) }.getOrElse {
                    return@withContext ToolResult.failure("正则表达式无效：" + (it.message ?: ""))
                }
            } else {
                null
            }
            val exclude = args.str("exclude")?.takeIf { it.isNotBlank() } ?: ".git"
            val filesOnly = args.bool("files_only", false)
            // **上下文行**（issue #32 第 1 项）：模型为了判断匹配行处在什么上下文里，
            // 以前要再调一次 read_file —— 那是一次完整的 LLM 往返（几秒）。
            // 输出格式与 `grep -C` 一致（':' 匹配行，'-' 上下文），模型训练过这种格式。
            val context = args.int("context", 0).coerceIn(0, 5)
            val maxFiles = args.int("max_files", 400).coerceIn(1, 5000)
            val limit = args.int("limit", 60).coerceIn(1, 500)
            val hits = ArrayList<String>()
            val hitFiles = LinkedHashSet<String>()
            var scanned = 0
            walk(root, 0, 12, { hits.size >= limit || hitFiles.size >= limit || scanned >= maxFiles }) { file ->
                val full = hits.size >= limit || hitFiles.size >= limit || scanned >= maxFiles
                if (full || !file.isFile) return@walk
                if (file.absolutePath.contains(exclude)) return@walk
                if (file.length() > MAX_SEARCH_BYTES) return@walk
                scanned++
                val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@walk
                if ((0 until minOf(2048, bytes.size)).any { bytes[it] == 0.toByte() }) return@walk
                val lines = bytes.toString(Charsets.UTF_8).lines()
                var lastEmitted = -1
                for ((index, line) in lines.withIndex()) {
                    val matched = searchLineMatches(line, query, caseSensitive, regex)
                    if (!matched) continue
                    if (filesOnly) {
                        hitFiles.add(file.absolutePath)
                        return@walk
                    }
                    if (context == 0) {
                        hits.add(file.absolutePath + ":" + (index + 1) + ": " + line.trim().take(200))
                    } else {
                        // 上下文窗口；`lastEmitted` 防止相邻匹配把同一行重复打印。
                        val from = (index - context).coerceAtLeast(0)
                        val to = (index + context).coerceAtMost(lines.lastIndex)
                        for (i in from..to) {
                            if (i <= lastEmitted) continue
                            val mark = if (i == index) ":" else "-"
                            hits.add(
                                file.absolutePath + ":" + (i + 1) + mark + " " + lines[i].trim().take(200),
                            )
                        }
                        lastEmitted = to
                    }
                    if (hits.size >= limit) return@walk
                }
            }
            val found: List<String> = if (filesOnly) hitFiles.toList() else hits
            if (found.isEmpty()) {
                ToolResult.success("在 " + scanned + " 个文件里没有找到「" + query + "」")
            } else {
                ToolResult.success(
                    "命中 " + found.size + " 条（扫描 " + scanned + " 个文件）：" + "\n" + found.joinToString("\n"),
                )
            }
        } catch (error: Exception) {
            ToolResult.failure("搜索失败：" + (error.message ?: error.javaClass.simpleName))
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

    private companion object {
        /** read_file / edit_file 的文本上限。 */
        const val MAX_TEXT_BYTES = 4L * 1024 * 1024

        /** 搜索时单个文件的上限：再大就不是"搜一段文字"，而是扫数据文件。 */
        const val MAX_SEARCH_BYTES = 2L * 1024 * 1024
        // MAX_SEARCH_BYTES 与 PROTECTED_PATHS 随 searchInFiles / deletePath 一起删掉了
        // （v0.8.322：那 7 个文件工具改用 run_shell 的 grep/find/stat/rm）。
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
 * tools used to check those against a protected-path table by canonical path (removed with
 * `delete_path` in v0.8.322 — deletions go through `run_shell` now). This is depth instead — a
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

/**
 * 文件内容的**版本标识**（issue #44 第八项，乐观锁用）。
 *
 * 不是时间戳：`mtime` 在部分文件系统上只有秒级精度，同一次 run 内的两次写入可能拿到同一个值。
 * 用「长度 + 内容哈希」—— 内容变了它就变，且不需要额外的文件元数据。
 */
internal fun revisionOf(text: String): String {
    val hash = text.hashCode().toUInt().toString(16)
    return "${text.length.toString(16)}-$hash"
}

internal fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    // Locale.US on purpose: a size is a number, and a German or French device would otherwise
    // render it as "1,5 KB" while every other size in the app stays ASCII.
    bytes < 1024 * 1024 -> "%.1f KB".format(Locale.US, bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(Locale.US, bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(Locale.US, bytes / 1024.0 / 1024.0 / 1024.0)
}
