package com.lingshu.agent.tools

import com.lingshu.agent.data.SettingsRepository
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
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * Everything that reaches out to the network. A phone agent is only as useful as
 * the facts it can look up, and the model's memory is not one of them.
 */
class WebTools(
    private val context: Context,
    private val settings: SettingsRepository,
) : ToolGroup() {

    override val specs: List<ToolSpec> = listOf(
        ToolSpec(
            name = "web_fetch",
            description = "抓取一个网址的内容。默认把 HTML 转成纯文本，适合读文章、文档、接口返回。",
            parameters = Schema.objectOf(
                listOf(
                    "url" to Schema.string("完整地址，必须以 http:// 或 https:// 开头"),
                    "max_chars" to Schema.integer("最多返回多少字符", default = 12000, minimum = 500, maximum = 60000),
                    "raw" to Schema.boolean("true 则返回原始 HTML 不做转换", default = false),
                ),
                listOf("url"),
            ),
        ),
        ToolSpec(
            name = "web_download",
            description = "把网址指向的文件下载到本机路径（APK、图片、压缩包等）。",
            parameters = Schema.objectOf(
                listOf(
                    "url" to Schema.string("完整地址"),
                    "path" to Schema.string("保存到的本机路径，支持 ~ 开头的相对路径"),
                ),
                listOf("url", "path"),
            ),
        ),
        ToolSpec(
            name = "web_search",
            description = "搜索互联网，返回标题、链接与摘要。适合先搜再决定抓哪个页面。",
            parameters = Schema.objectOf(
                listOf(
                    "query" to Schema.string("搜索词"),
                    "count" to Schema.integer("返回条数", default = 5, minimum = 1, maximum = 10),
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
        else -> ToolResult.failure("未知网络工具：$name")
    }

    private suspend fun fetch(args: JsonObject): ToolResult {
        val url = args.str("url")?.trim().orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult.failure("url 必须以 http:// 或 https:// 开头")
        }
        val limit = args.int("max_chars", 12000).coerceIn(500, 60_000)
        val raw = args.bool("raw", false)
        return withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(request(url).build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use ToolResult.failure("请求失败：HTTP ${response.code} ${response.message}")
                    }
                    val body = readText(response)
                    val text = if (raw) body else htmlToText(body)
                    val head = text.take(limit)
                    val suffix = if (text.length > limit) "\n…（内容共 ${text.length} 字，已截断）" else ""
                    ToolResult.success("$url\n\n$head$suffix")
                }
            }.getOrElse { error ->
                ToolResult.failure("抓取失败：${error.message ?: error::class.java.simpleName}")
            }
        }
    }

    private suspend fun download(args: JsonObject): ToolResult {
        val url = args.str("url")?.trim().orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult.failure("url 必须以 http:// 或 https:// 开头")
        }
        val rawPath = args.str("path")?.trim().orEmpty()
        if (rawPath.isEmpty()) return ToolResult.failure("缺少参数 path")
        return withContext(Dispatchers.IO) {
            runCatching {
                val target = resolveUserPath(context, rawPath)
                target.parentFile?.mkdirs()
                client.newCall(request(url).build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use ToolResult.failure("下载失败：HTTP ${response.code} ${response.message}")
                    }
                    val body = response.body ?: return@use ToolResult.failure("下载失败：响应为空")
                    val declared = body.contentLength()
                    if (declared > MAX_DOWNLOAD_BYTES) {
                        return@use ToolResult.failure("文件太大（${declared / 1024 / 1024} MB），上限 ${MAX_DOWNLOAD_BYTES / 1024 / 1024} MB")
                    }
                    body.byteStream().use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (target.length() > MAX_DOWNLOAD_BYTES) {
                        target.delete()
                        return@use ToolResult.failure("文件超过 ${MAX_DOWNLOAD_BYTES / 1024 / 1024} MB，已删除")
                    }
                    ToolResult.success("已保存到 ${target.absolutePath}（${humanSize(target.length())}）")
                }
            }.getOrElse { error ->
                ToolResult.failure("下载失败：${error.message ?: error::class.java.simpleName}")
            }
        }
    }

    private suspend fun search(args: JsonObject): ToolResult {
        val query = args.str("query")?.trim().orEmpty()
        if (query.isEmpty()) return ToolResult.failure("缺少参数 query")
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
                        return@use ToolResult.failure("搜索失败：HTTP ${response.code} ${body.take(200)}")
                    }
                    val results = runCatching {
                        Json.parseToJsonElement(body).jsonObject["results"]?.jsonArray.orEmpty()
                    }.getOrElse { emptyList() }
                    if (results.isEmpty()) return@use ToolResult.success("没有搜到结果。")
                    ToolResult.success(
                        results.take(count).joinToString("\n\n") { item ->
                            val obj = item.jsonObject
                            val title = obj["title"]?.jsonPrimitive?.content.orEmpty()
                            val link = obj["url"]?.jsonPrimitive?.content.orEmpty()
                            val snippet = obj["content"]?.jsonPrimitive?.content.orEmpty()
                            "【$title】\n$link\n${snippet.take(400)}"
                        },
                    )
                }
            }.getOrElse { error ->
                ToolResult.failure("搜索失败：${error.message ?: error::class.java.simpleName}")
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
                        "内置搜索不可用（HTTP ${response.code}）。可在「设置 → 搜索接口」填一个 Tavily 兼容接口。",
                    )
                }
                val html = readText(response)
                val hits = parseDuckDuckGo(html, count)
                if (hits.isEmpty()) {
                    return@use ToolResult.failure(
                        "内置搜索没有解析到结果（可能被限流）。可在「设置 → 搜索接口」填一个 Tavily 兼容接口。",
                    )
                }
                ToolResult.success(hits.joinToString("\n\n"))
            }
        }.getOrElse { error ->
            ToolResult.failure(
                "内置搜索失败：${error.message ?: error::class.java.simpleName}。" +
                    "可在「设置 → 搜索接口」填一个 Tavily 兼容接口。",
            )
        }
    }

    private fun parseDuckDuckGo(html: String, count: Int): List<String> {
        val linkPattern = Regex(
            "<a[^>]+class=\"[^\"]*result__a[^\"]*\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val snippetPattern = Regex(
            "<a[^>]+class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val links = linkPattern.findAll(html).toList()
        val snippets = snippetPattern.findAll(html).toList()
        return links.take(count).mapIndexed { index, match ->
            val href = decodeEntities(match.groupValues[1])
            val title = htmlToText(match.groupValues[2])
            val snippet = snippets.getOrNull(index)?.let { htmlToText(it.groupValues[1]) }.orEmpty()
            "【$title】\n${cleanDuckDuckGoLink(href)}\n${snippet.take(300)}"
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
        val bytes = response.body?.bytes() ?: return ""
        val declared = Regex("charset=[\"']?([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE)
            .find(response.header("Content-Type").orEmpty())
            ?.groupValues?.get(1)
        val fromMeta = Regex(
            "<meta[^>]+charset=[\"']?([A-Za-z0-9_\\-]+)",
            RegexOption.IGNORE_CASE,
        ).find(String(bytes, Charsets.ISO_8859_1))?.groupValues?.get(1)
        val name = declared ?: fromMeta ?: "utf-8"
        val charset = runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
        return runCatching { String(bytes, charset) }.getOrDefault(String(bytes, Charsets.UTF_8))
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        const val MAX_DOWNLOAD_BYTES = 64L * 1024L * 1024L
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

/** Turns a page into something worth spending context on. */
internal fun htmlToText(html: String): String {
    var text = html
    text = text.replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
    text = text.replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
    text = text.replace(Regex("(?is)<noscript[^>]*>.*?</noscript>"), " ")
    text = text.replace(Regex("(?s)<!--.*?-->"), " ")
    text = text.replace(Regex("(?i)<li[^>]*>"), "\n- ")
    text = text.replace(
        Regex("(?i)<(br|/p|/div|/li|/tr|/h[1-6]|/table|/section|/article|/header|/footer)[^>]*>"),
        "\n",
    )
    text = text.replace(Regex("(?s)<[^>]+>"), " ")
    text = decodeEntities(text)
    text = text.replace(Regex("[ \\t\\u00A0\\u3000]+"), " ")
    text = text.replace(Regex(" *\\n *"), "\n")
    text = text.replace(Regex("\\n{3,}"), "\n\n")
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
    return Regex("&(#x?[0-9A-Fa-f]+|[A-Za-z][A-Za-z0-9]*);").replace(text) { match ->
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
