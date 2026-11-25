package com.beauahill.hilltv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryNameTests {
    private fun parts(raw: String): List<String> {
        val name = CategoryName(raw)
        return listOf(name.title, name.tags.joinToString(","))
    }

    /** The names Beau sent from his provider, as he typed them. */
    @Test
    fun testNamesFromARealProvider() {
        assertEquals(listOf("USA Sports", "NA"), parts("|NA| USA Sports"))
        assertEquals(listOf("HULU", "EN"), parts("SRS - HULU [EN}"))
        assertEquals(listOf("NEW ADDED", "EN"), parts("VOD - NEW ADDED [EN]"))
        assertEquals(listOf("NETFLIX", "MULTISUB"), parts("VOD - NETFLIX [MULTISUB]"))
        assertEquals(listOf("SPORT", "EN"), parts("VOD - SPORT [EN]"))
        assertEquals("MULTISUB", CategoryName("VOD - NETFLIX [MULTISUB]").detail)
        assertNull(CategoryName("Drama").detail)
        assertEquals("NETFLIX · MULTISUB", CategoryName("VOD - NETFLIX [MULTISUB]").line)
        assertEquals("Drama", CategoryName("Drama").line)
    }

    @Test
    fun testTwoCategoriesThatDifferOnlyByTagStayApart() {
        val english = CategoryName("VOD - NETFLIX [EN]")
        val multi = CategoryName("VOD - NETFLIX [MULTISUB]")
        assertEquals(english.title, multi.title)
        assertNotEquals(english.detail, multi.detail)
    }

    @Test
    fun testSeveralLabels() {
        assertEquals(listOf("KIDS", "NA,EN,4K"), parts("|NA| VOD - KIDS [EN] [4K]"))
        assertEquals(listOf("UK Sports", "EU"), parts("|EU| UK Sports"))
        assertEquals(listOf("APPLE TV+", "MULTI-SUB"), parts("SRS - APPLE TV+ [MULTI-SUB]"))
        assertEquals("NA · EN", CategoryName("|NA| VOD - KIDS [EN]").detail)
    }

    @Test
    fun testNamesWithNothingToCut() {
        assertEquals(listOf("Drama", ""), parts("Drama"))
        assertEquals(listOf("Action & Adventure", ""), parts("  Action & Adventure  "))
        assertEquals(listOf("", ""), parts(""))
    }

    @Test
    fun testLookAlikesAreKept() {
        // The kind word needs its dash and spaces.
        assertEquals(listOf("VODKA NIGHTS", ""), parts("VODKA NIGHTS"))
        assertEquals(listOf("VOD-CAST", ""), parts("VOD-CAST"))
        assertEquals(listOf("SRS", ""), parts("SRS"))
        // A bracket in the middle is part of the name.
        assertEquals(listOf("Best [EN] Dramas", ""), parts("Best [EN] Dramas"))
        // Too long, or not letters, to be a label.
        assertEquals(listOf("Films [the complete collection]", ""), parts("Films [the complete collection]"))
        assertEquals(listOf("Odd [!!]", ""), parts("Odd [!!]"))
        // A lone bar is not a marker.
        assertEquals(listOf("| Sports", ""), parts("| Sports"))
        assertEquals(listOf("|a very long marker| Sports", ""), parts("|a very long marker| Sports"))
        // Round brackets weren't in the names seen, so they stay.
        assertEquals(listOf("Movies (2024)", ""), parts("Movies (2024)"))
    }

    @Test
    fun testNothingLeftShowsTheWholeName() {
        assertEquals(listOf("[EN]", ""), parts("[EN]"))
        assertEquals(listOf("VOD - [EN]", ""), parts("VOD - [EN]"))
        assertEquals(listOf("|NA|", ""), parts("|NA|"))
    }

    @Test
    fun testLetterCaseIsKept() {
        assertEquals(listOf("New Releases", "en"), parts("vod - New Releases [en]"))
    }

    @Test
    fun testCategoryExtension() {
        assertEquals("HULU", XtreamCategory(id = "7", name = "SRS - HULU [EN]").displayName.title)
    }

    /**
     * Found by running both cores over the same names. A tag's length is
     * counted in characters as a reader sees them, as Swift counts, so a
     * short word in a script that writes vowels as marks is still a tag.
     */
    @Test
    fun testTagLengthCountsCharactersNotCodePoints() {
        // Eight code points, five characters.
        assertEquals(listOf("X", "\u0939\u093F\u0928\u094D\u0926\u0940 \u092D"), parts("|\u0939\u093F\u0928\u094D\u0926\u0940 \u092D| X"))
        // Twelve accented letters written as letter plus accent: twelve characters, within the limit.
        val twelve = "e\u0301".repeat(12)
        assertEquals(listOf("X", twelve), parts("X [$twelve]"))
        // Thirteen is one too many.
        val thirteen = "e\u0301".repeat(13)
        assertEquals(listOf("X [$thirteen]", ""), parts("X [$thirteen]"))
    }
}
