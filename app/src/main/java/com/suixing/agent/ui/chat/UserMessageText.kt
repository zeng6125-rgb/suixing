package com.suixing.agent.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.suixing.agent.ui.chat.markdown.plainTextLinks

/** Adds link and command spans without changing the user's source text. */
internal fun annotatedUserMessage(
    text: String,
    highlightColor: Color,
    onPathClick: ((String) -> Unit)? = null,
): AnnotatedString {
    val linked = plainTextLinks(text, highlightColor, onPathClick)
    val commandEnd = slashTokenEnd(text)?.takeIf { parseSlashCommand(text) != null } ?: return linked
    val commandStart = text.indexOfFirst { !it.isWhitespace() }
    return AnnotatedString.Builder(linked).apply {
        addStyle(SpanStyle(color = highlightColor), commandStart, commandEnd)
    }.toAnnotatedString()
}
