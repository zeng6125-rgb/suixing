package com.suixing.agent.core

import android.content.Context
import com.suixing.agent.agent.AgentEngine
import com.suixing.agent.agent.ApprovalGate
import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.capability.Shell
import com.suixing.agent.data.ChatRepository
import com.suixing.agent.data.ConversationReads
import com.suixing.agent.data.ConversationViewports
import com.suixing.agent.data.JobStore
import com.suixing.agent.data.McpStore
import com.suixing.agent.data.MemoryStore
import com.suixing.agent.data.PerfMonitor
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
import com.suixing.agent.llm.LlmClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
     * 决定用哪套启动主题 —— 那一刻没有协程可以等。所以这里只同步读独立的主题快照，
     * DataStore 由 IO 收集器异步读取并更新快照。读不到时保持 0＝跟系统，
     * 也就是资源系统本来的默认行为（不会崩，只是那一帧仍按系统 uiMode 画）。
     */
    @Volatile
    var themeMode: Int = 0
        private set

    lateinit var appContext: Context
        private set

    /** 性能记录（有界落盘）。设置里的「性能」页与调试都读它，见 [PerfMonitor]。 */
    lateinit var perf: PerfMonitor
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var chats: ChatRepository
        private set
    internal lateinit var conversationReads: ConversationReads
        private set
    internal lateinit var conversationViewports: ConversationViewports
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
    internal val startup = StartupReadiness()

    /** One lazily prepared network owner for runs, setup and model metadata. */
    val llm: LlmClient by lazy { LlmClient() }

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
            // 自带 bash/busybox 的目录（见 Shell.initBinaries 的注释）：只读一次，工具里同步取用。
            Shell.initBinaries(appContext.applicationInfo.nativeLibraryDir)
            settings = SettingsRepository(appContext)
            conversationReads = ConversationReads.create(appContext, appScope)
            conversationViewports = ConversationViewports.create(appContext, appScope)
            chats = ChatRepository(appContext, onDeleted = { id ->
                conversationReads.forget(id)
                conversationViewports.forget(id)
            })
            memory = MemoryStore(appContext)
            profile = UserProfileStore(appContext)
            capabilities = CapabilityHub(appContext, settings, beforeInitialProbe = startup::awaitMaintenance)
            skills = SkillStore(appContext)
            speaker = Speaker(appContext)
            jobs = JobStore(File(appContext.filesDir, "jobs"), appScope)
            todos = TodoStore(File(appContext.filesDir, "todos.json"))
            schedules = ScheduleStore(File(appContext.filesDir, "schedules.json"))
            plugins = PluginStore(File(appContext.filesDir, "plugins"))
            usage = UsageStore(appContext)
            perf = PerfMonitor(File(appContext.filesDir, "perf.json"))
            appScope.launch(Dispatchers.IO) {
                startup.maintenance { perf.load() }
            }
            tools = ToolRegistry(
                appContext, capabilities, skills, speaker, settings, memory, profile, jobs, todos,
                schedules,
            )
            mcp = McpManager(McpStore(appContext), tools)
            gate = ApprovalGate(settings, appScope)
            engine = AgentEngine(llm = llm, scope = appScope, onCompleted = conversationReads::completed)
            appScope.launch(Dispatchers.IO) {
                // Construct TLS in the background before the usual first send; no network request.
                startup.maintenance { runCatching { llm.prepare() } }
            }
            val appearance = appContext.getSharedPreferences("startup_appearance", Context.MODE_PRIVATE)
            themeMode = appearance.getInt("theme_mode", 0)
            appScope.launch {
                settings.flow.collect {
                    themeMode = it.themeMode
                    if (appearance.getInt("theme_mode", -1) != it.themeMode) {
                        appearance.edit().putInt("theme_mode", it.themeMode).apply()
                    }
                }
            }
            appScope.launch {
                memory.load()
                profile.load()
            }
            // **用量缓存的周期刷新**（issue #41）：`refresh()` 原来只在用量页的
            // LaunchedEffect 里调 —— 页面不开就永远不写盘，实测 usage.json 三天没更新，
            // 用量页一打开才补算。这里开机先跑一次，之后每 30 分钟一次；内部是
            // 水位增量（只扫新落盘的 meta），代价与新增会话数成正比。
            appScope.launch {
                while (true) {
                    runCatching { startup.maintenance { usage.refresh() } }
                        .onFailure {
                            android.util.Log.w("suixing-usage", "periodic refresh failed", it)
                        }
                    kotlinx.coroutines.delay(30L * 60L * 1000L)
                }
            }
            // Alarms are dropped when the app is updated or force-stopped, so aiming the next
            // one on every launch is what keeps a reminder set weeks ago from going quiet.
            appScope.launch(Dispatchers.IO) { SchedulerService.arm(appContext) }
            // 插件链路 clinit 探针（FEEDBACK v0.8.112 P0#1 / 真机 ExceptionInInitializerError）：
            // 启动时主动碰一次两个门面类，clinit 炸就把 cause 链写进 plugin-trace.log —— 这台真机
            // logcat 读不出来，不主动记就永远拿不到真凶。探针只读不写业务状态，失败也只落日志。
            appScope.launch {
                startup.maintenance {
                  for (probe in listOf(
                    { com.suixing.agent.plugins.ManifestValidator.rank("low") },
                    { com.suixing.agent.plugins.PluginRuntime.probeClinit() },
                )) {
                    runCatching { probe() }.exceptionOrNull()?.let { error ->
                        val chain = buildString {
                            append(error::class.java.simpleName)
                            error.message?.let { append("(").append(it).append(")") }
                            var cause = error.cause
                            var depth = 0
                            while (cause != null && depth < 4) {
                                append(" ← ").append(cause::class.java.simpleName)
                                cause.message?.let { append("(").append(it).append(")") }
                                cause = cause.cause
                                depth++
                            }
                        }
                        runCatching { com.suixing.agent.plugins.appendPluginTrace("clinit 探针失败：$chain") }
                    }
                  }
                }
            }
            initialized = true
        }
    }
}
