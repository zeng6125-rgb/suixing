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
        // **会话落盘自愈**（0.8.308）：0.8.307 那版误把会话写进了新格式（`chats/store/`），
        // 并把旧文件改名成 `<id>.json.migrated` ⇒ 对旧版本不可见、导出与文件详情也读不到。
        // 这里在启动后（不是 load() 的锁内）做一次性修复：能改名的改名回来、只有新格式副本的
        // 读出来重新落成 `<id>.json`（校验通过才删副本），失败则什么都不删、下次再试。
        // 可中断可重入：任何时刻被杀都留着一份完整的数据。
        //
        // **异常必须在这里吃掉**：`Core.appScope` 是 `SupervisorJob() + Dispatchers.Default`，
        // 协程里逃出去的异常会走默认未捕获处理器 ⇒ Android 上直接崩进程。自愈失败（坏文件、
        // 磁盘满、权限）绝不能变成"打开就闪退"，所以整段 runCatching + 只记日志。
        Core.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { Core.chats.repairPending() }
                .onFailure { android.util.Log.w("ChatRepository", "repairPending failed", it) }
        }
        // **旧格式迁移**（#31）：把 `<id>.json` 逐会话搬进「meta + JSONL 追加日志」。
        //
        // 为什么不在 `load()` 里做：读一个 54 MB 的目录、每个会话全量解析 + 写回 + 校验，
        // 是秒级的 IO，列表必须先在屏幕上。这里排在 IO 协程里，界面照常可用；
        // 迁移是**幂等、可中断、可重入**的（`migratePending` 内部逐会话加锁、校验通过才改名，
        // 失败就保留旧文件下次再来），所以每次启动跑一次是安全的。
        Core.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { Core.chats.migratePending() }
                .onFailure { android.util.Log.w("ChatRepository", "migratePending failed", it) }
        }
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
