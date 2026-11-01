package com.suixing.agent.llm

/** Full HTTP dumps require a fresh, explicitly created marker; a marker never lasts forever. */
internal fun fullDiagnosticAllowed(markerModifiedAt: Long, now: Long): Boolean =
    markerModifiedAt > 0L && now >= markerModifiedAt && now - markerModifiedAt <= 15 * 60_000L

/** 诊断文件（`files/last-http-error.json`）的最长保留时间：与完整转储开关同一个窗口。 */
internal const val DIAGNOSTIC_FILE_TTL_MS = 15 * 60_000L

/**
 * 这份落盘的诊断文件是否该删掉。
 *
 * `last-http-error.json` 只在出错时写，**没有下一次出错就不会被覆盖** —— 于是它会一直躺在
 * 私有目录里（还带着错误时的模型名与时间），直到用户手动删。完整转储开关已经限制在 15 分钟内，
 * 诊断文件本身也应当同样过期，两者口径一致。
 *
 * `modifiedAt <= 0` 表示文件不存在或时间戳不可用：那时无事可做，返回 false（不删）。
 * 时钟回拨（`now < modifiedAt`）按"未过期"处理 —— 宁可多留一会儿，也不要因时钟问题误删证据。
 */
internal fun staleDiagnosticFile(modifiedAt: Long, now: Long): Boolean =
    modifiedAt > 0L && now >= modifiedAt && now - modifiedAt > DIAGNOSTIC_FILE_TTL_MS

/**
 * 启动时清掉过期的诊断残留。
 *
 * 返回被删掉的文件名（便于测试与日志）；文件不存在或未过期时返回空列表。
 * 只处理约定好的两个名字，不做通配删除 —— 私有目录里还有会话、工具输出等真数据。
 */
internal fun expireDiagnosticFiles(
    filesDir: java.io.File,
    now: Long = System.currentTimeMillis(),
): List<String> {
    val names = listOf("last-http-error.json", "http-dump-full")
    val removed = ArrayList<String>(2)
    for (name in names) {
        val file = java.io.File(filesDir, name)
        if (!file.isFile) continue
        if (!staleDiagnosticFile(file.lastModified(), now)) continue
        if (file.delete()) removed.add(name)
    }
    return removed
}
