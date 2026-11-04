package com.suixing.agent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which endpoint + model one turn goes to.
 *
 * Two sources, in this order: the conversation's own pick, then the global default. 助手不再钉模型
 * （用户：模型只靠用户选、按会话隔离、最近一次选择成为新会话的默认），所以旧版的"人设固定模型"
 * 那一层连同它的用例一起删掉了——它不再是这条链路上的优先级。
 */
class ChoiceForTest {

    private fun provider(id: String, url: String = "https://$id/v1", key: String = "key-$id") =
        ProviderConfig(id = id, name = id, baseUrl = url, apiKey = key, enabled = listOf("$id/model"))

    private fun settings(providers: List<ProviderConfig>) = AppSettings(
        baseUrl = "https://global/v1",
        apiKey = "global-key",
        model = "global-model",
        providers = providers,
    )

    @Test
    fun `the conversation's own pick wins`() {
        val choice = settings(listOf(provider("a"), provider("b")))
            .choiceFor("b", "b/picked")
        assertEquals(LlmChoice("b", "https://b/v1", "key-b", "b/picked"), choice)
    }

    @Test
    fun `a conversation pointing at a provider that is gone falls back to the global triple`() {
        val settings = settings(listOf(provider("a")))
        val gone = settings.choiceFor("ghost", "ghost/model")
        assertEquals(LlmChoice(null, "https://global/v1", "global-key", "global-model"), gone)
        assertTrue(gone.baseUrl.isNotBlank())
    }

    @Test
    fun `a conversation that picked a provider but no model counts as not picked`() {
        val settings = settings(listOf(provider("a"), provider("b")))
        val expected = LlmChoice(null, "https://global/v1", "global-key", "global-model")
        assertEquals(expected, settings.choiceFor("a", null))
        assertEquals(expected, settings.choiceFor("a", ""))
        assertEquals(expected, settings.choiceFor("a", "   "))
    }

    @Test
    fun `a conversation that picked a model but no provider counts as not picked`() {
        val settings = settings(listOf(provider("a"), provider("b")))
        // A bare model id has no endpoint to travel to, so the global triple stands.
        assertEquals(
            LlmChoice(null, "https://global/v1", "global-key", "global-model"),
            settings.choiceFor(null, "b/loose"),
        )
        assertEquals(
            LlmChoice(null, "https://global/v1", "global-key", "global-model"),
            settings.choiceFor("", "b/loose"),
        )
    }

    @Test
    fun `a conversation keeps a model that its provider no longer lists`() {
        // enabled is only what the endpoint reported; a pick must not evaporate with it.
        val choice = settings(listOf(provider("a")))
            .choiceFor("a", "a/legacy")
        assertEquals(LlmChoice("a", "https://a/v1", "key-a", "a/legacy"), choice)
    }

    @Test
    fun `nothing picked falls back to the global triple`() {
        // The provider matches the global address, so the choice can name it.
        val choice = settings(listOf(provider("a", url = "https://global/v1/")))
            .choiceFor(null, null)
        assertEquals(LlmChoice("a", "https://global/v1", "global-key", "global-model"), choice)
    }

    @Test
    fun `no providers at all still resolves to the global triple`() {
        val settings = settings(emptyList())
        val expected = LlmChoice(null, "https://global/v1", "global-key", "global-model")
        assertEquals(expected, settings.choiceFor(null, null))
        assertEquals(expected, settings.choiceFor("ghost", "ghost/model"))
    }
}
