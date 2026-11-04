package com.suixing.agent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.suixing.agent.ui.chat.markdown.base64PayloadOf

/**
 * 助手回复里的一幅图（用户：「给助手加上可发图片的功能，也就是助手的回复可以加上图片显示」）。
 *
 * 两种来源走两条路，但长得一样：
 *  - `data:image/...;base64,...` → 复用 [ImageBlock]（与用户自己贴的图同一个面板：同一套
 *    Coil 缓存、同一屏高、同样的点击放大）；
 *  - `http(s)://…` → Coil 直接取网络图，点击后按预览窗口尺寸加载。
 *
 * 高度上限与 [ImageBlock] 保持一致（240dp + Fit），同一段正文里文字与图混排时行高不会忽大忽小。
 */
@Composable
internal fun MessageImage(url: String, alt: String) {
    val base64 = remember(url) { base64PayloadOf(url) }
    if (base64 != null) {
        ImageBlock(base64)
        return
    }
    val context = LocalContext.current
    val request = remember(context, url) {
        ImageRequest.Builder(context).data(url).crossfade(false).build()
    }
    val description = alt.ifBlank { "图片" }
    var loaded by remember(request) { mutableStateOf<Boolean?>(null) }
    var preview by remember(request) { mutableStateOf(false) }
    Spacer(Modifier.height(6.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp, max = 240.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = loaded == true, role = Role.Button, onClickLabel = "预览图片") {
                preview = true
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = request,
            contentDescription = if (loaded == false) null else description,
            contentScale = ContentScale.Fit,
            modifier = Modifier.matchParentSize(),
            onLoading = { loaded = null },
            onSuccess = { loaded = true },
            onError = { loaded = false },
        )
        if (loaded == false) {
            Text(
                text = "图片加载失败",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (preview) {
        var failed by remember { mutableStateOf(false) }
        ImagePreviewFrame(onDismiss = { preview = false }) { modifier ->
            AsyncImage(
                model = request,
                contentDescription = description,
                contentScale = ContentScale.Fit,
                modifier = modifier,
                onError = { failed = true },
                onSuccess = { failed = false },
            )
            if (failed) {
                Text("图片加载失败", color = Color.White)
            }
        }
    }
}
