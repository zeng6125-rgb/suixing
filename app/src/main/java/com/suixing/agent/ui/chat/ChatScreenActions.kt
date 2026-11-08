package com.suixing.agent.ui.chat

import com.suixing.agent.core.Core

/** Screen boundary: children receive immutable streams and bound events, not a ViewModel. */
internal class ChatScreenActions(viewModel: ChatViewModel) {
    val state = viewModel.state
    val screenState = viewModel.screenState
    val liveContent = viewModel.liveContent
    val currentLiveContent = viewModel::currentLiveContent
    val liveStats = viewModel.liveStats
    val liveActive = viewModel.liveActive
    val conversations = viewModel.conversations
    val sessionStats = viewModel.sessionStats
    val drafts = viewModel.drafts
    val goal = viewModel.goal
    val draftFor = viewModel::draftFor
    val addAttachment = viewModel::addAttachment
    val removeAttachmentAt = viewModel::removeAttachmentAt
    val attachmentsFor = viewModel::attachmentsFor
    val onDraftChange = viewModel::onDraftChange
    val bindViewport = viewModel::bindViewport
    val unbindViewport = viewModel::unbindViewport
    val presentViewport = viewModel::presentViewport
    val clearGoal = viewModel::clearGoal
    val resumeGoal = viewModel::resumeGoal
    val newConversation = viewModel::newConversation
    val chooseModel = viewModel::chooseModel
    val chooseReasoning = viewModel::chooseReasoning
    val chooseApproval = viewModel::chooseApproval
    val prefetchWindows = viewModel::prefetchWindows
    val useAssistant = viewModel::useAssistant
    val setDefaultAssistant = viewModel::setDefaultAssistant
    val saveAssistant = viewModel::saveAssistant
    val deleteAssistant = viewModel::deleteAssistant
    val regenerate = viewModel::regenerate
    val switchBranch = viewModel::switchBranch
    val editMessage = viewModel::editMessage
    val editAndResend = viewModel::editAndResend
    val stop = viewModel::stop
    val promoteSteer = viewModel::promoteSteer
    val retractSteer = viewModel::retractSteer
    val retractTurn = viewModel::retractTurn
    val promoteTurn = viewModel::promoteTurn
    val requestCompress = viewModel::requestCompress
    val compressNow = viewModel::compressNow
    val refreshContextOverhead = viewModel::refreshContextOverhead
    private val loadEarlierPage = viewModel::loadEarlier
    private val sendTurn = viewModel::send
    private val steerRun = viewModel::steer

    fun loadEarlier(turns: Int = Core.chats.openTurns(), onComplete: (Boolean) -> Unit = {}): Boolean =
        loadEarlierPage(turns, onComplete)
    fun send(text: String, imagesBase64: List<String> = emptyList()): Boolean =
        sendTurn(text, imagesBase64)
    fun steer(text: String, imagesBase64: List<String> = emptyList()): Boolean =
        steerRun(text, imagesBase64)
}
