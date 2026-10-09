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
    fun `typing a provider name is not an edit, while its switches still are`() {
        val a = provider("a")
        val disk = stored(a)

        // Names are written through a beat after typing stops. Counting them lit 「有未保存的改动」
        // on every keystroke, and that subtitle appearing and disappearing is what shook the title.
        assertFalse(
            "a typed name must not flip the dirty flag",
            disk.copy(providers = listOf(a.copy(name = "改了一半的名字"))).differsOutsideEndpoint(disk),
        )
        assertTrue(
            "a switch outside the name still counts",
            disk.copy(allowInsecureTls = !disk.allowInsecureTls).differsOutsideEndpoint(disk),
        )
    }
}
