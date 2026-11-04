package com.suixing.agent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
 *  - `http(s)://…` → Coil 直接取网络图。
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
    Spacer(Modifier.height(6.dp))
    AsyncImage(
        model = request,
        contentDescription = alt.ifBlank { "图片" },
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp, max = 240.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}
