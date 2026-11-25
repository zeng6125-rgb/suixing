package com.suixing.agent.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.defaultTextStyles
import top.yukonga.miuix.kmp.theme.lightColorScheme
import kotlin.math.abs

/**
 * 为 `true` 时，外观处于官方第三方 Miuix (`top.yukonga.miuix.kmp`) 主题模式：
 * 根部由 [MiuixTheme] 提供 HyperOS 字体层级、组件规范与反馈，
 * 并通过 [miuixColorsFrom] 与当前选中的配色（跟随壁纸 / JAgent / MIUIX 默认）同步。
 *
 * 注：Android 运行时由 D8/ART 加载 Java 21 字节码的 Miuix 组件；纯 JVM 单元测试则读取
 * `Color.kt` 中的静态调色板，避免 JDK 17 单测进程触发 classfile 65 错误。
 */
internal val LocalUseMiuix = staticCompositionLocalOf { false }

/** 当前设置的圆角档位（0 方正 / 1 标准 / 2 圆润），供 Miuix 组件与 Card/Button 保持响应。 */
internal val LocalMiuixCornerScale = staticCompositionLocalOf { 1 }

internal fun miuixScaledCorner(base: Dp, cornerScale: Int): Dp =
    base * CORNER_SCALES.getOrElse(cornerScale) { 1f }

internal fun scaleMiuixTextStyles(fontScale: Int, serif: Boolean = false): TextStyles {
    val factor = FONT_SCALES.getOrElse(fontScale) { 1f }
    val base = defaultTextStyles()
    if (factor == 1f && !serif) return base
    fun TextUnit.scaled(): TextUnit = if (isUnspecified) this else (value * factor).sp
    fun TextStyle.applied(): TextStyle {
        val sized = if (factor == 1f) this else copy(
            fontSize = fontSize.scaled(),
            lineHeight = lineHeight.scaled(),
            letterSpacing = letterSpacing.scaled(),
        )
        return if (serif) sized.copy(fontFamily = FontFamily.Serif) else sized
    }
    return base.copy(
        main = base.main.applied(),
        paragraph = base.paragraph.applied(),
        body1 = base.body1.applied(),
        body2 = base.body2.applied(),
        button = base.button.applied(),
        footnote1 = base.footnote1.applied(),
        footnote2 = base.footnote2.applied(),
        headline1 = base.headline1.applied(),
        headline2 = base.headline2.applied(),
        subtitle = base.subtitle.applied(),
        title1 = base.title1.applied(),
        title2 = base.title2.applied(),
        title3 = base.title3.applied(),
        title4 = base.title4.applied(),
    )
}

internal fun miuixColorsFrom(scheme: ColorScheme, dark: Boolean): Colors {
    val cardSurface = if (dark) {
        if (abs(scheme.surfaceContainer.luminance() - scheme.background.luminance()) < 0.015f) {
            lerp(scheme.background, Color.White, 0.06f)
        } else {
            scheme.surfaceContainer
        }
    } else {
        if (scheme.background == Color.White) {
            scheme.surfaceContainer
        } else {
            lerp(scheme.surfaceContainerLowest, Color.White, 0.55f)
        }
    }
    val fieldSurface = if (dark) {
        lerp(cardSurface, Color.White, 0.06f)
    } else {
        if (scheme.background == Color.White) {
            Color(0xFFEDEDEE)
        } else {
            scheme.surfaceContainerHigh
        }
    }
    val highSurface = if (dark) {
        lerp(cardSurface, Color.White, 0.08f)
    } else {
        lerp(cardSurface, scheme.onSurface, 0.06f)
    }
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = scheme.primary,
        onPrimary = scheme.onPrimary,
        primaryVariant = scheme.primary,
        onPrimaryVariant = scheme.onPrimary,
        error = scheme.error,
        onError = scheme.onError,
        errorContainer = scheme.errorContainer,
        onErrorContainer = scheme.onErrorContainer,
        disabledPrimary = lerp(scheme.surface, scheme.primary, 0.35f),
        disabledOnPrimary = lerp(scheme.surface, scheme.onPrimary, 0.60f),
        disabledPrimaryButton = lerp(scheme.surface, scheme.primary, 0.32f),
        disabledOnPrimaryButton = scheme.onPrimary.copy(alpha = 0.72f),
        disabledPrimarySlider = lerp(scheme.surface, scheme.primary, 0.40f),
        primaryContainer = scheme.primaryContainer,
        onPrimaryContainer = scheme.onPrimaryContainer,
        secondary = lerp(scheme.surface, scheme.onSurface, if (dark) 0.24f else 0.10f),
        onSecondary = scheme.onSurface,
        secondaryVariant = fieldSurface,
        onSecondaryVariant = scheme.onSurface,
        disabledSecondary = cardSurface,
        disabledOnSecondary = scheme.onSurface.copy(alpha = 0.38f),
        disabledSecondaryVariant = cardSurface,
        disabledOnSecondaryVariant = scheme.onSurface.copy(alpha = 0.38f),
        secondaryContainer = fieldSurface,
        onSecondaryContainer = scheme.onSurfaceVariant,
        secondaryContainerVariant = cardSurface,
        onSecondaryContainerVariant = scheme.onSurfaceVariant,
        tertiaryContainer = scheme.primaryContainer,
        onTertiaryContainer = scheme.onPrimaryContainer,
        tertiaryContainerVariant = scheme.secondaryContainer,
        background = scheme.background,
        onBackground = scheme.onBackground,
        onBackgroundVariant = scheme.onSurfaceVariant,
        surface = scheme.surface,
        onSurface = scheme.onSurface,
        surfaceVariant = cardSurface,
        onSurfaceSecondary = scheme.onSurface.copy(alpha = 0.80f),
        onSurfaceVariantSummary = scheme.onSurfaceVariant,
        onSurfaceVariantActions = scheme.onSurfaceVariant,
        disabledOnSurface = scheme.onSurface.copy(alpha = 0.38f),
        surfaceContainer = cardSurface,
        onSurfaceContainer = scheme.onSurface,
        onSurfaceContainerVariant = scheme.onSurfaceVariant,
        surfaceContainerHigh = highSurface,
        onSurfaceContainerHigh = scheme.onSurface,
        surfaceContainerHighest = scheme.surfaceContainerHighest,
        onSurfaceContainerHighest = scheme.onSurface,
        outline = scheme.outline,
        dividerLine = scheme.outlineVariant,
        windowDimming = Color.Black.copy(alpha = if (dark) 0.6f else 0.3f),
        sliderKeyPoint = scheme.onSurface.copy(alpha = 0.30f),
        sliderKeyPointForeground = lerp(scheme.primary, Color.White, if (dark) 0.20f else 0.35f),
        sliderBackground = scheme.onSurface.copy(alpha = if (dark) 0.16f else 0.08f),
    )
}

@Composable
internal fun MiuixPaletteContainer(
    scheme: ColorScheme,
    darkTheme: Boolean,
    fontScale: Int,
    serif: Boolean,
    nativeMiuixPalette: Boolean = false,
    content: @Composable () -> Unit,
) {
    val controller = remember(scheme, darkTheme, nativeMiuixPalette) {
        val mapped = if (nativeMiuixPalette) {
            if (darkTheme) darkColorScheme() else lightColorScheme()
        } else {
            miuixColorsFrom(scheme, darkTheme)
        }
        ThemeController(
            colorSchemeMode = if (darkTheme) ColorSchemeMode.Dark else ColorSchemeMode.Light,
            lightColors = if (!darkTheme) mapped else lightColorScheme(),
            darkColors = if (darkTheme) mapped else darkColorScheme(),
        )
    }
    val textStyles = remember(fontScale, serif) { scaleMiuixTextStyles(fontScale, serif = serif) }
    MiuixTheme(
        controller = controller,
        textStyles = textStyles,
        content = content,
    )
}
