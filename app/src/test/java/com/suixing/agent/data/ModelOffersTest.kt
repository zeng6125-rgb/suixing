package com.suixing.agent.data

import org.junit.Assert.assertEquals
import org.junit.Test

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
    fun `a model already ticked somewhere is not appended a second time`() {
        val a = provider("a", "a/1", "a/2")
        val b = provider("b", "b/1")
        val offers = modelOffers(listOf(a, b), "b/1", b)
        assertEquals(listOf("a", "b"), offers.map { it.first.id })
        assertEquals(listOf("b/1"), offers.first { it.first.id == "b" }.second)
        assertEquals(listOf("a/1", "a/2"), offers.first { it.first.id == "a" }.second)
        // The model in use appears exactly once across the whole picker.
        assertEquals(1, offers.sumOf { (_, ids) -> ids.count { it == "b/1" } })
    }

    @Test
    fun `a model missing from every list is appended to the active endpoint`() {
        val a = provider("a", "a/1")
        val b = provider("b", "b/1", "b/2")
        val offers = modelOffers(listOf(a, b), "b/9", b)
        assertEquals(listOf("a", "b"), offers.map { it.first.id })
        assertEquals(listOf("b/1", "b/2", "b/9"), offers.first { it.first.id == "b" }.second)
        assertEquals(listOf("a/1"), offers.first { it.first.id == "a" }.second)
    }

    @Test
    fun `appending the current model keeps the list free of duplicates`() {
        val a = provider("a", "m1", "m1")
        val offers = modelOffers(listOf(a), "m2", a)
        assertEquals(listOf("m1", "m2"), offers.single().second)
    }

    @Test
    fun `without an active endpoint the first one takes the current model`() {
        val a = provider("a", "a/1")
        val b = provider("b", "b/1")
        val offers = modelOffers(listOf(a, b), "a/9", null)
        assertEquals(listOf("a", "b"), offers.map { it.first.id })
        assertEquals(listOf("a/1", "a/9"), offers[0].second)
        assertEquals(listOf("b/1"), offers[1].second)
    }

    @Test
    fun `no endpoints means nothing to offer`() {
        assertEquals(emptyList<Pair<ProviderConfig, List<String>>>(), modelOffers(emptyList(), "m1", null))
    }

    @Test
    fun `an active endpoint with no ticked models still offers the current one`() {
        val a = provider("a", "a/1")
        val b = provider("b")
        val offers = modelOffers(listOf(a, b), "b/7", b)
        val offered = offers.first { it.first.id == "b" }
        assertEquals(listOf("b/7"), offered.second)
        assertEquals(listOf("a/1"), offers.first { it.first.id == "a" }.second)
    }
}
