package com.suixing.agent.terminal

import android.content.Context
import android.util.AtomicFile
import com.suixing.agent.service.TerminalForegroundService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
internal data class TerminalInfo(
    val id: String,
    val name: String,
    val kind: String,
    val shell: String,
    val cwd: String,
    val pid: Int? = null,
    val phase: String = "running",
    val ownerChatId: String? = null,
    val exitCode: Int? = null,
    val note: String? = null,
)

internal data class TerminalEntry(val info: TerminalInfo, val session: TerminalSession? = null)

/** Process owner, independent of a screen or chat run. No PTY handle is restored after process death. */
internal class TerminalSessionManager(
    private val context: Context,
    appScope: CoroutineScope,
    private val linux: LinuxInstaller,
) {
    private val scope = CoroutineScope(SupervisorJob(appScope.coroutineContext[Job]) + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val _entries = MutableStateFlow<List<TerminalEntry>>(emptyList())
    val entries = _entries.asStateFlow()
    val openRequested = MutableStateFlow(false)
    private val _backgroundWarning = MutableStateFlow<String?>(null)
    val backgroundWarning = _backgroundWarning.asStateFlow()
    private var loaded = false
    var selectedId: String? = null
    private val metadata = AtomicFile(File(context.noBackupFilesDir, "terminal-sessions.json"))
    private val json = Json { ignoreUnknownKeys = true }
    private val logs = File(context.noBackupFilesDir, "terminal-logs")

    suspend fun load() = mutex.withLock { loadLocked() }
    private suspend fun loadLocked() {
        if (loaded) return
        _entries.value = withContext(Dispatchers.IO) {
            runCatching {
                json.decodeFromString<List<TerminalInfo>>(metadata.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
                    .takeLast(16).map { info ->
                        TerminalEntry(if (info.phase in listOf("starting", "running", "stopping"))
                            info.copy(phase = "interrupted", pid = null, note = "应用进程已结束，请新建会话") else info.copy(pid = null))
                    }
            }.getOrDefault(emptyList())
        }
        loaded = true
    }

    suspend fun open(kind: String = "android", caller: TerminalCaller? = null): TerminalEntry = mutex.withLock {
      withContext(NonCancellable) {
        loadLocked()
        check(_entries.value.count { it.info.phase == "running" } < 4) { "最多同时运行 4 个终端会话，请先停止一个" }
        val plan = if (kind == "android") androidTerminalLaunch(context) else {
            linux.load()
            linux.launch(kind)
        }
        val native = withContext(Dispatchers.IO) {
            TerminalNative.nativeOpenProcess(plan.argv.toTypedArray(),
                plan.environment.map { (key, value) -> "$key=$value" }.toTypedArray(), plan.cwd, 80, 24)
        }
        val id = UUID.randomUUID().toString()
        val info = TerminalInfo(id, plan.label, kind, plan.label, plan.cwd, native[1].toInt(),
            ownerChatId = caller?.chatId, note = plan.note)
        val session = withContext(Dispatchers.Main.immediate) {
            TerminalSession(id, native[0].toInt(), native[1].toInt(), plan, TerminalPolicy(caller?.chatId),
                scope, TerminalLog(logs, id)) { code, failure ->
                scope.launch {
                    mutex.withLock {
                        _entries.value = _entries.value.map { entry ->
                            if (entry.info.id == id) entry.copy(info = entry.info.copy(phase = "exited",
                                pid = null, exitCode = code, note = failure ?: entry.info.note)) else entry
                        }
                        saveLocked()
                    }
                }
            }
        }
        val entry = TerminalEntry(info, session)
        val retained = _entries.value
        _entries.value = (retained.filter { it.info.phase == "running" } +
            retained.filter { it.info.phase != "running" }.takeLast(11) + entry).distinctBy { it.info.id }
        selectedId = id
        withContext(Dispatchers.Main.immediate) { session.start() }
        _backgroundWarning.value = TerminalForegroundService.ensure(context)
        saveLocked()
        entry
      }
    }

    fun session(id: String): TerminalSession =
        _entries.value.firstOrNull { it.info.id == id }?.session ?: error("会话不存在或已断开，请重新创建")

    fun forCaller(caller: TerminalCaller): List<TerminalEntry> =
        _entries.value.filter { it.session?.policy?.allow(caller) == true }

    fun authorized(id: String, caller: TerminalCaller): TerminalSession =
        session(id).also { check(it.policy.allow(caller)) { "终端未授权当前任务，或已由用户接管" } }

    suspend fun stop(id: String) { session(id).stop() }
    suspend fun stopAll() {
        _entries.value.filter { it.info.phase == "running" }.mapNotNull { it.session }
            .map { session -> scope.async { session.stop() } }.awaitAll()
    }
    suspend fun remove(id: String) {
        _entries.value.firstOrNull { it.info.id == id }?.session?.stop()
        mutex.withLock {
            _entries.value = _entries.value.filterNot { it.info.id == id }
            saveLocked()
        }
    }

    suspend fun removeLinux(distro: LinuxDistro) = mutex.withLock {
        check(_entries.value.none { it.info.kind == distro.id && it.info.phase == "running" }) {
            "请先停止该环境的终端会话"
        }
        linux.remove(distro)
    }

    private suspend fun saveLocked() = withContext(Dispatchers.IO) {
        val bytes = json.encodeToString(_entries.value.map { it.info }).toByteArray()
        runCatching {
            val stream = metadata.startWrite()
            try { stream.write(bytes); metadata.finishWrite(stream) }
            catch (error: Exception) { metadata.failWrite(stream); throw error }
        }.onFailure { _backgroundWarning.value = "终端状态保存失败：" + it.message }
        Unit
    }
}
