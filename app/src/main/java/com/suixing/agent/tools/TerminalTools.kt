package com.suixing.agent.tools

import com.suixing.agent.terminal.TerminalCaller
import com.suixing.agent.terminal.TerminalSessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.*

/** All calls pass the engine's existing conversation approval gate before reaching this group. */
internal class TerminalTools(private val managerProvider: () -> TerminalSessionManager) : ToolGroup() {
    override val group = "交互式终端"
    private val sessionParameter = "session_id" to Schema.string("")
    private fun sessionSchema() = Schema.objectOf(listOf(sessionParameter), listOf("session_id"))
    override val specs = listOf(
        ToolSpec("terminal_open",
            "Open an interactive App-UID PTY owned by this chat. Android or an already installed Linux environment. " +
                "App UID can access this app's private files; Linux guest root is not Android root or a security sandbox.",
            Schema.objectOf(listOf("environment" to Schema.string("", listOf("android", "alpine", "debian"), "android")))),
        ToolSpec("terminal_list", "List only this chat's PTYs and manual sessions explicitly granted to this task.",
            Schema.objectOf(emptyList())),
        ToolSpec("terminal_read", "Read bounded incremental UTF-8 PTY output; reuse the returned opaque cursor. Reports lost history.",
            Schema.objectOf(listOf(sessionParameter, "cursor" to Schema.integer("", minimum = 0),
                "max_bytes" to Schema.integer("", 16384, 1, 16384)), listOf("session_id"))),
        ToolSpec("terminal_write", "Send exact input to an authorized PTY, at most 16 KiB. Newline executes commands. Human takeover revokes writes.",
            Schema.objectOf(listOf(sessionParameter, "text" to Schema.string("Exact terminal input"),
                "append_newline" to Schema.boolean("", false)), listOf("session_id", "text"))),
        ToolSpec("terminal_interrupt", "Send Ctrl-C to an authorized terminal's foreground command.", sessionSchema()),
        ToolSpec("terminal_close", "Stop an authorized PTY and its session processes.", sessionSchema()),
    )

    override suspend fun execute(name: String, args: JsonObject): ToolResult {
        val caller = currentCoroutineContext()[TerminalCaller]
            ?: return ToolResult.failure("终端工具需要当前任务的授权上下文")
        return try {
            val manager = managerProvider()
            manager.load()
            when (name) {
                "terminal_open" -> {
                    val entry = manager.open(args.str("environment") ?: "android", caller)
                    ToolResult.success(buildJsonObject {
                        put("session_id", entry.info.id); put("environment", entry.info.kind)
                        put("shell", entry.info.shell); put("cwd", entry.info.cwd)
                        put("identity", "Android App UID"); put("state", entry.info.phase)
                    }.toString())
                }
                "terminal_list" -> ToolResult.success(JsonArray(manager.forCaller(caller).map { entry ->
                    buildJsonObject {
                        put("session_id", entry.info.id); put("environment", entry.info.kind)
                        put("shell", entry.info.shell); put("state", entry.info.phase)
                    }
                }).toString())
                else -> {
                    val id = args.str("session_id") ?: return ToolResult.failure("缺少 session_id")
                    val session = manager.authorized(id, caller)
                    when (name) {
                        "terminal_read" -> {
                            val read = session.read((args["cursor"] as? JsonPrimitive)?.longOrNull, args.int("max_bytes", 16384))
                            ToolResult.success(buildJsonObject {
                                put("session_id", id); put("new_cursor", read.cursor); put("truncated", read.truncated)
                                put("state", session.status.value.name.lowercase()); put("output", read.output)
                                session.ended.value?.let { put("ended", it) }
                            }.toString())
                        }
                        "terminal_write" -> {
                            val text = args.str("text") ?: return ToolResult.failure("缺少 text")
                            session.write(text + if (args.bool("append_newline", false)) "\r" else "", caller)
                            ToolResult.success("输入已发送：$id")
                        }
                        "terminal_interrupt" -> { session.interrupt(caller); ToolResult.success("已发送 Ctrl-C：$id") }
                        "terminal_close" -> { session.stop(caller); ToolResult.success("会话已停止：$id") }
                        else -> ToolResult.failure("未知终端工具")
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ToolResult.failure(error.message ?: "终端操作失败")
        }
    }
}
