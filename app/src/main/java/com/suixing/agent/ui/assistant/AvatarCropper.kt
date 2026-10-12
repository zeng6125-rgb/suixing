package com.suixing.agent.ui.assistant

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.suixing.agent.R
import com.suixing.agent.data.AvatarCropRect
import com.suixing.agent.data.avatarCropRect
import kotlin.math.min

/**
 * 换头像的中间一步：先框，再存。
 *
 * 选完图直接存下来意味着**框在哪由我们猜**——头像画在圆里，一张合影或一张风景照被居中切开时，
 * 人脸常常正好在边上。所以这里给一个圆框：拖动移动、双指缩放，点「确定」才把框里的方形交给
 * [com.suixing.agent.data.AssistantAvatarStore.saveCropped]；「取消」什么都不写，头像保持原样。
 *
 * 屏幕上画的是**整张图**：框内是原亮度，框外压暗（一整屏的黑减掉一个圆洞）。只画框内那一块的话，
 * 用户看不到自己在整张图的哪个位置——「框外的起码也要显示一点」。
 *
 * 图片以 [source] 传进来（已经按 `AVATAR_CROP_WORK_PX` 采样过），这一层只负责手势与框，把框换算
 * 成源图像素的那一步是 [avatarCropRect]，纯函数、有单测。
 */
@Composable
fun AvatarCropper(
    source: Bitmap,
    onCancel: () -> Unit,
    onConfirm: (AvatarCropRect) -> Unit,
) {
    val density = LocalDensity.current

    Dialog(
        onDismissRequest = onCancel,
        // 这一页也要铺到状态栏（背景延伸到那一条），内容自己吃 inset：顶部返回键与底部按钮
        // 各让开一条系统栏，按钮不会被手势条顶掉。
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // 保险丝（与编辑页同一个，见 DialogEdgeToEdge 的注释：机制是假设、症状来自截图，
        // 两种成因它都治、开关本来生效时是空操作）。
        DialogEdgeToEdge()
        // 完全不透明的底色：这一层就是整页，后面那份编辑页不该透过来（用户看到的「画了两份」
        // 有一半是半透明底导致的透视）。0xF2 → 0xFF。
        Surface(Modifier.fillMaxSize(), color = Color(0xFF0B0C0D)) {
            // 几何全部在这里算：`BoxWithConstraints` 的 maxWidth/maxHeight 就是**本组合拿到的可用
            // 尺寸**（对话框窗口在这台 ROM 上不是整屏 —— 截图里那圈超出图片的大圆，就是按屏幕宽
            // 算、却画在小窗口里的结果）。它天然是"窗口/容器"而不是屏幕，类型也全是 Dp，不用再换算。
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val frameDp = minOf(maxWidth * 0.78f, maxHeight * 0.5f, 320.dp)
                val framePx = with(density) { frameDp.toPx() }
                // 1× 的定义就是「刚好盖住方框」：短边贴住框，另一轴溢出。初始画面没有黑边。
                val baseScale = framePx / min(source.width, source.height).toFloat()
                var zoom by remember { mutableStateOf(1f) }
                var offset by remember { mutableStateOf(Offset.Zero) }

                fun clamp(current: Offset, scale: Float): Offset {
                    val displayScale = baseScale * scale
                    val maxX = ((source.width * displayScale - framePx) / 2f).coerceAtLeast(0f)
                    val maxY = ((source.height * displayScale - framePx) / 2f).coerceAtLeast(0f)
                    return Offset(current.x.coerceIn(-maxX, maxX), current.y.coerceIn(-maxY, maxY))
                }
                Box(Modifier.fillMaxSize()) {
                // 1) 整张图，居中、可平移缩放。requiredSize 而不是 size：它故意比框大，size 会被
                //    父约束夹住、缩放就失效了。
                val displayScale = baseScale * zoom
                Image(
                    bitmap = source.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .requiredSize(
                            with(density) { (source.width * displayScale).toDp() },
                            with(density) { (source.height * displayScale).toDp() },
                        )
                        .graphicsLayer {
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
                // 2) 压暗：一整屏的黑减掉一个圆洞（Path 差集），框内保持原亮度、框外看得见但变暗。
                //    用差集而不是 BlendMode.Clear：不需要离屏合成，也不会在某些 ROM 上留下黑块。
                Canvas(Modifier.fillMaxSize()) {
                    val hole = Path().apply {
                        addOval(Rect(center = center, radius = framePx / 2f))
                    }
                    val scrim = Path().apply {
                        addRect(Rect(Offset.Zero, size))
                        op(this, hole, PathOperation.Difference)
                    }
                    drawPath(scrim, Color.Black.copy(alpha = 0.55f))
                }
                // 3) 圈线：框要有边界，而它不能遮住框里的图。
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(frameDp)
                        .border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape),
                )
                // 4) 手势铺满整屏：手指落在圆框外面也能拖，这比只让框内可拖友好得多。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(source) {
                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                zoom = (zoom * gestureZoom).coerceIn(1f, 4f)
                                offset = clamp(offset + pan, zoom)
                            }
                        },
                )
                // 5) 顶部：左上角一个返回键（与「取消」同一个动作），中间一句提示。
                //    inset 用 dialogSystemBarPad()：这个对话框窗口不一定会把系统栏 inset 送进来
                //    （见它的注释），报 0 时退回平台自己的状态栏高度，否则返回键会压在状态栏上。
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = dialogSystemBarPad())
                        .padding(start = 4.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_arrow_left),
                            contentDescription = "取消裁剪",
                            tint = Color.White,
                        )
                    }
                }
                Text(
                    text = "拖动移动 · 双指缩放",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = dialogSystemBarPad())
                        .padding(top = 16.dp),
                )
                // 6) 底部按钮：绝对定位在屏幕底部（不是放在会被内容挤走的 Column 里），并让开手势条。
                //    同样是 dialogSystemBarPad(bottom)：窗口不给 inset 时退回平台的手势条高度，
                //    否则这一行会被手势条盖掉一半（截图里那半截绿色按钮）。
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // 安全区 + 额外的 16dp：只靠 navigationBarsPadding 时，手势条机型上按钮会贴
                        // 到屏幕最下缘（截图里只露出一截绿色）。inset 拿不到时 dialogSystemBarPad
                        // 退回平台的手势条高度。
                        .padding(bottom = dialogSystemBarPad(bottom = true) + 16.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onCancel) { Text("取消", color = Color.White) }
                    Button(
                        onClick = {
                            onConfirm(
                                avatarCropRect(
                                    imageWidth = source.width,
                                    imageHeight = source.height,
                                    framePx = framePx,
                                    scale = baseScale * zoom,
                                    offsetXPx = offset.x,
                                    offsetYPx = offset.y,
                                ),
                            )
                        },
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text("确定")
                    }
                }
                }
            }
        }
    }
}
