package com.suixing.agent.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suixing.agent.agent.AgentEngine
import com.suixing.agent.agent.AgentEvent
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
    val running: Boolean = false,
    val status: String = "",
    val error: String? = null,
) {
    /** The transcript as drawn: one run per turn, newest first pick wins. */
    val visible: List<StoredMessage> get() = Transcript.active(messages, activeRuns)
}

class ChatViewModel : ViewModel() {

    private val engine = AgentEngine()
    private val gate = Core.gate

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    val conversations: StateFlow<List<Conversation>> = Core.chats.conversations

    private var job: Job? = null

    /** The run the in-flight engine pass writes into; stamped on every message it emits. */
    private var runId: String = ""

    init {
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
        if (_state.value.running) return
        viewModelScope.launch {
            val settings = Core.settings.current()
            val created = Core.chats.create()
            _state.value = ChatUiState(
                conversationId = created.id,
                title = created.title,
                assistantId = settings.activeAssistant()?.id.orEmpty(),
            )
        }
    }

    fun openConversation(conversation: Conversation) {
        if (_state.value.running) return
        // A conversation that never picked a persona keeps the one already on screen,
        // which was seeded from the settings when this screen was created.
        _state.value = conversation.toUiState(_state.value.assistantId)
    }

    fun deleteConversation(conversation: Conversation) {
        if (_state.value.running) return
        viewModelScope.launch {
            Core.chats.delete(conversation.id)
            if (conversation.id != _state.value.conversationId) return@launch
            val settings = Core.settings.current()
            val next = Core.chats.conversations.value.firstOrNull()
            _state.value = if (next == null) {
                val created = Core.chats.create()
                ChatUiState(
                    conversationId = created.id,
                    title = created.title,
                    assistantId = settings.activeAssistant()?.id.orEmpty(),
                )
            } else {
                next.toUiState(settings.activeAssistant()?.id.orEmpty())
            }
        }
    }

    // ---------------------------------------------------------------- assistants

    /** Makes [assistant] the face of this conversation and the app-wide default. */
    fun useAssistant(assistant: Assistant) {
        if (_state.value.running) return
        _state.update { it.copy(assistantId = assistant.id) }
        viewModelScope.launch {
            Core.settings.update { it.copy(activeAssistantId = assistant.id) }
            persist()
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
            _state.update { it.copy(assistantId = assistant.id) }
            persist()
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
            _state.update { it.copy(assistantId = next?.id.orEmpty()) }
            persist()
        }
    }

    // -------------------------------------------------------------------- turns

    /** Returns false when the message was dropped, so the caller keeps the input text. */
    fun send(text: String, imageBase64: String? = null): Boolean {
        val body = text.trim()
        if (body.isEmpty() && imageBase64 == null) return false
        if (_state.value.running) return false
        if (_state.value.conversationId.isEmpty()) return false

        val user = StoredMessage(
            id = UUID.randomUUID().toString(),
            role = Roles.USER,
            text = body,
            imageBase64 = imageBase64,
        )
        val fresh = UUID.randomUUID().toString()
        runId = fresh
        _state.update {
            it.copy(
                messages = it.messages + user,
                activeRuns = it.activeRuns + (user.id to fresh),
                running = true,
                streaming = "",
                reasoning = "",
                status = "正在思考…",
                error = null,
            )
        }
        start(_state.value.messages)
        return true
    }

    /** Answers the last question again, keeping the old reply as a branch. */
    fun regenerate() {
        if (_state.value.running) return
        val snapshot = _state.value
        val active = Transcript.active(snapshot.messages, snapshot.activeRuns)
        val question = active.lastOrNull { it.role == Roles.USER } ?: return
        val prompt = Transcript.withoutLastAnswer(active)
        val fresh = UUID.randomUUID().toString()
        runId = fresh
        _state.update {
            it.copy(
                activeRuns = it.activeRuns + (question.id to fresh),
                running = true,
                streaming = "",
                reasoning = "",
                status = "正在重新回答…",
                error = null,
            )
        }
        start(prompt)
    }

    /** Draws another candidate answer for [turnId]; the transcript itself is untouched. */
    fun switchBranch(turnId: String, runId: String) {
        if (_state.value.running) return
        if (_state.value.activeRuns[turnId] == runId) return
        _state.update { it.copy(activeRuns = it.activeRuns + (turnId to runId)) }
        viewModelScope.launch { persist() }
    }

    /**
     * Rewrites a message in place without asking the model anything. This is how a
     * typo gets fixed, or a long reply gets trimmed down to the part worth keeping.
     */
    fun editMessage(id: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        _state.update { snapshot ->
            snapshot.copy(
                messages = snapshot.messages.map { message ->
                    if (message.id == id) message.copy(text = body, edited = true) else message
                },
            )
        }
        viewModelScope.launch { persist() }
    }

    /**
     * Rewrites the user message [id] and asks again from there. Everything the model
     * said afterwards is dropped: those answers belong to a question that no longer
     * exists, so keeping them would hang them off the wrong prompt.
     */
    fun editAndResend(id: String, text: String) {
        if (_state.value.running) return
        val body = text.trim()
        if (body.isEmpty()) return
        val snapshot = _state.value
        val active = Transcript.active(snapshot.messages, snapshot.activeRuns)
        val index = active.indexOfFirst { it.id == id }
        if (index < 0) return
        val kept = active.take(index) + active[index].copy(text = body, edited = true)
        val fresh = UUID.randomUUID().toString()
        runId = fresh
        _state.update {
            it.copy(
                messages = kept,
                // Runs whose messages just disappeared would only leak memory.
                activeRuns = it.activeRuns.filterKeys { key -> kept.any { row -> row.id == key } } + (id to fresh),
                running = true,
                streaming = "",
                reasoning = "",
                status = "正在重新回答…",
                error = null,
            )
        }
        start(kept)
    }

    fun stop() {
        gate.cancelPending()
        Core.speaker.stop()
        job?.cancel()
        job = null
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun start(history: List<StoredMessage>) {
        job = viewModelScope.launch {
            AgentForegroundService.start(Core.appContext, "随行正在执行任务")
            try {
                persist()
                val settings = Core.settings.current()
                val assistant = settings.assistant(_state.value.assistantId) ?: settings.activeAssistant()
                // The persona bends the live settings; anything it left blank stays as-is.
                val effective = settings.forAssistant(assistant)
                val registry = Core.tools.excluding(assistant?.disabledTools?.toSet().orEmpty())
                val produced = engine.run(
                    history = history,
                    settings = effective,
                    registry = registry,
                    capabilities = Core.capabilities,
                    skills = Core.skills,
                    gate = gate,
                    onEvent = ::handleEvent,
                )
                // Only the run's own last reply is read out, not the whole history.
                if (effective.speakReplies) {
                    produced.lastOrNull { it.role == Roles.ASSISTANT && it.text.isNotBlank() }
                        ?.text
                        ?.let { Core.speaker.speak(it) }
                }
                _state.update { it.copy(running = false, streaming = "", reasoning = "", status = "") }
            } catch (cancellation: CancellationException) {
                _state.update { it.copy(running = false, streaming = "", reasoning = "", status = "已停止") }
                throw cancellation
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        running = false,
                        streaming = "",
                        reasoning = "",
                        status = "",
                        error = error.message ?: error::class.java.simpleName,
                    )
                }
            } finally {
                AgentForegroundService.stop(Core.appContext)
                // A cancelled coroutine refuses to suspend, so the final save has to
                // run in a NonCancellable block or a stopped run loses its messages.
                withContext(NonCancellable) { persist() }
            }
        }
    }

    private fun handleEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.TextDelta ->
                _state.update { it.copy(streaming = it.streaming + event.text, status = "") }

            is AgentEvent.ReasoningDelta ->
                _state.update { it.copy(reasoning = it.reasoning + event.text, status = "") }

            is AgentEvent.AssistantMessage ->
                _state.update {
                    it.copy(
                        messages = it.messages + event.message.copy(runId = runId),
                        streaming = "",
                        reasoning = "",
                    )
                }

            is AgentEvent.ToolStarted ->
                _state.update { it.copy(status = "调用 ${event.name}") }

            is AgentEvent.ToolMessage ->
                _state.update {
                    it.copy(messages = it.messages + event.message.copy(runId = runId), status = "")
                }

            is AgentEvent.ToolFinished -> Unit

            is AgentEvent.Failed ->
                _state.update { it.copy(error = event.message) }

            is AgentEvent.Finished -> Unit
        }
    }

    private suspend fun persist() {
        val snapshot = _state.value
        if (snapshot.conversationId.isEmpty()) return
        val previous = Core.chats.conversations.value.firstOrNull { it.id == snapshot.conversationId }
        val title = if (snapshot.title.isBlank() || snapshot.title == "新会话") {
            snapshot.messages.firstOrNull { it.role == Roles.USER }
                ?.text
                ?.replace('\n', ' ')
                ?.take(18)
                ?.takeIf { it.isNotBlank() }
                ?: snapshot.title.ifBlank { "新会话" }
        } else {
            snapshot.title
        }
        Core.chats.save(
            Conversation(
                id = snapshot.conversationId,
                title = title,
                createdAt = previous?.createdAt ?: System.currentTimeMillis(),
                messages = snapshot.messages,
                assistantId = snapshot.assistantId,
                activeRuns = snapshot.activeRuns,
            ),
        )
        if (title != snapshot.title) _state.update { it.copy(title = title) }
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
