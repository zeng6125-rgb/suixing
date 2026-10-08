package com.suixing.agent.core

import android.content.Context
import com.suixing.agent.agent.ApprovalGate
import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.data.ChatRepository
import com.suixing.agent.data.McpStore
import com.suixing.agent.data.SettingsRepository
import com.suixing.agent.mcp.McpManager
import com.suixing.agent.skills.SkillStore
import com.suixing.agent.tools.ToolRegistry
import com.suixing.agent.voice.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide service locator. The app is small enough that a hand-rolled
 * container keeps things readable; every field is created once in [init].
 */
object Core {
    @Volatile
    private var initialized = false

    lateinit var appContext: Context
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var chats: ChatRepository
        private set
    lateinit var capabilities: CapabilityHub
        private set
    lateinit var skills: SkillStore
        private set
    lateinit var speaker: Speaker
        private set
    lateinit var tools: ToolRegistry
        private set
    lateinit var mcp: McpManager
        private set

    /** Outlives any screen: MCP servers keep running while the UI is gone. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Shared on purpose: an approval prompt must be visible no matter which tab
     * the user is on, so the gate lives here and the dialog is rendered above the
     * navigation host rather than inside the chat screen.
     */
    lateinit var gate: ApprovalGate
        private set

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            settings = SettingsRepository(appContext)
            chats = ChatRepository(appContext)
            capabilities = CapabilityHub(appContext)
            skills = SkillStore(appContext)
            speaker = Speaker(appContext)
            tools = ToolRegistry(appContext, capabilities, skills, speaker, settings)
            mcp = McpManager(McpStore(appContext), tools)
            gate = ApprovalGate()
            initialized = true
        }
    }
}
