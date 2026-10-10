package com.suixing.agent.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 文件列表里按扩展名区分的图标色，集中在这里，避免同一组值散落在页面代码里。
 *
 * 不走 MaterialTheme 的语义色：M3 的 colorScheme 里没有「图片/代码/压缩包」这类角色，而列表靠
 * 颜色扫读比靠图标形状更快。
 *
 * 对比度（WCAG 图形对象 3:1 为准）：深色底 0xFF0F1412 上全部 ≥6:1，够；浅色底 0xFFFFFFFF 上
 * 只有 folder 3.0:1、text 2.1:1、other 3.0:1 附近，archive 1.7:1 / audio 2.6:1 / video 2.6:1 /
 * image 1.9:1 / code 2.2:1 明显偏亮 —— 浅色主题下这些图标是装饰性着色，靠图标形状与文件名兜底。
 * 若要让浅色下也达标，改这里一处即可（例如按 LocalDarkTheme 给一组深色变体）。
 */
internal object FileTypeColors {
    val folder = Color(0xFFAD7BF9)
    val image = Color(0xFF89C8AF)
    val code = Color(0xFF7FB3E8)
    val text = Color(0xFFB9B4AA)
    val archive = Color(0xFFF0C070)
    val audio = Color(0xFFD1A3E8)
    val video = Color(0xFFE89A9A)
    val other = Color(0xFF9A958C)
}
