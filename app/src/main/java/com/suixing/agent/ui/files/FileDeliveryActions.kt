package com.suixing.agent.ui.files

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.suixing.agent.tools.humanSize
import com.suixing.agent.tools.resolveUserPath
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class LocalFilePreview(
    val path: String,
    val name: String,
    val size: String,
    val content: String,
    val imagePath: String? = null,
    val imageKey: String = "",
)
internal data class PreparedDeliveryFile(val uri: Uri, val mimeType: String)

/** Called by a file action, never by composition or a scrolling item. */
internal suspend fun loadFilePreview(context: Context, path: String): LocalFilePreview = withContext(Dispatchers.IO) {
    val file = readableFile(context, path)
    val size = file.length()
    val image = mimeType(file.extension).startsWith("image/")
    LocalFilePreview(file.absolutePath, file.name, humanSize(size), if (image) "" else readPreview(file),
        imagePath = file.absolutePath.takeIf { image }, imageKey = "${file.absolutePath}:${file.lastModified()}:$size")
}

/** Copy just the clicked file. Only this dedicated cache directory is exposed by FileProvider. */
internal suspend fun prepareDeliveryFile(context: Context, path: String): PreparedDeliveryFile = withContext(Dispatchers.IO) {
    val file = readableFile(context, path)
    val root = File(context.cacheDir, "chat_delivery").apply {
        check(isDirectory || mkdirs()) { "无法准备文件分享目录" }
    }
    trimDeliveryCache(root)
    val directory = File(root, UUID.randomUUID().toString())
    check(directory.mkdir()) { "无法准备文件分享" }
    val copy = File(directory, file.name)
    try {
        file.inputStream().use { input ->
            copy.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) output.write(buffer, 0, count)
                }
            }
        }
        currentCoroutineContext().ensureActive()
        PreparedDeliveryFile(
            FileProvider.getUriForFile(context, "${context.packageName}.filedelivery", copy),
            mimeType(file.extension),
        )
    } catch (failure: Exception) {
        copy.delete()
        directory.delete()
        throw failure
    }
}

/** The caller invokes this on the main thread after IO completes. Grants are read-only, per URI. */
internal fun launchDeliveryFile(context: Context, file: PreparedDeliveryFile, share: Boolean) {
    val intent = if (share) {
        Intent(Intent.ACTION_SEND).setType(file.mimeType).putExtra(Intent.EXTRA_STREAM, file.uri)
    } else {
        Intent(Intent.ACTION_VIEW).setDataAndType(file.uri, file.mimeType)
    }
    intent.clipData = ClipData.newRawUri("文件", file.uri)
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val launch = if (share) Intent.createChooser(intent, "分享文件").apply {
        clipData = intent.clipData
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } else intent
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(launch)
}

private fun readableFile(context: Context, path: String): File {
    val file = resolveUserPath(context, path).canonicalFile
    require(file.isFile) { "文件不存在或不是普通文件" }
    require(file.canRead()) { "当前无法读取这个文件" }
    return file
}

private fun mimeType(extension: String): String {
    val normalized = extension.lowercase(Locale.ROOT)
    return when (normalized) {
        "md", "txt", "log", "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "sh",
        "yml", "yaml", "toml", "ini", "conf", "properties", "csv" -> "text/plain"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(normalized) ?: "application/octet-stream"
    }
}

/** Old copies have a one-day lifetime. No recursion and no deletion outside our generated folders. */
private fun trimDeliveryCache(root: File) {
    val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1_000L
    val parent = root.canonicalFile
    root.listFiles().orEmpty().forEach { directory ->
        if (runCatching { UUID.fromString(directory.name) }.isFailure ||
            !directory.isDirectory || directory.canonicalFile.parentFile != parent || directory.lastModified() >= cutoff) return@forEach
        directory.listFiles().orEmpty().forEach { child -> if (!child.isDirectory) child.delete() }
        directory.delete()
    }
}
