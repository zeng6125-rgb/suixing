package com.suixing.agent.data

import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal object AppLinks {
    const val REPOSITORY = "https://github.com/zeng6125-rgb/suixing"
    const val RELEASES = "$REPOSITORY/releases"
    const val ISSUES = "$REPOSITORY/issues"
    fun feedback(version: String): String = "$ISSUES/new?body=" + URLEncoder.encode(
        "版本：$version\n\n问题描述：\n\n复现步骤：\n1. \n\n期望表现：\n\n实际表现：\n", "UTF-8")
}

internal enum class ReleaseRelation { UPDATE_AVAILABLE, CURRENT, LOCAL_AHEAD }
internal data class ReleaseInfo(val version: String, val title: String, val notes: String, val url: String)

/** Numeric components, rather than lexicographic comparison (e.g. 545 is newer than 99). */
internal fun releaseRelation(current: String, published: String): ReleaseRelation {
    fun components(value: String): List<Int> = value.trim().removePrefix("v").removePrefix("V")
        .substringBefore('+').substringBefore('-').split('.').map {
            it.toIntOrNull()?.takeIf { number -> number >= 0 } ?: error("无法识别版本号：$value")
        }.also { require(it.size in 2..4) { "无法识别版本号：$value" } }
    val mine = components(current)
    val theirs = components(published)
    for (index in 0 until maxOf(mine.size, theirs.size)) {
        val comparison = (mine.getOrElse(index) { 0 }).compareTo(theirs.getOrElse(index) { 0 })
        if (comparison < 0) return ReleaseRelation.UPDATE_AVAILABLE
        if (comparison > 0) return ReleaseRelation.LOCAL_AHEAD
    }
    return ReleaseRelation.CURRENT
}

internal fun parseReleaseInfo(raw: String): ReleaseInfo {
    val fields = Json.parseToJsonElement(raw).jsonObject
    require(fields["draft"]?.jsonPrimitive?.booleanOrNull != true &&
        fields["prerelease"]?.jsonPrimitive?.booleanOrNull != true) { "没有可用的正式版本" }
    val version = fields["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
    releaseRelation(version, version)
    val url = fields["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val uri = URI(url)
    require(uri.scheme == "https" && uri.host == "github.com" &&
        uri.path.startsWith("/zeng6125-rgb/suixing/releases/tag/")) { "发布链接不是官方仓库" }
    return ReleaseInfo(version, fields["name"]?.jsonPrimitive?.contentOrNull?.ifBlank { version } ?: version,
        fields["body"]?.jsonPrimitive?.contentOrNull.orEmpty().take(12_000), url)
}

/** Button-triggered only. Cancellation closes the call; no Activity or credentials are retained. */
internal class ReleaseChecker {
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS).build()
    }

    suspend fun latest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val raw = suspendCancellableCoroutine<String?> { continuation ->
            val call = http.newCall(Request.Builder()
                .url("https://api.github.com/repos/zeng6125-rgb/suixing/releases/latest")
                .header("Accept", "application/vnd.github+json").header("User-Agent", "suixing-android").build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!continuation.isActive) return
                            if (it.code == 404) { continuation.resume(null); return }
                            if (!it.isSuccessful) throw IOException("更新服务返回 HTTP ${it.code}")
                            val source = (it.body ?: throw IOException("更新服务返回空内容")).source()
                            source.request(1_048_577L)
                            if (source.buffer.size > 1_048_576L) throw IOException("发布信息过大")
                            continuation.resume(source.readUtf8())
                        }
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
        raw?.let(::parseReleaseInfo)
    }
}
