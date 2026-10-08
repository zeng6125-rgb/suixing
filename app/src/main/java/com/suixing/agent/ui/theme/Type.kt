package com.suixing.agent.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp

/** 小 / 标准 / 大 / 特大 → the multiplier applied to every length in the type ramp. */
private val FONT_SCALES = listOf(0.88f, 1f, 1.12f, 1.26f)

/** 方正 / 标准 / 圆润 → the multiplier applied to the card-sized corners. */
private val CORNER_SCALES = listOf(0.55f, 1f, 1.5f)

private val Sans = FontFamily.Default

/**
 * 圆角比 Material 默认略大（卡片 20dp），但没有大到「胶囊化」——
 * Aether 用的就是 Material 默认圆角，靠的是配色与留白而不是圆角堆出来的可爱感。
 *
 * `extraSmall` 不跟档位走：它是 chip 那一级的发丝圆角，放大会把控件撑成胶囊。
 */
fun suixingShapes(cornerScale: Int): Shapes {
    val scale = CORNER_SCALES.getOrElse(cornerScale) { 1f }
    return Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp * scale),
        medium = RoundedCornerShape(16.dp * scale),
        large = RoundedCornerShape(20.dp * scale),
        extraLarge = RoundedCornerShape(28.dp * scale),
    )
}

/** The 标准 shape set; [SuixingTheme] builds its own from the 圆角 setting. */
val SuixingShapes = suixingShapes(1)

/**
 * 行高按 Aether 的取值放大（正文 15/24、17/28），标题给负字距。
 * Material 默认行高在手机上偏挤，中文尤其容易糊成一团。
 */
private val BaseTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.9).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 29.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 31.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 29.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 25.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 28.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 24.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.4.sp,
    ),
)

/**
 * The whole ramp for one 字号 tier. Scaling runs over every role, not just the body
 * styles: leaving the headings alone would flatten the hierarchy at 特大.
 */
fun suixingTypography(fontScale: Int): Typography {
    val scale = FONT_SCALES.getOrElse(fontScale) { 1f }
    return Typography(
        displayLarge = BaseTypography.displayLarge.scaled(scale),
        displayMedium = BaseTypography.displayMedium.scaled(scale),
        displaySmall = BaseTypography.displaySmall.scaled(scale),
        headlineLarge = BaseTypography.headlineLarge.scaled(scale),
        headlineMedium = BaseTypography.headlineMedium.scaled(scale),
        headlineSmall = BaseTypography.headlineSmall.scaled(scale),
        titleLarge = BaseTypography.titleLarge.scaled(scale),
        titleMedium = BaseTypography.titleMedium.scaled(scale),
        titleSmall = BaseTypography.titleSmall.scaled(scale),
        bodyLarge = BaseTypography.bodyLarge.scaled(scale),
        bodyMedium = BaseTypography.bodyMedium.scaled(scale),
        bodySmall = BaseTypography.bodySmall.scaled(scale),
        labelLarge = BaseTypography.labelLarge.scaled(scale),
        labelMedium = BaseTypography.labelMedium.scaled(scale),
        labelSmall = BaseTypography.labelSmall.scaled(scale),
    )
}

/** The 标准 ramp, which is what the app shipped with. */
val SuixingTypography = suixingTypography(1)

/**
 * Every length in [this] times [scale]. A role the ramp leaves unset has no length
 * to scale, so it stays unset rather than snapping to some default size.
 */
private fun TextStyle.scaled(scale: Float): TextStyle = copy(
    fontSize = fontSize.scaled(scale),
    lineHeight = lineHeight.scaled(scale),
    letterSpacing = letterSpacing.scaled(scale),
)

private fun TextUnit.scaled(scale: Float): TextUnit =
    if (isUnspecified) this else (value * scale).sp

/** Used for tool arguments, file contents and anything the model produced verbatim. */
val MonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 19.sp,
)
