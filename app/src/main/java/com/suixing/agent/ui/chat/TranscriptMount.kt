package com.suixing.agent.ui.chat

/** Initial fill belongs to a chat; older rows publish together while existing rows stay mounted. */
internal fun mountedRowCount(
    chatId: String,
    filledChatId: String,
    completedChatId: String,
    loadingEarlier: Boolean,
    preparedRows: Int,
    totalRows: Int,
): Int = when {
    filledChatId != chatId -> 0
    completedChatId == chatId && !loadingEarlier -> totalRows
    else -> preparedRows.coerceIn(0, totalRows)
}
