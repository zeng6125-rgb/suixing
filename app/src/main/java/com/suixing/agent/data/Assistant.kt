package com.suixing.agent.data

import kotlinx.serialization.Serializable

/**
 * One persona: a name, a face, and the parts of the settings it overrides.
 *
 * Everything left blank inherits the live settings, so a fresh assistant behaves
 * exactly like 随行 itself and only diverges where the user asked it to. That is
 * what keeps the concept additive: the app still has one active model, one
 * approval mode and one set of tools; an assistant merely bends them.
 */
@Serializable
data class Assistant(
    val id: String,
    val name: String = "新助手",
    /** A single emoji; the card and the chat avatar both draw it. */
    val avatar: String = "🪶",
    /**
     * A picture the user picked, as a path relative to the app's own files directory —
     * `assistants/<id>-<stamp>.png`, written by [AssistantAvatarStore]. Blank means "use
     * [avatar]".
     *
     * Relative on purpose: the app's private directory is named after the install, so an
     * absolute path stored here would point at nothing after a reinstall or a restore. The
     * file itself is capped at 256px on its longest side, so a 4MB photo from the gallery
     * never lands in this JSON or in the private directory at full size.
     */
    val avatarPath: String = "",
    val description: String = "",
    val systemPrompt: String = "",
    /** Provider id + model id; empty means "whatever is active right now". */
    val providerId: String = "",
    val model: String = "",
    /** null follows the global temperature. */
    val temperature: Double? = null,
    /** Tool names this persona must not call. */
    val disabledTools: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
) {
    val face: String get() = avatar.ifBlank { "🪶" }

    val label: String get() = name.ifBlank { "未命名助手" }

    /** True when the persona pins its own model instead of following the app. */
    val pinsModel: Boolean get() = model.isNotBlank()
}

/** The emoji a new assistant picks from; index 0 is the house default. */
val ASSISTANT_AVATARS = listOf(
    "🪶", "🧭", "🔍", "📚", "🛠️", "🧪", "🎨", "📝",
    "🌐", "💡", "🧮", "🗂️", "🎧", "🩺", "⚖️", "🧠",
)

/** The persona a fresh install starts with. */
fun defaultAssistant(systemPrompt: String = DEFAULT_SYSTEM_PROMPT): Assistant = Assistant(
    id = "default",
    name = "随行",
    avatar = "🪶",
    description = "全能手机智能体",
    systemPrompt = systemPrompt,
)
