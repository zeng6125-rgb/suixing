package com.suixing.agent.ui.chat

import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.choiceFor
import com.suixing.agent.data.StoredMessage

/**
 * Per-conversation guard for the window between "the user pressed send" and "the run is
 * registered" (issue #76 F-06).
 *
 * A long conversation has to read its full wire from disk before the engine can start, and
 * during that read `AgentEngine.isRunning` is still false. Without this gate a second tap
 * starts a second preparation chain from a different window snapshot. It is per chat: a
 * preparation in A must not block sending in B.
 *
 * UI thread only, like the ViewModel's other small maps: every caller is a Compose callback
 * or a coroutine resumed on the main dispatcher.
 */
internal class SendPreparationGate {
    private val preparing = mutableSetOf<String>()

    /** True when this call took the slot; a second call for the same chat is refused. */
    fun tryBegin(chatId: String, busy: Boolean): Boolean {
        if (chatId.isEmpty() || busy) return false
        return preparing.add(chatId)
    }

    fun finish(chatId: String) {
        preparing.remove(chatId)
    }

    fun isPreparing(chatId: String): Boolean = chatId in preparing
}

/**
 * The history a send actually hands to the engine.
 *
 * The disk wire is the full conversation, but it may predate the line being sent: the
 * transcript is only written when a run ends, so [user] is usually not in it yet. The
 * current window ([shown]) already contains that line because [ChatViewModel.send] adds it
 * before reading. Without the append the model answers the previous question (issue #30).
 *
 * A wire that already ends with [user] is used as-is, so a save that landed between the
 * keystroke and the read cannot produce the line twice.
 */
internal fun sendHistory(
    wire: List<StoredMessage>?,
    shown: List<StoredMessage>,
    user: StoredMessage,
): List<StoredMessage> {
    val filled = wire.orEmpty()
    return when {
        filled.isEmpty() -> shown
        filled.lastOrNull()?.id == user.id -> filled
        else -> filled + user
    }
}

/**
 * The optimistic half of a send: the line is on screen and owns a run id before the
 * engine has been given it. Keeping this here (rather than inline in `send`) is what makes
 * the rollback below testable.
 */
internal fun ChatUiState.acceptSend(message: StoredMessage, runId: String): ChatUiState = copy(
    messages = messages + message,
    activeRuns = activeRuns + (message.id to runId),
    streaming = "",
    reasoningRevision = 0,
    status = "正在思考中",
    error = null,
)

/**
 * Puts the screen back when a synchronous start fails after the line was accepted.
 *
 * Only the state that still owns this exact run may roll back: a newer run on the same
 * line (or a newer message) must not be erased by a late failure. [before] is the whole
 * snapshot captured before [acceptSend], so status and the active-run map come back
 * together rather than being guessed field by field.
 */
internal fun ChatUiState.rollbackSend(
    before: ChatUiState,
    messageId: String,
    runId: String,
): ChatUiState = if (activeRuns[messageId] == runId) before else this


/**
 * The endpoint a send will actually reach (issue #76 F-04).
 *
 * `send()` used to gate on `settings.baseUrl` (the app-wide default) while `start()` sent to
 * `choiceFor(...).baseUrl` — the conversation's own pick, then the persona's pin, then that
 * default. A LAN-only permission check on the wrong endpoint either blocked a public chat or
 * let a LAN chat run without the permission and time out. Both sides now ask this one chain.
 */
internal fun effectiveBaseUrl(settings: AppSettings, state: ChatUiState): String {
    val assistant = settings.assistant(state.assistantId) ?: settings.activeAssistant()
    return settings.choiceFor(state.providerId, state.model, assistant).baseUrl
}


/**
 * Whether a send should move the reading position to the newest line (issue #76 F-02).
 *
 * A refused send changes nothing, and a slash command is not a new turn even when it is
 * accepted — jumping for it would drag a reader away from where they were. The leading `/`
 * test is enough here because `send()` only treats a valid leading slash as a command; any
 * other text that starts with `/` is refused by the parser and never reaches this as true.
 */
internal fun shouldJumpToLatest(accepted: Boolean, body: String): Boolean =
    accepted && !body.trimStart().startsWith("/")
