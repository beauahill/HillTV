package com.beauahill.hilltv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelNameTests {
    private fun parts(raw: String): List<String> {
        val name = ChannelName(raw)
        return listOf(name.title, name.region ?: "-", name.quality.joinToString(","))
    }

    @Test
    fun testRegionAndQualityAreSplitOff() {
        assertEquals(listOf("CBS 13 CHEYENNE", "US", "HD"), parts("US | CBS 13 CHEYENNE HD"))
        assertEquals(listOf("ESPN", "US", "FHD"), parts("US: ESPN FHD"))
        assertEquals(listOf("BBC One", "UK", ""), parts("UK ▎ BBC One"))
        assertEquals(listOf("ABC", "US", ""), parts("US| ABC"))
        assertEquals(listOf("FOX NEWS", "US", ""), parts("US - FOX NEWS"))
        assertEquals(listOf("Syfy", "USA", ""), parts("USA | Syfy"))
        assertEquals(listOf("ESPN 2", "LATINO", ""), parts("LATINO | ESPN 2"))
        assertEquals(listOf("TVA Sports", "CA FR", ""), parts("CA FR | TVA Sports"))
        assertEquals(listOf("ZDF", "de", ""), parts("  de: ZDF  "))
        assertEquals("US · HD", ChannelName("US | CBS 13 CHEYENNE HD").detail)
        assertNull(ChannelName("HGTV").detail)
    }

    @Test
    fun testBracketedRegions() {
        assertEquals(listOf("CBS", "US", ""), parts("[US] CBS"))
        assertEquals(listOf("Sky Sports Main Event", "UK", "UHD"), parts("(UK) Sky Sports Main Event UHD"))
        assertEquals(listOf("TF1", "FR", "4K"), parts("|FR| TF1 4K"))
        assertEquals(listOf("NBC", "US", ""), parts("[US] - NBC"))
        // The form Beau's provider uses, "NA" for North America.
        assertEquals(listOf("USA Sports", "NA", ""), parts("|NA| USA Sports"))
        assertEquals(listOf("ESPN", "NA", "HD"), parts("|NA| ESPN HD"))
        // Not a region, so the bracket is part of the name.
        assertEquals(listOf("(WNYW) FOX 5 New York", "-", ""), parts("(WNYW) FOX 5 New York"))
    }

    @Test
    fun testNamesThatLookLikePrefixesAreKept() {
        // A network name before a colon is not a country.
        assertEquals(listOf("ABC: World News", "-", ""), parts("ABC: World News"))
        assertEquals(listOf("NFL | Sunday Ticket 01", "-", ""), parts("NFL | Sunday Ticket 01"))
        // "AL" is a country code, but not when a hyphen joins it to a word.
        assertEquals(listOf("AL-JAZEERA", "-", ""), parts("AL-JAZEERA"))
        assertEquals(listOf("AL JAZEERA ENGLISH", "-", "HD"), parts("AL JAZEERA ENGLISH HD"))
        // No separator, so nothing says "US" is a label.
        assertEquals(listOf("US Open Channel", "-", ""), parts("US Open Channel"))
        // The separator is too far in to be a prefix.
        assertEquals(listOf("Sky Sports Football | UK", "-", ""), parts("Sky Sports Football | UK"))
    }

    @Test
    fun testQualityOnlyFromTheEnd() {
        assertEquals(listOf("ESPN", "-", "HD,FHD,60FPS"), parts("ESPN HD FHD 60FPS"))
        assertEquals(listOf("ESPN", "-", "HD"), parts("ESPN - HD"))
        assertEquals(listOf("CNN", "-", "FHD"), parts("CNN [FHD]"))
        assertEquals(listOf("TNT", "-", "H265"), parts("TNT (H.265)"))
        assertEquals(listOf("Channel 4", "-", "HD"), parts("Channel 4 hd"))
        assertEquals(listOf("HBO HD East", "-", ""), parts("HBO HD East"))
        assertEquals(listOf("CBS 13 CHEYENNE", "US", ""), parts("US | CBS 13 CHEYENNE"))
    }

    @Test
    fun testSuperscriptLabels() {
        assertEquals(listOf("BBC ONE", "UK", "HD"), parts("UK | BBC ONE ᴴᴰ"))
        assertEquals(listOf("ESPN", "-", "FHD"), parts("ESPN ᶠᴴᴰ"))
        assertEquals(listOf("ESPN", "-", "RAW"), parts("ESPN ᴿᴬᵂ"))
    }

    @Test
    fun testLabelsThatAreAlsoWords() {
        assertEquals(listOf("WWE RAW", "US", ""), parts("US | WWE RAW"))
        assertEquals(listOf("BALLY SPORTS SD", "-", ""), parts("BALLY SPORTS SD"))
        assertEquals(listOf("CNN", "-", "SD"), parts("CNN [SD]"))
        assertEquals(listOf("ESPN", "-", "HD,BACKUP"), parts("ESPN HD (BACKUP)"))
    }

    @Test
    fun testDividerRowsAndNamesWithNothingLeft() {
        assertEquals(listOf("NEWS", "-", ""), parts("##### NEWS #####"))
        assertEquals(listOf("SPORTS", "US", ""), parts("--- US | SPORTS ---"))
        assertEquals(listOf("#1 News", "-", ""), parts("#1 News"))
        // Stripping would leave nothing, so the name is shown whole.
        assertEquals(listOf("HD", "-", ""), parts("HD"))
        assertEquals(listOf("US | HD", "-", ""), parts("US | HD"))
        assertEquals(listOf("US", "-", ""), parts("US |"))
        assertEquals(listOf("#####", "-", ""), parts("#####"))
        assertEquals(listOf("", "-", ""), parts(""))
    }

    @Test
    fun testLetterCaseAndAccentsAreKept() {
        assertEquals(listOf("Ciné+ Émotion", "FR", "HD"), parts("FR | Ciné+ Émotion HD"))
        assertEquals(listOf("La 1", "ES", ""), parts("ES: La 1"))
    }

    /**
     * Found by running both cores over the same names. The Kotlin `fold` reads
     * look-alike letters as plain ones; Foundation's doesn't, so these are held
     * to what the Apple TV shows.
     */
    @Test
    fun testRegionMarkersAreReadAsOnAppleTV() {
        // Superscript letters are not a region, though a superscript quality label still counts.
        assertEquals(listOf("\u1D41\u02E2 : 13 WGN", "-", ""), parts("\u1D41\u02E2 : 13 WGN"))
        assertEquals(listOf("\u1D41\u02E2 - Canal+ Sport", "-", "HD"), parts("\u1D41\u02E2 - Canal+ Sport \u1D34\u1D30"))
        assertEquals(listOf("\u24E4\u24E2 | ESPN", "-", ""), parts("\u24E4\u24E2 | ESPN"))
        // A no-break space between two markers is still a space.
        assertEquals(listOf("WWE RAW", "US\u00A0UK", ""), parts("US\u00A0UK - WWE RAW"))
        // A symbol ends the word; it is not spelled out ("\u2100" is not "a c").
        assertEquals(listOf("ESPN", "US\u2100", ""), parts("US\u2100 | ESPN"))
        // Full-width letters are plain letters on both.
        assertEquals(listOf("CBS", "\uFF35\uFF33", ""), parts("\uFF35\uFF33 | CBS"))
        // An accent written as its own code point is still dropped.
        assertEquals(listOf("CBS", "Espan\u0303ol", ""), parts("Espan\u0303ol | CBS"))
    }
}
