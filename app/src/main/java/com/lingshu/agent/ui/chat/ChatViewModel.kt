package com.lingshu.agent.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingshu.agent.agent.AgentEngine
import com.lingshu.agent.agent.AgentEvent
import com.lingshu.agent.agent.ApprovalGate
import com.lingshu.agent.core.Core
import com.lingshu.agent.data.Conversation
import com.lingshu.agent.data.Roles
import com.lingshu.agent.data.StoredMessage
import com.lingshu.agent.service.AgentForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatUiState(
    val conversationId: String = "",
    val title: String = "新会话",
    val messages: List<StoredMessage> = emptyList(),
    val streaming: String = "",
    val running: Boolean = false,
    val status: String = "",
    val error: String? = null,
    val pending: ApprovalGate.Request? = null,
)

class ChatViewModel : ViewModel() {

    private val engine = AgentEngine()
    private val gate = ApprovalGate()

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    val conversations: StateFlow<List<Conversation>> = Core.chats.conversations

    private var job: Job? = null

    init {
        viewModelScope.launch {
            Core.chats.load()
            val existing = Core.chats.conversations.value.firstOrNull()
            if (existing == null) {
                val created = Core.chats.create()
                _state.value = ChatUiState(conversationId = created.id, title = created.title)
            } else {
                _state.value = ChatUiState(
                    conversationId = existing.id,
                    title = existing.title,
                    messages = existing.messages,
                )
            }
        }
        viewModelScope.launch {
            gate.pending.collect { request -> _state.update { it.copy(pending = request) } }
        }
    }

    fun newConversation() {
        if (_state.value.running) return
        viewModelScope.launch {
            val created = Core.chats.create()
            _state.value = ChatUiState(conversationId = created.id, title = created.title)
        }
    }

    fun openConversation(conversation: Conversation) {
        if (_state.value.running) return
        _state.value = ChatUiState(
            conversationId = conversation.id,
            title = conversation.title,
            messages = conversation.messages,
        )
    }

    fun deleteConversation(conversation: Conversation) {
        if (_state.value.running) return
        viewModelScope.launch {
            Core.chats.delete(conversation.id)
            if (conversation.id == _state.value.conversationId) {
                val next = Core.chats.conversations.value.firstOrNull()
                if (next == null) {
                    val created = Core.chats.create()
                    _state.value = ChatUiState(conversationId = created.id, title = created.title)
                } else {
                    _state.value = ChatUiState(
                        conversationId = next.id,
                        title = next.title,
                        messages = next.messages,
                    )
                }
            }
        }
    }

    fun send(text: String, imageBase64: String? = null) {
        val body = text.trim()
        if (body.isEmpty() && imageBase64 == null) return
        if (_state.value.running) return
        if (_state.value.conversationId.isEmpty()) return

        job = viewModelScope.launch {
            val user = StoredMessage(
                id = UUID.randomUUID().toString(),
                role = Roles.USER,
                text = body,
                imageBase64 = imageBase64,
            )
            _state.update {
                it.copy(
                    messages = it.messages + user,
                    running = true,
                    streaming = "",
                    status = "正在思考…",
                    error = null,
                )
            }
            persist()
            AgentForegroundService.start(Core.appContext, "灵枢正在执行任务")
            try {
                val settings = Core.settings.current()
                engine.run(
                    history = _state.value.messages,
                    settings = settings,
                    registry = Core.tools,
                    capabilities = Core.capabilities,
                    gate = gate,
                    onEvent = ::handleEvent,
                )
                _state.update { it.copy(running = false, streaming = "", status = "") }
            } catch (cancellation: CancellationException) {
                _state.update { it.copy(running = false, streaming = "", status = "已停止") }
                throw cancellation
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        running = false,
                        streaming = "",
                        status = "",
                        error = error.message ?: error::class.java.simpleName,
                    )
                }
            } finally {
                AgentForegroundService.stop(Core.appContext)
                persist()
            }
        }
    }

    fun stop() {
        gate.cancelPending()
        job?.cancel()
        job = null
    }

    fun approve(always: Boolean = false) {
        gate.resolve(true, always)
    }

    fun reject() {
        gate.resolve(false)
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun handleEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.TextDelta ->
                _state.update { it.copy(streaming = it.streaming + event.text, status = "") }

            is AgentEvent.AssistantMessage ->
                _state.update { it.copy(messages = it.messages + event.message, streaming = "") }

            is AgentEvent.ToolStarted ->
                _state.update { it.copy(status = "调用 ${event.name}") }

            is AgentEvent.ToolMessage ->
                _state.update { it.copy(messages = it.messages + event.message, status = "") }

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
