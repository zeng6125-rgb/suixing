package com.suixing.agent.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-conversation model choice is only *stored* here, never resolved: [Conversation]
 * just has to carry it. Two directions matter — a chat file written before the two fields
 * existed must still load with its history, and a chat that picked a model must still have
 * that model after a restart without touching any other chat's file.
 */
class ConversationModelChoiceTest {

    /** Exactly the configuration [ChatRepository] writes with. */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `a file written before the fields existed decodes with both left null`() {
        val legacy = """
            {"id":"old","title":"旧会话","createdAt":1,"updatedAt":2,"messages":[
            {"id":"m1","role":"user","text":"你好","model":"","promptTokens":0,
            "completionTokens":0,"reasoningTokens":0,"cachedTokens":0,"elapsedMs":0,
            "edited":false,"steer":false,"timestamp":2}],
            "assistantId":"a1","activeRuns":{}}
        """.trimIndent()
        val conversation = json.decodeFromString(Conversation.serializer(), legacy)
        assertNull(conversation.providerId)
        assertNull(conversation.model)
        // The rest of the file still loads: the new fields cost us no history.
        assertEquals("旧会话", conversation.title)
        assertEquals(listOf("你好"), conversation.messages.map { it.text })
    }

    @Test
    fun `a picked model reaches the file and comes back`() {
        val saved = Conversation(id = "c1", title = "会话", providerId = "p1", model = "m1")
        val text = json.encodeToString(Conversation.serializer(), saved)
        // The keys have to be in the file at all: a choice that only lived in memory would
        // look like it worked until the app restarts.
        assertTrue(text, text.contains("\"providerId\":\"p1\""))
        assertTrue(text, text.contains("\"model\":\"m1\""))

        val loaded = json.decodeFromString(Conversation.serializer(), text)
        assertEquals("p1", loaded.providerId)
        assertEquals("m1", loaded.model)
    }

    @Test
    fun `a conversation that never picked a model round-trips as unset`() {
        val text = json.encodeToString(Conversation.serializer(), Conversation(id = "c1", title = "会话"))
        val loaded = json.decodeFromString(Conversation.serializer(), text)
        assertNull(loaded.providerId)
        assertNull(loaded.model)
    }

    @Test
    fun `two conversations keep their own model`() {
        val a = Conversation(id = "a", title = "A", providerId = "pa", model = "ma")
        val b = Conversation(id = "b", title = "B", providerId = "pb", model = "mb")
        // One file each, exactly as ChatRepository stores them.
        val backA = json.decodeFromString(Conversation.serializer(), json.encodeToString(Conversation.serializer(), a))
        val backB = json.decodeFromString(Conversation.serializer(), json.encodeToString(Conversation.serializer(), b))
        assertEquals("pa", backA.providerId)
        assertEquals("ma", backA.model)
        assertEquals("pb", backB.providerId)
        assertEquals("mb", backB.model)
    }
}
