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
import java.io.File

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
            usage = UsageStore(appContext)
            tools = ToolRegistry(
                appContext, capabilities, skills, speaker, settings, memory, profile, jobs, todos,
                schedules,
            )
            mcp = McpManager(McpStore(appContext), tools)
            gate = ApprovalGate(settings, appScope)
            engine = AgentEngine(scope = appScope)
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
