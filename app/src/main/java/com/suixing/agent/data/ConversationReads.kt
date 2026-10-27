package com.suixing.agent.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal sealed interface ConversationReadEvent {
    data class Viewing(val chatId: String?) : ConversationReadEvent
    data class Completed(val chatId: String) : ConversationReadEvent
    data class Deleted(val chatId: String) : ConversationReadEvent
}

/** Only real completion events create badges; reopening the app never scans old terminal states. */
internal data class ConversationReadState(
    val unread: Set<String> = emptySet(),
    val viewing: String? = null,
) {
    fun accept(event: ConversationReadEvent): ConversationReadState = when (event) {
        is ConversationReadEvent.Viewing -> {
            val id = event.chatId?.takeIf { it.isNotBlank() }
            copy(viewing = id, unread = if (id == null) unread else unread - id)
        }
        is ConversationReadEvent.Completed -> if (event.chatId.isBlank() || event.chatId == viewing) this
            else copy(unread = unread + event.chatId)
        is ConversationReadEvent.Deleted -> copy(
            unread = unread - event.chatId,
            viewing = viewing.takeUnless { it == event.chatId },
        )
    }
}

/** Completion/read events are serialized with startup loading and persistence, off the UI thread. */
internal class ConversationReads(
    private val preferences: DataStore<Preferences>,
    scope: CoroutineScope,
) {
    private val events = Channel<ConversationReadEvent>(Channel.UNLIMITED)
    private val _unread = MutableStateFlow<Set<String>>(emptySet())
    val unread = _unread.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            val saved = try {
                preferences.data.first()[UNREAD].orEmpty().toSet()
            } catch (failure: IOException) {
                android.util.Log.w("suixing-chat-read", "cannot load completion badges", failure)
                emptySet()
            }
            var state = ConversationReadState(unread = saved)
            _unread.value = state.unread
            for (event in events) {
                val next = state.accept(event)
                val changed = next.unread != state.unread
                state = next
                if (!changed) continue
                _unread.value = state.unread
                try {
                    preferences.edit { it[UNREAD] = state.unread }
                } catch (failure: IOException) {
                    // Badges can keep working in memory without affecting the transcript or run.
                    android.util.Log.w("suixing-chat-read", "cannot save completion badges", failure)
                }
            }
        }
    }

    fun viewing(chatId: String?) { events.trySend(ConversationReadEvent.Viewing(chatId)) }
    fun completed(chatId: String) { events.trySend(ConversationReadEvent.Completed(chatId)) }
    fun forget(chatId: String) { events.trySend(ConversationReadEvent.Deleted(chatId)) }

    companion object {
        private val UNREAD = stringSetPreferencesKey("completed_unread")

        fun create(context: Context, scope: CoroutineScope): ConversationReads = ConversationReads(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
                produceFile = { context.preferencesDataStoreFile("Suixing_conversation_reads") },
            ),
            scope,
        )
    }
}
