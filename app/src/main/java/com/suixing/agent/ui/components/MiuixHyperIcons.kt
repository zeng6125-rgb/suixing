package com.suixing.agent.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.unit.dp

/**
 * 补全并统一 MIUIX (`HyperOS`) 视觉风格的矢量图标集：
 * - 与 `miuix-icons` (`MiuixIcons.Regular.*`) 在线宽、外轮廓占有率与视觉重心上完全一致：
 *   统一使用 `24dp` 视口、`[2.0..22.0]` 主轴外轮廓（`20.0dp` 占有率）、`1.65f` 描边（对应官方
 *   `1.60–1.68dp` 线条）及圆头圆角；
 * - 不直接依赖 `top.yukonga.miuix.kmp.*` 字节码，纯 Jetpack Compose `ImageVector` 实现。
 */
internal object MiuixHyperIcons {
    private const val STROKE = 1.65f
    private val BLACK = SolidColor(Color.Black)

    val ArrowUp: ImageVector by lazy {
        strokeIcon("HyperArrowUp") {
            stroke {
                moveTo(12f, 21.15f)
                verticalLineTo(2.85f)
                moveTo(5.5f, 9.35f)
                lineTo(12f, 2.85f)
                lineTo(18.5f, 9.35f)
            }
        }
    }

    val ArrowDown: ImageVector by lazy {
        strokeIcon("HyperArrowDown") {
            stroke {
                moveTo(12f, 2.85f)
                verticalLineTo(21.15f)
                moveTo(5.5f, 14.65f)
                lineTo(12f, 21.15f)
                lineTo(18.5f, 14.65f)
            }
        }
    }

    val MenuSoft: ImageVector by lazy {
        strokeIcon("HyperMenuSoft") {
            stroke(width = 1.85f) {
                moveTo(2.95f, 6.2f); horizontalLineTo(21.05f)
                moveTo(2.95f, 12.0f); horizontalLineTo(21.05f)
                moveTo(2.95f, 17.8f); horizontalLineTo(21.05f)
            }
        }
    }

    val Circle: ImageVector by lazy {
        strokeIcon("HyperCircle") {
            stroke { addCircle(12f, 12f, 9.17f) }
        }
    }

    val CircleCheck: ImageVector by lazy {
        strokeIcon("HyperCircleCheck") {
            stroke {
                addCircle(12f, 12f, 9.17f)
                moveTo(8.0f, 12.2f)
                lineTo(10.8f, 15.0f)
                lineTo(16.2f, 9.3f)
            }
        }
    }

    val BadgeCheck: ImageVector by lazy {
        strokeIcon("HyperBadgeCheck") {
            stroke {
                addRoundedRect(2.85f, 2.85f, 21.15f, 21.15f, 5.5f)
                moveTo(8.0f, 12.2f)
                lineTo(10.8f, 15.0f)
                lineTo(16.1f, 9.3f)
            }
        }
    }

    val CircleAlert: ImageVector by lazy {
        strokeIcon("HyperCircleAlert") {
            stroke {
                addCircle(12f, 12f, 9.17f)
                moveTo(12f, 7.6f)
                verticalLineTo(12.6f)
            }
            fill { addCircle(12f, 15.9f, 1.08f) }
        }
    }

    val Clock: ImageVector by lazy {
        strokeIcon("HyperClock") {
            stroke {
                addCircle(12f, 12f, 9.17f)
                moveTo(12f, 7.0f)
                verticalLineTo(12.2f)
                lineTo(15.5f, 14.2f)
            }
        }
    }

    val Compass: ImageVector by lazy {
        strokeIcon("HyperCompass") {
            stroke {
                addCircle(12f, 12f, 9.17f)
            }
            stroke(width = 1.55f) {
                moveTo(15.7f, 8.3f)
                lineTo(10.2f, 10.2f)
                lineTo(8.3f, 15.7f)
                lineTo(13.8f, 13.8f)
                close()
            }
        }
    }

    val FolderOpen: ImageVector by lazy {
        strokeIcon("HyperFolderOpen") {
            stroke {
                // 对齐官方 MiuixIcons.Regular.Folder ([2.00..22.00] x [3.26..20.74])
                moveTo(2.9f, 19.3f)
                verticalLineTo(5.45f)
                quadTo(2.9f, 4.1f, 4.25f, 4.1f)
                horizontalLineTo(8.9f)
                lineTo(10.75f, 6.35f)
                horizontalLineTo(17.75f)
                quadTo(18.95f, 6.35f, 18.95f, 7.55f)
                verticalLineTo(9.6f)
                moveTo(2.9f, 19.9f)
                horizontalLineTo(17.85f)
                quadTo(19.0f, 19.9f, 19.4f, 18.8f)
                lineTo(21.05f, 11.15f)
                quadTo(21.3f, 10.0f, 20.1f, 10.0f)
                horizontalLineTo(7.1f)
                quadTo(6.0f, 10.0f, 5.6f, 11.0f)
                close()
            }
        }
    }

    val FolderCog: ImageVector by lazy {
        strokeIcon("HyperFolderCog") {
            stroke {
                moveTo(11.6f, 19.4f)
                horizontalLineTo(4.2f)
                quadTo(2.9f, 19.4f, 2.9f, 18.1f)
                verticalLineTo(5.4f)
                quadTo(2.9f, 4.1f, 4.2f, 4.1f)
                horizontalLineTo(8.95f)
                lineTo(10.8f, 6.35f)
                horizontalLineTo(18.65f)
                quadTo(19.95f, 6.35f, 19.95f, 7.65f)
                verticalLineTo(9.9f)
            }
            stroke(width = 1.55f) {
                addCircle(17.2f, 16.6f, 2.6f)
                moveTo(17.2f, 12.8f); verticalLineTo(13.9f)
                moveTo(17.2f, 19.3f); verticalLineTo(20.4f)
                moveTo(13.4f, 16.6f); horizontalLineTo(14.5f)
                moveTo(19.9f, 16.6f); horizontalLineTo(21.0f)
            }
        }
    }

    val Film: ImageVector by lazy {
        strokeIcon("HyperFilm") {
            stroke { addRoundedRect(2.85f, 4.1f, 21.15f, 19.9f, 3.3f) }
            fill {
                moveTo(10.0f, 8.7f)
                verticalLineTo(15.3f)
                lineTo(15.9f, 12.0f)
                close()
            }
        }
    }

    val Globe: ImageVector by lazy {
        strokeIcon("HyperGlobe") {
            stroke { addCircle(12f, 12f, 9.17f) }
            stroke(width = 1.5f) {
                // 经线椭圆 (rx = 4.1, ry = 9.17)
                moveTo(12f, 2.83f)
                arcTo(4.1f, 9.17f, 0f, isMoreThanHalf = false, isPositiveArc = true, 12f, 21.17f)
                arcTo(4.1f, 9.17f, 0f, isMoreThanHalf = false, isPositiveArc = true, 12f, 2.83f)
                moveTo(2.95f, 12f)
                horizontalLineTo(21.05f)
            }
        }
    }

    val Bot: ImageVector by lazy {
        strokeIcon("HyperBot") {
            stroke {
                moveTo(12f, 6.4f)
                verticalLineTo(3.2f)
                horizontalLineTo(8.7f)
                addRoundedRect(4.2f, 6.5f, 19.8f, 20.15f, 3.7f)
                moveTo(2.85f, 13.3f); horizontalLineTo(4.1f)
                moveTo(19.9f, 13.3f); horizontalLineTo(21.15f)
            }
            stroke(width = 1.95f) {
                moveTo(9.1f, 11.9f); verticalLineTo(14.6f)
                moveTo(14.9f, 11.9f); verticalLineTo(14.6f)
            }
        }
    }

    val Brain: ImageVector by lazy {
        strokeIcon("HyperBrain") {
            stroke(width = 1.55f) {
                moveTo(12f, 4.1f)
                verticalLineTo(19.9f)
            }
            stroke {
                // 左脑叶（[2.0..22.0] x [2.3..21.7]）
                moveTo(12f, 5.6f)
                curveTo(11.3f, 3.5f, 8.7f, 2.9f, 6.8f, 3.7f)
                curveTo(4.6f, 4.6f, 4.1f, 7.0f, 4.5f, 8.4f)
                curveTo(2.6f, 9.5f, 2.4f, 13.0f, 4.4f, 15.0f)
                curveTo(4.0f, 17.6f, 5.8f, 20.0f, 8.4f, 20.3f)
                curveTo(10.3f, 20.5f, 11.7f, 19.5f, 12f, 18.4f)
                // 右脑叶
                moveTo(12f, 5.6f)
                curveTo(12.7f, 3.5f, 15.3f, 2.9f, 17.2f, 3.7f)
                curveTo(19.4f, 4.6f, 19.9f, 7.0f, 19.5f, 8.4f)
                curveTo(21.4f, 9.5f, 21.6f, 13.0f, 19.6f, 15.0f)
                curveTo(20.0f, 17.6f, 18.2f, 20.0f, 15.6f, 20.3f)
                curveTo(13.7f, 20.5f, 12.3f, 19.5f, 12f, 18.4f)
            }
        }
    }

    val Terminal: ImageVector by lazy {
        strokeIcon("HyperTerminal") {
            stroke {
                // 对齐官方 MiuixIcons.Regular.ScreenMirroring ([2.00..22.00] x [3.00..21.00])
                addRoundedRect(2.85f, 3.85f, 21.15f, 20.15f, 3.5f)
                moveTo(7.0f, 8.8f)
                lineTo(10.4f, 12.0f)
                lineTo(7.0f, 15.2f)
                moveTo(12.7f, 15.2f)
                horizontalLineTo(17.2f)
            }
        }
    }

    val FileCode: ImageVector by lazy {
        strokeIcon("HyperFileCode") {
            stroke {
                // 对齐官方 MiuixIcons.Regular.File ([3.75..20.25] x [2.00..22.00])
                moveTo(4.58f, 4.55f)
                verticalLineTo(19.35f)
                quadTo(4.58f, 21.17f, 6.4f, 21.17f)
                horizontalLineTo(17.6f)
                quadTo(19.42f, 21.17f, 19.42f, 19.35f)
                verticalLineTo(8.05f)
                lineTo(14.2f, 2.83f)
                horizontalLineTo(6.4f)
                quadTo(4.58f, 2.83f, 4.58f, 4.55f)
                close()
            }
            stroke(width = 1.55f) {
                moveTo(14.2f, 2.83f)
                verticalLineTo(8.05f)
                horizontalLineTo(19.42f)
            }
            stroke(width = 1.6f) {
                moveTo(9.9f, 11.4f); lineTo(7.7f, 13.8f); lineTo(9.9f, 16.2f)
                moveTo(14.1f, 11.4f); lineTo(16.3f, 13.8f); lineTo(14.1f, 16.2f)
            }
        }
    }

    val BookOpen: ImageVector by lazy {
        strokeIcon("HyperBookOpen") {
            stroke {
                moveTo(12f, 5.6f); verticalLineTo(20.5f)
                moveTo(12f, 5.6f)
                quadTo(10.3f, 3.75f, 7.8f, 3.75f)
                horizontalLineTo(4.1f)
                quadTo(2.85f, 3.75f, 2.85f, 5.0f)
                verticalLineTo(17.9f)
                quadTo(2.85f, 19.15f, 4.1f, 19.15f)
                horizontalLineTo(9.15f)
                quadTo(10.8f, 19.15f, 12f, 20.5f)
                moveTo(12f, 5.6f)
                quadTo(13.7f, 3.75f, 16.2f, 3.75f)
                horizontalLineTo(19.9f)
                quadTo(21.15f, 3.75f, 21.15f, 5.0f)
                verticalLineTo(17.9f)
                quadTo(21.15f, 19.15f, 19.9f, 19.15f)
                horizontalLineTo(14.85f)
                quadTo(13.2f, 19.15f, 12f, 20.5f)
            }
        }
    }

    val Camera: ImageVector by lazy {
        strokeIcon("HyperCamera") {
            stroke {
                addRoundedRect(2.85f, 6.35f, 21.15f, 20.15f, 3.3f)
                moveTo(8.2f, 6.35f)
                lineTo(9.55f, 3.85f)
                horizontalLineTo(14.45f)
                lineTo(15.8f, 6.35f)
                addCircle(12f, 13.25f, 3.45f)
            }
        }
    }

    val ChartColumn: ImageVector by lazy {
        strokeIcon("HyperChartColumn") {
            stroke { addRoundedRect(2.85f, 3.05f, 21.15f, 20.95f, 3.4f) }
            stroke(width = 1.95f) {
                moveTo(8.0f, 16.5f); verticalLineTo(11.3f)
                moveTo(12.0f, 16.5f); verticalLineTo(7.5f)
                moveTo(16.0f, 16.5f); verticalLineTo(12.8f)
            }
        }
    }

    val Cpu: ImageVector by lazy {
        strokeIcon("HyperCpu") {
            stroke {
                addRoundedRect(5.8f, 5.8f, 18.2f, 18.2f, 2.5f)
                moveTo(9.4f, 2.85f); verticalLineTo(5.6f)
                moveTo(14.6f, 2.85f); verticalLineTo(5.6f)
                moveTo(9.4f, 18.4f); verticalLineTo(21.15f)
                moveTo(14.6f, 18.4f); verticalLineTo(21.15f)
                moveTo(2.85f, 9.4f); horizontalLineTo(5.6f)
                moveTo(2.85f, 14.6f); horizontalLineTo(5.6f)
                moveTo(18.4f, 9.4f); horizontalLineTo(21.15f)
                moveTo(18.4f, 14.6f); horizontalLineTo(21.15f)
            }
            stroke(width = 1.55f) {
                addRoundedRect(9.5f, 9.5f, 14.5f, 14.5f, 1.05f)
            }
        }
    }

    val Keyboard: ImageVector by lazy {
        strokeIcon("HyperKeyboard") {
            stroke {
                addRoundedRect(2.83f, 4.85f, 21.17f, 19.15f, 3.3f)
                moveTo(7.9f, 15.1f); horizontalLineTo(16.1f)
            }
            fill {
                addCircle(6.8f, 9.8f, 1.05f)
                addCircle(10.25f, 9.8f, 1.05f)
                addCircle(13.75f, 9.8f, 1.05f)
                addCircle(17.2f, 9.8f, 1.05f)
            }
        }
    }

    val Pointer: ImageVector by lazy {
        strokeIcon("HyperPointer") {
            stroke {
                moveTo(5.6f, 3.1f)
                lineTo(9.1f, 20.5f)
                lineTo(12.3f, 14.6f)
                lineTo(19.3f, 11.8f)
                close()
                moveTo(12.8f, 14.4f)
                lineTo(18.3f, 19.9f)
            }
        }
    }

    val Server: ImageVector by lazy {
        strokeIcon("HyperServer") {
            stroke {
                addRoundedRect(2.85f, 3.2f, 21.15f, 10.4f, 2.5f)
                addRoundedRect(2.85f, 13.6f, 21.15f, 20.8f, 2.5f)
            }
            fill {
                addCircle(6.8f, 6.8f, 1.12f)
                addCircle(6.8f, 17.2f, 1.12f)
            }
        }
    }

    val Star: ImageVector by lazy {
        strokeIcon("HyperStar") {
            stroke {
                moveTo(12.0f, 2.95f)
                lineTo(14.85f, 8.65f)
                lineTo(21.15f, 9.55f)
                lineTo(16.6f, 14.0f)
                lineTo(17.65f, 20.25f)
                lineTo(12.0f, 17.3f)
                lineTo(6.35f, 20.25f)
                lineTo(7.4f, 14.0f)
                lineTo(2.85f, 9.55f)
                lineTo(9.15f, 8.65f)
                close()
            }
        }
    }

    val StopFilled: ImageVector by lazy {
        strokeIcon("HyperStopFilled") {
            fill {
                addRoundedRect(3.5f, 3.5f, 20.5f, 20.5f, 4.2f)
            }
        }
    }

    val SparkFilled: ImageVector by lazy {
        strokeIcon("HyperSpark") {
            fill {
                moveTo(12f, 2.0f)
                quadTo(13.8f, 10.2f, 22.0f, 12f)
                quadTo(13.8f, 13.8f, 12f, 22.0f)
                quadTo(10.2f, 13.8f, 2.0f, 12f)
                quadTo(10.2f, 10.2f, 12f, 2.0f)
                close()
            }
        }
    }

    val Wrench: ImageVector by lazy {
        strokeIcon("HyperWrench") {
            stroke {
                // 对齐 Edit / Close 对角图标量感 ([3.0..21.0])
                moveTo(19.75f, 6.85f)
                arcTo(3.95f, 3.95f, 0f, isMoreThanHalf = true, isPositiveArc = true, 16.95f, 4.05f)
                lineTo(15.3f, 5.7f)
                quadTo(14.6f, 6.4f, 15.3f, 7.1f)
                lineTo(17.4f, 9.2f)
                quadTo(18.1f, 9.9f, 18.8f, 9.2f)
                close()
            }
            stroke(width = 1.95f) {
                moveTo(13.2f, 11.1f)
                lineTo(4.25f, 20.05f)
            }
        }
    }

    val ShieldUser: ImageVector by lazy {
        strokeIcon("HyperShieldUser") {
            stroke {
                // 对齐官方 MiuixIcons.Regular.Report ([3.26..20.74] x [2.00..22.00])
                moveTo(12.0f, 2.83f)
                lineTo(19.45f, 5.65f)
                quadTo(19.9f, 5.85f, 19.9f, 6.55f)
                verticalLineTo(11.9f)
                curveTo(19.9f, 16.3f, 16.8f, 19.5f, 12.0f, 21.17f)
                curveTo(7.2f, 19.5f, 4.1f, 16.3f, 4.1f, 11.9f)
                verticalLineTo(6.55f)
                quadTo(4.1f, 5.85f, 4.55f, 5.65f)
                close()
            }
            stroke(width = 1.65f) {
                addCircle(12.0f, 9.8f, 2.25f)
                moveTo(8.25f, 16.35f)
                quadTo(9.15f, 14.3f, 12.0f, 14.3f)
                quadTo(14.85f, 14.3f, 15.75f, 16.35f)
            }
        }
    }

    private class VectorContent(private val builder: ImageVector.Builder) {
        fun stroke(width: Float = STROKE, block: PathBuilder.() -> Unit) {
            val path = PathBuilder().apply(block).nodes
            builder.addPath(
                pathData = path,
                pathFillType = PathFillType.NonZero,
                fill = null,
                stroke = BLACK,
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }

        fun fill(block: PathBuilder.() -> Unit) {
            val path = PathBuilder().apply(block).nodes
            builder.addPath(
                pathData = path,
                pathFillType = PathFillType.NonZero,
                fill = BLACK,
            )
        }
    }

    private fun strokeIcon(name: String, block: VectorContent.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            VectorContent(this).block()
        }.build()

    private fun PathBuilder.addCircle(cx: Float, cy: Float, r: Float) {
        moveTo(cx, cy - r)
        arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, cx, cy + r)
        arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, cx, cy - r)
        close()
    }

    private fun PathBuilder.addRoundedRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        r: Float,
    ) {
        moveTo(left + r, top)
        horizontalLineTo(right - r)
        quadTo(right, top, right, top + r)
        verticalLineTo(bottom - r)
        quadTo(right, bottom, right - r, bottom)
        horizontalLineTo(left + r)
        quadTo(left, bottom, left, bottom - r)
        verticalLineTo(top + r)
        quadTo(left, top, left + r, top)
        close()
    }
}
