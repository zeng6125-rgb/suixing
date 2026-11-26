package com.suixing.agent.ui.browser

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URI
import com.suixing.agent.core.NativeCore
import java.net.URLConnection
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap

private const val LOCAL_HOST_SUFFIX = ".jagent.invalid"

/** Each opened directory has one HTTPS origin, including its relative assets and history. */
internal class BrowserLocalFiles(restoredRoots: List<String> = emptyList()) {
    private val roots = CopyOnWriteArrayList(restoredRoots.map(::File))
    private val directories = ConcurrentHashMap<String, File>().apply {
        roots.forEach { put(localHost(it), it) }
    }

    fun savedRoots(): ArrayList<String> = ArrayList(roots.map { it.path })

    fun open(target: BrowserTarget.Document, file: File): String {
        val entry = file.canonicalFile
        require(entry.isFile) { "文件不存在或不是普通文件" }
        val root = entry.parentFile!!
        roots.addIfAbsent(root)
        val host = localHost(root)
        directories.putIfAbsent(host, root)
        return buildString {
            append(URI("https", host, "/${entry.name}", null).toASCIIString())
            target.query?.let { append('?').append(it) }
            target.fragment?.let { append('#').append(it) }
        }
    }

    private fun root(uri: URI): File? {
        if (uri.scheme != "https") return null
        return directories[uri.host ?: return null]
    }

    fun fileForUrl(url: String): File? = fileForUri(URI(url))

    private fun fileForUri(uri: URI): File? {
        val directory = root(uri) ?: return null
        val file = File(directory, uri.path.removePrefix("/")).canonicalFile
        return file.takeIf { it.toPath().startsWith(directory.toPath()) && it.isFile }
    }

    fun displayUrl(url: String): String {
        if (LOCAL_HOST_SUFFIX !in url) return url
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val directory = root(uri) ?: return url
        return buildString {
            append(File(directory, uri.path.removePrefix("/")).path)
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }
    }

    fun response(url: String): WebResourceResponse? {
        if (LOCAL_HOST_SUFFIX !in url) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.host?.endsWith(LOCAL_HOST_SUFFIX) != true) return null
        return try {
            val file = fileForUri(uri)
            if (file == null) notFound() else {
                val mime = browserMimeType(file.name)
                WebResourceResponse(mime, if (mime.startsWith("text/") || mime in TEXT_MIMES) "UTF-8" else null,
                    file.inputStream())
            }
        } catch (_: IOException) { notFound() }
        catch (_: SecurityException) { notFound() }
    }

    private fun notFound() = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(),
        ByteArrayInputStream("文件不存在或不在当前网页目录中".toByteArray(Charsets.UTF_8)))
}

/** Stable directory origins preserve web storage without a separate persisted ID registry. */
private fun localHost(directory: File): String {
    val digest = NativeCore.sha256Digest(directory.path.toByteArray(Charsets.UTF_8))
    val hex = buildString(64) {
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            append("0123456789abcdef"[value ushr 4])
            append("0123456789abcdef"[value and 15])
        }
    }
    return "local-${hex.take(32)}.${hex.drop(32)}$LOCAL_HOST_SUFFIX"
}

private val TEXT_MIMES = setOf("application/javascript", "application/json", "application/xhtml+xml", "image/svg+xml")

internal fun browserMimeType(name: String): String = when (name.substringAfterLast('.').lowercase(Locale.ROOT)) {
    "html", "htm" -> "text/html"
    "xhtml" -> "application/xhtml+xml"
    "css" -> "text/css"
    "js", "mjs" -> "application/javascript"
    "json" -> "application/json"
    "svg" -> "image/svg+xml"
    "wasm" -> "application/wasm"
    else -> URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
}
