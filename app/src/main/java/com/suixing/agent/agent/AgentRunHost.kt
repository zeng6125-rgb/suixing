package com.suixing.agent.agent

import com.suixing.agent.data.ChildRunConfig
import com.suixing.agent.data.GoalStore
import com.suixing.agent.llm.LlmClient
import com.suixing.agent.tools.GoalTools
import kotlinx.coroutines.flow.MutableStateFlow

/** Shared services and the engine-owned publication boundary; no per-run state lives here. */
internal class AgentRunHost(
    val llm: LlmClient,
    val goals: GoalStore?,
    val goalTools: GoalTools?,
    val childConfig: ChildRunConfig?,
    val approvalSourceTitle: String?,
    val childStates: MutableStateFlow<Map<String, List<SubagentSnapshot>>>,
    val subagentReadyChats: MutableStateFlow<Set<String>>,
    val isLive: (String, AgentRun) -> Boolean,
    val publishState: (String, RunState) -> Unit,
    val onCompleted: (String) -> Unit,
)
