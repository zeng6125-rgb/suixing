package com.lingshu.agent.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A minimal MCP server used by [McpClientTest]. It speaks newline-delimited
 * JSON-RPC on stdin/stdout exactly like a real stdio server, and it is written
 * in Kotlin so the test only needs the JVM it is already running on.
 *
 * It deliberately writes one non-JSON line to stdout first, to prove the client
 * skips server chatter instead of dying on it.
 */
object FakeMcpServer {
    @JvmStatic
    fun main(args: Array<String>) {
        val reader = System.`in`.bufferedReader()
        val writer = System.out.bufferedWriter()
        writer.write("fake mcp server starting\n")
        writer.flush()

        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val message = McpProtocol.json.parseToJsonElement(line).jsonObject
            // Notifications carry no id and need no answer.
            val id: JsonElement = message["id"] ?: continue
            val method = message["method"]?.jsonPrimitive?.content.orEmpty()
            val result = when (method) {
                "initialize" -> buildJsonObject {
                    put("protocolVersion", McpProtocol.PROTOCOL_VERSION)
                    put("capabilities", buildJsonObject { put("tools", buildJsonObject { }) })
                    put(
                        "serverInfo",
                        buildJsonObject {
                            put("name", "fake")
                            put("version", "1.2.3")
                        },
                    )
                }

                "tools/list" -> buildJsonObject {
                    put(
                        "tools",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("name", "echo")
                                    put("description", "Echo the text back")
                                    put(
                                        "inputSchema",
                                        buildJsonObject {
                                            put("type", "object")
                                            put(
                                                "properties",
                                                buildJsonObject {
                                                    put("text", buildJsonObject { put("type", "string") })
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("name", "boom")
                                    put("description", "Always fails")
                                    put("inputSchema", buildJsonObject { put("type", "object") })
                                },
                            )
                        },
                    )
                }

                "tools/call" -> {
                    val params = message["params"]?.jsonObject
                    if (params?.get("name")?.jsonPrimitive?.content == "boom") {
                        buildJsonObject {
                            put(
                                "content",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "text")
                                            put("text", "boom failed")
                                        },
                                    )
                                },
                            )
                            put("isError", true)
                        }
                    } else {
                        val text = params?.get("arguments")?.jsonObject?.get("text")?.jsonPrimitive?.content.orEmpty()
                        buildJsonObject {
                            put(
                                "content",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "text")
                                            put("text", "echo:$text")
                                        },
                                    )
                                },
                            )
                        }
                    }
                }

                else -> buildJsonObject { put("unexpectedMethod", method) }
            }

            writer.write(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("result", result)
                }.toString(),
            )
            writer.write("\n")
            writer.flush()
        }
    }
}
