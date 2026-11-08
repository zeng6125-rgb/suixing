package com.suixing.agent.ui.chat



internal fun ChatRuntime.bindViewport(chatId: String, capture: () -> Unit) {
    viewportOwner = chatId
    viewportCapture = capture
}


internal fun ChatRuntime.unbindViewport(capture: () -> Unit) {
    if (viewportCapture === capture) {
        viewportCapture = null
        viewportOwner = ""
        _presentedConversation.value = null
    }
}


internal fun ChatRuntime.presentViewport(chatId: String) {
    if (chatId == _state.value.conversationId) _presentedConversation.value = chatId
}


internal fun ChatRuntime.leaveViewport() {
    if (viewportOwner == _state.value.conversationId) viewportCapture?.invoke()
    _presentedConversation.value = null
}
