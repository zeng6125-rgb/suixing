package com.lingshu.agent.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Indigo = Color(0xFF4F46E5)
private val IndigoDark = Color(0xFFB9B4FF)
private val Teal = Color(0xFF0F766E)
private val TealDark = Color(0xFF6FD8CE)
private val Amber = Color(0xFFB45309)
private val AmberDark = Color(0xFFF3C077)

@Composable
fun LingshuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> darkColorScheme(
            primary = IndigoDark,
            secondary = TealDark,
            tertiary = AmberDark,
            background = Color(0xFF111114),
            surface = Color(0xFF17171B),
            surfaceVariant = Color(0xFF23232A),
        )

        else -> lightColorScheme(
            primary = Indigo,
            secondary = Teal,
            tertiary = Amber,
            background = Color(0xFFF7F7FB),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFEDEDF3),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
