package com.suixing.agent.ui.settings

import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two field regressions behind these rules: a freshly seeded draft counted as an edit
 * ("未保存" shown with nothing typed), and a name typed for one provider landed on another.
 * The functions live in this package as `internal`, so the test sits here too.
 */
class ModelDraftTest {

    private fun provider(
        id: String,
        name: String = id,
        baseUrl: String = "https://$id/v1",
        apiKey: String = "key-$id",
        enabled: List<String> = listOf("$id/model"),
    ) = ProviderConfig(id = id, name = name, baseUrl = baseUrl, apiKey = apiKey, enabled = enabled)

    /** A stored settings whose live endpoint is [provider]'s. */
    private fun stored(provider: ProviderConfig, vararg others: ProviderConfig) = AppSettings(
        baseUrl = provider.baseUrl,
        apiKey = provider.apiKey,
        model = provider.enabled.firstOrNull() ?: "picked/model",
        providers = listOf(provider) + others,
    )

    /** What a chip tap writes into the draft: that provider's endpoint, nothing else. */
    private fun AppSettings.switchedTo(provider: ProviderConfig) = copy(
        baseUrl = provider.baseUrl,
        apiKey = provider.apiKey,
        model = provider.enabled.firstOrNull() ?: model,
    )

    @Test
    fun `a freshly seeded draft is not an edit even when its model is not the provider's first`() {
        val a = provider("a", enabled = listOf("a/one", "a/two"))
        // The live model is the provider's second enabled one: seeding must not read as an edit.
        val disk = stored(a).copy(model = "a/two")
        val form = disk

        assertTrue("the case only bites when the model is not the first", form.model != a.enabled.first())
        assertFalse("an untouched draft is not an edit", endpointHandEdited(form, disk, a))
    }

    @Test
    fun `a chip tap is not an edit`() {
        val a = provider("a")
        val b = provider("b")
        val disk = stored(a, b)
        val form = disk.switchedTo(b)

        assertTrue("the switch really moved the triple", form.baseUrl != disk.baseUrl)
        assertFalse("tapping a chip writes no edit", endpointHandEdited(form, disk, b))
    }

    @Test
    fun `a hand edit to any of the three endpoint fields is an edit`() {
        val a = provider("a")
        val b = provider("b")
        val disk = stored(a, b)
        val switched = disk.switchedTo(b)

        assertTrue("address", endpointHandEdited(switched.copy(baseUrl = "https://typed/v1"), disk, b))
        assertTrue("key", endpointHandEdited(switched.copy(apiKey = "typed-key"), disk, b))
        assertTrue("model", endpointHandEdited(switched.copy(model = "typed/model"), disk, b))
    }

    @Test
    fun `only the endpoint triple is forgiven, other fields still count`() {
        val disk = stored(provider("a"))
        val switched = disk.copy(baseUrl = "https://b/v1", apiKey = "key-b", model = "b/model")

        assertFalse("the triple alone is not a difference", switched.differsOutsideEndpoint(disk))
        assertTrue("a switch is not a field edit", disk.copy(allowInsecureTls = true).differsOutsideEndpoint(disk))
        assertTrue("nor is a slider", disk.copy(maxToolRounds = 7).differsOutsideEndpoint(disk))
    }

    @Test
    fun `mapping one provider leaves every other provider untouched, and an absent id changes nothing`() {
        val a = provider("a", name = "甲", baseUrl = "https://a/v1", apiKey = "key-a")
        val b = provider("b", name = "乙", baseUrl = "https://b/v1", apiKey = "key-b")
        val c = provider("c", name = "丙", baseUrl = "https://c/v1", apiKey = "key-c")
        val before = stored(a, b, c)

        val after = before.mapProvider("b") { it.copy(name = "乙改", baseUrl = "https://b2/v1", apiKey = "key-b2") }

        assertEquals("the addressed provider takes the edit", "乙改", after.providers[1].name)
        assertEquals("https://b2/v1", after.providers[1].baseUrl)
        assertEquals("key-b2", after.providers[1].apiKey)
        assertEquals("its neighbour must not take the name", a, after.providers[0])
        assertEquals("nor must the one after it", c, after.providers[2])
        assertEquals("the rest of the settings is untouched", before.copy(providers = after.providers), after)

        val block = { it: ProviderConfig -> it.copy(name = "改") }
        assertSame("no id at all is a no-op", before, before.mapProvider(null, block))
        assertSame("an id nobody has is a no-op", before, before.mapProvider("ghost", block))
        assertEquals("and neither call rewrote anything", listOf("a", "b", "c"), before.providers.map { it.id })
    }

    @Test
    fun `a blank address keeps the live endpoint, a typed one is written through`() {
        val live = AppSettings(baseUrl = "https://live/v1", apiKey = "live-key", model = "live/model")
        val b = provider("b")
        val blank = AppSettings(
            baseUrl = "   ",
            apiKey = "typed-key",
            model = "typed/model",
            providers = listOf(b),
            allowInsecureTls = true,
        )

        val saved = blank.forSaving(live)

        assertEquals("a blank address must not blank the endpoint", "https://live/v1", saved.baseUrl)
        assertEquals("live-key", saved.apiKey)
        assertEquals("live/model", saved.model)
        assertEquals("the rest of the draft still saves", listOf(b), saved.providers)
        assertTrue("including its switches", saved.allowInsecureTls)

        val typed = live.copy(baseUrl = "https://typed/v1", apiKey = "typed-key", model = "typed/model")
        assertSame("a filled draft is written through unchanged", typed, typed.forSaving(live))
    }

    @Test
    fun `the save button is quiet after a chip tap, loud after a hand edit, quiet again after a save`() {
        val a = provider("a")
        val b = provider("b")
        val disk = stored(a, b)
        val seeded = true
        fun dirty(form: AppSettings) = seeded && (endpointHandEdited(form, disk, b) || form.differsOutsideEndpoint(disk))

        assertFalse("a chip tap alone leaves the button quiet", dirty(disk.switchedTo(b)))
        assertTrue("a hand edit outside the triple lights it", dirty(disk.switchedTo(b).copy(maxToolRounds = 3)))
        assertTrue("a hand edit to the triple lights it too", dirty(disk.switchedTo(b).copy(apiKey = "typed")))
        assertFalse("and saving makes it quiet again", dirty(disk))
    }

    @Test
    fun `provider names and switches both require explicit saving`() {
        val a = provider("a")
        val disk = stored(a)

        assertTrue(
            "a typed name is a draft until explicitly saved",
            disk.copy(providers = listOf(a.copy(name = "改了一半的名字"))).differsOutsideEndpoint(disk),
        )
        assertTrue(
            "a switch outside the name still counts",
            disk.copy(allowInsecureTls = !disk.allowInsecureTls).differsOutsideEndpoint(disk),
        )
    }
    @Test fun `leaving removes only uncommitted suppliers and restores the saved endpoint`() {
        val saved = stored(provider("a"))
        val draft = provider("new", baseUrl = "http://lan:8000/v1", enabled = emptyList())
        val form = saved.copy(providers = saved.providers + draft, baseUrl = draft.baseUrl,
            apiKey = draft.apiKey, model = "", protocol = "anthropic")
        val kept = form.discardUncommittedProviders(saved, draft.id)
        assertEquals(saved, kept)
        assertFalse(kept.differsOutsideEndpoint(saved))
    }

    @Test fun `discarding a new supplier retains edits to existing suppliers and generation parameters`() {
        val saved = stored(provider("a"), provider("b"))
        val draft = provider("new")
        val form = saved.copy(providers = saved.providers.map { if (it.id == "a") it.copy(name = "重命名") else it } + draft,
            baseUrl = draft.baseUrl, apiKey = draft.apiKey, model = draft.enabled.first(), maxToolRounds = 9)
        val kept = form.discardUncommittedProviders(saved, draft.id)
        assertEquals(listOf("a", "b"), kept.providers.map { it.id })
        assertEquals("重命名", kept.providers.first().name)
        assertEquals(9, kept.maxToolRounds)
        assertTrue(kept.differsOutsideEndpoint(saved))
    }

    @Test fun `a supplier remains after it has been explicitly saved`() {
        val saved = stored(provider("a"), provider("b"))
        assertSame(saved, saved.discardUncommittedProviders(saved, "b"))
    }

    @Test fun `discarding supplier limits removes orphan edits but retains shared model limits`() {
        val a = provider("a", enabled = listOf("shared"))
        val saved = stored(a).copy(modelContexts = mapOf("historic" to 2048))
        val b = provider("b", enabled = listOf("shared", "new", "historic"))
        val form = saved.copy(providers = listOf(a, b),
            modelContexts = mapOf("shared" to 32000, "new" to 128000, "historic" to 4096),
            modelMaxTokens = mapOf("new" to 8192, "shared" to 1024))
        val result = form.discardUncommittedProviders(saved, "b")
        assertEquals(mapOf("shared" to 32000, "historic" to 2048), result.modelContexts)
        assertEquals(mapOf("shared" to 1024), result.modelMaxTokens)
    }

    @Test fun `saving prunes an unnamed supplier without clearing the live connection`() {
        val saved = stored(provider("a"))
        val draft = saved.copy(providers = saved.providers + provider("bad", name = ""))
        val result = draft.forSaving(saved)
        assertEquals(saved.providers, result.providers)
        assertEquals(saved.baseUrl, result.baseUrl)
        assertEquals(saved.apiKey, result.apiKey)
        assertEquals(saved.model, result.model)
    }

}
