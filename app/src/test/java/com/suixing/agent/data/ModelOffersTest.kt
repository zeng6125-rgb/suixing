package com.suixing.agent.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the chat's model switcher offers.
 *
 * **只有已启用的模型**（用户：「没在启用的模型的列表，为什么会在分组里面显示，而且还会默认被
 * 选中」）。这里原来还有一组用例专门钉住"把当前模型强行塞进某个分组"的旧行为；那正是未启用模型
 * 混进列表、并显示为选中的来源，已JAgent为一起删除。想让它出现在切换器里，就在模型页把它勾上。
 */
class ModelOffersTest {

    private fun provider(id: String, vararg enabled: String) =
        ProviderConfig(id = id, name = id, baseUrl = "https://$id/v1", enabled = enabled.toList())

    @Test
    fun `every endpoint with ticked models is offered in the user's order`() {
        val a = provider("a", "a/1", "a/2")
        val b = provider("b", "b/1")
        val offers = modelOffers(listOf(a, b), "a/1", a)
        assertEquals(listOf("a", "b"), offers.map { it.first.id })
        assertEquals(listOf("a/1", "a/2"), offers[0].second)
        assertEquals(listOf("b/1"), offers[1].second)
    }

    @Test
    fun `a model already ticked somewhere is not listed a second time`() {
        val a = provider("a", "a/1", "a/2")
        val b = provider("b", "b/1")
        val offers = modelOffers(listOf(a, b), "b/1", b)
        assertEquals(listOf("a", "b"), offers.map { it.first.id })
        assertEquals(listOf("b/1"), offers.first { it.first.id == "b" }.second)
        assertEquals(listOf("a/1", "a/2"), offers.first { it.first.id == "a" }.second)
        assertEquals(1, offers.sumOf { (_, ids) -> ids.count { it == "b/1" } })
    }

    @Test
    fun `a model that is not enabled is never offered`() {
        val a = provider("a", "a/1")
        val b = provider("b", "b/1", "b/2")
        val offers = modelOffers(listOf(a, b), "b/9", b)
        assertEquals(listOf("a", "b"), offers.map { it.first.id })
        // b/9 is the current model but nobody ticked it: it must not appear at all.
        assertEquals(listOf("b/1", "b/2"), offers.first { it.first.id == "b" }.second)
        assertEquals(listOf("a/1"), offers.first { it.first.id == "a" }.second)
    }

    @Test
    fun `a disabled model is not resurrected by being the current one`() {
        val a = provider("a", "m1")
        val offers = modelOffers(listOf(a), "m2", a)
        assertEquals(listOf("m1"), offers.single().second)
    }

    @Test
    fun `an endpoint with nothing ticked offers no group at all`() {
        val a = provider("a", "a/1")
        val b = provider("b")
        val offers = modelOffers(listOf(a, b), "b/7", b)
        assertEquals(listOf("a"), offers.map { it.first.id })
        assertEquals(listOf("a/1"), offers.single().second)
    }

    @Test
    fun `no endpoints means nothing to offer`() {
        assertEquals(emptyList<Pair<ProviderConfig, List<String>>>(), modelOffers(emptyList(), "m1", null))
    }
}
