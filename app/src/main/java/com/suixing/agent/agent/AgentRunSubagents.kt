package com.suixing.agent.agent

import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.resolveSubagentConfig
import com.suixing.agent.data.MemoryStore
import com.suixing.agent.data.UserProfileStore
import com.suixing.agent.data.Roles
import com.suixing.agent.data.StoredMessage
import com.suixing.agent.tools.ToolSpec
import com.suixing.agent.skills.SkillStore
import com.suixing.agent.tools.GOAL_TOOL_NAMES
import com.suixing.agent.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.sync.withPermit
import com.suixing.agent.agent.AgentRun.Companion.SUBAGENT_EXECUTION_SLOTS
import com.suixing.agent.agent.AgentRun.Companion.CHILD_AGENT_INSTRUCTIONS

/** Create children on this run's job; the parent objective stays a live provider. */
internal suspend fun AgentRun.createSubagentPool(
    settings: AppSettings,
    specs: List<ToolSpec>,
    registry: ToolRegistry,
    capabilities: CapabilityHub,
    skills: SkillStore,
    memory: MemoryStore,
    profile: UserProfileStore,
    gate: ApprovalGate,
    parentGoalText: () -> String,
): SubagentPool {
    val excluded = SUBAGENT_TOOL_NAMES + GOAL_TOOL_NAMES +
        setOf("todo_read", "todo_write", "create_assistant", "get_session_status")
    val childCatalogue = specs.mapTo(HashSet()) { it.name } - excluded
    return SubagentPool(CoroutineScope(currentCoroutineContext() + Dispatchers.Default),
        parallelism = settings.subagentParallelism,
        resolveConfig = { options -> settings.resolveSubagentConfig(options, childCatalogue) },
        onCreated = { task -> SubagentRecords.create(chatId, childParentRunId, task, task.config) },
        onStatusChanged = { task, snapshot -> SubagentRecords.update(task.id, snapshot.status, snapshot.result) },
        onTerminated = { task -> SubagentRecords.terminated(task.id) },
        onChanged = { snapshots -> childStates.update { it + (chatId to snapshots) } }) { task ->
        SUBAGENT_EXECUTION_SLOTS.withPermit {
            val child = AgentEngine(llm = llm,
                approvalSourceTitle = "$title · 子任务：${task.name}", childConfig = task.config)
            val relay = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { child.cancel(task.id) }
            }
            try {
                var failure: String? = null
                val childSettings = task.resolvedSettings.copy(subagentsEnabled = false, autoProfile = false,
                    systemPrompt = task.resolvedSettings.systemPrompt + "\n\n" + CHILD_AGENT_INSTRUCTIONS)
                val input = buildString {
                    append("Task:\n").append(task.task)
                    if (task.context.isNotBlank()) append("\n\nRelevant context:\n").append(task.context)
                    if (parentGoalText().isNotBlank()) append("\n\nParent objective:\n").append(parentGoalText())
                }
                val history = listOf(StoredMessage(id = task.id + "_input", role = Roles.USER, text = input))
                val childRegistry = registry.excluding(excluded + (registry.names().toSet() - task.config.allowedTools.toSet()))
                SubagentRecords.complete(task.id, history)
                val messages = child.startRun(chatId = task.id, title = task.name,
                    history = history, settings = childSettings, registry = childRegistry,
                    capabilities = capabilities, skills = skills, memory = memory, profile = profile, gate = gate,
                    onEvent = { event ->
                        SubagentRecords.onEvent(task.id, event)
                        if (event is AgentEvent.Failed) failure = event.message
                    })
                SubagentRecords.complete(task.id, history + messages)
                failure?.let { error(it) }
                messages.lastOrNull { it.role == Roles.ASSISTANT && it.text.isNotBlank() }?.text
                    ?: "子任务结束，未返回文字结果。"
            } finally { relay.cancel() }
        }
    }
}
