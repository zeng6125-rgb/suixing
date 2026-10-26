package com.suixing.agent.ui.chat

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
/**
 * 行内时间戳：`HH:mm`，超过一天则 `M月d日 HH:mm`。
 *
 * **格式化器按线程复用**（性能优化，输出逐字节不变）：`SimpleDateFormat` 的构造很贵（解析 pattern、
 * 建符号表），而这段代码原来**每次调用都 new 一个**，被消息时间戳与导航条标签反复调用。
 * 用 `ThreadLocal` 而不是共享单例，是因为 `SimpleDateFormat` 本身不是线程安全的。
 */
private val timeFormatShort = ThreadLocal.withInitial {
    SimpleDateFormat("HH:mm", Locale.getDefault())
}

private val timeFormatWithDate = ThreadLocal.withInitial {
    SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())
}

internal fun formatTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val withDate = now - timestamp >= 24 * 60 * 60 * 1000L
    val fmt = (if (withDate) timeFormatWithDate else timeFormatShort).get()!!
    return fmt.format(Date(timestamp))
}

internal fun formatDuration(millis: Long): String =
    if (millis < 1000) "${millis}ms" else String.format(Locale.getDefault(), "%.1fs", millis / 1000.0)

/** 12 秒 / 1 分 03 秒 — how the run caption counts, never more than two units. */
internal fun formatRunElapsed(millis: Long): String {
    val seconds = millis / 1000
    return if (seconds < 60) {
        "$seconds 秒"
    } else {
        "${seconds / 60} 分 ${(seconds % 60).toString().padStart(2, '0')} 秒"
    }
}
