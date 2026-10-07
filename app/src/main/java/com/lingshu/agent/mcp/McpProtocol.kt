package com.lingshu.agent.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** One tool as advertised by an MCP server. */
data class McpToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

data class McpServerInfo(
    val name: String,
    val version: String,
    val protocolVersion: String,
)

data class McpCallResult(
    val ok: Boolean,
    val text: String,
)

class McpException(message: String) : Exception(message)

/**
 * The wire format of the Model Context Protocol, client side only: initialize,
 * tools/list and tools/call. Everything here is plain JSON with no Android
 * imports, so the whole layer runs under a plain JVM unit test.
 */
object McpProtocol {
    const val PROTOCOL_VERSION = "2024-11-05"

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun initialize(id: Long, clientName: String, clientVersion: String): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", "initialize")
        put(
            "params",
            buildJsonObject {
                put("protocolVersion", PROTOCOL_VERSION)
                put("capabilities", buildJsonObject { })
                put(
                    "clientInfo",
                    buildJsonObject {
                        put("name", clientName)
                        put("version", clientVersion)
                    },
                )
            },
        )
    }

    fun initializedNotification(): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", "notifications/initialized")
    }

    fun listTools(id: Long): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", "tools/list")
        put("params", buildJsonObject { })
    }

    fun callTool(id: Long, name: String, arguments: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("method", "tools/call")
        put(
            "params",
            buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            },
        )
    }

    /** The `error.message` of a JSON-RPC error response, or null when there is none. */
    fun errorMessage(response: JsonObject): String? {
        val error = response["error"] as? JsonObject ?: return null
        val message = error["message"].stringOrNull() ?: "服务器返回了未说明的错误"
        val code = (error["code"] as? JsonPrimitive)?.contentOrNull
        return if (code != null) "$message（code $code）" else message
    }

    fun parseServerInfo(result: JsonElement?): McpServerInfo {
        val root = result as? JsonObject
        val info = root?.get("serverInfo") as? JsonObject
        return McpServerInfo(
            name = info?.get("name").stringOrNull() ?: "mcp",
            version = info?.get("version").stringOrNull().orEmpty(),
            protocolVersion = root?.get("protocolVersion").stringOrNull() ?: PROTOCOL_VERSION,
        )
    }

    fun parseTools(result: JsonElement?): List<McpToolSpec> {
        val array = (result as? JsonObject)?.get("tools") as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val tool = element as? JsonObject ?: return@mapNotNull null
            val name = tool["name"].stringOrNull() ?: return@mapNotNull null
            McpToolSpec(
                name = name,
                description = tool["description"].stringOrNull().orEmpty(),
                inputSchema = tool["inputSchema"] as? JsonObject
                    ?: buildJsonObject { put("type", "object") },
            )
        }
    }

    fun parseCallResult(result: JsonElement?): McpCallResult {
        val root = result as? JsonObject
            ?: return McpCallResult(false, "MCP 服务器返回了非对象结果：${result.toString().take(300)}")
        val isError = (root["isError"] as? JsonPrimitive)?.contentOrNull == "true"
        val text = buildString {
            val content = root["content"] as? JsonArray
            if (content == null) {
                append(root.toString())
            } else {
                for (item in content) {
                    val block = item as? JsonObject ?: continue
                    when (block["type"].stringOrNull()) {
                        "text" -> appendLine(block["text"].stringOrNull().orEmpty())
                        "image" -> appendLine("（MCP 服务器返回了图片，当前版本不解析 MCP 图片内容）")
                        "resource" -> appendLine(block["resource"]?.toString().orEmpty())
                        else -> appendLine(block.toString())
                    }
                }
            }
        }.trim()
        val fallback = if (isError) "工具执行失败（服务器未给出详情）" else "（无输出）"
        return McpCallResult(!isError, text.ifEmpty { fallback })
    }

    /**
     * Splits a command line the way a shell would for the simple cases: single
     * and double quotes group arguments, backslash escapes the next character
     * outside single quotes. stdio MCP servers are configured as one line, so
     * this keeps `node "/sdcard/my server.js"` working.
     */
    fun splitCommand(line: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        for (ch in line) {
            when {
                escaped -> {
                    current.append(ch)
                    escaped = false
                }

                ch == '\\' && quote != '\'' -> escaped = true
                quote != null && ch == quote -> quote = null
                quote != null -> current.append(ch)
                ch == '"' || ch == '\'' -> quote = ch
                ch.isWhitespace() -> if (current.isNotEmpty()) {
                    out += current.toString()
                    current.clear()
                }

                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    /** `KEY=VALUE` lines into an environment map, ignoring blanks and comments. */
    fun parseEnv(text: String): Map<String, String> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .associate { line ->
            val index = line.indexOf('=')
            line.substring(0, index).trim() to line.substring(index + 1).trim()
        }

    /** `Name: value` lines into an HTTP header map. */
    fun parseHeaders(text: String): Map<String, String> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains(':') }
        .associate { line ->
            val index = line.indexOf(':')
            line.substring(0, index).trim() to line.substring(index + 1).trim()
        }
}

internal fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull
