package com.suixing.agent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which endpoint + model one turn goes to. The order that wins is: the conversation's
 * own pick, then the assistant's pin, then the global default.
 */
class ChoiceForTest {

    private fun provider(id: String, url: String = "https://$id/v1", key: String = "key-$id") =
        ProviderConfig(id = id, name = id, baseUrl = url, apiKey = key, enabled = listOf("$id/model"))

    private fun assistant(providerId: String = "", model: String = "") =
        Assistant(id = "as", name = "助手", providerId = providerId, model = model)

    private fun settings(providers: List<ProviderConfig>) = AppSettings(
        baseUrl = "https://global/v1",
        apiKey = "global-key",
        model = "global-model",
        providers = providers,
    )

    @Test
    fun `the conversation's own pick beats the assistant pin`() {
        val choice = settings(listOf(provider("a"), provider("b")))
            .choiceFor("b", "b/picked", assistant(providerId = "a", model = "a/pinned"))
        assertEquals(LlmChoice("b", "https://b/v1", "key-b", "b/picked"), choice)
    }

    @Test
    fun `a conversation pointing at a provider that is gone falls back instead of blanking the url`() {
        val settings = settings(listOf(provider("a")))
        val pinned = settings.choiceFor("ghost", "ghost/model", assistant(providerId = "a", model = "a/pinned"))
        assertEquals(LlmChoice("a", "https://a/v1", "key-a", "a/pinned"), pinned)
        assertTrue(pinned.baseUrl.isNotBlank())
        val alone = settings.choiceFor("ghost", "ghost/model", null)
        assertEquals(LlmChoice(null, "https://global/v1", "global-key", "global-model"), alone)
        assertTrue(alone.baseUrl.isNotBlank())
    }

    @Test
    fun `a conversation that picked a provider but no model counts as not picked`() {
        val settings = settings(listOf(provider("a"), provider("b")))
        val pin = assistant(providerId = "b", model = "b/pinned")
        val expected = LlmChoice("b", "https://b/v1", "key-b", "b/pinned")
        assertEquals(expected, settings.choiceFor("a", null, pin))
        assertEquals(expected, settings.choiceFor("a", "", pin))
        assertEquals(expected, settings.choiceFor("a", "   ", pin))
    }

    @Test
    fun `a conversation that picked a model but no provider counts as not picked`() {
        val settings = settings(listOf(provider("a"), provider("b")))
        val pin = assistant(providerId = "b", model = "b/pinned")
        // A bare model id has no endpoint to travel to, so the safe fallback wins.
        assertEquals(LlmChoice("b", "https://b/v1", "key-b", "b/pinned"), settings.choiceFor(null, "b/loose", pin))
        assertEquals(LlmChoice("b", "https://b/v1", "key-b", "b/pinned"), settings.choiceFor("", "b/loose", pin))
        // Nothing pinned either: the loose id is dropped and the global triple stands.
        assertEquals(
            LlmChoice(null, "https://global/v1", "global-key", "global-model"),
            settings.choiceFor(null, "b/loose", null),
        )
    }

    @Test
    fun `a conversation keeps a model that its provider no longer lists`() {
        // enabled is only what the endpoint reported; a pick must not evaporate with it.
        val choice = settings(listOf(provider("a")))
            .choiceFor("a", "a/legacy", null)
        assertEquals(LlmChoice("a", "https://a/v1", "key-a", "a/legacy"), choice)
    }

    @Test
    fun `the assistant pin is used when the conversation never picked`() {
        val settings = settings(listOf(provider("a"), provider("b")))
        assertEquals(
            LlmChoice("b", "https://b/v1", "key-b", "b/pinned"),
            settings.choiceFor(null, null, assistant(providerId = "b", model = "b/pinned")),
        )
        // A pin that names only the endpoint keeps the global model id.
        assertEquals(
            LlmChoice("b", "https://b/v1", "key-b", "global-model"),
            settings.choiceFor(null, null, assistant(providerId = "b")),
        )
    }

    @Test
    fun `an assistant that only pins a model keeps the global endpoint`() {
        val choice = settings(listOf(provider("a")))
            .choiceFor(null, null, assistant(model = "x/9"))
        assertEquals(LlmChoice(null, "https://global/v1", "global-key", "x/9"), choice)
    }

    @Test
    fun `an assistant pinning a provider that is gone keeps its model on the global endpoint`() {
        val choice = settings(listOf(provider("a")))
            .choiceFor(null, null, assistant(providerId = "ghost", model = "ghost/model"))
        assertEquals(LlmChoice(null, "https://global/v1", "global-key", "ghost/model"), choice)
        assertTrue(choice.baseUrl.isNotBlank())
    }

    @Test
    fun `nothing picked anywhere falls back to the global triple`() {
        // The provider matches the global address, so the choice can name it.
        val choice = settings(listOf(provider("a", url = "https://global/v1/")))
            .choiceFor(null, null, null)
        assertEquals(LlmChoice("a", "https://global/v1", "global-key", "global-model"), choice)
    }

    @Test
    fun `no providers at all still resolves to the global triple`() {
        val settings = settings(emptyList())
        val expected = LlmChoice(null, "https://global/v1", "global-key", "global-model")
        assertEquals(expected, settings.choiceFor(null, null, null))
        assertEquals(expected, settings.choiceFor("ghost", "ghost/model", null))
        assertEquals(expected, settings.choiceFor("ghost", "ghost/model", assistant(providerId = "ghost")))
    }
}
