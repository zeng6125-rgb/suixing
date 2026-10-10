package com.suixing.agent.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens to a gallery photo on its way into an assistant's avatar.
 *
 * Two halves, and both have bitten a phone camera roll: the decode has to throw pixels away
 * *before* it allocates them, and the result has to be capped without distorting the face.
 * The arithmetic is what this file pins down; the encode itself needs a device.
 */
class AssistantAvatarSizingTest {

    // ------------------------------------------------------------------ the cap

    @Test
    fun `a landscape photo is capped on its long side`() {
        assertEquals(256 to 192, avatarSize(4000, 3000))
    }

    @Test
    fun `a portrait photo is capped on its long side`() {
        assertEquals(192 to 256, avatarSize(3000, 4000))
    }

    @Test
    fun `a square photo stays square`() {
        assertEquals(256 to 256, avatarSize(1000, 1000))
    }

    @Test
    fun `a picture already small enough is left alone`() {
        assertEquals(120 to 90, avatarSize(120, 90))
        assertEquals(256 to 100, avatarSize(256, 100))
    }

    /** A panorama is still a picture: neither side may be rounded away to zero. */
    @Test
    fun `a very wide picture keeps a pixel of height`() {
        val (width, height) = avatarSize(6000, 30)
        assertEquals(256, width)
        assertTrue("height was $height", height >= 1)
    }

    @Test
    fun `nonsense dimensions do not produce an empty bitmap`() {
        assertEquals(1 to 1, avatarSize(0, 0))
        assertEquals(1 to 1, avatarSize(-5, 100))
    }

    // ------------------------------------------------------- the decode's sampling

    /**
     * The sampled decode has to stay *at or above* the cap: sampling is the cheap half of the
     * shrink, and a sample that lands under 256 would mean upscaling it back, which loses more
     * than it saves.
     */
    @Test
    fun `sampling never lands under the cap`() {
        listOf(4000 to 3000, 1080 to 1920, 800 to 800, 1024 to 768).forEach { (width, height) ->
            val sample = avatarSampleSize(width, height)
            val longest = maxOf(width, height) / sample
            assertTrue("$width x $height sampled by $sample gave $longest", longest >= AVATAR_MAX_PX)
        }
    }

    /** And it is the *largest* such power of two, so a 4000px photo is not decoded at 250px. */
    @Test
    fun `sampling takes the largest power of two that still fits`() {
        assertEquals(8, avatarSampleSize(4000, 3000))
        assertEquals(4, avatarSampleSize(1080, 1920))
        assertEquals(2, avatarSampleSize(800, 800))
        // 512/2 is exactly the cap, so the sample may take that step and no more.
        assertEquals(2, avatarSampleSize(512, 512))
    }

    @Test
    fun `a picture that needs no sampling is not sampled`() {
        assertEquals(1, avatarSampleSize(256, 256))
        assertEquals(1, avatarSampleSize(300, 200))
        assertEquals(1, avatarSampleSize(0, 0))
    }

    /** Sampling then capping lands exactly on the cap for a square source. */
    @Test
    fun `sample and cap agree on the stored size`() {
        val sample = avatarSampleSize(4000, 4000)
        val sampled = 4000 / sample
        assertEquals(256 to 256, avatarSize(sampled, sampled))
    }

    // ------------------------------------------------------------- the JSON

    /**
     * An assistant saved by an older build has no `avatarPath` key at all, and it must still
     * load — the field is an addition, not a migration.
     */
    @Test
    fun `an assistant without the avatar key still decodes`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = """{"id":"default","name":"随行","avatar":"🪶","description":"全能手机智能体"}"""
        val assistant = json.decodeFromString(Assistant.serializer(), old)
        assertEquals("default", assistant.id)
        assertEquals("🪶", assistant.face)
        assertEquals("", assistant.avatarPath)
    }
}
