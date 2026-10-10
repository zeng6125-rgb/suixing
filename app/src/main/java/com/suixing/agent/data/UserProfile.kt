package com.suixing.agent.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 自动整理画像的记账：上次成功总结的时间，以及自那以后结束的轮数。
 *
 * 放在 `profile.md` 旁边的小文件里，而不是设置里：它每一轮都在变，混进设置会让设置流
 * 每轮重新发射一次，而它本身也不是用户做过的选择。
 */
@Serializable
data class ProfileState(
    /** 上次成功总结的墙钟时间；0 = 从未总结。 */
    val lastProfileAt: Long = 0,
    /** 自上次总结以来结束的轮数；到门限就总结一次，然后归零。 */
    val profileTurnMark: Int = 0,
)

/**
 * What the agent knows about the user, backed by `filesDir/profile.md`.
 *
 * Markdown rather than JSON on purpose: the text is injected into the system prompt
 * verbatim and the user is expected to read and edit it by hand (it also turns up in
 * 会话文件), so a format that survives both matters more than structure. The file is the
 * single source of truth — the tool layer and the settings UI both go through this class,
 * so what the model writes is exactly what the user sees.
 *
 * 画像由模型自己定期总结（[com.suixing.agent.agent.ProfileSummarizer]），用户手改只是补充
 * 与纠正：两条路都落到这一个文件，模型下一轮看到的就是用户看到的那一份。它与记忆分开——
 * 记忆是模型在任务里主动记下的键值（`memory.json`），画像是它从对话里读出来的、关于这个人的
 * 滚动摘要。
 */
class UserProfileStore(private val context: Context) {

    companion object {
        /**
         * Cap on the whole profile. The file is rewritten on every save and the text is
         * injected into every request, so one runaway profile slows down both.
         */
        const val MAX_LENGTH = 8000
    }

    private val file: File get() = File(context.filesDir, "profile.md")

    /** `filesDir/profile.state.json`：画像自己的记账，见 [ProfileState]。 */
    private val stateFile: File get() = File(context.filesDir, "profile.state.json")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Serialises load and write: the tool layer and the settings UI both write, and without
     * it the later writer starts from a stale read and silently drops the other's text.
     */
    private val lock = Mutex()

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    private val _summary = MutableStateFlow(ProfileState())

    /** 自动总结的记账，画像页用它显示「最近更新」。 */
    val summary: StateFlow<ProfileState> = _summary.asStateFlow()

    /** Reads the file into [text] and the bookkeeping into [summary]; the UI and the engine call this once. */
    suspend fun load() = withContext(Dispatchers.IO) {
        lock.withLock {
            _text.value = read()
            _summary.value = readState()
        }
    }

    /**
     * 记下一轮已经结束的对话，返回自上次总结以来累计的轮数。
     *
     * 计数器在这里而不是在总结器里，是因为它必须跨进程重启活着：否则每次冷启动都从零
     * 开始数，画像就永远等不到那一轮。
     */
    suspend fun bumpTurn(): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = readState()
            val next = current.copy(profileTurnMark = current.profileTurnMark + 1)
            writeState(next)
            _summary.value = next
            next.profileTurnMark
        }
    }

    /** 记下一次成功的总结：时间更新，轮数归零。手改画像不走这里——那不是模型总结出来的。 */
    suspend fun markSummarized(at: Long) = withContext(Dispatchers.IO) {
        lock.withLock {
            val next = ProfileState(lastProfileAt = at, profileTurnMark = 0)
            writeState(next)
            _summary.value = next
        }
    }

    /** Replaces the whole profile. Refused — never truncated — past [MAX_LENGTH]. */
    suspend fun write(value: String): Boolean = withContext(Dispatchers.IO) {
        // A profile the user cannot read back in full is worse than one they have to
        // shorten. Callers can compare against [MAX_LENGTH] to say which limit was hit.
        if (value.length > MAX_LENGTH) return@withContext false
        lock.withLock {
            val ok = writeFile(value)
            if (ok) _text.value = value
            ok
        }
    }

    private fun read(): String {
        if (!file.exists()) return ""
        val text = runCatching { file.readText() }.getOrNull()
        // A plain-text file has no schema to fail against, so the two ways it can stop being
        // text are the two ways it can be unreadable: the bytes cannot be read at all, or
        // they are not UTF-8 (readText then hands back U+FFFD). Both used to read as "no
        // profile", and the next save then buried the original — keep it under a name that
        // can still be recovered by hand instead.
        if (text == null || text.contains(REPLACEMENT_CHAR)) {
            quarantine()
            return ""
        }
        return text
    }

    private fun writeFile(value: String): Boolean = writeAtomically(file, value)

    /**
     * 记账不是内容：读不出来就当成「还没总结过」。丢的只是计数与时间，[read] 那套隔离
     * 不能用在它身上——`profile.md` 一个字都不会因此动。
     */
    private fun readState(): ProfileState {
        if (!stateFile.exists()) return ProfileState()
        return runCatching { json.decodeFromString<ProfileState>(stateFile.readText()) }
            .getOrDefault(ProfileState())
    }

    private fun writeState(state: ProfileState) {
        writeAtomically(stateFile, json.encodeToString(ProfileState.serializer(), state))
    }

    private fun writeAtomically(target: File, value: String): Boolean = runCatching {
        target.parentFile?.mkdirs()
        // Write-then-rename, so a kill mid-save cannot leave a half-written file that [read]
        // would quarantine — the old profile survives instead.
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(value)
        if (!temp.renameTo(target)) {
            // renameTo reports failure by returning false, so fall back to a direct write;
            // its exception has to stay visible rather than lose the text.
            try {
                target.writeText(value)
            } finally {
                temp.delete()
            }
        }
    }.isSuccess

    /**
     * Keeps an unreadable file next to its own name (the same call [MemoryStore] makes for
     * memory.corrupt.json) instead of letting the next write bury it.
     */
    private fun quarantine() {
        runCatching { file.renameTo(File(file.parentFile, "profile.corrupt.md")) }
    }
}

/** What `File.readText()` leaves behind for bytes it cannot decode. */
private const val REPLACEMENT_CHAR = '\uFFFD'
