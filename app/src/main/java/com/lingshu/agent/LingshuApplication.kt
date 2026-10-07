package com.lingshu.agent

import android.app.Application
import com.lingshu.agent.core.Core
import kotlinx.coroutines.launch

class LingshuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Core.init(this)
        // MCP servers are reconnected on every cold start so their tools are
        // already in the registry by the time the user sends the first message.
        Core.appScope.launch { Core.mcp.load() }
    }
}
