package com.suixing.agent.ui.chat.markdown

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp

/**
 * Row-level layouts retain the normal measurement cache for unchanged rows. Decorative
 * Spacer/Divider nodes and weight measurement passes are replaced by shared column geometry.
 * Text nodes stay mounted for selection and character anchors; there is no inner scroll owner.
 */
@Composable
internal fun MeasuredTable(
    rows: List<List<String>>,
    headerColor: Color,
    dividerColor: Color,
    modifier: Modifier = Modifier,
    rowContent: @Composable (Int, List<String>) -> Unit,
) {
    val columns = remember { TableColumnCache() }
    Column(modifier) {
        rows.forEachIndexed { index, cells ->
            key(index) {
                MeasuredTableRow(index, cells, columns, headerColor, dividerColor, rowContent)
            }
        }
    }
}

@Composable
private fun MeasuredTableRow(
    row: Int,
    cells: List<String>,
    cache: TableColumnCache,
    headerColor: Color,
    dividerColor: Color,
    rowContent: @Composable (Int, List<String>) -> Unit,
) {
    // Geometry changes only with this node's size/column count. Size changes invalidate its
    // drawing, and count changes recompose the draw modifier. No snapshot write in placement.
    val drawing = remember { arrayOfNulls<TableRowDrawing>(1) }
    Layout(
        modifier = Modifier.fillMaxWidth().drawBehind {
            val plan = drawing[0] ?: return@drawBehind
            if (row == 0) drawRect(headerColor)
            if (plan.inset > 0) {
                // Match HorizontalDivider's floating-point stroke at fractional densities;
                // the space reserved by layout is still the same rounded 1dp height.
                val stroke = 1.dp.toPx()
                drawLine(dividerColor, Offset(0f, stroke / 2f),
                    Offset(size.width, stroke / 2f), strokeWidth = stroke)
            }
            val rtl = layoutDirection == LayoutDirection.Rtl
            for (column in plan.columns.dividers.indices) {
                drawRect(dividerColor,
                    Offset(plan.columns.dividerX(column, plan.width, rtl).toFloat(), plan.inset.toFloat()),
                    Size(plan.columns.dividerWidths[column].toFloat(), size.height - plan.inset))
            }
        },
        content = { rowContent(row, cells) },
    ) { measurables, constraints ->
        val divider = 1.dp.roundToPx()
        val inset = if (row == 0) 0 else minOf(divider, constraints.maxHeight)
        val maximumHeight = if (constraints.maxHeight == Constraints.Infinity) Constraints.Infinity
            else (constraints.maxHeight - inset).coerceAtLeast(0)
        val naturalWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else
            maxOf(constraints.minWidth, ((cells.size - 1L).coerceAtLeast(0) * divider)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val width = constraints.constrainWidth(naturalWidth)
        val columns = cache.get(width, cells.size, divider)
        check(measurables.size == cells.size)
        val placeables = measurables.mapIndexed { index, measurable ->
            val cellWidth = columns.widths[index]
            measurable.measure(Constraints(minWidth = cellWidth, maxWidth = cellWidth, maxHeight = maximumHeight))
        }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        drawing[0] = TableRowDrawing(width, inset, columns)
        layout(width, constraints.constrainHeight(height + inset)) {
            placeables.forEachIndexed { index, cell -> cell.placeRelative(columns.starts[index], inset) }
        }
    }
}

private class TableRowDrawing(val width: Int, val inset: Int, val columns: TableColumns)
