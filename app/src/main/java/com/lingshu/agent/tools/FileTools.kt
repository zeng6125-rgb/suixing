package com.lingshu.agent.tools

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

    override val specs: List<ToolSpec> = listOf(
        ToolSpec(
            "read_file",
            "读取文本文件内容（可按行截取）。相对路径基于工作目录，绝对路径按原样使用。",
            Schema.objectOf(
                properties = listOf(
                    "path" to Schema.string("文件路径，如 note.txt 或 /sdcard/Download/a.md"),
                    "offset" to Schema.integer("从第几行开始，默认 1", default = 1, minimum = 1),
                    "limit" to Schema.integer("最多读多少行，默认 400", default = 400, minimum = 1, maximum = 5000),
                ),
                required = listOf("path"),
            ),
        ),
        ToolSpec(
            "write_file",
            "写入文本文件（自动创建上级目录），可选择追加。",
            Schema.objectOf(
                properties = listOf(
                    "path" to Schema.string("文件路径"),
                    "content" to Schema.string("要写入的文本内容"),
                    "append" to Schema.boolean("是否追加到文件末尾，默认覆盖", default = false),
                ),
                required = listOf("path", "content"),
            ),
        ),
        ToolSpec(
            "list_dir",
            "列出目录内容（目录在前，含大小）。",
            Schema.objectOf(
                properties = listOf(
                    "path" to Schema.string("目录路径，默认工作目录", default = "~"),
                    "limit" to Schema.integer("最多列出多少项，默认 200", default = 200, minimum = 1, maximum = 1000),
                ),
            ),
        ),
        ToolSpec(
            "make_dir",
            "创建目录（含所有上级目录）。",
            Schema.objectOf(
                properties = listOf("path" to Schema.string("要创建的目录路径")),
                required = listOf("path"),
            ),
        ),
        ToolSpec(
            "stat_path",
            "查看路径的详细信息：是否存在、类型、大小、修改时间、可读可写。",
            Schema.objectOf(
                properties = listOf("path" to Schema.string("文件或目录路径")),
                required = listOf("path"),
            ),
        ),
        ToolSpec(
            "move_path",
            "移动或重命名文件/目录。",
            Schema.objectOf(
                properties = listOf(
                    "from" to Schema.string("源路径"),
                    "to" to Schema.string("目标路径"),
                ),
                required = listOf("from", "to"),
            ),
        ),
        ToolSpec(
            "delete_path",
            "删除文件或目录（目录会递归删除）。这是不可逆操作。",
            Schema.objectOf(
                properties = listOf(
                    "path" to Schema.string("要删除的路径"),
                    "recursive" to Schema.boolean("目录是否递归删除，默认 true", default = true),
                ),
                required = listOf("path"),
            ),
        ),
        ToolSpec(
            "find_files",
            "按名称在目录树中查找文件。",
            Schema.objectOf(
                properties = listOf(
                    "root" to Schema.string("起始目录，默认工作目录", default = "~"),
                    "name" to Schema.string("文件名包含的关键字（不区分大小写）"),
                    "max_depth" to Schema.integer("最大搜索深度，默认 8", default = 8, minimum = 1, maximum = 30),
                    "limit" to Schema.integer("最多返回多少个，默认 60", default = 60, minimum = 1, maximum = 500),
                ),
                required = listOf("name"),
            ),
        ),
        ToolSpec(
            "search_in_files",
            "在目录下的文本文件里搜索内容，返回「文件:行号: 内容」。",
            Schema.objectOf(
                properties = listOf(
                    "root" to Schema.string("起始目录，默认工作目录", default = "~"),
                    "query" to Schema.string("要搜索的文本或正则"),
                    "regex" to Schema.boolean("是否按正则解释 query，默认 false", default = false),
                    "max_files" to Schema.integer("最多扫描多少个文件，默认 400", default = 400, minimum = 1, maximum = 5000),
                    "limit" to Schema.integer("最多返回多少条命中，默认 60", default = 60, minimum = 1, maximum = 500),
                ),
                required = listOf("query"),
            ),
        ),
    )

    override suspend fun execute(name: String, args: JsonObject): ToolResult = when (name) {
        "read_file" -> readFile(args)
        "write_file" -> writeFile(args)
        "list_dir" -> listDir(args)
        "make_dir" -> makeDir(args)
        "stat_path" -> statPath(args)
        "move_path" -> movePath(args)
        "delete_path" -> deletePath(args)
        "find_files" -> findFiles(args)
        "search_in_files" -> searchInFiles(args)
        else -> ToolResult.failure("未知工具：$name")
    }

    private fun resolve(raw: String?): File {
        val path = raw?.trim().orEmpty()
        return when {
            path.isEmpty() || path == "~" -> workspace
            path.startsWith("~/") -> File(workspace, path.removePrefix("~/"))
            File(path).isAbsolute -> File(path)
            else -> File(workspace, path)
        }
    }

    private fun describe(file: File): String = when {
        file.isDirectory -> "[目录]"
        else -> "[文件 ${humanSize(file.length())}]"
    }

    private fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes} B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
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
