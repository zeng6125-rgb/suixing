package com.suixing.agent.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 配色取自 Aether（扶摇）的主题取值：柔紫主色 + 暖灰三级表面阶梯 + 米白正文。
 * 只对齐「取值」，代码是自己写的（Aether 是 GPL-3.0，随行是 MIT）。
 *
 * 深色不是纯黑而是 #151619，表面阶梯 #1C1F23 → #24282D → #2C3036 三层叠加，
 * 描边用 #3D424A 这种「比表面亮一点点」的灰，所以整屏读起来是连续的而不是一块块切开的。
 * 浅色是纯白底 + 暖灰面（#F3F3F2 / #ECECEC），比冷灰更耐看。
 */
private val BrandDark = darkColorScheme(
    primary = Color(0xFFC0AEFF),
    onPrimary = Color(0xFF251448),
    primaryContainer = Color(0xFF3A275F),
    onPrimaryContainer = Color(0xFFF0E9FF),
    inversePrimary = Color(0xFFAD7BF9),

    secondary = Color(0xFF89C8AF),
    onSecondary = Color(0xFF143126),
    secondaryContainer = Color(0xFF24483A),
    onSecondaryContainer = Color(0xFFDDF6EA),

    tertiary = Color(0xFFD1C2FF),
    onTertiary = Color(0xFF2A1B52),
    tertiaryContainer = Color(0xFF45327A),
    onTertiaryContainer = Color(0xFFEDE6FF),

    error = Color(0xFFFF8E8E),
    onError = Color(0xFF5C1010),
    errorContainer = Color(0xFF6E2020),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF151619),
    onBackground = Color(0xFFF3F1EC),
    surface = Color(0xFF151619),
    onSurface = Color(0xFFF3F1EC),
    surfaceVariant = Color(0xFF343941),
    onSurfaceVariant = Color(0xFFB9B4AA),
    surfaceTint = Color(0xFFC0AEFF),

    inverseSurface = Color(0xFFF3F1EC),
    inverseOnSurface = Color(0xFF1C1F23),
    outline = Color(0xFF4A5059),
    outlineVariant = Color(0xFF3D424A),
    scrim = Color(0xFF000000),

    surfaceBright = Color(0xFF343941),
    surfaceDim = Color(0xFF151619),
    surfaceContainerLowest = Color(0xFF111214),
    surfaceContainerLow = Color(0xFF191B1F),
    surfaceContainer = Color(0xFF1C1F23),
    surfaceContainerHigh = Color(0xFF24282D),
    surfaceContainerHighest = Color(0xFF2C3036),
)

private val BrandLight = lightColorScheme(
    primary = Color(0xFFAD7BF9),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF1E5FF),
    onPrimaryContainer = Color(0xFF4D2F8E),
    inversePrimary = Color(0xFFC0AEFF),

    secondary = Color(0xFF4A7B6B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDF1E8),
    onSecondaryContainer = Color(0xFF1E4A3B),

    tertiary = Color(0xFF9A7DF8),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEDE4FF),
    onTertiaryContainer = Color(0xFF3A2470),

    error = Color(0xFFB43E3E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF202123),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF202123),
    surfaceVariant = Color(0xFFE5E5E5),
    onSurfaceVariant = Color(0xFF6B6B6B),
    surfaceTint = Color(0xFFAD7BF9),

    inverseSurface = Color(0xFF2C3036),
    inverseOnSurface = Color(0xFFF3F1EC),
    outline = Color(0xFFD9D9D9),
    outlineVariant = Color(0xFFE7E7E7),
    scrim = Color(0xFF000000),

    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFECECEC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF9F9F9),
    surfaceContainer = Color(0xFFF3F3F2),
    surfaceContainerHigh = Color(0xFFECECEC),
    surfaceContainerHighest = Color(0xFFE5E5E5),
)

internal fun brandScheme(dark: Boolean) = if (dark) BrandDark else BrandLight

/** Status tones used by the pills across the capability, MCP and settings screens. */
enum class Tone { OK, WARN, OFF, INFO }

internal fun Tone.color(dark: Boolean): Color = when (this) {
    Tone.OK -> if (dark) Color(0xFF89C8AF) else Color(0xFF2E7D6B)
    Tone.WARN -> if (dark) Color(0xFFF0C070) else Color(0xFFB4741A)
    Tone.OFF -> if (dark) Color(0xFF9A958C) else Color(0xFF8A8A8A)
    Tone.INFO -> if (dark) Color(0xFFB9B4FF) else Color(0xFF5B5BD6)
}
