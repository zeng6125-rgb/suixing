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
    val scheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        brandScheme(darkTheme)
    }
    // Rebuilding the ramp is cheap but not free, and a tier only moves in settings,
    // so the two of them are keyed on the setting instead of running every recomposition.
    val typography = remember(fontScale) { suixingTypography(fontScale) }
    val shapes = remember(cornerScale) { suixingShapes(cornerScale) }
    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            shapes = shapes,
            content = content,
        )
    }
}
