package com.suixing.agent.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the *effective* scheme is dark. Screens need this for the few colours
 * that are not Material roles (status tones), and reading the system setting
 * directly would be wrong when the user forces light or dark in settings.
 */
val LocalDarkTheme = staticCompositionLocalOf { false }

/**
 * 用户气泡的底色。默认 null ⇒ 用 Material 的 `primaryContainer`，也就是JAgent / 壁纸 /
 * Codex 与深色 Claude 的原样。只有 Claude 浅色单独给一个暖米色（用户给的截图色
 * `#EAE4D5`），因为 `primaryContainer` 同时被模型 chip、助手选中态等 5 处复用，
 * 改那个槽位会把它们一起带走。
 */
val LocalUserBubbleColor = staticCompositionLocalOf<Color?> { null }

/** Optional sidebar surface when a palette distinguishes the sidebar from input controls. */
val LocalDrawerSurfaceColor = staticCompositionLocalOf<Color?> { null }

@Composable
fun SuixingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    paletteMode: Int = 2,
    fontScale: Int = 1,
    cornerScale: Int = 1,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // **配色方案要记住**（性能审计 P0.1）：ColorScheme 有约 50 个 Color，重建一次不便宜；
    // 同一函数里的 `typography`/`shapes` 都 remember 了，只有 scheme 每次重组都重算 ——
    // 而外壳（MainActivity）每次写设置都会重组一次，等于每次改设置都重建整份配色。
    // 键里带 `LocalConfiguration`：动态取色跟着壁纸与深浅色走，换壁纸必须重新取一次。
    val configuration = LocalConfiguration.current
    val palette = AppearancePalette.fromId(paletteMode)
    val scheme = remember(context, darkTheme, palette, configuration) {
        if (palette == AppearancePalette.WALLPAPER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            palette.scheme(darkTheme)
        }
    }
    // Rebuilding the ramp is cheap but not free, and a tier only moves in settings,
    // so the two of them are keyed on the setting instead of running every recomposition.
    val useSerif = palette == AppearancePalette.CLAUDE
    val typography = remember(fontScale, useSerif) { suixingTypography(fontScale, serif = useSerif) }
    val shapes = remember(cornerScale) { suixingShapes(cornerScale) }
    // **选中高亮必须自己给**（用户：「有些地方长按复制没有底色，看不出选了哪儿」）：
    // 全项目此前没有设置 `LocalTextSelectionColors`，用的是 Compose 默认值 ——
    // 它由 primary 派生、透明度很低，浅色画布上几乎看不见。
    // Codex follows the desktop's independent blue selection; other palettes use primary 32%.
    val selectionBackground = palette.textSelectionBackgroundColor(darkTheme)
    val selectionColors = remember(scheme.primary, selectionBackground) {
        androidx.compose.foundation.text.selection.TextSelectionColors(
            handleColor = scheme.primary,
            backgroundColor = selectionBackground ?: scheme.primary.copy(alpha = 0.32f),
        )
    }
    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
        LocalUserBubbleColor provides palette.userBubbleColor(darkTheme),
        LocalDrawerSurfaceColor provides palette.drawerSurfaceColor(darkTheme),
        androidx.compose.foundation.text.selection.LocalTextSelectionColors provides selectionColors,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            shapes = shapes,
            content = content,
        )
    }
}
