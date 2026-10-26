package com.suixing.agent.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.suixing.agent.agent.StreamStats
import com.suixing.agent.data.label
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.suixing.agent.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
/** The chip above the pill: a thumbnail, the name, and a way out of it. */
@Composable
internal fun AttachmentChip(attachment: Attachment, onRemove: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // The same inset the queue rows and the board above use: the strip and the rows under it are
    // one column of things belonging to the field. 多附件后内缩交给外面那一排 Row（每张 chip 自带
    // 会在一排里叠出参差的间距）。
    Row {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = scheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
        ) {
            Row(
                modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val preview = attachment.preview
                var full by remember { mutableStateOf(false) }
                // The picture and its name are one tap target; 移除 stays its own button beside
                // them. Making the whole chip clickable would put "preview" and "delete" on the
                // same 26dp of glass, which is how a user deletes what they meant to look at.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = if (preview != null) {
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(),
                                onClick = { full = true },
                            )
                    } else {
                        Modifier
                    },
                ) {
                if (preview != null) {
                    Image(
                        bitmap = preview,
                        contentDescription = "查看图片",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                } else {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_file),
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = attachment.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 190.dp),
                )
                }
                // The attachment's own bitmap, not a scaled-down copy: this is the picture the user
                // picked, at the size it was stored, so opening it shows what will be sent.
                if (full && preview != null) {
                    ImagePreviewDialog(
                        bitmap = preview,
                        contentDescription = attachment.label,
                        // 与对话里那张图片预览同一形态（Dialog + 系统遮罩）：用户要的是
                        // 「图1 那个效果」，附件与队列两处都跟它对齐。
                        keepKeyboard = false,
                    ) { full = false }
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(26.dp)) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_x),
                        contentDescription = "移除",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}

/**
 * What the ring draws, and whether the number is the endpoint's own or this app's estimate.
 *
 * Four sources, in one order, and the order is the whole point:
 *
 * 1. [liveReported] — the count the endpoint itself sent for the round in flight. The only
 *    honest measure of 「上下文用了多少」 (see `StreamStats.lastPromptTokens`).
 * 2. [newestStored] — the newest round in the transcript that carries a count. This is what the
 *    ring reads on a conversation that was just opened, and between the start of a round and
 *    the endpoint's usage block at its end.
 * 3. [liveEstimate] — the run's own character-based estimate of the wire, used while a round is
 *    going and before its usage block lands.
 * 4. [transcriptEstimate] — the transcript's own size, for a conversation that is *not* running
 *    and whose stored rounds carry no counts at all: a gateway that ignores
 *    `stream_options.include_usage` (see LlmClient's own note), or rows written before the counts
 *    existed. Without it the card was empty in exactly that case — every line zero, 「暂无数据」
 *    on a conversation with plenty of text in it — which is the report this answers.
 *
 * The last two are estimates, so [estimated] is set and the card writes 「约」: a proportion is not
 * a price, but a proportion that says 6% beats a blank.
 */

/**
 * The draft's `attachmentUri` for a picture taken back out of the queue by 「修改」.
 *
 * A marker, not a uri: the picture lives in the view model's attachment cache and nowhere else, so
 * this exists only to make the composer re-read that cache (its `remember` is keyed on the draft's
 * uri and name). After a restart the cache is empty and the chip is simply absent, which is the
 * honest outcome — the alternative is a draft pointing at a uri that cannot be read.
 */
internal const val QUEUED_ATTACHMENT_URI = "queued://steer-image"

/** What the restored picture is called in the composer's chip. */
internal const val QUEUED_IMAGE_LABEL = "图片"

/** Target width of a queue row's thumbnail, in pixels: the row draws it at 24dp. */
internal const val THUMB_PX = 96

/**
 * Target width for a picture opened from a queue row, in pixels.
 *
 * Larger than any phone's screen is tall, so nothing is lost on the way in, while still leaving the
 * decoder a bound: a 12-megapixel photo decoded at full size is ~48 MB and an OOM on a cheap device.
 */
internal const val FULL_PREVIEW_PX = 2160

/**
 * How many board rows are drawn above the queue — the same three as the queue, for the same
 * reason: this stack sits over the field the user is typing into, and a long plan must not
 * push that field off the screen. The tally covers the items that do not fit.
 */

internal const val ATTACHMENT_MAX_WIDTH = 1280

internal fun readAttachment(context: Context, uri: Uri, kind: AttachKind): Attachment? = when (kind) {
    AttachKind.IMAGE -> decodeImage(context, uri)
    AttachKind.FILE -> importFile(context, uri)
}

internal fun decodeImage(context: Context, uri: Uri): Attachment? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

    var scale = 1
    while (bounds.outWidth / scale > ATTACHMENT_MAX_WIDTH || bounds.outHeight / scale > ATTACHMENT_MAX_WIDTH) {
        scale *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = scale }
    val bitmap = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: return@runCatching null
    bitmapAttachment(bitmap, displayName(context, uri) ?: "图片")
}.getOrNull()

/** Camera and gallery both land here: one JPEG, small enough to send every turn. */
internal fun bitmapAttachment(bitmap: Bitmap, label: String = "照片"): Attachment {
    val scaled = if (bitmap.width > ATTACHMENT_MAX_WIDTH) {
        val ratio = ATTACHMENT_MAX_WIDTH.toFloat() / bitmap.width
        Bitmap.createScaledBitmap(
            bitmap,
            ATTACHMENT_MAX_WIDTH,
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    } else {
        bitmap
    }
    val bytes = java.io.ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 82, bytes)
    return Attachment(
        base64 = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP),
        label = label,
        preview = scaled.asImageBitmap(),
    )
}

/**
 * Decodes stored base64 back into a bitmap, off the main thread.
 *
 * Used by the queue row's 24dp thumbnail and by 「修改」, which puts the picture back into the
 * composer: both hold bytes, not a uri, so neither can go through [readAttachment]. Downsampled to
 * about [target] pixels wide, because the callers draw it small and the full JPEG would cost
 * megabytes per row. Null when the bytes are not an image, which every caller treats as "no
 * picture" rather than as an error worth showing.
 */
internal suspend fun decodeAttachment(base64: String, target: Int = 512): Bitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return@runCatching null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= target) sample *= 2
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }.getOrNull()
    }

/**
 * A picked file is copied into the app's own folder, because the agent reads absolute
 * paths and a `content://` handle means nothing to it. The path goes into the question.
 */
internal fun importFile(context: Context, uri: Uri): Attachment? = runCatching {
    val name = displayName(context, uri) ?: "文件"
    val inbox = File(context.getExternalFilesDir(null) ?: context.filesDir, "inbox").apply { mkdirs() }
    val target = File(inbox, name)
    context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    } ?: return@runCatching null
    Attachment(base64 = "", label = target.absolutePath, filePath = target.absolutePath)
}.getOrNull()

internal fun displayName(context: Context, uri: Uri): String? {
    if (uri.scheme == "file") return uri.lastPathSegment
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
}

/**
 * The one full-screen picture viewer: a bubble's screenshot, the attachment chip above the field and
 * a queued line's thumbnail all open this.
 *
 * Shared rather than copied because it is not merely a Dialog: the black scrim, the pinch-and-pan
 * transform and tap-anywhere-to-close are the behaviour the user already knows from the transcript,
 * and a second implementation would drift from it. A `Dialog` and not a focusable overlay, so it
 * cannot take the keyboard away from the field — the same rule the composer's own cards follow.
 */
/**
 * 全屏预览 [bytes]：只在**用户点击**时解码一次（不在滚动路径上）。
 *
 * Coil 负责列表里的缩略显示；全屏要看原图，这里就地解一张交给既有的
 * [ImagePreviewDialog]（黑色遮罩 + 双指缩放 + 点哪关哪，行为不变）。
 */
@Composable
internal fun ImagePreviewBytes(
    bytes: ByteArray?,
    contentDescription: String,
    keepKeyboard: Boolean = false,
    onDismiss: () -> Unit,
) {
    var bitmap by remember(bytes) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(bytes) {
        bitmap = withContext(Dispatchers.Default) {
            bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
        }
    }
    bitmap?.let { ImagePreviewDialog(it, contentDescription, keepKeyboard, onDismiss) }
}

@Composable
internal fun ImagePreviewDialog(
    bitmap: ImageBitmap,
    contentDescription: String,
    /**
     * Draw it without taking window focus, for the two callers that sit right above the field the
     * user is typing in — the attachment chip and the queued rows. A focusable window takes the
     * keyboard away, and the composer would jump under the user's hands for a tap meant to look at a
     * picture. The transcript's own screenshots keep the ordinary dialog.
     */
    keepKeyboard: Boolean = false,
    onDismiss: () -> Unit,
) {
    // Reading a shot needs the whole screen and a pinch.
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformable = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 4f)
        offset = if (scale > 1f) offset + pan else Offset.Zero
    }
    val viewer: @Composable () -> Unit = {
        // 手势返回也要关（用户：「预览图片的时候，手势返回为什么不会关闭图片」）。keepKeyboard 那条
        BackHandler(onBack = onDismiss)
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.9f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = contentDescription,
                modifier = Modifier
                    .fillMaxSize()
                    .transformable(transformable)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
            )
        }
    }
    if (keepKeyboard) {
        // Focus-less, like the composer's own cards: it cannot see BACK, which is why the scrim
        // above closes on a tap anywhere — the same trade the model sheet makes.
        Popup(properties = PopupProperties(focusable = false)) { viewer() }
    } else {
        Dialog(onDismissRequest = onDismiss) { viewer() }
    }
}

@Composable
internal fun ImageBlock(base64: String) {
    // **图片加载换 Coil 3**（issue #44 第 10 项）：原来这里是手写的 64 行 ——
    // BitmapFactory 手动降采样 + 容量 4 的 LRU（滚出 4 张就丢，滚回来重新解码一张 1080p）。
    // Coil 3 覆盖同样的事并且更强：内存 + 磁盘双缓存、按目标尺寸自动采样、生命周期感知、
    // 解码在它自己的 Dispatcher 上 ⇒ 组合期零阻塞。手写的那套已删除（ImageDecode.kt）。
    var full by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // 图片是内存里的 base64（不是 URL），解码成字节交给 Coil 当 data：
    // 它按内容做内存缓存键，滚出视口再滚回来直接命中，不必重新解码。
    val bytes = remember(base64) {
        runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull()
    }
    // **必须包成 ByteBuffer**：Coil 3 的 fetcher 里没有 `ByteArray` 这一种 data 类型
    // （只有 ByteBuffer / File / Uri / String 等）。直接传 ByteArray 编译不报错、
    // 运行时静默不出图 —— 这个坑换成 ByteBuffer 才绕过去。
    // **每次组合新建一个视图**（`wrap` 是 O(1)、不拷贝字节）：ByteBuffer 有读位置，
    // 复用同一个实例时 Coil 读过一次后位置就到底了，第二次拿到的会是空。
    val model = bytes?.let { java.nio.ByteBuffer.wrap(it) }
    Spacer(Modifier.height(6.dp))
    val shape = RoundedCornerShape(12.dp)
    // **固定占位高度**（滚动跳变根因之一，与替换前一致）：截图几乎都是竖长图，Fit 后顶满
    // 240dp 上限。等 Coil 解码期间保持这个高度，行高不变 ⇒ 整列不会被顶一下。
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(model)
            // 不做淡入：这里要的是"到位就是到位"，交叉淡入在滚动中反而像闪烁。
            .crossfade(false)
            .build(),
        contentDescription = "工具截图",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp, max = 240.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = { full = true },
            ),
    )
    if (full) {
        // 全屏预览仍用手上这份字节，走同一个对话框（Coil 重新解码一次比传 bitmap 更省事，
        // 而全屏只发生在一次点击里，不在滚动路径上）。
        ImagePreviewBytes(bytes, contentDescription = "工具截图") { full = false }
    }
}
