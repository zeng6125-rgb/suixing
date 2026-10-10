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
     * Decodes [uri], shrinks it to [AVATAR_MAX_PX] and writes it as this assistant's picture,
     * returning the path to store in [Assistant.avatarPath] — or null when the pick could not
     * be read or decoded, in which case nothing was written and [previous] is untouched.
     */
    suspend fun save(
        context: Context,
        id: String,
        uri: Uri,
        previous: String = "",
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@runCatching null
            // Bounds only: the point is to learn the size before allocating the pixels.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val decoded = BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = avatarSampleSize(bounds.outWidth, bounds.outHeight)
                },
            ) ?: return@runCatching null
            val (width, height) = avatarSize(decoded.width, decoded.height)
            val scaled = if (width == decoded.width && height == decoded.height) {
                decoded
            } else {
                Bitmap.createScaledBitmap(decoded, width, height, true)
            }
            val path = pathFor(id, System.currentTimeMillis())
            val target = File(context.filesDir, path)
            target.parentFile?.mkdirs()
            target.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
            // Only once the new file is on disk: a failed pick must not cost the old picture.
            if (previous.isNotBlank() && previous != path) {
                runCatching { File(context.filesDir, previous).delete() }
            }
            path
        }.getOrNull()
    }

    /** The stored picture, or null when it is missing or no longer decodes. */
    suspend fun load(context: Context, path: String): Bitmap? = withContext(Dispatchers.IO) {
        if (path.isBlank()) return@withContext null
        runCatching {
            val file = File(context.filesDir, path)
            if (!file.isFile) null else BitmapFactory.decodeFile(file.absolutePath)
        }.getOrNull()
    }

    /** Drops one picture: restoring the emoji, or deleting the assistant that owned it. */
    suspend fun delete(context: Context, path: String) = withContext(Dispatchers.IO) {
        if (path.isNotBlank()) runCatching { File(context.filesDir, path).delete() }
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
