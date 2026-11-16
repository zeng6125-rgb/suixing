package com.suixing.agent.ui.chat

import com.suixing.agent.core.NativeCore
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object NativeTranscriptJni {
    external fun nativePlan(request: IntArray): ByteArray
    external fun nativePage(turns: ByteArray, want: Int): IntArray
}

internal object NativeTranscript {
    fun page(messages: List<StoredMessage>, want: Int): TurnPage? {
        if (!NativeCore.available) return null
        val flags = ByteArray(messages.size) { if (messages[it].role == Roles.USER && !messages[it].steer) 1 else 0 }
        val page = NativeTranscriptJni.nativePage(flags, want)
        return TurnPage(page[0], page[1], page[2])
    }

    fun rows(
        messages: List<StoredMessage>,
        active: Map<String, String>,
        running: Boolean,
        pending: String?,
    ): List<ChatRow>? {
        if (!NativeCore.available) return null
        val request = IntArray(5 + messages.size * 4 + active.size * 2)
        var position = 0
        val strings = ArrayList<String>(messages.size * 2 + active.size * 2 + 2)
        val ids = HashMap<String, Int>(messages.size + active.size * 2 + 1)
        fun id(value: String): Int = ids.getOrPut(value) {
            strings.add(value)
            strings.lastIndex
        }
        fun integer(value: Int) { request[position++] = value }
        id("")
        integer(messages.size)
        integer(if (running) 1 else 0)
        integer(if (pending != null) 1 else 0)
        integer(id(pending.orEmpty()))
        for (message in messages) {
            integer(id(message.id))
            integer(id(message.runId.orEmpty()))
            integer(when (message.role) {
                Roles.USER -> 1
                Roles.ASSISTANT -> 2
                Roles.TOOL -> 3
                Roles.SYSTEM -> 4
                else -> 0
            })
            integer((if (message.steer) 1 else 0) or (if (message.isVisible()) 2 else 0))
        }
        integer(active.size)
        active.forEach { (turn, run) -> integer(id(turn)); integer(id(run)) }
        val input = ByteBuffer.wrap(NativeTranscriptJni.nativePlan(request)).order(ByteOrder.LITTLE_ENDIAN)
        return List(input.int) {
            val first = input.int
            val owner = input.int
            val previous = input.int
            val branch = if (input.int == 0) null else {
                val turn = strings[input.int]
                val index = input.int
                val regenerate = input.int != 0
                Branch(turn, List(input.int) { strings[input.int] }, index, regenerate)
            }
            val parts = ArrayList<Part>()
            val rounds = arrayListOf(messages[first])
            var tools: ArrayList<ToolLine>? = null
            fun finishTools() {
                tools?.let { parts += Part.Tools(it.toList()) }
                tools = null
            }
            repeat(input.int) {
                val kind = input.int
                val source = input.int
                val message = messages[source]
                when (kind) {
                    1 -> {
                        finishTools()
                        val previousThinking = parts.lastOrNull() as? Part.Thinking
                        message.traceNote?.takeIf { it.isNotBlank() }?.let { parts += Part.Trace(it) }
                        message.reasoning?.takeIf { it.isNotBlank() && (source == first || previousThinking?.text != it) }?.let {
                            parts += Part.Thinking(it, message.reasoningTokens, message.elapsedMs, message.runId.orEmpty())
                        }
                        if (message.text.isNotBlank()) parts += Part.Text(message.text)
                        if (source != first) rounds += message
                    }
                    2 -> {
                        val calls = tools ?: ArrayList<ToolLine>().also { tools = it }
                        calls += ToolLine.of(message)
                    }
                    3 -> {
                        finishTools()
                        parts += Part.Steer(message)
                    }
                }
            }
            finishTools()
            ChatRow(
                messages[owner], needsTimeLabel(messages.getOrNull(previous), messages[first]),
                parts.toList(), branch, rounds.toList(),
            )
        }
    }
}
