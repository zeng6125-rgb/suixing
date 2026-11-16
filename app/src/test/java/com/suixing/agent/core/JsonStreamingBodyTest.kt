package com.suixing.agent.core

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class JsonStreamingBodyTest {
    @Before fun setUp() {
        mockkObject(NativeCore, NativeJsonDocument.Companion)
    }

    @After fun tearDown() {
        unmockkObject(NativeCore, NativeJsonDocument.Companion)
    }

    @Test fun `native uploads retain prepared content across repeated writes`() {
        uploads(native = true)
    }

    @Test fun `fallback uploads retain the fixed snapshot across repeated writes`() {
        uploads(native = false)
    }

    private fun uploads(native: Boolean) {
        val parts = WireStringParts()
        val list = mutableListOf<JsonElement>(JsonPrimitive("first"), JsonPrimitive("中文😀"))
        val values = linkedMapOf<String, JsonElement>(
            "text" to JsonPrimitive("before\n\"\\"),
            "items" to JsonArray(list),
            "image" to parts.imageDataUri("QUJD=="),
        )
        val expected = encoded(JsonObject(values), parts)
        every { NativeCore.available } returns native
        every { NativeJsonDocument.prepare(any(), any(), any()) } answers {
            if (native) {
                val bytes = encoded(firstArg(), secondArg())
                assertEquals(expected, bytes)
                prepared(bytes)
            } else null
        }
        val body = JsonStreamingBody(JsonObject(values), parts)
        assertEquals(expected.toByteArray(Charsets.UTF_8).size.toLong(), body.contentLength())
        assertEquals(expected.length.toLong(), body.characterLength)
        values["text"] = JsonPrimitive("after")
        list[0] = JsonPrimitive("changed")
        parts.imageDataUri("another image")

        repeat(2) {
            val buffer = Buffer()
            body.writeTo(buffer)
            assertEquals(expected, buffer.readUtf8())
        }
        verify(exactly = 1) { NativeJsonDocument.prepare(any(), any(), any()) }
    }

    @Test fun `native write failures propagate and the prepared body remains repeatable`() {
        every { NativeCore.available } returns true
        val native = prepared("\"before\"")
        var writes = 0
        every { native.write(any(), any()) } answers {
            if (++writes == 1) throw IOException("upload stopped")
            val bytes = "\"before\"".toByteArray(Charsets.UTF_8)
            firstArg<NativeJsonSink>().writeBytes(bytes, bytes.size)
        }
        every { NativeJsonDocument.prepare(any(), any(), any()) } returns native
        val body = JsonStreamingBody(JsonPrimitive("before"))
        assertEquals(8L, body.contentLength())
        val error = assertThrows(IOException::class.java) { body.writeTo(Buffer()) }
        assertEquals("upload stopped", error.message)
        val buffer = Buffer()
        body.writeTo(buffer)
        assertEquals("\"before\"", buffer.readUtf8())
        verify(exactly = 1) { NativeJsonDocument.prepare(any(), any(), any()) }
    }

    @Test fun `cancellation still stops uploads after native preparation`() {
        every { NativeCore.available } returns true
        val native = prepared("\"before\"")
        every { NativeJsonDocument.prepare(any(), any(), any()) } returns native
        val job = Job()
        val body = JsonStreamingBody(JsonPrimitive("before"), context = job)
        assertEquals(8L, body.contentLength())
        job.cancel()
        val error = assertThrows(IOException::class.java) { body.writeTo(Buffer()) }
        assertEquals("Request upload cancelled", error.message)
        verify(exactly = 0) { native.write(any(), any()) }
    }

    private fun prepared(encoded: String): NativeJsonDocument = mockk<NativeJsonDocument>().also { native ->
        val bytes = encoded.toByteArray(Charsets.UTF_8)
        every { native.byteLength } returns bytes.size.toLong()
        every { native.characterLength } returns encoded.length.toLong()
        every { native.write(any(), any()) } answers {
            firstArg<NativeJsonSink>().writeBytes(bytes, bytes.size)
        }
    }

    private fun encoded(document: JsonObject, parts: WireStringParts): String = buildJsonObject {
        for ((key, value) in document) {
            put(key, if (key == "image") JsonPrimitive(parts.get(value as JsonPrimitive)!!.joinToString("")) else value)
        }
    }.toString()
}
