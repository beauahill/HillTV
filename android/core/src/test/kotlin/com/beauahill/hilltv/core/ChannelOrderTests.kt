package com.beauahill.hilltv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelOrderTests {
    private fun shown(order: ChannelOrder, provider: List<Int>, key: String = "12"): List<Int> =
        order.apply(provider, key) { it }

    @Test
    fun testNoOrderLeavesTheListAlone() {
        val order = ChannelOrder()
        assertEquals(listOf(5, 6, 7), shown(order, listOf(5, 6, 7)))
        assertFalse(order.hasOrder("12"))
        assertTrue(order.isEmpty)
    }

    @Test
    fun testMoveUpStoresOnlyTheTopOfTheList() {
        val provider = (1..1000).toList()
        // Channel 4 goes to the top.
        val order = ChannelOrder().move(4, 0, provider, "12")
        assertEquals(listOf(4, 1, 2, 3, 5, 6), shown(order, provider).take(6))
        assertEquals(listOf(4, 1, 2, 3), order.lists["12"])
        assertTrue(order.hasOrder("12"))
    }

    @Test
    fun testMoveDown() {
        val provider = listOf(1, 2, 3, 4, 5, 6)
        val order = ChannelOrder().move(2, 4, provider, "12")
        assertEquals(listOf(1, 3, 4, 5, 2, 6), shown(order, provider))
        assertEquals(listOf(1, 3, 4, 5, 2), order.lists["12"])
    }

    @Test
    fun testSecondMoveKeepsTheFirst() {
        val provider = (1..10).toList()
        var order = ChannelOrder().move(8, 0, provider, "12")
        assertEquals(listOf(8, 1, 2, 3, 4, 5, 6, 7, 9, 10), shown(order, provider))
        // A small move near the top must not forget that 8 came from further down.
        order = order.move(1, 2, shown(order, provider), "12")
        assertEquals(listOf(8, 2, 1, 3, 4, 5, 6, 7, 9, 10), shown(order, provider))
        order = order.move(10, 1, shown(order, provider), "12")
        assertEquals(listOf(8, 10, 2, 1, 3, 4, 5, 6, 7, 9), shown(order, provider))
    }

    @Test
    fun testListsAreSeparate() {
        val order = ChannelOrder().move(3, 0, listOf(1, 2, 3), "news")
        assertEquals(listOf(3, 1, 2), shown(order, listOf(1, 2, 3), key = "news"))
        assertEquals(listOf(1, 2, 3), shown(order, listOf(1, 2, 3), key = "sports"))
    }

    @Test
    fun testProviderAddsAndDropsChannels() {
        val order = ChannelOrder().move(3, 0, listOf(1, 2, 3, 4), "12")
        // 2 is gone, 9 is new and listed first by the provider.
        assertEquals(listOf(3, 1, 9, 4), shown(order, listOf(9, 1, 3, 4)))
        // A list with none of the stored channels is shown as sent.
        assertEquals(listOf(20, 21), shown(order, listOf(20, 21)))
    }

    @Test
    fun testRepeatedChannelIsShownOnce() {
        val order = ChannelOrder().move(2, 0, listOf(1, 2, 3), "12")
        assertEquals(listOf(2, 1, 3), shown(order, listOf(1, 2, 2, 3)))
    }

    @Test
    fun testMovesThatChangeNothing() {
        var order = ChannelOrder()
        order = order.move(2, 1, listOf(1, 2, 3), "12")
        order = order.move(99, 0, listOf(1, 2, 3), "12")
        order = order.move(1, 0, emptyList(), "12")
        assertTrue(order.isEmpty)
        // Past the end means the end.
        order = order.move(1, 50, listOf(1, 2, 3), "12")
        assertEquals(listOf(2, 3, 1), shown(order, listOf(1, 2, 3)))
        order = order.move(1, -4, listOf(2, 3, 1), "12")
        assertEquals(listOf(1, 2, 3), shown(order, listOf(1, 2, 3)))
    }

    @Test
    fun testReset() {
        var order = ChannelOrder().move(3, 0, listOf(1, 2, 3), "12")
        order = order.reset("12")
        assertEquals(listOf(1, 2, 3), shown(order, listOf(1, 2, 3)))
        assertTrue(order.isEmpty)
    }

    @Test
    fun testSurvivesSavingAndLoading() {
        val order = ChannelOrder().move(3, 0, listOf(1, 2, 3), "12")
        val text = order.toJson().toString()
        assertEquals("""{"12":[3,1,2]}""", text)
        assertEquals(order, ChannelOrder.fromJson(parseJson(text)))
        // Something else under the same key reads as "no order", not a failure.
        assertTrue(ChannelOrder.fromJson(parseJson("""["x"]""")).isEmpty)
        // The same goes for one list that isn't channel IDs, and for nothing at all.
        assertTrue(ChannelOrder.fromJson(parseJson("""{"12":[3,1,2],"13":["x"]}""")).isEmpty)
        assertTrue(ChannelOrder.fromJson(parseJson("""{"12":"3,1,2"}""")).isEmpty)
        assertTrue(ChannelOrder.fromJson(null).isEmpty)
    }

    @Test
    fun testMovingHelper() {
        assertEquals(listOf("a", "d", "b", "c"), ChannelOrder.moving(listOf("a", "b", "c", "d"), from = 3, to = 1))
        assertEquals(listOf("b", "c", "d", "a"), ChannelOrder.moving(listOf("a", "b", "c", "d"), from = 0, to = 3))
        assertEquals(listOf("a", "b"), ChannelOrder.moving(listOf("a", "b"), from = 7, to = 0))
        assertEquals(emptyList<Int>(), ChannelOrder.moving(emptyList<Int>(), from = 0, to = 0))
    }

    @Test
    fun testLiveStreamsKeepTheirOrder() {
        val streams = listOf(
            LiveStream(id = 1, name = "US | ABC"),
            LiveStream(id = 2, name = "US | CBS"),
            LiveStream(id = 3, name = "US | NBC"),
        )
        val order = ChannelOrder().move(3, 0, streams.map { it.id }, "12")
        assertEquals(listOf("US | NBC", "US | ABC", "US | CBS"), order.apply(streams, "12").map { it.name })
    }
}
