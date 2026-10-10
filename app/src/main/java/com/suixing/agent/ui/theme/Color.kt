package com.suixing.agent.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 品牌色是墨绿：一支白羽该配的底色是砚台和竹叶，不是柔紫。
 *
 * 深色不是纯黑而是 #0F1412（带一点绿的黑），表面阶梯 #171E1A → #1E2621 → #262F29
 * 三层叠加，描边用 #333C36 这种「比表面亮一点点」的绿灰，所以整屏读起来是连续的
 * 而不是一块块切开的。浅色是纯白底 + 冷绿灰面（#F1F5F2 / #E9EFEB）。
 *
 * primary 在深色下必须够亮（#8FD6B6）才压得住 #0F1412，浅色下反过来要够深
 * （#1F6B4C）才压得住白底——同一个色相两端取值，不是简单加透明度。
 */
private val BrandDark = darkColorScheme(
    primary = Color(0xFF8FD6B6),
    onPrimary = Color(0xFF06301F),
    primaryContainer = Color(0xFF1F4B39),
    onPrimaryContainer = Color(0xFFD6F5E7),
    inversePrimary = Color(0xFF2F6C51),

    secondary = Color(0xFFB6C9A8),
    onSecondary = Color(0xFF1B2A16),
    secondaryContainer = Color(0xFF2E3F28),
    onSecondaryContainer = Color(0xFFDCEBD2),

    tertiary = Color(0xFFE0C88C),
    onTertiary = Color(0xFF3A2E10),
    tertiaryContainer = Color(0xFF4E3F1B),
    onTertiaryContainer = Color(0xFFF5E6BF),

    error = Color(0xFFFF8E8E),
    onError = Color(0xFF5C1010),
    errorContainer = Color(0xFF6E2020),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF0F1412),
    onBackground = Color(0xFFE8EFEA),
    surface = Color(0xFF0F1412),
    onSurface = Color(0xFFE8EFEA),
    surfaceVariant = Color(0xFF2A322D),
    onSurfaceVariant = Color(0xFFAEB8B0),
    surfaceTint = Color(0xFF8FD6B6),

    inverseSurface = Color(0xFFE8EFEA),
    inverseOnSurface = Color(0xFF1B221E),
    outline = Color(0xFF47514B),
    outlineVariant = Color(0xFF333C36),
    scrim = Color(0xFF000000),

    surfaceBright = Color(0xFF262F29),
    surfaceDim = Color(0xFF0F1412),
    surfaceContainerLowest = Color(0xFF0A0F0D),
    surfaceContainerLow = Color(0xFF121815),
    surfaceContainer = Color(0xFF171E1A),
    surfaceContainerHigh = Color(0xFF1E2621),
    surfaceContainerHighest = Color(0xFF262F29),
)

private val BrandLight = lightColorScheme(
    primary = Color(0xFF1F6B4C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC9EBD8),
    onPrimaryContainer = Color(0xFF0B3A27),
    inversePrimary = Color(0xFF8FD6B6),

    secondary = Color(0xFF4F6B57),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD6E8DA),
    onSecondaryContainer = Color(0xFF1B3324),

    tertiary = Color(0xFF7A6320),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF2E3BC),
    onTertiaryContainer = Color(0xFF3A2E10),

    error = Color(0xFFB43E3E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF161D19),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF161D19),
    surfaceVariant = Color(0xFFDFE5E0),
    onSurfaceVariant = Color(0xFF5C665F),
    surfaceTint = Color(0xFF1F6B4C),

    inverseSurface = Color(0xFF2A322D),
    inverseOnSurface = Color(0xFFE8EFEA),
    // 3.52:1 on white — WCAG 1.4.11 wants ≥3:1 for a control's boundary, and M3's own baseline
    // outline is ≈4.6:1, so this stays the lighter, quieter of the two. Fitted against the
    // *darkest* light container (#E9EFEB → 3.02) rather than white alone, so an
    // OutlinedTextField sitting inside a card is covered as well. It was #BFC9C2 (1.70:1) —
    // a border nobody could see. outlineVariant below stays untouched: that one is a hairline
    // between surfaces, not a control.
    outline = Color(0xFF848A86),
    outlineVariant = Color(0xFFE2E8E4),
    scrim = Color(0xFF000000),

    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE9EFEB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7FAF8),
    surfaceContainer = Color(0xFFF1F5F2),
    surfaceContainerHigh = Color(0xFFE9EFEB),
    surfaceContainerHighest = Color(0xFFE1E8E3),
)

internal fun brandScheme(dark: Boolean) = if (dark) BrandDark else BrandLight

/**
 * Status tones used by the pills across the capability, MCP and settings screens.
 *
 * 浅色那一套是按**实测对比度**定的，不是挑好看的：胶囊文字是 13sp `labelMedium`（小字 ⇒ WCAG AA
 * 要 4.5:1），而底色是 `color.copy(alpha = 0.15f)` 压 `surfaceContainer` —— 也就是文字压在自己的
 * 淡底上，比压在纯白上还低。深色侧四个都在 4.5 以上，不用动；浅色侧 OK/WARN/OFF 原本只有
 * 3.72 / 2.98 / 2.72，已按最小幅度压深到 4.56 / 4.57 / 4.60（INFO 5.05 本来就过）。
 * 色相仍分开：OK 167°、WARN 35°、OFF 无彩度、INFO 199°，不会糊成同一个灰。
 * 改这三个值之前请重新量一遍，别凭感觉调亮。
 */
enum class Tone { OK, WARN, OFF, INFO }

internal fun Tone.color(dark: Boolean): Color = when (this) {
    Tone.OK -> if (dark) Color(0xFF89C8AF) else Color(0xFF286C5D)
    Tone.WARN -> if (dark) Color(0xFFF0C070) else Color(0xFF865713)
    Tone.OFF -> if (dark) Color(0xFF9A958C) else Color(0xFF616161)
    Tone.INFO -> if (dark) Color(0xFF9CC3D6) else Color(0xFF2F6076)
}
