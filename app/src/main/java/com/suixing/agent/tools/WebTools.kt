package com.suixing.agent.tools

import com.suixing.agent.data.SettingsRepository
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * Everything that reaches out to the network. A phone agent is only as useful as
 * the facts it can look up, and the model's memory is not one of them.
 */

/** DuckDuckGo 结果解析用的两条正则：提到文件级，避免每次搜索重复编译（issue #53 B-1）。 */
private val DDG_LINK = Regex(
    "<a[^>]+class=\"[^\"]*result__a[^\"]*\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val DDG_SNIPPET = Regex(
    "<a[^>]+class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</a>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

class WebTools(
    private val context: Context,
    private val settings: SettingsRepository,
) : ToolGroup() {

    override val group: String = "网络"

    override val specs: List<ToolSpec> = listOf(
        ToolSpec(
            name = "web_fetch",
            description = "Fetch the contents of a URL. HTML is converted to plain text by default, " +
                "which suits articles, docs and API responses.",
            parameters = Schema.objectOf(
                listOf(
                    "url" to Schema.string("Full address; must start with http:// or https://"),
                    "max_chars" to Schema.integer(
                        "Maximum number of characters to return",
                        default = 12000,
                        minimum = 500,
                        maximum = 60000,
                    ),
                    "raw" to Schema.boolean("true returns the raw HTML without conversion", default = false),
                ),
                listOf("url"),
            ),
        ),
        ToolSpec(
            name = "web_download",
            description = "Download the file a URL points to into a local path (APKs, images, archives).",
            parameters = Schema.objectOf(
                listOf(
                    "url" to Schema.string("Full address"),
                    "path" to Schema.string("Local path to save to; a relative path starting with ~ is allowed"),
                ),
                listOf("url", "path"),
            ),
        ),
        ToolSpec(
            name = "web_search",
            description = "Search the web and return titles, links and snippets. Search first, then " +
                "decide which page to fetch.",
            parameters = Schema.objectOf(
                listOf(
                    "query" to Schema.string("Search terms"),
                    "count" to Schema.integer("Number of results", default = 5, minimum = 1, maximum = 10),
                ),
                listOf("query"),
            ),
        ),
    )

    override val tier: String = TIER_NONE

    override suspend fun execute(name: String, args: JsonObject): ToolResult = when (name) {
        "web_fetch" -> fetch(args)
        "web_download" -> download(args)
        "web_search" -> search(args)
        else -> ToolResult.failure("Unknown web tool: $name")
    }

    private suspend fun fetch(args: JsonObject): ToolResult {
        val url = args.str("url")?.trim().orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult.failure("url must start with http:// or https://")
        }
        val limit = args.int("max_chars", 12000).coerceIn(500, 60_000)
        val raw = args.bool("raw", false)
        return withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(request(url).build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use ToolResult.failure("Request failed: HTTP ${response.code} ${response.message}")
                    }
                    val body = readText(response)
                    val text = if (raw) body else htmlToText(body)
                    val head = text.take(limit)
                    val suffix = if (text.length > limit) {
                        "\n… (${text.length} characters in total, truncated)"
                    } else {
                        ""
                    }
                    ToolResult.success("$url\n\n$head$suffix")
                }
            }.getOrElse { error ->
                ToolResult.failure("Fetch failed: ${error.message ?: error::class.java.simpleName}")
            }
        }
    }

    private suspend fun download(args: JsonObject): ToolResult {
        val url = args.str("url")?.trim().orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult.failure("url must start with http:// or https://")
        }
        val rawPath = args.str("path")?.trim().orEmpty()
        if (rawPath.isEmpty()) return ToolResult.failure("Missing argument: path")
        return withContext(Dispatchers.IO) {
            runCatching {
                // Leftovers from a download that was killed mid-write: the part file under this
                // very target is deleted below, and this clears the ones nobody will retry.
                sweepStaleParts(workspaceDir(context))
                val target = resolveUserPath(context, rawPath)
                target.parentFile?.mkdirs()
                client.newCall(request(url).build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use ToolResult.failure("Download failed: HTTP ${response.code} ${response.message}")
                    }
                    val body = response.body ?: return@use ToolResult.failure("Download failed: empty response")
                    val declared = body.contentLength()
                    if (declared > MAX_DOWNLOAD_BYTES) {
                        return@use ToolResult.failure(
                            "File too large (${declared / 1024 / 1024} MB); the limit is " +
                                "${MAX_DOWNLOAD_BYTES / 1024 / 1024} MB",
                        )
                    }
                    // Written beside the target and renamed only once it is complete: a
                    // half-finished download must never sit under the final name, where the
                    // model would read it as if it were whole.
                    val part = File(target.absolutePath + PART_SUFFIX)
                    part.delete()
                    try {
                        body.byteStream().use { input ->
                            part.outputStream().use { output -> input.copyTo(output) }
                        }
                        if (part.length() > MAX_DOWNLOAD_BYTES) {
                            part.delete()
                            return@use ToolResult.failure(
                                "File exceeds ${MAX_DOWNLOAD_BYTES / 1024 / 1024} MB; deleted",
                            )
                        }
                        if (!part.renameTo(target)) {
                            part.delete()
                            return@use ToolResult.failure("Download failed: cannot write ${target.absolutePath}")
                        }
                    } catch (error: Exception) {
                        part.delete()
                        throw error
                    }
                    ToolResult.success("Saved to ${target.absolutePath} (${humanSize(target.length())})")
                }
            }.getOrElse { error ->
                ToolResult.failure("Download failed: ${error.message ?: error::class.java.simpleName}")
            }
        }
    }

    private suspend fun search(args: JsonObject): ToolResult {
        val query = args.str("query")?.trim().orEmpty()
        if (query.isEmpty()) return ToolResult.failure("Missing argument: query")
        val count = args.int("count", 5).coerceIn(1, 10)
        val config = settings.current()
        return if (config.searchEndpoint.isNotBlank()) {
            tavily(config.searchEndpoint.trim(), config.searchApiKey.trim(), query, count)
        } else {
            duckduckgo(query, count)
        }
    }

    private suspend fun tavily(endpoint: String, apiKey: String, query: String, count: Int): ToolResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = buildJsonObject {
                    put("query", JsonPrimitive(query))
                    put("max_results", JsonPrimitive(count))
                    put("search_depth", JsonPrimitive("basic"))
                    if (apiKey.isNotEmpty()) put("api_key", JsonPrimitive(apiKey))
                }
                val builder = Request.Builder()
                    .url(endpoint)
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                if (apiKey.isNotEmpty()) builder.header("Authorization", "Bearer $apiKey")
                client.newCall(builder.build()).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@use ToolResult.failure("Search failed: HTTP ${response.code} ${body.take(200)}")
                    }
                    val results = runCatching {
                        Json.parseToJsonElement(body).jsonObject["results"]?.jsonArray.orEmpty()
                    }.getOrElse { emptyList() }
                    if (results.isEmpty()) return@use ToolResult.success("No results found.")
                    ToolResult.success(
                        results.take(count).joinToString("\n\n") { item ->
                            val obj = item.jsonObject
                            val title = obj["title"]?.jsonPrimitive?.content.orEmpty()
                            val link = obj["url"]?.jsonPrimitive?.content.orEmpty()
                            val snippet = obj["content"]?.jsonPrimitive?.content.orEmpty()
                            "[$title]\n$link\n${snippet.take(400)}"
                        },
                    )
                }
            }.getOrElse { error ->
                ToolResult.failure("Search failed: ${error.message ?: error::class.java.simpleName}")
            }
        }

    /**
     * Zero-config fallback: DuckDuckGo's no-JS endpoint. It rate-limits harder than
     * a paid API, so the failure message points at the setting that fixes it.
     */
    private suspend fun duckduckgo(query: String, count: Int): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://html.duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(query, "UTF-8")
            client.newCall(request(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use ToolResult.failure(
                        "Built-in search unavailable (HTTP ${response.code}). You can set a " +
                            "Tavily-compatible endpoint under 「设置 → 搜索接口」.",
                    )
                }
                val html = readText(response)
                val hits = parseDuckDuckGo(html, count)
                if (hits.isEmpty()) {
                    return@use ToolResult.failure(
                        "Built-in search parsed no results (it may be rate-limited). You can set a " +
                            "Tavily-compatible endpoint under 「设置 → 搜索接口」.",
                    )
                }
                ToolResult.success(hits.joinToString("\n\n"))
            }
        }.getOrElse { error ->
            ToolResult.failure(
                "Built-in search failed: ${error.message ?: error::class.java.simpleName}. " +
                    "You can set a Tavily-compatible endpoint under 「设置 → 搜索接口」.",
            )
        }
    }

    private fun parseDuckDuckGo(html: String, count: Int): List<String> {
        val linkPattern = DDG_LINK
        val snippetPattern = DDG_SNIPPET
        val links = linkPattern.findAll(html).toList()
        val snippets = snippetPattern.findAll(html).toList()
        return links.take(count).mapIndexed { index, match ->
            val href = decodeEntities(match.groupValues[1])
            val title = htmlToText(match.groupValues[2])
            val snippet = snippets.getOrNull(index)?.let { htmlToText(it.groupValues[1]) }.orEmpty()
            "[$title]\n${cleanDuckDuckGoLink(href)}\n${snippet.take(300)}"
        }
    }

    /** DDG wraps outbound links in /l/?uddg=<encoded>. */
    private fun cleanDuckDuckGoLink(href: String): String {
        val marker = "uddg="
        val index = href.indexOf(marker)
        if (index < 0) return href
        val encoded = href.substring(index + marker.length).substringBefore('&')
        return runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(href)
    }

    private fun request(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")

    /** OkHttp only trusts the charset in Content-Type, so decode the bytes ourselves. */
    private fun readText(response: Response): String {
        val (bytes, truncated) = readCapped(response)
        if (bytes.isEmpty()) return ""
        val declared = RE_HEADER_CHARSET.find(response.header("Content-Type").orEmpty())
            ?.groupValues?.get(1)
        // **只解码前 4KB 去嗅探 charset**（优化清单 tools-P1）：原来把 ≤1MB 的整段正文用
        // ISO_8859_1 解成一个大 String，只为在开头找一个 meta 标签。
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        val fromMeta = RE_META_CHARSET.find(head)?.groupValues?.get(1)
        val name = declared ?: fromMeta ?: "utf-8"
        val charset = runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
        val text = runCatching { String(bytes, charset) }.getOrDefault(String(bytes, Charsets.UTF_8))
        return if (truncated) {
            "$text\n… (response too large; only the first ${humanSize(MAX_FETCH_BYTES.toLong())} were read)"
        } else {
            text
        }
    }

    /**
     * Reads at most [MAX_FETCH_BYTES] into memory. `body.bytes()` pulls the whole response in and
     * only then could anything be truncated, so one web_fetch of a big page — or of a URL that
     * turned out to be a video — can OOM the app; this stops reading at the cap instead.
     */
    private fun readCapped(response: Response): Pair<ByteArray, Boolean> {
        val body = response.body ?: return ByteArray(0) to false
        val declared = body.contentLength()
        body.byteStream().use { input ->
            // 预分配（优化清单 tools-P2）：contentLength 已知时按它开，上限仍由 MAX_FETCH_BYTES 夹住。
            val initial = if (declared in 1..MAX_FETCH_BYTES.toLong()) declared.toInt() else 16 * 1024
            val out = ByteArrayOutputStream(initial)
            val buffer = ByteArray(16 * 1024)
            while (out.size() < MAX_FETCH_BYTES) {
                val room = (MAX_FETCH_BYTES.toLong() - out.size()).coerceAtMost(buffer.size.toLong()).toInt()
                val read = input.read(buffer, 0, room)
                if (read <= 0) break
                out.write(buffer, 0, read)
            }
            val bytes = out.toByteArray()
            return bytes to (declared > MAX_FETCH_BYTES || bytes.size >= MAX_FETCH_BYTES)
        }
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        const val MAX_DOWNLOAD_BYTES = 64L * 1024L * 1024L

        /**
         * How much of a fetched response may enter memory. The tool's own output is capped at
         * 60k characters (≈240 KB worst-case UTF-8) and markup still has to be stripped on top
         * of that, so 1 MB is several times more than any useful answer while keeping a mistaken
         * binary URL from pulling tens of megabytes into RAM.
         */
        const val MAX_FETCH_BYTES = 1024 * 1024
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}

private val RE_HEADER_CHARSET = Regex("charset=[\"']?([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
private val RE_META_CHARSET = Regex("<meta[^>]+charset=[\"']?([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
private val RE_SCRIPT = Regex("(?is)<script[^>]*>.*?</script>")
private val RE_STYLE = Regex("(?is)<style[^>]*>.*?</style>")
private val RE_NOSCRIPT = Regex("(?is)<noscript[^>]*>.*?</noscript>")
private val RE_COMMENT = Regex("(?s)<!--.*?-->")
private val RE_LI = Regex("(?i)<li[^>]*>")
private val RE_BLOCK_END = Regex("(?i)<(br|/p|/div|/li|/tr|/h[1-6]|/table|/section|/article|/header|/footer)[^>]*>")
private val RE_TAG = Regex("(?s)<[^>]+>")

private val RE_TEXT_SPACES = Regex("[ \\t\\u00A0\\u3000]+")
private val RE_LINE_SPACES = Regex(" *\\n *")
private val RE_BLANK_LINES = Regex("\\n{3,}")
private val RE_ENTITY = Regex("&(#x?[0-9A-Fa-f]+|[A-Za-z][A-Za-z0-9]*);")

/** Turns a page into something worth spending context on. */
internal fun htmlToText(html: String): String {
    var text = html
    // 这些正则原来是**每次转换都现场编译**一遍（每抓一个页面一次）。提到文件级，行为不变。
    text = text.replace(RE_SCRIPT, " ")
    text = text.replace(RE_STYLE, " ")
    text = text.replace(RE_NOSCRIPT, " ")
    text = text.replace(RE_COMMENT, " ")
    text = text.replace(RE_LI, "\n- ")
    text = text.replace(RE_BLOCK_END, "\n")
    text = text.replace(RE_TAG, " ")
    text = decodeEntities(text)
    text = text.replace(RE_TEXT_SPACES, " ")
    text = text.replace(RE_LINE_SPACES, "\n")
    text = text.replace(RE_BLANK_LINES, "\n\n")
    return text.trim()
}

private val NAMED_ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    "mdash" to "—", "ndash" to "–", "hellip" to "…", "ldquo" to "“", "rdquo" to "”",
    "lsquo" to "‘", "rsquo" to "’", "middot" to "·", "times" to "×", "divide" to "÷",
    "copy" to "©", "reg" to "®", "trade" to "™", "yen" to "¥", "euro" to "€", "pound" to "£",
    "deg" to "°", "plusmn" to "±", "laquo" to "«", "raquo" to "»", "bull" to "•",
    "dagger" to "†", "sect" to "§", "para" to "¶", "micro" to "µ", "prime" to "′",
)

internal fun decodeEntities(text: String): String {
    if (!text.contains('&')) return text
    return RE_ENTITY.replace(text) { match ->
        val body = match.groupValues[1]
        when {
            body.startsWith("#x", ignoreCase = true) ->
                body.drop(2).toIntOrNull(16)?.let { codePoint(it) } ?: match.value

            body.startsWith("#") -> body.drop(1).toIntOrNull()?.let { codePoint(it) } ?: match.value
            else -> NAMED_ENTITIES[body.lowercase()] ?: match.value
        }
    }
}

private fun codePoint(value: Int): String =
    runCatching { String(Character.toChars(value)) }.getOrDefault("")
