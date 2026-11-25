package com.suixing.agent.ui.chat

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.offset
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Measure the editor before its optional panels, and the actions before the editor. */
internal val ComposerStackMeasurePolicy = MeasurePolicy { measurables, constraints ->
    val loose = constraints.copy(minWidth = 0, minHeight = 0)
    var width = 0
    var height = 0
    val bottomUp = Array(measurables.size) { index ->
        val child = measurables[measurables.lastIndex - index].measure(loose.offset(vertical = -height))
        width = maxOf(width, child.width)
        height += child.height
        child
    }
    layout(constraints.constrainWidth(width), constraints.constrainHeight(height)) {
        var y = 0
        for (index in bottomUp.indices.reversed()) {
            val child = bottomUp[index]
            child.placeRelative(0, y)
            y += child.height
        }
    }
}

/** Stabilizes the single-line baseline while preserving the multiline editor viewport. */
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
    if (lines() > 1) {
        // BasicTextField already limits and scrolls its viewport. Place it intact above the controls.
        return layout(child.width, constraints.constrainHeight(child.height)) {
            child.placeRelative(0, 0)
        }
    }
    val height = constraints.constrainHeight(ceil(lineHeightPx).toInt())
    val baseline = baselinePx.roundToInt()
    val naturalBaseline = child[FirstBaseline]
    val top = if (naturalBaseline == AlignmentLine.Unspecified) 0 else baseline - naturalBaseline
    return layout(child.width, height, mapOf(FirstBaseline to baseline)) {
        // Anchor the glyph baseline, not the font-dependent paragraph rectangle. CJK fallback
        // can change ascent/descent even when the requested line height remains exactly fixed.
        child.placeRelative(0, top)
    }
}
