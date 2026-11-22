package com.suixing.agent.ui.browser

import android.webkit.WebView
import com.suixing.agent.tools.ToolResult
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val PAGE_TEXT_LIMIT = 12_000

internal class BrowserOpenRequest(val target: BrowserTarget) {
    val id: String = UUID.randomUUID().toString()
    val result = CompletableDeferred<ToolResult>()
    private var reading = false

    fun fail(message: String) {
        if (result.isActive) result.complete(ToolResult.failure(message))
    }

    fun read(view: WebView, url: String, address: String) {
        if (reading || !result.isActive) return
        reading = true
        val title = view.title.orEmpty()
        view.evaluateJavascript(
            "(function(){return (document.body ? document.body.innerText || '' : '').slice(0,${PAGE_TEXT_LIMIT + 1})})()",
        ) { response ->
            if (result.isActive) {
                if (view.url != url) fail("网页在读取期间已切换")
                else result.complete(browserPageResult(address, title, response))
            }
        }
    }
}

internal fun browserPageResult(address: String, title: String, response: String?): ToolResult {
    val text = response?.let {
        (runCatching { Json.parseToJsonElement(it) }.getOrNull() as? JsonPrimitive)
            ?.takeIf { value -> value.isString }?.content
    } ?: return ToolResult.failure("网页正文无法读取")
    return ToolResult.success(buildJsonObject {
        put("url", address)
        put("title", title.take(512))
        put("text", text.take(PAGE_TEXT_LIMIT))
        put("truncated", text.length > PAGE_TEXT_LIMIT)
    }.toString())
}

/** Browser screen registration, navigation, and tool calls all run on the main thread. */
internal class BrowserOpenRequests {
    var navigate: ((BrowserOpenRequest) -> Boolean)? = null
    private var active: BrowserOpenRequest? = null

    fun find(id: String?): BrowserOpenRequest? = active?.takeIf { it.id == id }

    suspend fun open(target: BrowserTarget): ToolResult {
        val navigate = navigate ?: return ToolResult.failure("请先在前台打开 JAgent")
        if (active != null) return ToolResult.failure("浏览器正在处理另一个请求")
        val request = BrowserOpenRequest(target)
        active = request
        return try {
            if (!navigate(request)) ToolResult.failure("请先在前台打开 JAgent")
            else withTimeoutOrNull(30_000) { request.result.await() }
                ?: ToolResult.failure("网页加载超时")
        } finally {
            request.result.cancel()
            active = null
        }
    }
}

internal val browserOpenRequests = BrowserOpenRequests()
