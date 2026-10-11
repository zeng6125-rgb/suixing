package com.suixing.agent.core

import android.content.Context
import com.suixing.agent.agent.AgentEngine
import com.suixing.agent.agent.ApprovalGate
import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.data.ChatRepository
import com.suixing.agent.data.JobStore
import com.suixing.agent.data.McpStore
import com.suixing.agent.data.MemoryStore
import com.suixing.agent.data.ScheduleStore
import com.suixing.agent.data.SettingsRepository
import com.suixing.agent.data.TodoStore
import com.suixing.agent.data.UsageStore
import com.suixing.agent.plugins.PluginStore
import com.suixing.agent.data.UserProfileStore
import com.suixing.agent.mcp.McpManager
import com.suixing.agent.service.SchedulerService
import com.suixing.agent.skills.SkillStore
import com.suixing.agent.tools.ToolRegistry
import com.suixing.agent.voice.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Process-wide service locator. The app is small enough that a hand-rolled
 * container keeps things readable; every field is created once in [init].
 */
object Core {
    @Volatile
    private var initialized = false

    /**
     * 主题模式的同步快照：1＝浅、2＝深、其它＝跟系统（与 `MainActivity` 里那份 when 同一套语义）。
     *
     * `settings.current()` 是挂起的，而 `MainActivity.onCreate` 必须在 `setContentView` **之前**
     * 决定用哪套启动主题 —— 那一刻没有协程可以等。所以这里备一份同步可读的副本：[init] 里先同步
     * 读一次（settings.json 只有几百字节），之后由收集器跟着用户的改动走。读不到时保持 0＝跟系统，
     * 也就是资源系统本来的默认行为（不会崩，只是那一帧仍按系统 uiMode 画）。
     */
    @Volatile
    var themeMode: Int = 0
        private set

    lateinit var appContext: Context
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var chats: ChatRepository
        private set

    /** The agent's long-term notes: `filesDir/memory.json`, shared with the UI. */
    lateinit var memory: MemoryStore
        private set

    /** 用户画像：`filesDir/profile.md`，纯文本，每次请求都注入系统提示词。 */
    lateinit var profile: UserProfileStore
        private set

    lateinit var capabilities: CapabilityHub
        private set
    lateinit var skills: SkillStore
        private set
    lateinit var speaker: Speaker
        private set

    /** Background shell jobs; their logs live under `filesDir/jobs`. */
    lateinit var jobs: JobStore
        private set

    /** The task board: `filesDir/todos.json`, shared with the UI. */
    lateinit var todos: TodoStore
        private set

    /** Reminders: `filesDir/schedules.json`. */
    lateinit var schedules: ScheduleStore
        private set

    /**
     * Installed plugins: one folder per plugin under `filesDir/plugins`.
     *
     * A store rather than a live registry: M1 installs, validates and shows what a package *would*
     * do. Nothing here is wired into the tool table or the prompt yet, which is why the UI is a
     * white-box view of the manifest rather than a switch that changes behaviour.
     */
    lateinit var plugins: PluginStore
        private set

    /** Token usage aggregated from the chat transcripts; shared so one watermark serves all readers. */
    lateinit var usage: UsageStore
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

    /**
     * Shared for the same reason as [gate]: the floating console drives the run that
     * the chat screen started, so both need the same instance to call `cancel()` and
     * `steer()` on. One engine, one run per conversation, many conversations at once.
     */
    lateinit var engine: AgentEngine
        private set

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            settings = SettingsRepository(appContext)
            chats = ChatRepository(appContext)
            memory = MemoryStore(appContext)
            profile = UserProfileStore(appContext)
            capabilities = CapabilityHub(appContext, settings)
            skills = SkillStore(appContext)
            speaker = Speaker(appContext)
            jobs = JobStore(File(appContext.filesDir, "jobs"), appScope)
            todos = TodoStore(File(appContext.filesDir, "todos.json"))
            schedules = ScheduleStore(File(appContext.filesDir, "schedules.json"))
            plugins = PluginStore(File(appContext.filesDir, "plugins"))
            usage = UsageStore(appContext)
            tools = ToolRegistry(
                appContext, capabilities, skills, speaker, settings, memory, profile, jobs, todos,
                schedules,
            )
            mcp = McpManager(McpStore(appContext), tools)
            gate = ApprovalGate(settings, appScope)
            engine = AgentEngine(scope = appScope)
            // 启动窗口的底色必须在 MainActivity 之前定下来，所以这一份是**同步**读的：
            // settings.json 只有几百字节，读盘几毫秒；读不到就等于「跟系统」（0），不会崩。
            themeMode = runCatching { runBlocking { settings.current().themeMode } }.getOrDefault(0)
            // 用户在设置里改了主题：下一次冷启动就该按新的那一套画启动窗口。
            appScope.launch { settings.flow.collect { themeMode = it.themeMode } }
            appScope.launch {
                memory.load()
                profile.load()
            }
            // Alarms are dropped when the app is updated or force-stopped, so aiming the next
            // one on every launch is what keeps a reminder set weeks ago from going quiet.
            SchedulerService.arm(appContext)
            initialized = true
        }
    }
}
