package com.beauahill.hilltv.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchIndexTests {
    @Test
    fun testFoldRemovesCaseAccentsAndPunctuation() {
        assertEquals("cine fr amelie", SearchIndex.fold("Ciné+ | FR: Amélie"))
        assertEquals("bbc one hd", SearchIndex.fold("  BBC—One HD "))
        assertEquals("", SearchIndex.fold("|||"))
    }

    @Test
    fun testRanksPrefixThenWordStartThenContains() {
        val names = listOf("The Batman", "Batman Begins", "Lego Batman Movie", "Superbatman")
        val index = SearchIndex(names) { it }
        assertEquals(listOf("Batman Begins", "The Batman", "Lego Batman Movie", "Superbatman"), index.search("batman", 10))
    }

    @Test
    fun testAllWordsMustMatchInAnyOrder() {
        val index = SearchIndex(listOf("US: ESPN 2 HD", "UK: Sky Sports", "ESPN Deportes")) { it }
        assertEquals(listOf("US: ESPN 2 HD"), index.search("hd espn", 10))
        assertEquals(listOf("ESPN Deportes", "US: ESPN 2 HD"), index.search("ÉSPN", 10))
    }

    @Test
    fun testEmptyQueryAndLimit() {
        val index = SearchIndex((0 until 50).map { "Channel $it" }) { it }
        assertEquals(emptyList<String>(), index.search("   ", 10))
        assertEquals(5, index.search("channel", 5).size)
        assertEquals(emptyList<String>(), index.search("channel", 0))
    }
}
