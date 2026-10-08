package com.suixing.agent.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Conversations are stored one JSON file per conversation under filesDir/chats. */
class ChatRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dir: File get() = File(context.filesDir, "chats").apply { if (!exists()) mkdirs() }
    private val lock = Mutex()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        lock.withLock {
            val all = dir.listFiles { f -> f.extension == "json" }
                ?.mapNotNull { file ->
                    runCatching { json.decodeFromString<Conversation>(file.readText()) }.getOrNull()
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
        val stamped = conversation.copy(updatedAt = System.currentTimeMillis())
        write(stamped)
        val rest = _conversations.value.filterNot { it.id == stamped.id }
        _conversations.value = (listOf(stamped) + rest).sortedByDescending { it.updatedAt }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        lock.withLock { File(dir, "$id.json").delete() }
        _conversations.value = _conversations.value.filterNot { it.id == id }
    }

    private fun write(conversation: Conversation) {
        File(dir, "${conversation.id}.json").writeText(json.encodeToString(Conversation.serializer(), conversation))
    }
}
