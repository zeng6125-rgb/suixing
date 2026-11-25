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
    val command = slashCommandToken(text) ?: return linked
    return AnnotatedString.Builder(linked).apply {
        addStyle(SpanStyle(color = highlightColor), command.first, command.last + 1)
    }.toAnnotatedString()
}
