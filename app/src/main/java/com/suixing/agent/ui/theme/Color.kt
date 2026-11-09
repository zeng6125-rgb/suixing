package com.suixing.agent.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 底层色板是墨绿（JAgent 配色从它 copy 覆盖而来，「跟随壁纸」也以它为底；Codex 使用独立色板）：
 * 一支白羽该配的底色是砚台和竹叶，不是柔紫。它已不是独立的配色选项，默认的「JAgent 配色」
 * 是下面的 Claude 暖纸色 / 陶土橙覆盖版。
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

internal enum class AppearancePalette(val id: Int, val label: String) {
    // id 0 是旧的墨绿主题，已降级移除；老设置里存着 0 的话由 fromId 落到 JAgent 配色。
    WALLPAPER(1, "跟随壁纸"),
    CLAUDE(2, "JAgent 配色"),
    CODEX(3, "Codex");

    fun scheme(dark: Boolean) = when (this) {
        WALLPAPER -> if (dark) BrandDark else BrandLight
        CLAUDE -> if (dark) ClaudeDark else ClaudeLight
        CODEX -> if (dark) CodexDark else CodexLight
    }

    /**
     * 用户气泡的专属底色；null = 沿用 `primaryContainer`。
     *
     * Claude 浅色的气泡按用户给的截图改成暖米色 `#EAE4D5`（在 `#FAF9F5` 纸底上），
     * 不用 `primaryContainer` 那档偏粉陶土色 `#F6DED4`。深色不跟着换，保持原来
     * 的深棕容器色，避免在近黑背景上出现一块突兀的浅色。
     */
    fun userBubbleColor(dark: Boolean): Color? = when (this) {
        CLAUDE -> if (dark) null else Color(0xFFEAE4D5)
        else -> null
    }

    /** Codex's sidebar is a lower surface, separate from its opaque input and popup surfaces. */
    fun drawerSurfaceColor(dark: Boolean): Color? = when (this) {
        CODEX -> if (dark) Color(0xFF141414) else Color(0xFFF6F6F6)
        else -> null
    }

    /**
     * Desktop selection also replaces text foreground. Compose keeps styled foreground, so use
     * the desktop blue with gentler opacity to keep selected supporting text readable as well.
     */
    fun textSelectionBackgroundColor(dark: Boolean): Color? = when (this) {
        CODEX -> if (dark) Color(0x3363A8F8) else Color(0x2E539AF8)
        else -> null
    }

    companion object {
        val labels = entries.map { it.label }

        /** 旧存档里的 0（已移除的墨绿）与任何未知值都落到默认的 JAgent 配色。 */
        fun fromId(id: Int): AppearancePalette = entries.firstOrNull { it.id == id } ?: CLAUDE
    }
}

private val ClaudeLight = BrandLight.copy(
    primary = Color(0xFFA94728),
    primaryContainer = Color(0xFFF6DED4),
    onPrimaryContainer = Color(0xFF54271C),
    inversePrimary = Color(0xFFE78D70),
    secondary = Color(0xFF625D52),
    secondaryContainer = Color(0xFFEFEDE4),
    onSecondaryContainer = Color(0xFF35332C),
    tertiary = Color(0xFF56664C),
    tertiaryContainer = Color(0xFFE5EADC),
    onTertiaryContainer = Color(0xFF2E3A28),
    background = Color(0xFFFAF9F5),
    onBackground = Color(0xFF22211E),
    surface = Color(0xFFFAF9F5),
    onSurface = Color(0xFF22211E),
    surfaceTint = Color(0xFFA94728),
    surfaceVariant = Color(0xFFECEAE1),
    onSurfaceVariant = Color(0xFF635F55),
    inverseSurface = Color(0xFF30302C),
    inverseOnSurface = Color(0xFFF2F0E8),
    outline = Color(0xFF827E72),
    outlineVariant = Color(0xFFDEDCD2),
    surfaceBright = Color(0xFFFAF9F5),
    surfaceDim = Color(0xFFECEAE1),
    surfaceContainerLowest = Color(0xFFFDFCF9),
    surfaceContainerLow = Color(0xFFF6F5F0),
    surfaceContainer = Color(0xFFF2F0E9),
    surfaceContainerHigh = Color(0xFFEDEBE3),
    surfaceContainerHighest = Color(0xFFE8E6DC),
)

private val ClaudeDark = BrandDark.copy(
    primary = Color(0xFFE78D70),
    onPrimary = Color(0xFF27150F),
    primaryContainer = Color(0xFF57362C),
    onPrimaryContainer = Color(0xFFFFDCCD),
    inversePrimary = Color(0xFFA94728),
    secondary = Color(0xFFC9C3B5),
    onSecondary = Color(0xFF302D26),
    secondaryContainer = Color(0xFF3D3B34),
    onSecondaryContainer = Color(0xFFE9E4D8),
    tertiary = Color(0xFFBCCBA9),
    onTertiary = Color(0xFF283421),
    tertiaryContainer = Color(0xFF394435),
    onTertiaryContainer = Color(0xFFE4EDD9),
    background = Color(0xFF262624),
    onBackground = Color(0xFFF0EEE7),
    surface = Color(0xFF262624),
    onSurface = Color(0xFFF0EEE7),
    surfaceTint = Color(0xFFE78D70),
    surfaceVariant = Color(0xFF363531),
    onSurfaceVariant = Color(0xFFC3BFB3),
    inverseSurface = Color(0xFFF0EEE7),
    inverseOnSurface = Color(0xFF22211E),
    outline = Color(0xFF8F8A7E),
    outlineVariant = Color(0xFF42413B),
    surfaceBright = Color(0xFF373633),
    surfaceDim = Color(0xFF262624),
    surfaceContainerLowest = Color(0xFF1F1F1D),
    surfaceContainerLow = Color(0xFF292927),
    surfaceContainer = Color(0xFF2D2D2A),
    surfaceContainerHigh = Color(0xFF32312E),
    surfaceContainerHighest = Color(0xFF373633),
)

/**
 * Codex desktop's default neutral theme, mapped to Material roles. Reference: installed
 * OpenAI.Codex 26.1027.3384.0, webview/assets/app-theme-98273c284c4e.js and app-initial-4267e52fe5b0.js.
 * Runtime-generated surfaces take precedence over the CSS fallbacks: sidebar F6F6F6/141414,
 * opaque controls FFFFFF/2D2D2D, popups FFFFFF/363636. Keep coloured status tones separate.
 */
private val CodexLight = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF4F4F4),
    onPrimaryContainer = Color(0xFF1A1C1F),
    inversePrimary = Color.White,

    secondary = Color(0xFF606163),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF6F6F6),
    onSecondaryContainer = Color(0xFF1A1C1F),
    tertiary = Color(0xFF606163),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDEDEE),
    onTertiaryContainer = Color(0xFF1A1C1F),

    error = Color(0xFFB43E3E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410002),

    background = Color.White,
    onBackground = Color(0xFF1A1C1F),
    surface = Color.White,
    onSurface = Color(0xFF1A1C1F),
    // Material replaces tint alpha at elevation; tinting with the surface itself keeps it neutral.
    surfaceTint = Color.White,
    surfaceVariant = Color(0xFFEDEDEE),
    onSurfaceVariant = Color(0xFF606163),
    inverseSurface = Color(0xFF282828),
    inverseOnSurface = Color.White,
    // Control outlines stay visible on Android; quiet separators use the desktop border tone.
    outline = Color(0xFF848484),
    outlineVariant = Color(0xFFEDEDEE),
    scrim = Color.Black,

    surfaceBright = Color.White,
    surfaceDim = Color(0xFFF6F6F6),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F6F6),
    surfaceContainer = Color(0xFFF6F6F6),
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,

    primaryFixed = Color(0xFFEDEDEE),
    primaryFixedDim = Color(0xFFDFDFDF),
    onPrimaryFixed = Color(0xFF1A1C1F),
    onPrimaryFixedVariant = Color(0xFF606163),
    secondaryFixed = Color(0xFFF6F6F6),
    secondaryFixedDim = Color(0xFFEDEDEE),
    onSecondaryFixed = Color(0xFF1A1C1F),
    onSecondaryFixedVariant = Color(0xFF6A6B6D),
    tertiaryFixed = Color(0xFFF6F6F6),
    tertiaryFixedDim = Color(0xFFEDEDEE),
    onTertiaryFixed = Color(0xFF1A1C1F),
    onTertiaryFixedVariant = Color(0xFF6A6B6D),
)

private val CodexDark = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF2D2D2D),
    onPrimaryContainer = Color.White,
    inversePrimary = Color.Black,

    secondary = Color(0xFFBCBCBC),
    onSecondary = Color(0xFF181818),
    secondaryContainer = Color(0xFF242424),
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFFBCBCBC),
    onTertiary = Color(0xFF181818),
    tertiaryContainer = Color(0xFF282828),
    onTertiaryContainer = Color.White,

    error = Color(0xFFFF8E8E),
    onError = Color(0xFF5C1010),
    errorContainer = Color(0xFF6E2020),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF181818),
    onBackground = Color.White,
    surface = Color(0xFF181818),
    onSurface = Color.White,
    surfaceTint = Color(0xFF181818),
    surfaceVariant = Color(0xFF2D2D2D),
    onSurfaceVariant = Color(0xFFBCBCBC),
    inverseSurface = Color.White,
    inverseOnSurface = Color(0xFF1A1C1F),
    outline = Color(0xFF858585),
    outlineVariant = Color(0xFF2B2B2B),
    scrim = Color.Black,

    surfaceBright = Color(0xFF363636),
    surfaceDim = Color(0xFF141414),
    surfaceContainerLowest = Color(0xFF141414),
    surfaceContainerLow = Color(0xFF212121),
    surfaceContainer = Color(0xFF242424),
    surfaceContainerHigh = Color(0xFF2D2D2D),
    surfaceContainerHighest = Color(0xFF363636),

    primaryFixed = Color(0xFFEDEDEE),
    primaryFixedDim = Color(0xFFDFDFDF),
    onPrimaryFixed = Color(0xFF1A1C1F),
    onPrimaryFixedVariant = Color(0xFF606163),
    secondaryFixed = Color(0xFFF6F6F6),
    secondaryFixedDim = Color(0xFFEDEDEE),
    onSecondaryFixed = Color(0xFF1A1C1F),
    onSecondaryFixedVariant = Color(0xFF6A6B6D),
    tertiaryFixed = Color(0xFFF6F6F6),
    tertiaryFixedDim = Color(0xFFEDEDEE),
    onTertiaryFixed = Color(0xFF1A1C1F),
    onTertiaryFixedVariant = Color(0xFF6A6B6D),
)

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
