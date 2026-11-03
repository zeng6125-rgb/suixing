package com.suixing.agent.ui.chat

/**
 * `/goal` is a user configuration command, so it stays available even when a persona
 * disables the model-facing `goal` tool. Every other slash command obeys the same
 * `disabledTools` set as that conversation's normal agent run.
 */
internal fun slashToolAllowed(name: String, disabledTools: Set<String>): Boolean =
    name == "goal" || name !in disabledTools

/**
 * A command may finish while the approval dialog is open. Only clear the field if it still
 * holds exactly the submitted command (and no attachment was added meanwhile); otherwise
 * the user's newer input survives.
 */
internal fun submittedDraftStillMatches(
    currentText: String,
    hasAttachment: Boolean,
    submittedText: String,
): Boolean = !hasAttachment && currentText.trim() == submittedText.trim()

/**
 * End offset (exclusive) of the leading `/name` token, or null when the field does not
 * start one. Used to tint the command while the rest of the line stays normal text.
 */
internal fun slashTokenEnd(text: String): Int? {
    val start = text.indexOfFirst { !it.isWhitespace() }
    if (start < 0 || text[start] != '/') return null
    var end = start + 1
    while (end < text.length && !text[end].isWhitespace()) end++
    return if (end <= start + 1) null else end
}

/** True while the user is still naming the command (no argument typed yet). */
internal fun slashQuery(text: String): String? {
    val trimmed = text.trimStart()
    if (!trimmed.startsWith("/")) return null
    val token = trimmed.drop(1)
    if (token.any { it.isWhitespace() }) return null
    return token
}

/**
 * Put `/goal` ahead of the tool groups. The registry is ordered by package, and the card
 * only shows five rows: without this, `goal` sits behind the ten system tools and a bare
 * `/` never offers the one command that is a user setting rather than a model call.
 */
internal fun slashCommands(commands: List<Pair<String, String>>): List<Pair<String, String>> {
    val goal = commands.firstOrNull { it.first == "goal" } ?: return commands
    return listOf(goal) + commands.filterNot { it.first == "goal" }
}

/**
 * Commands worth offering for what has been typed. `/goal` is included even when a persona
 * disables the `goal` tool: it is a user setting, not a model call.
 */
internal fun slashSuggestions(
    commands: List<Pair<String, String>>,
    text: String,
    limit: Int = 5,
): List<Pair<String, String>> {
    val query = slashQuery(text) ?: return emptyList()
    return commands
        .filter { it.first.startsWith(query, ignoreCase = true) }
        .sortedBy { if (it.first.equals(query, ignoreCase = true)) 0 else 1 }
        .take(limit)
}