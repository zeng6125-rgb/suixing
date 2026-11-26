package com.suixing.agent.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import com.suixing.agent.R
import com.suixing.agent.ui.theme.LocalUseMiuix
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Background
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Help
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Mic
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.MoveFile
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Pin
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Report
import top.yukonga.miuix.kmp.icon.extended.ScreenMirroring
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.Unpin
import top.yukonga.miuix.kmp.icon.extended.ZoomOut

private fun ImageVector.Builder.appendVectorGroup(group: VectorGroup) {
    addGroup(
        name = group.name,
        rotate = group.rotation,
        pivotX = group.pivotX,
        pivotY = group.pivotY,
        scaleX = group.scaleX,
        scaleY = group.scaleY,
        translationX = group.translationX,
        translationY = group.translationY,
        clipPathData = group.clipPathData,
    )
    for (node in group) {
        when (node) {
            is VectorGroup -> appendVectorGroup(node)
            is VectorPath -> addPath(
                pathData = node.pathData,
                pathFillType = node.pathFillType,
                name = node.name,
                fill = node.fill,
                fillAlpha = node.fillAlpha,
                stroke = node.stroke,
                strokeAlpha = node.strokeAlpha,
                strokeLineWidth = node.strokeLineWidth,
                strokeLineCap = node.strokeLineCap,
                strokeLineJoin = node.strokeLineJoin,
                strokeLineMiter = node.strokeLineMiter,
                trimPathStart = node.trimPathStart,
                trimPathEnd = node.trimPathEnd,
                trimPathOffset = node.trimPathOffset,
            )
        }
    }
    clearGroup()
}

private fun transformedMiuixVector(
    source: ImageVector,
    suffix: String,
    degrees: Float = 0f,
    scale: Float = 1f,
    shiftFractionX: Float = 0f,
    shiftFractionY: Float = 0f,
): ImageVector = ImageVector.Builder(
    name = "${source.name}.$suffix",
    defaultWidth = source.defaultWidth,
    defaultHeight = source.defaultHeight,
    viewportWidth = source.viewportWidth,
    viewportHeight = source.viewportHeight,
    tintColor = source.tintColor,
    tintBlendMode = source.tintBlendMode,
    autoMirror = source.autoMirror,
).apply {
    addGroup(
        rotate = degrees,
        pivotX = source.viewportWidth / 2f,
        pivotY = source.viewportHeight / 2f,
        scaleX = scale,
        scaleY = scale,
        translationX = source.viewportWidth * shiftFractionX,
        translationY = source.viewportHeight * shiftFractionY,
    )
    appendVectorGroup(source.root)
    clearGroup()
}.build()

private val miuixChevronDown: ImageVector by lazy {
    transformedMiuixVector(MiuixIcons.Regular.ChevronForward, "Down", degrees = 90f)
}
private val miuixChevronUp: ImageVector by lazy {
    transformedMiuixVector(MiuixIcons.Regular.ChevronForward, "Up", degrees = -90f)
}
private val miuixCenteredSend: ImageVector by lazy {
    // MiuixIcons.Regular.Send 虽外框接近居中，但箭头右侧实体像素质心偏右约 2dp、最小包围圆偏右上，
    // 在 40dp 圆形实底按钮内采用 (-1.05dp X, +0.15dp Y) 轻度左移以平衡视觉重心且不挤占左边缘。
    transformedMiuixVector(
        MiuixIcons.Regular.Send,
        "Centered",
        scale = 0.94f,
        shiftFractionX = -1.05f / 24f,
        shiftFractionY = 0.15f / 24f,
    )
}

internal object MiuixExtraIcons {
    val Create: ImageVector get() = MiuixIcons.Regular.Edit
    val Send: ImageVector get() = miuixCenteredSend
    val Pin: ImageVector get() = MiuixIcons.Regular.Pin
    val Unpin: ImageVector get() = MiuixIcons.Regular.Unpin
    val More: ImageVector get() = MiuixIcons.Regular.More
}

/**
 * 主题感知的图标解析：
 * - 非 MIUIX 主题下直接走 `ImageVector.vectorResource` / `painterResource`，保持原有 Lucide 矢量与单测兼容；
 * - MIUIX 主题（[LocalUseMiuix] == true）下使用官方 `MiuixIcons.Regular` 与同笔触/同圆角的
 *   [MiuixHyperIcons]（全套统一 HyperOS 线条与光学校正居中），不再混入细线 Lucide 矢量。
 */
@Composable
internal fun suixingIcon(@DrawableRes resId: Int): ImageVector =
    if (LocalUseMiuix.current) {
        miuixVectorFor(resId) ?: ImageVector.vectorResource(resId)
    } else {
        ImageVector.vectorResource(resId)
    }

@Composable
internal fun suixingIconPainter(@DrawableRes resId: Int): Painter =
    if (LocalUseMiuix.current) {
        miuixVectorFor(resId)?.let { rememberVectorPainter(it) } ?: painterResource(resId)
    } else {
        painterResource(resId)
    }

@Composable
internal fun suixingVector(
    @DrawableRes defaultResId: Int,
    miuix: ImageVector,
): ImageVector =
    if (LocalUseMiuix.current) miuix else ImageVector.vectorResource(defaultResId)

@Composable
internal fun suixingPainter(
    @DrawableRes defaultResId: Int,
    miuix: ImageVector,
): Painter =
    if (LocalUseMiuix.current) rememberVectorPainter(miuix) else painterResource(defaultResId)

internal fun miuixVectorFor(@DrawableRes resId: Int): ImageVector? = when (resId) {
    R.drawable.ic_arrow_down -> MiuixHyperIcons.ArrowDown
    R.drawable.ic_arrow_left -> MiuixIcons.Regular.Back
    R.drawable.ic_arrow_up -> MiuixHyperIcons.ArrowUp
    R.drawable.ic_badge_check -> MiuixHyperIcons.BadgeCheck
    R.drawable.ic_book_open -> MiuixHyperIcons.BookOpen
    R.drawable.ic_bot -> MiuixHyperIcons.Bot
    R.drawable.ic_brain -> MiuixHyperIcons.Brain
    R.drawable.ic_camera -> MiuixHyperIcons.Camera
    R.drawable.ic_chart_column -> MiuixHyperIcons.ChartColumn
    R.drawable.ic_check -> MiuixIcons.Regular.Ok
    R.drawable.ic_chevron_down -> miuixChevronDown
    R.drawable.ic_chevron_left -> MiuixIcons.Regular.ChevronBackward
    R.drawable.ic_chevron_right -> MiuixIcons.Regular.ChevronForward
    R.drawable.ic_chevron_up -> miuixChevronUp
    R.drawable.ic_circle -> MiuixHyperIcons.Circle
    R.drawable.ic_circle_alert -> MiuixHyperIcons.CircleAlert
    R.drawable.ic_circle_check -> MiuixHyperIcons.CircleCheck
    R.drawable.ic_circle_question_mark -> MiuixIcons.Regular.Help
    R.drawable.ic_clock -> MiuixHyperIcons.Clock
    R.drawable.ic_compass -> MiuixHyperIcons.Compass
    R.drawable.ic_compose -> MiuixIcons.Regular.Edit
    R.drawable.ic_copy -> MiuixIcons.Regular.Copy
    R.drawable.ic_cpu -> MiuixHyperIcons.Cpu
    R.drawable.ic_database -> MiuixIcons.Regular.Backup
    R.drawable.ic_download -> MiuixIcons.Regular.Download
    R.drawable.ic_eye -> MiuixIcons.Regular.Show
    R.drawable.ic_eye_off -> MiuixIcons.Regular.Hide
    R.drawable.ic_file -> MiuixIcons.Regular.File
    R.drawable.ic_file_archive -> MiuixIcons.Regular.MoveFile
    R.drawable.ic_file_code -> MiuixHyperIcons.FileCode
    R.drawable.ic_file_text -> MiuixIcons.Regular.Notes
    R.drawable.ic_film -> MiuixHyperIcons.Film
    R.drawable.ic_folder -> MiuixIcons.Regular.Folder
    R.drawable.ic_folder_cog -> MiuixHyperIcons.FolderCog
    R.drawable.ic_folder_open -> MiuixHyperIcons.FolderOpen
    R.drawable.ic_globe -> MiuixHyperIcons.Globe
    R.drawable.ic_id_card -> MiuixIcons.Regular.Contacts
    R.drawable.ic_image -> MiuixIcons.Regular.Image
    R.drawable.ic_info -> MiuixIcons.Regular.Info
    R.drawable.ic_keyboard -> MiuixHyperIcons.Keyboard
    R.drawable.ic_list_checks -> MiuixIcons.Regular.SelectAll
    R.drawable.ic_menu_soft -> MiuixHyperIcons.MenuSoft
    R.drawable.ic_mic -> MiuixIcons.Regular.Mic
    R.drawable.ic_minimize_2 -> MiuixIcons.Regular.ZoomOut
    R.drawable.ic_more_vertical -> MiuixIcons.Regular.More
    R.drawable.ic_music -> MiuixIcons.Regular.Music
    R.drawable.ic_palette -> MiuixIcons.Regular.Background
    R.drawable.ic_picture_in_picture_2 -> MiuixIcons.Regular.ScreenMirroring
    R.drawable.ic_plus -> MiuixIcons.Regular.Add
    R.drawable.ic_pointer -> MiuixHyperIcons.Pointer
    R.drawable.ic_rotate_cw -> MiuixIcons.Regular.Refresh
    R.drawable.ic_server -> MiuixHyperIcons.Server
    R.drawable.ic_settings -> MiuixIcons.Regular.Settings
    R.drawable.ic_shield_alert -> MiuixIcons.Regular.Report
    R.drawable.ic_shield_user -> MiuixHyperIcons.ShieldUser
    R.drawable.ic_sliders_horizontal -> MiuixIcons.Regular.Tune
    R.drawable.ic_square_pen -> MiuixIcons.Regular.Edit
    R.drawable.ic_star -> MiuixHyperIcons.Star
    R.drawable.ic_stat_spark -> MiuixHyperIcons.SparkFilled
    R.drawable.ic_stop_filled -> MiuixHyperIcons.StopFilled
    R.drawable.ic_terminal -> MiuixHyperIcons.Terminal
    R.drawable.ic_trash -> MiuixIcons.Regular.Delete
    R.drawable.ic_wrench -> MiuixHyperIcons.Wrench
    R.drawable.ic_x -> MiuixIcons.Regular.Close
    else -> null
}
