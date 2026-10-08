package com.suixing.agent.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suixing.agent.agent.AgentEvent
import com.suixing.agent.agent.RunState
import com.suixing.agent.agent.StreamStats
import com.suixing.agent.core.Core
import com.suixing.agent.data.Assistant
import com.suixing.agent.data.Conversation
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.data.Transcript
import com.suixing.agent.data.forAssistant
import com.suixing.agent.service.AgentForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class ChatUiState(
    val conversationId: String = "",
    val title: String = "新会话",
    val messages: List<StoredMessage> = emptyList(),
    /** Which run of each turn is on screen; see [Transcript]. */
    val activeRuns: Map<String, String> = emptyMap(),
    /** The persona this conversation speaks as. */
    val assistantId: String = "",
    val streaming: String = "",
    /** The thinking streamed ahead of [streaming], shown in its own block. */
    val reasoning: String = "",
    /** A run is in flight somewhere in the app — not necessarily in this conversation. */
    val running: Boolean = false,
    /** Which conversation owns that run; empty when nothing is running. */
    val runningChatId: String = "",
    /** Its title, so a refusal can name the conversation holding the one run slot. */
    val runningTitle: String = "",
    /** Why the last action was refused, or how a run in another conversation ended. */
    val notice: String? = null,
    val status: String = "",
    /** The tool the run is inside right now; the tool card names it live. */
    val runningTool: String? = null,
    /**
     * Speed of the run that is streaming, straight off the engine's `RunState`. Only the
     * live bubble reads it; once the reply is stored, its own usage numbers are the
     * frozen record and this is ignored.
     */
    val stats: StreamStats? = null,
    val error: String? = null,
) {
    /** The transcript as drawn: one run per turn, newest first pick wins. */
    val visible: List<StoredMessage> get() = Transcript.active(messages, activeRuns)

    /** The live run is writing into the conversation on screen, not some other one. */
    val runningHere: Boolean get() = running && runningChatId == conversationId
}

/**
 * The conversation a run is writing into.
 *
 * Kept apart from [ChatUiState] because those answer two different questions: the screen
 * shows whichever conversation the user last opened, while the run keeps streaming into
 * the one that started it. Fusing the two is what used to lock the drawer for the whole
 * length of a run — the run's own conversation was the only one the UI could hold.
 */
private data class RunSession(
    val chatId: String,
    val title: String,
    val assistantId: String,
    val runId: String,
    val messages: List<StoredMessage>,
    val activeRuns: Map<String, String>,
    val streaming: String = "",
    val reasoning: String = "",
    val status: String = "",
    val runningTool: String? = null,
    val error: String? = null,
)

class ChatViewModel : ViewModel() {

    // The process-wide instance, not a private one: the floating console has to be
    // able to stop and steer the very run this screen started.
    private val engine = Core.engine
    private val gate = Core.gate

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    val conversations: StateFlow<List<Conversation>> = Core.chats.conversations

    private var job: Job? = null

    /** The run in flight and the conversation it belongs to; null when nothing runs. */
    private var session: RunSession? = null

    init {
        // The engine is shared with the floating console, so its run state is the one
        // truth about whether a run is live — including one this screen never started.
        viewModelScope.launch {
            Core.engine.runState.collect { run ->
                when (run) {
                    is RunState.Running -> _state.update { it.copy(stats = run.stats) }
                    // Back to typing the moment the run is stopped, without waiting for
                    // the stream to wind down on its own. What already arrived is saved
                    // first: the run is over either way, and only this copy has it.
                    is RunState.Cancelled -> session?.let { live ->
                        viewModelScope.launch { saveSession(live) }
                        endSession("已停止")
                    }

                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            Core.chats.load()
            val settings = Core.settings.current()
            val existing = Core.chats.conversations.value.firstOrNull()
            if (existing == null) {
                val created = Core.chats.create()
                _state.value = ChatUiState(
                    conversationId = created.id,
                    title = created.title,
                    assistantId = settings.activeAssistant()?.id.orEmpty(),
                )
            } else {
                _state.value = existing.toUiState(settings.activeAssistant()?.id.orEmpty())
            }
        }
    }

    fun newConversation() {
        viewModelScope.launch {
            val settings = Core.settings.current()
            val created = Core.chats.create()
            _state.value = projected(
                ChatUiState(
                    conversationId = created.id,
                    title = created.title,
                    assistantId = settings.activeAssistant()?.id.orEmpty(),
                ),
            )
        }
    }

    fun openConversation(conversation: Conversation) {
        val live = session
        _state.value = if (live != null && live.chatId == conversation.id) {
            // Coming back to the conversation a run owns: pick up what it has streamed
            // since, rather than the older snapshot on disk.
            ChatUiState(
                conversationId = live.chatId,
                title = live.title,
                messages = live.messages,
                activeRuns = live.activeRuns,
                assistantId = live.assistantId,
                streaming = live.streaming,
                reasoning = live.reasoning,
                status = live.status,
                running = true,
                runningChatId = live.chatId,
                runningTitle = live.title,
            )
        } else {
            // A conversation that never picked a persona keeps the one already on screen,
            // which was seeded from the settings when this screen was created.
            projected(conversation.toUiState(_state.value.assistantId))
        }
    }

    fun deleteConversation(conversation: Conversation) {
        // The run owns its conversation until it ends: deleting it would only have the
        // next save write it straight back.
        if (session?.chatId == conversation.id) {
            _state.update { it.copy(notice = runningNotice()) }
            return
        }
        viewModelScope.launch {
            Core.chats.delete(conversation.id)
            if (conversation.id != _state.value.conversationId) return@launch
            val settings = Core.settings.current()
            val next = Core.chats.conversations.value.firstOrNull()
            _state.value = if (next == null) {
                val created = Core.chats.create()
                projected(
                    ChatUiState(
                        conversationId = created.id,
                        title = created.title,
                        assistantId = settings.activeAssistant()?.id.orEmpty(),
                    ),
                )
            } else {
                projected(next.toUiState(settings.activeAssistant()?.id.orEmpty()))
            }
        }
    }

    /** Adds the "who is running" half of the picture to a freshly built conversation. */
    private fun projected(state: ChatUiState): ChatUiState = state.copy(
        running = session != null,
        runningChatId = session?.chatId.orEmpty(),
        runningTitle = session?.title.orEmpty(),
    )

    // ---------------------------------------------------------------- assistants

    /** Makes [assistant] the face of this conversation and the app-wide default. */
    fun useAssistant(assistant: Assistant) {
        editDisplayed { it.copy(assistantId = assistant.id) }
        viewModelScope.launch {
            Core.settings.update { it.copy(activeAssistantId = assistant.id) }
            persistDisplayed()
        }
    }

    fun saveAssistant(assistant: Assistant) {
        viewModelScope.launch {
            Core.settings.update { settings ->
                val exists = settings.assistants.any { it.id == assistant.id }
                val list = if (exists) {
                    settings.assistants.map { if (it.id == assistant.id) assistant else it }
                } else {
                    settings.assistants + assistant
                }
                settings.copy(assistants = list, activeAssistantId = assistant.id)
            }
            editDisplayed { it.copy(assistantId = assistant.id) }
            persistDisplayed()
        }
    }

    fun deleteAssistant(assistant: Assistant) {
        viewModelScope.launch {
            Core.settings.update { settings ->
                // The app always needs one persona to fall back on.
                if (settings.assistants.size <= 1) return@update settings
                val list = settings.assistants.filterNot { it.id == assistant.id }
                settings.copy(
                    assistants = list,
                    activeAssistantId = if (settings.activeAssistantId == assistant.id) {
                        list.first().id
                    } else {
                        settings.activeAssistantId
                    },
                )
            }
            val settings = Core.settings.current()
            val next = settings.assistant(_state.value.assistantId) ?: settings.activeAssistant()
            editDisplayed { it.copy(assistantId = next?.id.orEmpty()) }
            persistDisplayed()
        }
    }

    // -------------------------------------------------------------------- turns

    /** Returns false when the message was dropped, so the caller keeps the input text. */
    fun send(text: String, imageBase64: String? = null): Boolean {
        val body = text.trim()
        if (body.isEmpty() && imageBase64 == null) return false
        if (_state.value.conversationId.isEmpty()) return false
        // One engine, one run: while one is in flight nothing else may start, whichever
        // conversation is on screen. The refusal names the conversation holding the slot.
        if (session != null) {
            _state.update { it.copy(notice = runningNotice()) }
            return false
        }

        val user = StoredMessage(
            id = UUID.randomUUID().toString(),
            role = Roles.USER,
            text = body,
            imageBase64 = imageBase64,
        )
        val fresh = UUID.randomUUID().toString()
        _state.update {
            it.copy(
                messages = it.messages + user,
                activeRuns = it.activeRuns + (user.id to fresh),
                streaming = "",
                reasoning = "",
                status = "正在思考…",
                error = null,
                notice = null,
            )
        }
        start(fresh, _state.value.messages)
        return true
    }

    /** Answers the last question again, keeping the old reply as a branch. */
    fun regenerate() {
        if (session != null) {
            _state.update { it.copy(notice = runningNotice()) }
            return
        }
        val snapshot = _state.value
        val active = Transcript.active(snapshot.messages, snapshot.activeRuns)
        val question = active.lastOrNull { it.role == Roles.USER } ?: return
        val prompt = Transcript.withoutLastAnswer(active)
        val fresh = UUID.randomUUID().toString()
        _state.update {
            it.copy(
                activeRuns = it.activeRuns + (question.id to fresh),
                streaming = "",
                reasoning = "",
                status = "正在重新回答…",
                error = null,
            )
        }
        start(fresh, prompt)
    }

    /** Draws another candidate answer for [turnId]; the transcript itself is untouched. */
    fun switchBranch(turnId: String, runId: String) {
        if (_state.value.activeRuns[turnId] == runId) return
        editDisplayed { it.copy(activeRuns = it.activeRuns + (turnId to runId)) }
        viewModelScope.launch { persistDisplayed() }
    }

    /**
     * Rewrites a message in place without asking the model anything. This is how a
     * typo gets fixed, or a long reply gets trimmed down to the part worth keeping.
     */
    fun editMessage(id: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        editDisplayed { snapshot ->
            snapshot.copy(
                messages = snapshot.messages.map { message ->
                    if (message.id == id) message.copy(text = body, edited = true) else message
                },
            )
        }
        viewModelScope.launch { persistDisplayed() }
    }

    /**
     * Rewrites the user message [id] and asks again from there. Everything the model
     * said afterwards is dropped: those answers belong to a question that no longer
     * exists, so keeping them would hang them off the wrong prompt.
     */
    fun editAndResend(id: String, text: String) {
        if (session != null) {
            _state.update { it.copy(notice = runningNotice()) }
            return
        }
        val body = text.trim()
        if (body.isEmpty()) return
        val snapshot = _state.value
        val active = Transcript.active(snapshot.messages, snapshot.activeRuns)
        val index = active.indexOfFirst { it.id == id }
        if (index < 0) return
        val kept = active.take(index) + active[index].copy(text = body, edited = true)
        val fresh = UUID.randomUUID().toString()
        _state.update {
            it.copy(
                messages = kept,
                // Runs whose messages just disappeared would only leak memory.
                activeRuns = it.activeRuns.filterKeys { key -> kept.any { row -> row.id == key } } + (id to fresh),
                streaming = "",
                reasoning = "",
                status = "正在重新回答…",
                error = null,
            )
        }
        start(fresh, kept)
    }

    fun stop() {
        gate.cancelPending()
        Core.speaker.stop()
        // Ask the engine first: it cancels the OkHttp call that is on the wire, which
        // is what makes the stop take effect now rather than at the next token. The
        // job cancel below is only the belt to that pair of braces.
        Core.engine.cancel()
        job?.cancel()
        job = null
    }

    /**
     * Slips a line to the model at its next turn instead of waiting for this run to end.
     * False means nothing was running, so the caller keeps the text.
     */
    /**
     * Queues a line for the model that is running, and writes it into the transcript at
     * the point it lands.
     *
     * The bubble matters: without it the answer reads as though the model volunteered
     * something nobody asked for. It is marked `steer` so the transcript keeps treating
     * it as an interruption rather than as the question the turn is answering.
     */
    fun steer(text: String): Boolean {
        val body = text.trim()
        if (body.isEmpty() || !Core.engine.steer(body)) return false
        val live = session ?: return true
        val line = StoredMessage(
            id = UUID.randomUUID().toString(),
            role = Roles.USER,
            text = body,
            steer = true,
        )
        val next = live.copy(messages = live.messages + line)
        setSession(next)
        // Saved straight away: a steer that only lived in memory would vanish the moment
        // the user switched conversations, which is exactly when they go looking for it.
        viewModelScope.launch { saveSession(next) }
        return true
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun start(runId: String, history: List<StoredMessage>) {
        val shown = _state.value
        setSession(
            RunSession(
                chatId = shown.conversationId,
                title = shown.title,
                assistantId = shown.assistantId,
                runId = runId,
                messages = shown.messages,
                activeRuns = shown.activeRuns,
                status = shown.status,
            ),
        )
        job = viewModelScope.launch {
            AgentForegroundService.start(Core.appContext, "随行正在执行任务")
            // Decided in the catches, applied once in the finally: there is exactly one
            // place that saves and one that closes the run, whichever way it ended.
            var status = ""
            var failure: String? = null
            try {
                persistSession()
                val settings = Core.settings.current()
                val assistant = settings.assistant(shown.assistantId) ?: settings.activeAssistant()
                // The persona bends the live settings; anything it left blank stays as-is.
                val effective = settings.forAssistant(assistant)
                val registry = Core.tools.excluding(assistant?.disabledTools?.toSet().orEmpty())
                val produced = engine.run(
                    history = history,
                    settings = effective,
                    registry = registry,
                    capabilities = Core.capabilities,
                    skills = Core.skills,
                    memory = Core.memory,
                    gate = gate,
                    onEvent = ::handleEvent,
                )
                // Only the run's own last reply is read out, not the whole history.
                if (effective.speakReplies) {
                    produced.lastOrNull { it.role == Roles.ASSISTANT && it.text.isNotBlank() }
                        ?.text
                        ?.let { Core.speaker.speak(it) }
                }
            } catch (cancellation: CancellationException) {
                // RunState.Cancelled usually released the screen first; this is the belt
                // to that pair of braces.
                status = "已停止"
                throw cancellation
            } catch (error: Throwable) {
                failure = error.message ?: error::class.java.simpleName
            } finally {
                AgentForegroundService.stop(Core.appContext)
                withContext(NonCancellable) {
                    // A cancelled coroutine refuses to suspend, so the final save has to
                    // run in a NonCancellable block or a stopped run loses its messages.
                    session?.let { saveSession(it) }
                    endSession(status, failure)
                }
            }
        }
    }

    private fun handleEvent(event: AgentEvent) {
        val live = session ?: return
        val next = when (event) {
            is AgentEvent.TextDelta ->
                live.copy(streaming = live.streaming + event.text, status = "")

            is AgentEvent.ReasoningDelta ->
                live.copy(reasoning = live.reasoning + event.text, status = "")

            is AgentEvent.AssistantMessage -> live.copy(
                messages = live.messages + event.message.copy(runId = live.runId),
                streaming = "",
                reasoning = "",
            )

            is AgentEvent.ToolStarted -> live.copy(
                status = "调用 ${event.name}",
                runningTool = event.name,
            )

            is AgentEvent.ToolMessage -> live.copy(
                messages = live.messages + event.message.copy(runId = live.runId),
                status = "",
                runningTool = null,
            )

            is AgentEvent.ToolFinished -> live.copy(runningTool = null)

            // The error rides with the run: it shows on the error card only while that
            // conversation is the one on screen, and travels with it otherwise.
            is AgentEvent.Failed -> live.copy(error = event.message)

            is AgentEvent.Finished -> live
        }
        setSession(next)
    }

    /**
     * Installs [next] as the run's copy of its conversation and, when that conversation is
     * the one on screen, copies the live parts of it onto the screen. A run in some other
     * conversation changes nothing here except the "who is running" line.
     */
    private fun setSession(next: RunSession?) {
        session = next
        _state.update { snapshot ->
            when {
                next == null ->
                    snapshot.copy(running = false, runningChatId = "", runningTitle = "")

                snapshot.conversationId != next.chatId -> snapshot.copy(
                    running = true,
                    runningChatId = next.chatId,
                    runningTitle = next.title,
                )

                else -> snapshot.copy(
                    running = true,
                    runningChatId = next.chatId,
                    runningTitle = next.title,
                    title = next.title,
                    messages = next.messages,
                    activeRuns = next.activeRuns,
                    streaming = next.streaming,
                    reasoning = next.reasoning,
                    status = next.status,
                    runningTool = next.runningTool,
                    error = next.error ?: snapshot.error,
                )
            }
        }
    }

    /**
     * Drops the run's claim on its conversation. The screen keeps whatever the user was
     * looking at: the run's own conversation loses its streaming state, any other one is
     * left exactly as it was.
     */
    private fun endSession(status: String, error: String? = null) {
        val live = session
        session = null
        _state.update { snapshot ->
            val failure = error ?: live?.error
            when {
                live != null && snapshot.conversationId == live.chatId -> snapshot.copy(
                    running = false,
                    runningChatId = "",
                    runningTitle = "",
                    streaming = "",
                    reasoning = "",
                    stats = null,
                    status = status,
                    runningTool = null,
                    notice = null,
                    error = failure ?: snapshot.error,
                )

                // A run in another conversation died: there is no error card to put it
                // on, so say it in the notice line rather than let it vanish.
                live != null && failure != null -> snapshot.copy(
                    running = false,
                    runningChatId = "",
                    runningTitle = "",
                    stats = null,
                    notice = "『${live.title}』运行失败：$failure",
                )

                else -> snapshot.copy(
                    running = false,
                    runningChatId = "",
                    runningTitle = "",
                    stats = null,
                )
            }
        }
    }

    /**
     * Applies [change] to the conversation on screen. While that conversation is the one
     * a run owns, the change goes into the run's copy as well — otherwise the next
     * streamed token would mirror the older messages back over the edit.
     */
    private fun editDisplayed(change: (ChatUiState) -> ChatUiState) {
        _state.update(change)
        val live = session ?: return
        val shown = _state.value
        if (live.chatId != shown.conversationId) return
        setSession(
            live.copy(
                title = shown.title,
                assistantId = shown.assistantId,
                messages = shown.messages,
                activeRuns = shown.activeRuns,
            ),
        )
    }

    /** Names the conversation holding the single run slot, so the refusal is actionable. */
    private fun runningNotice(): String {
        val live = session ?: return "正在运行，先停止或切回"
        return "『${live.title}』正在运行，先停止或切回"
    }

    /** Saves the conversation on screen. */
    private suspend fun persistDisplayed() {
        val shown = _state.value
        val title = save(
            chatId = shown.conversationId,
            title = shown.title,
            messages = shown.messages,
            assistantId = shown.assistantId,
            activeRuns = shown.activeRuns,
        ) ?: return
        if (title != shown.title) _state.update { it.copy(title = title) }
    }

    /** Saves the run's conversation — the only writer while a run is in flight. */
    private suspend fun persistSession() {
        session?.let { saveSession(it) }
    }

    private suspend fun saveSession(live: RunSession) {
        val title = save(
            chatId = live.chatId,
            title = live.title,
            messages = live.messages,
            assistantId = live.assistantId,
            activeRuns = live.activeRuns,
        ) ?: return
        val current = session
        if (current != null && current.chatId == live.chatId && title != current.title) {
            setSession(current.copy(title = title))
        }
    }

    /** Writes one conversation to disk; returns the title it ended up with. */
    private suspend fun save(
        chatId: String,
        title: String,
        messages: List<StoredMessage>,
        assistantId: String,
        activeRuns: Map<String, String>,
    ): String? {
        if (chatId.isEmpty()) return null
        val previous = Core.chats.conversations.value.firstOrNull { it.id == chatId }
        val derived = if (title.isBlank() || title == "新会话") {
            messages.firstOrNull { it.role == Roles.USER }
                ?.text
                ?.replace('\n', ' ')
                ?.take(18)
                ?.takeIf { it.isNotBlank() }
                ?: title.ifBlank { "新会话" }
        } else {
            title
        }
        Core.chats.save(
            Conversation(
                id = chatId,
                title = derived,
                createdAt = previous?.createdAt ?: System.currentTimeMillis(),
                messages = messages,
                assistantId = assistantId,
                activeRuns = activeRuns,
            ),
        )
        return derived
    }

    override fun onCleared() {
        gate.cancelPending()
        job?.cancel()
        super.onCleared()
    }
}

private fun Conversation.toUiState(fallbackAssistantId: String): ChatUiState = ChatUiState(
    conversationId = id,
    title = title,
    messages = messages,
    activeRuns = activeRuns,
    assistantId = assistantId.ifBlank { fallbackAssistantId },
)
