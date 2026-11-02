package com.suixing.agent.ui.chat.markdown

import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainWidth
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Compares our table geometry with the real Foundation Row measure policy. The policy and
 * RowColumnParentData are internal Foundation implementation details, so this test resolves them
 * reflectively while still exercising the shipped measurement code instead of copying its math.
 */
class TableFoundationGeometryTest {
    @Test
    fun `weighted row cell and spacer widths match table columns in both directions`() {
        val policy = foundationDefaultRowPolicy()
        val measure = policy.javaClass.methods.single { method ->
            method.name.startsWith("measure-") &&
                method.parameterCount == 3 &&
                method.parameterTypes[0] == MeasureScope::class.java &&
                List::class.java.isAssignableFrom(method.parameterTypes[1]) &&
                method.parameterTypes[2] == Long::class.javaPrimitiveType
        }.apply { isAccessible = true }

        // Covers sub-pixel separator pressure, both signs of the rounded-share remainder, exact
        // division, one/zero cells, and a wider many-column case without creating thousands of mocks.
        val cases = listOf(
            0 to 0,
            0 to 1,
            0 to 5,
            5 to 0,
            1 to 3,
            3 to 5,
            4 to 2,
            17 to 3,
            100 to 3,
            100 to 4,
            101 to 3,
            257 to 9,
        )

        for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            val scope = mockk<MeasureScope>(relaxed = true) {
                every { density } returns 1f
                every { layoutDirection } returns direction
            }
            for ((width, count) in cases) {
                val expected = TableColumns(width, count, divider = 1)
                val cellWidths = IntArray(count) { Int.MIN_VALUE }
                val dividerWidths = IntArray((count - 1).coerceAtLeast(0)) { Int.MIN_VALUE }
                val children = ArrayList<Measurable>(count * 2)

                for (column in 0 until count) {
                    children += fakeChild(
                        parentData = weightedCellParentData(),
                        desiredWidth = { it.maxWidth },
                        desiredHeight = 12,
                        onMeasure = { cellWidths[column] = it.maxWidth },
                    )
                    if (column < count - 1) {
                        children += fakeChild(
                            parentData = null,
                            desiredWidth = { it.constrainWidth(1) },
                            desiredHeight = 0,
                            onMeasure = { dividerWidths[column] = it.constrainWidth(1) },
                        )
                    }
                }

                measure.invoke(policy, scope, children, packedConstraints(Constraints.fixedWidth(width)))

                assertEquals("cell widths for $width px, $count columns, $direction",
                    expected.widths.toList(), cellWidths.toList())
                assertEquals("divider widths for $width px, $count columns, $direction",
                    expected.dividerWidths.toList(), dividerWidths.toList())
            }
        }
    }

    private fun fakeChild(
        parentData: Any?,
        desiredWidth: (Constraints) -> Int,
        desiredHeight: Int,
        onMeasure: (Constraints) -> Unit,
    ): Measurable = FakeMeasurable(parentData, desiredWidth, desiredHeight, onMeasure)

    // Keep this concrete: Compose's value-class Constraints is a JVM long at the interface call
    // boundary, and a MockK answer sees that raw Long rather than a boxed Constraints argument.
    private class FakeMeasurable(
        override val parentData: Any?,
        private val desiredWidth: (Constraints) -> Int,
        private val desiredHeight: Int,
        private val onMeasure: (Constraints) -> Unit,
    ) : Measurable {
        override fun measure(constraints: Constraints): Placeable {
            onMeasure(constraints)
            val placeableWidth = desiredWidth(constraints)
            return mockk<Placeable>(relaxed = true).also { placeable ->
                every { placeable.width } returns placeableWidth
                every { placeable.height } returns desiredHeight
            }
        }

        override fun minIntrinsicWidth(height: Int): Int = 0
        override fun maxIntrinsicWidth(height: Int): Int = 0
        override fun minIntrinsicHeight(width: Int): Int = 0
        override fun maxIntrinsicHeight(width: Int): Int = 0
    }

    private fun foundationDefaultRowPolicy(): Any {
        val rowKt = Class.forName("androidx.compose.foundation.layout.RowKt")
        val getter = rowKt.declaredMethods.single { method ->
            method.name == "getDefaultRowMeasurePolicy" && method.parameterCount == 0
        }.apply { isAccessible = true }
        return requireNotNull(getter.invoke(null)) { "Foundation's default Row measure policy was null" }
    }

    private fun weightedCellParentData(): Any {
        val dataClass = Class.forName("androidx.compose.foundation.layout.RowColumnParentData")
        val constructor = dataClass.declaredConstructors.single { candidate ->
            candidate.parameterCount == 4 &&
                candidate.parameterTypes[0] == Float::class.javaPrimitiveType &&
                candidate.parameterTypes[1] == Boolean::class.javaPrimitiveType
        }.apply { isAccessible = true }
        return constructor.newInstance(1f, true, null, null)
    }

    private fun packedConstraints(constraints: Constraints): Long {
        val unbox = Constraints::class.java.getDeclaredMethod("unbox-impl").apply { isAccessible = true }
        return unbox.invoke(constraints) as Long
    }
}
