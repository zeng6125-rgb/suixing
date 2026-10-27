package com.suixing.agent.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** UI-only bookmark. It never participates in chat-log or live-draft persistence. */
@Serializable
internal data class ViewportBookmark(
    val conversationId: String,
    val rowKey: String? = null,
    val messageId: String? = null,
    val runId: String? = null,
    val nearbyMessageIds: List<String> = emptyList(),
    val offset: Int = 0,
    val followLatest: Boolean = true,
    val shownTurns: Int = 12,
    val totalTurns: Int = 0,
    val width: Int = 0,
    val fontScale: Float = 1f,
)

internal class ConversationViewports(
    private val preferences: DataStore<Preferences>,
    scope: CoroutineScope,
) {
    private sealed interface Write {
        data class Save(val bookmark: ViewportBookmark) : Write
        data class Delete(val id: String) : Write
    }

    private val bookmarks = ConcurrentHashMap<String, ViewportBookmark>()
    private val writes = Channel<Write>(Channel.UNLIMITED)
    private val lock = Any()
    private val deleted = HashSet<String>()
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            val saved = try {
                preferences.data.first().asMap().mapNotNull { (key, value) ->
                    if (!key.name.startsWith(PREFIX) || value !is String) null
                    else runCatching { json.decodeFromString<ViewportBookmark>(value) }.getOrNull()
                }
            } catch (failure: IOException) {
                android.util.Log.w("suixing-viewport", "cannot load reading positions", failure)
                emptyList()
            }
            synchronized(lock) {
                // UI waits for ready; deletion events received during loading remain queued.
                saved.filterNot { it.conversationId in deleted }
                    .forEach { bookmarks.putIfAbsent(it.conversationId, it) }
                _ready.value = true
            }
            for (write in writes) {
                try {
                    preferences.edit { values ->
                        when (write) {
                            is Write.Save -> values[stringPreferencesKey(PREFIX + write.bookmark.conversationId)] =
                                json.encodeToString(ViewportBookmark.serializer(), write.bookmark)
                            is Write.Delete -> values.remove(stringPreferencesKey(PREFIX + write.id))
                        }
                    }
                } catch (failure: IOException) {
                    // In-process navigation retains its latest bookmark even if storage is unavailable.
                    android.util.Log.w("suixing-viewport", "cannot save reading position", failure)
                }
            }
        }
    }

    fun get(id: String): ViewportBookmark? = bookmarks[id]

    fun save(bookmark: ViewportBookmark) {
        if (bookmark.conversationId.isBlank()) return
        synchronized(lock) {
            if (bookmark.conversationId in deleted) return
            if (bookmarks.put(bookmark.conversationId, bookmark) != bookmark) {
                writes.trySend(Write.Save(bookmark))
            }
        }
    }

    fun forget(id: String) {
        synchronized(lock) {
            deleted.add(id)
            bookmarks.remove(id)
            writes.trySend(Write.Delete(id))
        }
    }

    companion object {
        private const val PREFIX = "position/"
        private val json = Json { ignoreUnknownKeys = true }

        fun create(context: Context, scope: CoroutineScope) = ConversationViewports(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
                produceFile = { context.preferencesDataStoreFile("Suixing_conversation_viewports") },
            ),
            scope,
        )
    }
}
