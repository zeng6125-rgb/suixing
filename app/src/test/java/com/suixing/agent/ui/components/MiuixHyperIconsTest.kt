package com.suixing.agent.ui.components

import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiuixHyperIconsTest {
    @Test
    fun `all HyperOS custom vectors build cleanly on a 24dp viewport with unified Miuix stroke weight`() {
        val all = listOf(
            MiuixHyperIcons.ArrowUp,
            MiuixHyperIcons.ArrowDown,
            MiuixHyperIcons.MenuSoft,
            MiuixHyperIcons.Circle,
            MiuixHyperIcons.CircleCheck,
            MiuixHyperIcons.BadgeCheck,
            MiuixHyperIcons.CircleAlert,
            MiuixHyperIcons.Clock,
            MiuixHyperIcons.Compass,
            MiuixHyperIcons.FolderOpen,
            MiuixHyperIcons.FolderCog,
            MiuixHyperIcons.Film,
            MiuixHyperIcons.Globe,
            MiuixHyperIcons.Bot,
            MiuixHyperIcons.Brain,
            MiuixHyperIcons.Terminal,
            MiuixHyperIcons.FileCode,
            MiuixHyperIcons.BookOpen,
            MiuixHyperIcons.Camera,
            MiuixHyperIcons.ChartColumn,
            MiuixHyperIcons.Cpu,
            MiuixHyperIcons.Keyboard,
            MiuixHyperIcons.Pointer,
            MiuixHyperIcons.Server,
            MiuixHyperIcons.Star,
            MiuixHyperIcons.StopFilled,
            MiuixHyperIcons.SparkFilled,
            MiuixHyperIcons.Wrench,
            MiuixHyperIcons.ShieldUser,
        )
        for (icon in all) {
            assertEquals("${icon.name} vw", 24f, icon.viewportWidth, 0.01f)
            assertEquals("${icon.name} vh", 24f, icon.viewportHeight, 0.01f)
            assertTrue("${icon.name} has nodes", icon.root.size > 0)
            for (node in icon.root) {
                val path = node as? VectorPath ?: continue
                if (path.stroke != null) {
                    assertTrue(
                        "${icon.name} stroke=${path.strokeLineWidth} outside Miuix Regular band [1.45..2.0]",
                        path.strokeLineWidth in 1.45f..2.0f,
                    )
                }
            }
        }
    }
}
