package com.suixing.agent.ui.chat

import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercises the production measure path with different font boxes; this is not device font QA. */
class ComposerFieldLayoutTest {
    private val scope = object : MeasureScope {
        override val density = 1f
        override val fontScale = 1f
        override val layoutDirection = LayoutDirection.Ltr
    }

    @Test fun latinToChineseAndBackKeepOneLineFieldHeight() {
        val constraints = Constraints(minHeight = 90, maxWidth = 500)
        val heights = listOf(78, 98, 78).map { naturalHeight ->
            with(scope) { measureComposerField(child(naturalHeight), constraints, 78f) { 1 }.height }
        }
        assertEquals(listOf(90, 90, 90), heights)
    }

    @Test fun wrappedLineCountChangesHeightRatherThanFallbackFontBox() {
        val constraints = Constraints(maxWidth = 500)
        for (natural in listOf(156, 164)) {
            val result = with(scope) { measureComposerField(child(natural), constraints, 78f) { 2 } }
            assertEquals(156, result.height)
            assertEquals(500, result.width)
        }
    }

    @Test fun textLayoutLineCountIsConsumedInTheSameMeasurementPass() {
        var lines = 1
        val measurable = child(164) { lines = 2 }
        val result = with(scope) { measureComposerField(measurable, Constraints(maxWidth = 500), 78f) { lines } }
        assertEquals(156, result.height)
    }

    @Test fun fontScaleAndFiveLineLimitRemainActive() {
        val result = with(scope) {
            measureComposerField(child(700), Constraints(maxWidth = 500), 101.4f) { 8 }
        }
        assertEquals(507, result.height)
    }

    @Test fun changingFallbackAscentMovesTheParagraphButKeepsTheEnglishBaselineFixed() {
        val visibleBaselines = mutableListOf<Int>()
        val positions = mutableListOf<Int>()
        for ((height, baseline) in listOf(78 to 54, 98 to 67, 78 to 54)) {
            val measurable = object : Measurable {
                override val parentData: Any? = null
                override fun measure(constraints: Constraints): Placeable = object : Placeable() {
                    init { measuredSize = IntSize(500, height) }
                    override fun get(alignmentLine: AlignmentLine): Int =
                        if (alignmentLine == FirstBaseline) baseline else AlignmentLine.Unspecified
                    override fun placeAt(position: IntOffset, zIndex: Float, layerBlock: (GraphicsLayerScope.() -> Unit)?) {
                        positions += position.y
                        visibleBaselines += position.y + baseline
                    }
                }
                override fun minIntrinsicWidth(height: Int) = 0
                override fun maxIntrinsicWidth(height: Int) = 500
                override fun minIntrinsicHeight(width: Int) = height
                override fun maxIntrinsicHeight(width: Int) = height
            }
            val result = with(scope) {
                measureComposerField(measurable, Constraints(maxWidth = 500), 78f, 54f) { 1 }
            }
            result.placeChildren()
        }
        assertEquals(listOf(54, 54, 54), visibleBaselines)
        assertEquals(listOf(0, -13, 0), positions)
    }

    private fun child(naturalHeight: Int, measured: () -> Unit = {}): Measurable =
        object : Measurable {
            override val parentData: Any? = null
            override fun measure(constraints: Constraints): Placeable {
                measured()
                return mockk<Placeable>(relaxed = true).also {
                    every { it.width } returns constraints.maxWidth
                    every { it.height } returns naturalHeight
                }
            }
            override fun minIntrinsicWidth(height: Int) = 0
            override fun maxIntrinsicWidth(height: Int) = 0
            override fun minIntrinsicHeight(width: Int) = naturalHeight
            override fun maxIntrinsicHeight(width: Int) = naturalHeight
        }
}
