package com.suixing.agent.ui.chat

import com.suixing.agent.core.Core
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import com.suixing.agent.agent.AgentEngine
import com.suixing.agent.agent.ApprovalGate
import com.suixing.agent.llm.LlmClient
import com.suixing.agent.data.SettingsRepository
import com.suixing.agent.data.ChatRepository
import com.suixing.agent.data.GoalStore
import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.skills.SkillStore
import com.suixing.agent.data.MemoryStore
import com.suixing.agent.data.UserProfileStore
import com.suixing.agent.data.PerfMonitor
import com.suixing.agent.voice.Speaker
import com.suixing.agent.tools.ToolRegistry

/** Injectable process services; runtime tests need neither an Android ViewModel nor the global Core. */
internal interface ChatRuntimeEnvironment {
    val appContext: Context
    val appScope: CoroutineScope
    val engine: AgentEngine
    val gate: ApprovalGate
    val llm: LlmClient
    val settings: SettingsRepository
    val chats: ChatRepository
    val goals: GoalStore
    val capabilities: CapabilityHub
    val skills: SkillStore
    val memory: MemoryStore
    val profile: UserProfileStore
    val perf: PerfMonitor
    val speaker: Speaker
    val tools: ToolRegistry
}

internal object CoreChatRuntimeEnvironment : ChatRuntimeEnvironment {
    override val appContext: Context get() = Core.appContext
    override val appScope: CoroutineScope get() = Core.appScope
    override val engine: AgentEngine get() = Core.engine
    override val gate: ApprovalGate get() = Core.gate
    override val llm: LlmClient get() = Core.llm
    override val settings: SettingsRepository get() = Core.settings
    override val chats: ChatRepository get() = Core.chats
    override val goals: GoalStore get() = Core.goals
    override val capabilities: CapabilityHub get() = Core.capabilities
    override val skills: SkillStore get() = Core.skills
    override val memory: MemoryStore get() = Core.memory
    override val profile: UserProfileStore get() = Core.profile
    override val perf: PerfMonitor get() = Core.perf
    override val speaker: Speaker get() = Core.speaker
    override val tools: ToolRegistry get() = Core.tools
}
