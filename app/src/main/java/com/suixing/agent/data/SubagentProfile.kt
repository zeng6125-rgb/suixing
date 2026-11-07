package com.suixing.agent.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A saved template contains choices, never endpoint credentials or the parent's transcript. */
@Immutable
@Serializable
data class SubagentProfile(
    val id: String,
    val name: String = "子任务模板",
    val assistantId: String = "",
    val model: SubagentModelSelection? = null,
    val approvalMode: ApprovalMode? = null,
    /** null inherits the parent catalogue; an empty list creates a text-only child. */
    val allowedTools: List<String>? = null,
    val allowedActions: Map<String, List<String>> = emptyMap(),
    /** null inherits; zero is unlimited only when the parent is also unlimited. */
    val maxToolRounds: Int? = null,
    val timeoutSeconds: Int = 180,
    val extraInstructions: String = "",
)

/** Per-launch overrides. draftProfile is a typed UI input and is not read from model arguments. */
data class SubagentLaunchOptions(
    val profileId: String? = null,
    val assistantId: String? = null,
    val model: SubagentModelSelection? = null,
    val approvalMode: ApprovalMode? = null,
    val allowedTools: List<String>? = null,
    val allowedActions: Map<String, List<String>>? = null,
    val maxToolRounds: Int? = null,
    val timeoutSeconds: Int? = null,
    val draftProfile: SubagentProfile? = null,
)

/** The choices actually bound to a child. Safe to retain beside its conversation. */
@Immutable
@Serializable
data class ChildRunConfig(
    val profileId: String = "",
    val profileName: String = "跟随父任务",
    val assistantId: String = "",
    val assistantName: String = "跟随父助手",
    val providerId: String = "",
    val model: String = "",
    val approvalMode: ApprovalMode = ApprovalMode.ASK,
    val allowedTools: List<String> = emptyList(),
    val allowedActions: Map<String, List<String>> = emptyMap(),
    val maxToolRounds: Int = 0,
    val timeoutSeconds: Int = 180,
    val extraInstructions: String = "",
) {
    /** Called before every execution path, including plugin tools and engine-owned tools. */
    fun denialReason(toolName: String, args: JsonObject): String? {
        if (toolName !in allowedTools) return "子任务配置不允许工具：$toolName"
        val actions = allowedActions[toolName] ?: return null
        val action = (args["action"] as? JsonPrimitive)?.contentOrNull
        return if (action != null && action in actions) null
        else "子任务配置不允许 $toolName 的动作：${action ?: "未指定"}"
    }
}

/** Runtime-only: the settings half may hold credentials and must never be serialized. */
data class ResolvedChildConfig(val config: ChildRunConfig, val settings: AppSettings)

/** Resolve once against the parent's frozen run settings and complete allowed tool catalogue. */
fun AppSettings.resolveSubagentConfig(
    options: SubagentLaunchOptions,
    availableToolNames: Set<String>,
): ResolvedChildConfig {
    val selectedId = options.profileId ?: defaultSubagentProfileId
    val saved = selectedId.takeIf { options.draftProfile == null && it.isNotBlank() }?.let { id ->
        subagentProfiles.firstOrNull { it.id == id } ?: error("子任务模板已不存在，请重新选择。")
    }
    val profile = options.draftProfile ?: saved
    val assistantId = options.assistantId ?: profile?.assistantId.orEmpty()
    val chosenAssistant = assistantId.takeIf { it.isNotBlank() }?.let { id ->
        assistant(id) ?: error("指定的子任务助手已不存在，请重新选择。")
    }
    var childSettings = when {
        chosenAssistant != null -> forAssistant(chosenAssistant)
        profile != null || options.profileId != null -> this
        else -> forSubagentModel()
    }
    val selectedModel = options.model ?: profile?.model
    if (selectedModel != null) {
        require(selectedModel.resolve(providers) != null) { "子任务模型不可用，请选择已启用的模型。" }
        childSettings = childSettings.copy(subagentModel = selectedModel).forSubagentModel()
    }
    // Assistant overrides can change the connection; parent request headers belong to the old one.
    val sameConnection = baseUrl.trimEnd('/') == childSettings.baseUrl.trimEnd('/') &&
        apiKey == childSettings.apiKey && protocol == childSettings.protocol
    val mode = listOfNotNull(approvalMode, profile?.approvalMode, options.approvalMode)
        .maxBy { when (it) { ApprovalMode.AUTO -> 0; ApprovalMode.ASK -> 1; ApprovalMode.DENY -> 2 } }
    val profileTools = profile?.allowedTools?.toSet()
    val requestedTools = options.allowedTools?.toSet()
    val assistantDenied = chosenAssistant?.disabledTools.orEmpty().toSet()
    val allowed = availableToolNames.filter { name ->
        name !in assistantDenied && (profileTools == null || name in profileTools) &&
            (requestedTools == null || name in requestedTools)
    }.sorted()
    val actions = intersectActions(profile?.allowedActions.orEmpty(), options.allowedActions.orEmpty())
        .filterKeys { it in allowed }
    val requestedRounds = options.maxToolRounds ?: profile?.maxToolRounds
    val rounds = listOfNotNull(maxToolRounds.takeIf { it > 0 }, requestedRounds?.takeIf { it > 0 }).minOrNull() ?: 0
    val timeout = (options.timeoutSeconds ?: profile?.timeoutSeconds ?: 180).coerceIn(1, 600)
    val instructions = profile?.extraInstructions.orEmpty()
    childSettings = childSettings.copy(
        approvalMode = mode,
        maxToolRounds = rounds,
        requestHeaders = if (sameConnection) requestHeaders else emptyMap(),
        maxTokens = childSettings.maxTokensFor(childSettings.model),
        systemPrompt = childSettings.systemPrompt + instructions.takeIf { it.isNotBlank() }?.let { "\n\n$it" }.orEmpty(),
        activeAssistantId = chosenAssistant?.id ?: activeAssistantId,
    )
    val config = ChildRunConfig(
        profileId = selectedId.ifBlank { profile?.id.orEmpty() },
        profileName = profile?.name?.ifBlank { "本次配置" } ?: "跟随父任务",
        assistantId = chosenAssistant?.id ?: activeAssistantId,
        assistantName = chosenAssistant?.label ?: activeAssistant()?.label ?: "跟随父助手",
        providerId = childSettings.activeProvider()?.id.orEmpty(),
        model = childSettings.model,
        approvalMode = mode,
        allowedTools = allowed,
        allowedActions = actions,
        maxToolRounds = rounds,
        timeoutSeconds = timeout,
        extraInstructions = instructions,
    )
    return ResolvedChildConfig(config, childSettings)
}

private fun intersectActions(
    inherited: Map<String, List<String>>,
    requested: Map<String, List<String>>,
): Map<String, List<String>> = (inherited.keys + requested.keys).associateWith { tool ->
    val first = inherited[tool]
    val second = requested[tool]
    when {
        first == null -> second.orEmpty().distinct()
        second == null -> first.distinct()
        else -> first.filter { it in second }.distinct()
    }
}
