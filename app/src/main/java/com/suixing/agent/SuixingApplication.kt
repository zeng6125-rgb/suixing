package com.suixing.agent

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.suixing.agent.core.Core
import kotlinx.coroutines.launch

class SuixingApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Core.init(this)
        // MCP servers are reconnected on every cold start so their tools are
        // already in the registry by the time the user sends the first message.
        Core.appScope.launch { Core.mcp.load() }
        // 老文件里的 API Key 是**明文**躺在 settings_json 里的：启动时一次性换成 Keystore
        // 密文（顺带把明文从文件里抹掉）。之后每次落盘都自动加密（见 SettingsRepository）。
        Core.appScope.launch { Core.settings.migrateSecrets() }
        observeForeground()
    }

    /**
     * Re-reads the cheap capability facts every time the app comes back to the front.
     *
     * The user toggles the accessibility service or grants Shizuku in *another* app,
     * so the state this one shows is stale by the time they return; without this the
     * card would claim the service is off until they leave the page and come back.
     * Root is deliberately not re-probed here — that would put a superuser prompt in
     * front of someone who only switched apps.
     */
    private fun observeForeground() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) {
                    Core.appScope.launch { Core.capabilities.refresh() }
                }
            },
        )
    }
}
