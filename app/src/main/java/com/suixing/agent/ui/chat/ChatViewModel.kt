package com.suixing.agent.ui.chat

import androidx.lifecycle.ViewModel
import androidx.compose.runtime.Stable
import androidx.lifecycle.viewModelScope
import com.suixing.agent.core.Core
import com.suixing.agent.data.ApprovalMode
import com.suixing.agent.data.Assistant
import com.suixing.agent.data.Conversation

/** Lifecycle facade: focused runtime modules own conversation, input, run and persistence work. */
@Stable
class ChatViewModel : ViewModel() {
    private val runtime = ChatRuntime(viewModelScope, CoreChatRuntimeEnvironment)

    val state = runtime.state
    internal val screenState = runtime.screenState
    internal val sessionState = runtime.sessionState
    internal val transcriptState = runtime.transcriptState
    internal val runState = runtime.runState
    internal val inputState = runtime.inputState
    internal val liveContent = runtime.liveContent
    val liveStats = runtime.liveStats
    val liveActive = runtime.liveActive
    val conversations = runtime.conversations
    internal val presentedConversation = runtime.presentedConversation
    val sessionStats = runtime.sessionStats
    internal val drafts = runtime.drafts
    internal val goal = runtime.goal

    init { runtime.observe() }

    internal fun currentLiveContent(): LiveContent = runtime.currentLiveContent()

    internal fun bindViewport(chatId: String, capture: () -> Unit) = runtime.bindViewport(chatId, capture)

    internal fun unbindViewport(capture: () -> Unit) = runtime.unbindViewport(capture)

    internal fun presentViewport(chatId: String) = runtime.presentViewport(chatId)

    internal fun draftFor(chatId: String): Draft = runtime.draftFor(chatId)

    fun addAttachment(chatId: String, attachment: Attachment) = runtime.addAttachment(chatId, attachment)

    fun removeAttachmentAt(chatId: String, index: Int) = runtime.removeAttachmentAt(chatId, index)

    fun cacheAttachment(chatId: String, attachment: Attachment?) = runtime.cacheAttachment(chatId, attachment)

    fun attachmentFor(chatId: String): Attachment? = runtime.attachmentFor(chatId)

    fun attachmentsFor(chatId: String): List<Attachment> = runtime.attachmentsFor(chatId)

    fun onDraftChange(chatId: String, draft: Draft) = runtime.onDraftChange(chatId, draft)

    fun referenceFile(path: String): Boolean = runtime.referenceFile(path)

    fun clearDraft(chatId: String) = runtime.clearDraft(chatId)

    fun resumeGoal() = runtime.resumeGoal()

    fun clearGoal() = runtime.clearGoal()

    fun loadEarlier(turns: Int = Core.chats.openTurns(), onComplete: (Boolean) -> Unit = {}): Boolean = runtime.loadEarlier(turns, onComplete)

    fun newConversation() = runtime.newConversation()

    fun openConversation(conversation: Conversation) = runtime.openConversation(conversation)

    fun chooseModel(providerId: String, model: String) = runtime.chooseModel(providerId, model)

    fun chooseReasoning(level: String) = runtime.chooseReasoning(level)

    fun chooseApproval(mode: ApprovalMode) = runtime.chooseApproval(mode)

    fun prefetchWindows() = runtime.prefetchWindows()

    fun renameConversation(id: String, title: String) = runtime.renameConversation(id, title)

    fun deleteConversation(conversation: Conversation) = runtime.deleteConversation(conversation)

    fun useAssistant(assistant: Assistant) = runtime.useAssistant(assistant)

    fun setDefaultAssistant(assistant: Assistant) = runtime.setDefaultAssistant(assistant)

    fun saveAssistant(assistant: Assistant) = runtime.saveAssistant(assistant)

    fun deleteAssistant(assistant: Assistant) = runtime.deleteAssistant(assistant)

    fun send(text: String, imagesBase64: List<String> = emptyList()): Boolean = runtime.send(text, imagesBase64)

    fun regenerate() = runtime.regenerate()

    fun switchBranch(turnId: String, runId: String) = runtime.switchBranch(turnId, runId)

    fun editMessage(id: String, text: String) = runtime.editMessage(id, text)

    fun editAndResend(id: String, text: String) = runtime.editAndResend(id, text)

    fun stop() = runtime.stop()

    fun steer(text: String, imagesBase64: List<String> = emptyList()): Boolean = runtime.steer(text, imagesBase64)

    fun promoteSteer(id: String) = runtime.promoteSteer(id)

    fun retractSteer(id: String) = runtime.retractSteer(id)

    fun retractTurn(chatId: String, id: String) = runtime.retractTurn(chatId, id)

    fun promoteTurn(chatId: String, id: String) = runtime.promoteTurn(chatId, id)

    fun requestCompress(): Boolean = runtime.requestCompress()

    fun compressNow() = runtime.compressNow()

    fun refreshContextOverhead() = runtime.refreshContextOverhead()

    fun dismissError() = runtime.dismissError()

    override fun onCleared() {
        runtime.dispose()
        super.onCleared()
    }
}
