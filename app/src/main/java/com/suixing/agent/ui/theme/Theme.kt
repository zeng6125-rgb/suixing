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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the *effective* scheme is dark. Screens need this for the few colours
 * that are not Material roles (status tones), and reading the system setting
 * directly would be wrong when the user forces light or dark in settings.
 */
val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
fun SuixingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
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
    val scheme = remember(context, darkTheme, dynamicColor, configuration) {
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            brandScheme(darkTheme)
        }
    }
    // Rebuilding the ramp is cheap but not free, and a tier only moves in settings,
    // so the two of them are keyed on the setting instead of running every recomposition.
    val typography = remember(fontScale) { suixingTypography(fontScale) }
    val shapes = remember(cornerScale) { suixingShapes(cornerScale) }
    // **选中高亮必须自己给**（用户：「有些地方长按复制没有底色，看不出选了哪儿」）：
    // 全项目此前没有设置 `LocalTextSelectionColors`，用的是 Compose 默认值 ——
    // 它由 primary 派生、透明度很低，浅色画布上几乎看不见。
    // 这里显式给一个明显的底色（primary 32%）和手柄色，全应用统一。
    val selectionColors = remember(scheme.primary) {
        androidx.compose.foundation.text.selection.TextSelectionColors(
            handleColor = scheme.primary,
            backgroundColor = scheme.primary.copy(alpha = 0.32f),
        )
    }
    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
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
