package com.suixing.agent.ui.chat

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Font fallback may change the paragraph box; only wrapped line count changes the field box. */
internal fun Modifier.composerFieldHeight(
    lineHeightPx: Float,
    baselinePx: Float,
    lines: () -> Int,
): Modifier =
    layout { measurable, constraints ->
        measureComposerField(measurable, constraints, lineHeightPx, baselinePx, lines)
    }

internal fun MeasureScope.measureComposerField(
    measurable: Measurable,
    constraints: Constraints,
    lineHeightPx: Float,
    baselinePx: Float = lineHeightPx * 0.75f,
    lines: () -> Int,
): MeasureResult {
    // Measure first: onTextLayout publishes the wrapped line count during this same pass.
    val child = measurable.measure(constraints.copy(minHeight = 0))
    val height = constraints.constrainHeight(ceil(lineHeightPx * lines().coerceIn(1, 5)).toInt())
    val baseline = baselinePx.roundToInt()
    val naturalBaseline = child[FirstBaseline]
    val top = if (naturalBaseline == AlignmentLine.Unspecified) 0 else baseline - naturalBaseline
    return layout(child.width, height, mapOf(FirstBaseline to baseline)) {
        // Anchor the glyph baseline, not the font-dependent paragraph rectangle. CJK fallback
        // can change ascent/descent even when the requested line height remains exactly fixed.
        child.placeRelative(0, top)
    }
}
