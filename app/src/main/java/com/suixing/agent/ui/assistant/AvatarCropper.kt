package com.suixing.agent.ui.assistant

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.graphics.Bitmap
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
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    // 方框：屏宽的 78%，最多 320dp —— 小屏上留出两侧的手指余量，大屏上不至于糊成一片。
    val frameDp = minOf(screenWidth * 0.78f, 320.dp)
    val framePx = with(density) { frameDp.toPx() }
    // 1× 的定义就是「刚好盖住方框」：短边贴住框，另一轴溢出。用户看到的初始画面没有黑边。
    val baseScale = framePx / min(source.width, source.height).toFloat()
    var zoom by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    fun clamp(current: Offset, scale: Float): Offset {
        val displayScale = baseScale * scale
        val maxX = ((source.width * displayScale - framePx) / 2f).coerceAtLeast(0f)
        val maxY = ((source.height * displayScale - framePx) / 2f).coerceAtLeast(0f)
        return Offset(current.x.coerceIn(-maxX, maxX), current.y.coerceIn(-maxY, maxY))
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = Color(0xF20B0C0D)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "拖动移动 · 双指缩放",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.padding(vertical = 14.dp),
                )
                // 手势挂在整个中间区域：手指落在圆框外面也能拖，这比只让框内可拖友好得多。
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .pointerInput(source) {
                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                zoom = (zoom * gestureZoom).coerceIn(1f, 4f)
                                offset = clamp(offset + pan, zoom)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(frameDp)
                            .clip(CircleShape)
                            .background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        val displayScale = baseScale * zoom
                        // requiredSize, not size: the picture is deliberately bigger than the frame
                        // (that is what zooming means), and size() would be clamped to the parent's
                        // constraints and quietly refuse to grow.
                        Image(
                            bitmap = source.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier
                                .requiredSize(
                                    with(density) { (source.width * displayScale).toDp() },
                                    with(density) { (source.height * displayScale).toDp() },
                                )
                                .graphicsLayer {
                                    translationX = offset.x
                                    translationY = offset.y
                                },
                        )
                    }
                    // 圈线画在裁剪之外：框里的图不能被这条线遮住，而框本身要有边界。
                    Box(
                        modifier = Modifier
                            .size(frameDp)
                            .border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape),
                    )
                }
                Row(
                    modifier = Modifier
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
