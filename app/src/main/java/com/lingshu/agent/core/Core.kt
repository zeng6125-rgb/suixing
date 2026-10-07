package com.lingshu.agent.core

import android.content.Context
import com.lingshu.agent.capability.CapabilityHub
import com.lingshu.agent.data.ChatRepository
import com.lingshu.agent.data.SettingsRepository
import com.lingshu.agent.tools.ToolRegistry

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
    lateinit var tools: ToolRegistry
        private set

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            settings = SettingsRepository(appContext)
            chats = ChatRepository(appContext)
            capabilities = CapabilityHub(appContext)
            tools = ToolRegistry(appContext, capabilities)
            initialized = true
        }
    }
}
