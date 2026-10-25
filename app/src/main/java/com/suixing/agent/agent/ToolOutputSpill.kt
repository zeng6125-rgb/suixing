package com.suixing.agent.agent

import java.io.File

/** 文件名安全字符集：工具名里的非法字符替换成下划线（原为每次写盘现场编译）。 */
private val UNSAFE_FILENAME = Regex("[^A-Za-z0-9_.-]")

/** 一次落盘的结果：[kept] 是要放进 wire 的文本（头部 + 提示），[path] 是完整输出所在文件。 */
internal data class SpilledToolOutput(val kept: String, val path: String)

/**
 * 工具输出超长时：**完整输出落盘**，wire 里只留头部与一行提示。
 *
 * 为什么：现在 `MAX_TOOL_OUTPUT` 直接 `take(8000)`，多出来的部分**无声消失** —— 模型不知道
 * 自己被截断了，也不知道去哪找，只能重跑同一条命令（更慢更贵）。DSH 与 pi 都是这个形状：
 * 模型侧只看尾部/头部，完整输出存文件，路径写进结果里。
 *
 * 与 pi 的差别（有意）：pi 的 bash 保留**尾部**，这里保留**头部**。本应用里的大输出主要是
 * `ui_dump`、`read_file`、`list_dir` 这类以开头为纲的结果，保头更常可用；被砍掉的尾部用路径 +
 * `read_file offset=` 分段取回。
 *
 * @param dir 落盘目录（通常是 `filesDir/toolout`）。
 * @param tool 工具名，只用来生成可读的文件名。
 * @param output 工具返回的原始文本。
 * @param maxKept 留在 wire 里的字符上限。
 */
internal fun spillToolOutput(
    dir: File,
    tool: String,
    output: String,
    maxKept: Int,
): SpilledToolOutput? {
    if (maxKept <= 0 || output.length <= maxKept) return null
    val safeName = tool.ifBlank { "tool" }.replace(UNSAFE_FILENAME, "_").take(40)
    val file = File(dir.apply { mkdirs() }, "$safeName-${System.currentTimeMillis()}.txt")
    val written = runCatching { file.writeText(output); true }.getOrDefault(false)
    if (!written) {
        // 落盘失败也要说清事实：不能假装没截断，否则模型以为自己看全了。
        val notice = "\n\n[输出已截断：全文 ${output.length} 字符，这里只有前 ${output.take(maxKept).length} 字符；" +
            "完整内容未能保存到磁盘。需要更多请缩小请求范围重新取一次。]"
        return SpilledToolOutput(output.take(maxKept) + notice, "")
    }
    pruneOldSpills(dir, KEEP_SPILL_FILES)
    val notice = "\n\n[输出已截断：全文 ${output.length} 字符，这里只显示前 $maxKept 字符。" +
        "完整内容已存 ${file.absolutePath}。" +
        "需要后面的部分用 read_file path=${file.absolutePath} offset=<起始行> limit=<行数> 分段读，" +
        "不要重跑同一条命令。]"
    return SpilledToolOutput(output.take(maxKept) + notice, file.absolutePath)
}

/** 只留最近 [keep] 个落盘文件，避免几十万字符的 dump 堆满存储。 */
internal fun pruneOldSpills(dir: File, keep: Int) {
    if (keep <= 0) return
    runCatching {
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(keep)?.forEach { it.delete() }
    }
}

/** 落盘文件保留个数。 */
internal const val KEEP_SPILL_FILES = 40
