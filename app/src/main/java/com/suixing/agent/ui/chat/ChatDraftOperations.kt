package com.suixing.agent.ui.chat

import kotlinx.coroutines.flow.update

internal fun ChatRuntime.draftFor(chatId: String): Draft = _drafts.value[chatId] ?: Draft()


/** 追加一张图/一个文件到这条会话的附件列（多附件：不再覆盖上一张）。 */
internal fun ChatRuntime.addAttachment(chatId: String, attachment: Attachment) {
    attachmentCache.compute(chatId) { _, current -> current.orEmpty() + attachment }
}


/** 删掉附件列里第 [index] 个；删空即移除整条缓存。 */
internal fun ChatRuntime.removeAttachmentAt(chatId: String, index: Int) {
    attachmentCache.computeIfPresent(chatId) { _, current ->
        if (index !in current.indices) current
        else current.filterIndexed { at, _ -> at != index }.takeIf { it.isNotEmpty() }
    }
}


internal fun ChatRuntime.cacheAttachment(chatId: String, attachment: Attachment?) {
    if (attachment == null) attachmentCache.remove(chatId)
    else attachmentCache[chatId] = listOf(attachment)
}


internal fun ChatRuntime.attachmentFor(chatId: String): Attachment? = attachmentCache[chatId]?.firstOrNull()


/** 这条会话当前的全部附件（顺序＝添加顺序＝发送顺序）。 */
internal fun ChatRuntime.attachmentsFor(chatId: String): List<Attachment> = attachmentCache[chatId] ?: emptyList()


/** Writes [draft] as the conversation's unsent text; the display follows at once. */
internal fun ChatRuntime.onDraftChange(chatId: String, draft: Draft) {
    _drafts.update { it + (chatId to draft) }
}


/** File-browser references append to this conversation's draft without replacing attachments. */
internal fun ChatRuntime.referenceFile(path: String): Boolean {
    val chatId = _state.value.conversationId
    if (chatId.isBlank()) return false
    _drafts.update { drafts ->
        val draft = drafts[chatId] ?: Draft()
        drafts + (chatId to draft.withFileReference(path))
    }
    return true
}


/**
 * 只把**正文**放回输入框，保留这个会话已有的附件等字段。
 *
 * 为什么不能直接 `onDraftChange(chatId, Draft(text = …))`（评审 9 第 2 条）：
 * 那是**全量覆盖**，会把 `imageNames` 清空；而附件 chip 读的是另一个缓存
 * （`attachmentCache`），于是"chip 还在、draft.imageNames 为空" ⇒ 用户点 ✕ 时
 * `removeAt(index)` 撞 `IndexOutOfBoundsException`（主线程崩）。
 */
internal fun ChatRuntime.backfillDraftText(chatId: String, text: String) {
    _drafts.update { drafts ->
        drafts + (chatId to (drafts[chatId] ?: Draft()).copy(text = text))
    }
}


/** Sending empties the conversation's draft; nothing else touches it. */
internal fun ChatRuntime.clearDraft(chatId: String) {
    _drafts.update { it - chatId }
    attachmentCache.remove(chatId)
    // And straight onto the screen when that chat is the one being shown. The collector above
    // would do it a hop later, and a field that keeps its text for even one frame after the user
    // pressed send reads as "it did not send" — which is how the queue was reported twice.
    if (_state.value.conversationId == chatId && _state.value.draft != Draft()) {
        _state.update { it.copy(draft = Draft()) }
    }
}
