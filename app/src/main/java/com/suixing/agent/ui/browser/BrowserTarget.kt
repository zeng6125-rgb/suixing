package com.suixing.agent.ui.browser

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.UriHandler
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

@Immutable
internal sealed interface BrowserTarget {
    data class Web(val url: String) : BrowserTarget
    data class Document(val path: String, val query: String? = null, val fragment: String? = null) : BrowserTarget
}

internal val LocalOpenBrowser = staticCompositionLocalOf<((BrowserTarget) -> Unit)?> { null }

internal const val BROWSER_ROUTE = "browser?url={url}&path={path}&query={query}&fragment={fragment}&request={request}"

internal fun browserRoute(target: BrowserTarget, requestId: String? = null): String {
    fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    val route = when (target) {
        is BrowserTarget.Web -> "browser?url=${encode(target.url)}"
        is BrowserTarget.Document -> buildString {
            append("browser?path=${encode(target.path)}")
            target.query?.let { append("&query=${encode(it)}") }
            target.fragment?.let { append("&fragment=${encode(it)}") }
        }
    }
    return if (requestId == null) route else "$route&request=${encode(requestId)}"
}

internal fun isWebUrl(value: String): Boolean =
    value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)

internal fun browserDocument(path: String): BrowserTarget.Document? {
    fun isDocument(value: String) = value.substringAfterLast('.').lowercase(Locale.ROOT) in
        HTML_EXTENSIONS
    if (isDocument(path)) return BrowserTarget.Document(path)
    val file = path.substringBefore('#').substringBefore('?')
    if (!isDocument(file)) return null
    val query = path.substringBefore('#').substringAfter('?', "").takeIf { '?' in path.substringBefore('#') }
    val fragment = path.substringAfter('#', "").takeIf { '#' in path }
    return BrowserTarget.Document(file, query, fragment)
}

internal fun browserTargetFromUri(value: String): BrowserTarget? {
    val input = value.trim()
    if (isWebUrl(input)) return BrowserTarget.Web(input)
    if (input.startsWith("file:", ignoreCase = true)) {
        val uri = runCatching { URI(input) }.getOrNull() ?: return null
        if (uri.host != null && uri.host != "localhost") return null
        val path = uri.path ?: return null
        return browserDocument(path)?.copy(query = uri.rawQuery, fragment = uri.rawFragment)
    }
    // Other schemes belong to their matching Android application.
    if (':' in input.substringBefore('/')) return null
    return browserDocument(input)
}

internal fun browserAddressTarget(value: String): BrowserTarget? {
    val input = value.trim()
    if (!input.any { it.isWhitespace() } && '/' in input && !input.startsWith('/') && !input.startsWith('~') && !input.startsWith('.')) {
        val host = input.substringBefore('/').substringBefore(':')
        if ('.' in host || host == "localhost") return BrowserTarget.Web("https://$input")
    }
    browserTargetFromUri(input)?.let { return it }
    if (input.isEmpty() || input.any { it.isWhitespace() } || input.startsWith('/') || input.startsWith('~')) return null
    val host = input.substringBefore('/').substringBefore(':')
    return if ('.' in host || host == "localhost") BrowserTarget.Web("https://$input") else null
}

private val HTML_EXTENSIONS = setOf("html", "htm", "xhtml")

internal class BrowserUriHandler(
    private val openBrowser: (BrowserTarget) -> Unit,
    private val fallback: UriHandler,
) : UriHandler {
    override fun openUri(uri: String) {
        val target = browserTargetFromUri(uri)
        if (target != null) openBrowser(target) else fallback.openUri(uri)
    }
}
