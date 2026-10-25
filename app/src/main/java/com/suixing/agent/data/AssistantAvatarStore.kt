package com.suixing.agent.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** The longest side an assistant picture is stored at. */
internal const val AVATAR_MAX_PX = 256

/**
 * The longest side the picture is decoded at before it is cropped.
 *
 * The crop screen needs real pixels to drag around, but it draws them at a few hundred dp: decoding
 * a 4000px camera roll at full size would cost tens of megabytes for a picture nobody can see at
 * that detail. 1080 keeps a 4× zoom inside the frame sharp on any phone this app runs on.
 */
internal const val AVATAR_CROP_WORK_PX = 1080

/**
 * The square a crop takes out of the decoded picture, in that picture's own pixels.
 *
 * [left] and [top] are the top-left corner, [size] the side of the square — always a square,
 * because the frame is one and an avatar is drawn in a circle.
 *
 * Public because the crop screen hands one back through a callback: a public composable cannot
 * expose an `internal` type, and this is the shape the two sides have to agree on.
 */
data class AvatarCropRect(val left: Int, val top: Int, val size: Int)

/**
 * The square the crop frame is showing, in the source picture's own pixels.
 *
 * [framePx] is the frame's side on screen, [scale] the factor the picture is drawn at (screen pixels
 * per picture pixel; the frame is exactly covered when `scale * min(width, height) == framePx`), and
 * [offsetXPx]/[offsetYPx] how far the picture's centre has been dragged away from the frame's
 * centre. Those three are what the gesture writes, and the picture's own size is what turns them
 * back into a pixel rectangle.
 *
 * The result is clamped into the picture: the gesture is not allowed to drag the frame off the
 * image, and this is the second pair of braces that keeps a rounding error from asking for a
 * rectangle `Bitmap.createBitmap` would refuse.
 */
internal fun avatarCropRect(
    imageWidth: Int,
    imageHeight: Int,
    framePx: Float,
    scale: Float,
    offsetXPx: Float,
    offsetYPx: Float,
): AvatarCropRect {
    if (imageWidth <= 0 || imageHeight <= 0 || framePx <= 0f || scale <= 0f) {
        return AvatarCropRect(0, 0, 1)
    }
    val side = (framePx / scale).roundToInt().coerceIn(1, minOf(imageWidth, imageHeight))
    val centreX = imageWidth / 2f - offsetXPx / scale
    val centreY = imageHeight / 2f - offsetYPx / scale
    val left = (centreX - side / 2f).roundToInt().coerceIn(0, imageWidth - side)
    val top = (centreY - side / 2f).roundToInt().coerceIn(0, imageHeight - side)
    return AvatarCropRect(left, top, side)
}

/**
 * The assistant pictures the user picked, as PNGs in the app's own private directory.
 *
 * The gallery's original is never what is stored. A photo from the camera roll is several
 * megabytes and thousands of pixels across, and this app only ever draws it at 24–92dp: kept
 * as-is it would bloat the private directory, slow every launch that decodes it, and — if it
 * had gone into the settings JSON — make that file unreadable. So the pick is decoded,
 * sampled down, capped at [AVATAR_MAX_PX] on its longest side and re-encoded as PNG, which is
 * the one format that needs no new dependency and keeps transparency.
 *
 * The file is named after the assistant **and the moment it was saved**, so a replacement is a
 * new path: the composables that draw it are keyed on that path, and reusing one name would
 * leave every already-decoded picture showing the old face until the app restarted. The old
 * file is dropped as part of the same save, so one assistant still owns at most one picture.
 */
object AssistantAvatarStore {

    private const val DIR = "assistants"

    /** What [Assistant.avatarPath] holds: relative to the files directory, never absolute. */
    fun pathFor(id: String, stamp: Long): String = "$DIR/$id-$stamp.png"

    /**
     * Decodes [uri] for the crop screen: sampled down to [cap] on its longest side, never the whole
     * camera roll.
     *
     * Two passes, and the first one allocates nothing but the bounds: a photo's size is what decides
     * the sample, and asking for the pixels first is exactly the out-of-memory this exists to avoid.
     * Null when the pick cannot be read or is not a picture — the caller keeps the old avatar and
     * says so.
     */
    suspend fun decodeForCrop(
        context: Context,
        uri: Uri,
        cap: Int = AVATAR_CROP_WORK_PX,
    ): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@runCatching null
            // Bounds only: the point is to learn the size before allocating the pixels.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = avatarSampleSize(bounds.outWidth, bounds.outHeight, cap)
                },
            )
        }.getOrNull()
    }

    /**
     * Crops [source] to [rect], shrinks the square to [AVATAR_MAX_PX] and writes it as this
     * assistant's picture, returning the path for [Assistant.avatarPath] — or null when nothing
     * could be written, in which case [previous] is untouched.
     *
     * The crop is what the user framed on screen; the shrink and the encode are the same ones a
     * direct pick always went through, so there is still exactly one format and one size on disk.
     */
    suspend fun saveCropped(
        context: Context,
        id: String,
        source: Bitmap,
        rect: AvatarCropRect,
        previous: String = "",
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val side = rect.size.coerceIn(1, minOf(source.width, source.height))
            val left = rect.left.coerceIn(0, source.width - side)
            val top = rect.top.coerceIn(0, source.height - side)
            val cropped = Bitmap.createBitmap(source, left, top, side, side)
            val (width, height) = avatarSize(cropped.width, cropped.height)
            val scaled = if (width == cropped.width && height == cropped.height) {
                cropped
            } else {
                Bitmap.createScaledBitmap(cropped, width, height, true)
            }
            val path = pathFor(id, System.currentTimeMillis())
            val target = File(context.filesDir, path)
            target.parentFile?.mkdirs()
            val written = runCatching {
                target.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }.isSuccess
            if (scaled !== cropped) scaled.recycle()
            if (cropped !== source) cropped.recycle()
            if (!written) return@runCatching null
            // Only once the new file is on disk: a failed crop must not cost the old picture.
            if (previous.isNotBlank() && previous != path) {
                runCatching { File(context.filesDir, previous).delete() }
            }
            path
        }.getOrNull()
    }

    /**
     * Decoded pictures, shared by every surface that draws the same one.
     *
     * 头像会同时出现在顶栏（92dp）、抽屉、助手列表和每一条消息行里。原来每个调用点各自
     * `decodeFile` 一次：一屏十几条消息就是十几份同样的 Bitmap（内存翻倍，切会话/滚动时
     * 又是十几次解码）。路径在每次保存时都会变（[saveCropped] 会盖新名字），所以按路径缓存
     * 不会把「被替换掉的旧图」留在内存里。
     *
     * 上限按条数算（不是字节）：一张头像就是几 MB 的整数倍，16 条足够覆盖所有界面同时可见的
     * 数量，再多的按 LRU 淘汰。
     */
    private val pictures = object : android.util.LruCache<String, Bitmap>(16) {}

    /** Drops the cached decode of [path] (called when the file itself goes away). */
    private fun forget(path: String) {
        pictures.remove(path)
    }

    /** The stored picture, or null when it is missing or no longer decodes. */
    suspend fun load(context: Context, path: String): Bitmap? = withContext(Dispatchers.IO) {
        if (path.isBlank()) return@withContext null
        pictures.get(path)?.let { return@withContext it }
        runCatching {
            val file = File(context.filesDir, path)
            if (!file.isFile) null else BitmapFactory.decodeFile(file.absolutePath)
        }.getOrNull()?.also { pictures.put(path, it) }
    }

    /** Drops one picture: restoring the emoji, or deleting the assistant that owned it. */
    suspend fun delete(context: Context, path: String) = withContext(Dispatchers.IO) {
        if (path.isNotBlank()) {
            forget(path)
            runCatching { File(context.filesDir, path).delete() }
        }
        Unit
    }

    /**
     * Drops every picture belonging to [id], whatever it is called.
     *
     * The path in settings is the one to delete, but a pick made and then abandoned by closing
     * the editor never reached settings — those files are still named after this id, and this is
     * the sweep that takes them with the persona.
     */
    suspend fun deleteAllFor(context: Context, id: String) = withContext(Dispatchers.IO) {
        runCatching {
            File(context.filesDir, DIR)
                .listFiles { file -> file.name.startsWith("$id-") || file.name == "$id.png" }
                ?.forEach { it.delete() }
        }
        Unit
    }
}

/**
 * How much [BitmapFactory] may throw away while decoding, as a power of two.
 *
 * Sampling is the cheap half of the shrink: a 4000px photo decoded at sample 16 costs a 250px
 * bitmap instead of a 4000px one, and the exact fit is then done on that. The loop stops one
 * step short of the cap so the sampled bitmap is never *smaller* than what is stored — a
 * sample of 32 on a 4000px photo would already be 125px, and upscaling it back to 256 would
 * lose more than it saves.
 */
internal fun avatarSampleSize(width: Int, height: Int, cap: Int = AVATAR_MAX_PX): Int {
    if (width <= 0 || height <= 0 || cap <= 0) return 1
    var sample = 1
    while (max(width, height) / (sample * 2) >= cap) sample *= 2
    return sample
}

/** The stored size for a decoded bitmap: [cap] on the longest side, aspect ratio kept. */
internal fun avatarSize(width: Int, height: Int, cap: Int = AVATAR_MAX_PX): Pair<Int, Int> {
    if (width <= 0 || height <= 0 || cap <= 0) return 1 to 1
    val longest = max(width, height)
    if (longest <= cap) return width to height
    val scale = cap.toFloat() / longest
    return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
}
