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
import java.util.UUID

/**
 * `<chatId>.live.json` — a draft beside its conversation, never inside it. Top-level because
 * every listing of `filesDir/chats` has to skip it: it ends in `.json`, exactly like a
 * transcript does.
 */
internal const val LIVE_SUFFIX = ".live.json"

/** Conversations are stored one JSON file per conversation under filesDir/chats. */
class ChatRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dir: File get() = File(context.filesDir, "chats").apply { if (!exists()) mkdirs() }
    private val lock = Mutex()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        lock.withLock {
            // A live draft is not a conversation: it sits in the same directory and also ends
            // in `.json`, so it has to be excluded by name or it would show up as a chat.
            val all = dir.listFiles { f -> f.extension == "json" && !f.name.endsWith(LIVE_SUFFIX) }
                ?.mapNotNull { file ->
                    runCatching { json.decodeFromString<Conversation>(file.readText()) }
                        // Skipping a file that no longer parses made the chat look deleted
                        // while its bytes were still on disk, with nothing left to recover.
                        .onFailure { quarantine(file) }
                        .getOrNull()
                }
                ?.sortedByDescending { it.updatedAt }
                ?: emptyList()
            _conversations.value = all
        }
    }

    suspend fun create(title: String = "新会话"): Conversation = withContext(Dispatchers.IO) {
        val conversation = Conversation(id = UUID.randomUUID().toString(), title = title)
        write(conversation)
        _conversations.value = listOf(conversation) + _conversations.value
        conversation
    }

    suspend fun save(conversation: Conversation) = withContext(Dispatchers.IO) {
        // Two saves for one chat really do overlap: steering saves from its own
        // coroutine while the run's finally saves the finished turn. Without the lock
        // the later write can be the older snapshot, and two truncating writes can
        // interleave into a file that no longer parses.
        lock.withLock {
            val stamped = conversation.copy(updatedAt = System.currentTimeMillis())
            write(stamped)
            val rest = _conversations.value.filterNot { it.id == stamped.id }
            _conversations.value = (listOf(stamped) + rest).sortedByDescending { it.updatedAt }
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            File(dir, "$id.json").delete()
            // A deleted chat's draft is garbage nobody will ever read: the conversation it
            // belongs to is gone from the list, so nothing will look for it again.
            liveFile(id).delete()
            // A marker pointing at a chat that no longer exists would only send the next
            // launch down the fallback path anyway, so it is cleared rather than left lying.
            if (readLastOpenedMarker() == id) lastOpenedFile.delete()
        }
        _conversations.value = _conversations.value.filterNot { it.id == id }
    }

    /**
     * Records the answer being streamed right now, so a run that is killed — by the system,
     * by a crash, by a restart — still leaves its half-finished text on disk. It goes to its
     * own file instead of into the conversation because the transcript is only written when a
     * run ends: a draft kept there would be lost exactly when it is needed.
     */
    suspend fun saveLive(draft: LiveDraft) = withContext(Dispatchers.IO) {
        lock.withLock {
            writeAtomically(liveFile(draft.chatId), json.encodeToString(LiveDraft.serializer(), draft))
        }
    }

    /**
     * The draft a killed run left behind, or null when there is none. A draft that does not
     * parse is deleted rather than quarantined: unlike a transcript there is nothing in it
     * worth recovering by hand, and keeping it would mean failing on it at every launch.
     */
    suspend fun readLive(chatId: String): LiveDraft? = withContext(Dispatchers.IO) {
        val file = liveFile(chatId)
        if (!file.exists()) return@withContext null
        runCatching { json.decodeFromString(LiveDraft.serializer(), file.readText()) }
            .getOrElse {
                file.delete()
                null
            }
    }

    /** Called once the draft has been appended to the transcript, so it is not read twice. */
    suspend fun clearLive(chatId: String) {
        withContext(Dispatchers.IO) { lock.withLock { liveFile(chatId).delete() } }
    }

    /**
     * Remembers which conversation was on screen, so the next launch opens that one instead of
     * whichever chat was written most recently — a chat the user only read has no reason to be
     * first, and re-saving it just to move it up the list would rewrite megabytes on every
     * switch.
     *
     * The marker is a bare id in a file with no extension, never JSON: [load] picks files out
     * of this directory by extension, and a dotless name has no extension to match, so this
     * can never be read back as a conversation.
     */
    suspend fun saveLastOpened(chatId: String) = withContext(Dispatchers.IO) {
        val id = chatId.trim()
        lock.withLock {
            if (id.isEmpty()) {
                lastOpenedFile.delete()
            } else {
                writeAtomically(lastOpenedFile, id)
            }
        }
    }

    /** The conversation that was on screen last, or null when there is none to restore. */
    suspend fun readLastOpened(): String? = withContext(Dispatchers.IO) {
        val id = readLastOpenedMarker()
        // Missing, empty and unreadable all mean the same thing: nothing to restore. The
        // marker holds no history worth rescuing, so it goes rather than being re-reported
        // at every launch.
        if (id == null) lastOpenedFile.delete()
        id
    }

    private fun readLastOpenedMarker(): String? =
        runCatching { lastOpenedFile.takeIf { it.exists() }?.readText()?.trim() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }

    private fun liveFile(chatId: String): File = File(dir, "$chatId$LIVE_SUFFIX")

    /**
     * `filesDir/chats/last-opened` — which conversation was on screen when the app went away.
     * No extension on purpose; see [saveLastOpened].
     */
    private val lastOpenedFile: File get() = File(dir, "last-opened")

    private fun write(conversation: Conversation) {
        val target = File(dir, "${conversation.id}.json")
        writeAtomically(target, json.encodeToString(Conversation.serializer(), conversation))
    }

    /**
     * Writes to a temp file in the same directory, then renames it over the real one.
     * A rename is atomic, so being killed mid-save leaves the previous file intact
     * instead of a half-written file that [load] cannot parse. The temp name ends in
     * `.tmp` on purpose: [load] only picks up `*.json` (and skips [LIVE_SUFFIX]).
     */
    private fun writeAtomically(target: File, text: String) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            // renameTo reports failure by returning false instead of throwing. Fall back
            // to a direct write and let its exception surface: a silently swallowed save
            // is how a finished turn disappears from disk.
            try {
                target.writeText(text)
            } finally {
                temp.delete()
            }
        }
    }

    /**
     * Keeps an unreadable transcript as `<id>.json.corrupt` rather than skipping it
     * (the same call McpStore makes for mcp.corrupt.json): the bytes stay recoverable
     * by hand, and the file no longer shows up in [load]'s listing.
     */
    private fun quarantine(file: File) {
        runCatching { file.renameTo(File(dir, "${file.name}.corrupt")) }
    }
}

/**
 * The answer a run was still streaming when the process died.
 *
 * Written while the answer streams — not at the end of the run — and read back on the next
 * launch: the caller appends [text] to the transcript as an assistant message of [runId] and
 * then clears it, so a kill mid-answer costs the user the last tick instead of the whole
 * answer — the failure they kept seeing as "only my question was saved".
 */
@Serializable
data class LiveDraft(
    val chatId: String,
    val runId: String,
    /** When the draft was last written, in milliseconds. */
    val at: Long,
    val text: String,
)
