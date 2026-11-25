package com.beauahill.hilltv.core

import com.beauahill.hilltv.core.CategoryGroup.KIDS
import com.beauahill.hilltv.core.CategoryGroup.MOVIES
import com.beauahill.hilltv.core.CategoryGroup.MUSIC
import com.beauahill.hilltv.core.CategoryGroup.NEWS
import com.beauahill.hilltv.core.CategoryGroup.OTHER
import com.beauahill.hilltv.core.CategoryGroup.SHOWS
import com.beauahill.hilltv.core.CategoryGroup.SPORTS
import com.beauahill.hilltv.core.CategoryGroup.WORLD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryGroupTests {
    private fun assertGroup(name: String, expected: CategoryGroup) {
        assertEquals("\"$name\"", expected, CategoryGroup.classify(name))
    }

    @Test
    fun testTopicsFromTypicalProviderNames() {
        assertGroup("US | SPORTS HD", SPORTS)
        assertGroup("NFL PACKAGE", SPORTS)
        assertGroup("PPV EVENTS", SPORTS)
        assertGroup("NBA LEAGUE PASS", SPORTS)
        assertGroup("VIP | BEIN SPORTS", SPORTS)
        assertGroup("World Cup 2026", SPORTS)
        assertGroup("UK | NEWS", NEWS)
        assertGroup("WEATHER & BUSINESS", NEWS)
        assertGroup("Christmas Movies", MOVIES)
        assertGroup("MOVIES IN 4K", MOVIES)
        assertGroup("US | ENTERTAINMENT", SHOWS)
        assertGroup("Documentaries", SHOWS)
        assertGroup("CA | LOCALS", SHOWS)
        assertGroup("Disney+ Kids", KIDS)
        assertGroup("MTV & MUSIC", MUSIC)
        assertGroup("Música", MUSIC)
    }

    @Test
    fun testANameMatchingTwoTopicsTakesTheEarlierOne() {
        assertGroup("KIDS MOVIES", KIDS)
        assertGroup("SPORTS NEWS", SPORTS)
        assertGroup("24/7 MOVIES", MOVIES)
    }

    @Test
    fun testForeignMarkersWinOverTopics() {
        assertGroup("FR | SPORT", WORLD)
        assertGroup("LATINO NEWS", WORLD)
        assertGroup("ARABIC | KIDS", WORLD)
        assertGroup("Canada French", WORLD)
        assertGroup("DE: KINDER", WORLD)
    }

    /** A live category as Beau's provider names them: a continent between bars. */
    @Test
    fun testBarMarkerFromARealProvider() {
        assertGroup("|NA| USA Sports", SPORTS)
        assertGroup("|NA| USA News", NEWS)
        assertGroup("|NA| USA Kids", KIDS)
    }

    @Test
    fun testEnglishMarkersDoNotCountAsForeign() {
        assertGroup("USA", SHOWS)
        assertGroup("UK HD", SHOWS)
        assertGroup("24/7 CHANNELS", SHOWS)
    }

    @Test
    fun testCountryCodesOnlyCountAsTheFirstWord() {
        // "it" and "es" appear as ordinary words here, not as a prefix.
        assertGroup("WATCH IT LIVE SPORTS", SPORTS)
        assertGroup("NO SIGNAL TEST", OTHER)
    }

    @Test
    fun testUnrecognizedAndEmptyNamesGoToOther() {
        assertGroup("ADULT", OTHER)
        assertGroup("4K UHD", OTHER)
        assertGroup("Religious", OTHER)
        assertGroup("|||", OTHER)
        assertGroup("", OTHER)
    }

    @Test
    fun testGroupingKeepsEveryCategoryInPillAndProviderOrder() {
        val names = listOf("UK | NEWS", "US | SPORTS", "Mystery Box", "NFL", "FR | CINEMA", "US | NEWS")
        val categories = names.mapIndexed { index, name -> XtreamCategory(index.toString(), name) }
        val grouped = CategoryGroup.grouping(categories)

        assertEquals(listOf(SPORTS, NEWS, WORLD, OTHER), grouped.map { it.group })
        assertEquals(listOf("US | SPORTS", "NFL"), grouped[0].categories.map { it.name })
        assertEquals(listOf("UK | NEWS", "US | NEWS"), grouped[1].categories.map { it.name })
        assertEquals(categories.size, grouped.sumOf { it.categories.size })
    }

    @Test
    fun testGroupingOfNothingIsEmpty() {
        assertEquals(emptyList<GroupedCategories>(), CategoryGroup.grouping(emptyList()))
    }
}

class CategoryVisibilityTests {
    private val categories = listOf("1", "2", "3", "4").map { XtreamCategory(it, "Category $it") }

    @Test
    fun testNothingIsHiddenAtFirst() {
        val visibility = CategoryVisibility()
        assertFalse(visibility.isHidden("1", CatalogKind.LIVE))
        assertEquals(0, visibility.hiddenCount(CatalogKind.LIVE))
        assertEquals(categories, visibility.visible(categories, CatalogKind.LIVE))
    }

    @Test
    fun testHidingIsPerCatalog() {
        val visibility = CategoryVisibility().setHidden(true, listOf("2", "3"), CatalogKind.LIVE)

        assertEquals(listOf("1", "4"), visibility.visible(categories, CatalogKind.LIVE).map { it.id })
        assertEquals(listOf("1", "2", "3", "4"), visibility.visible(categories, CatalogKind.MOVIES).map { it.id })
        assertEquals(2, visibility.hiddenCount(CatalogKind.LIVE))
        assertEquals(0, visibility.hiddenCount(CatalogKind.SERIES))
    }

    @Test
    fun testToggleAndBulkShow() {
        var visibility = CategoryVisibility().toggle("1", CatalogKind.MOVIES)
        assertTrue(visibility.isHidden("1", CatalogKind.MOVIES))
        visibility = visibility.toggle("1", CatalogKind.MOVIES)
        assertFalse(visibility.isHidden("1", CatalogKind.MOVIES))

        visibility = visibility.setHidden(true, categories.map { it.id }, CatalogKind.SERIES)
        visibility = visibility.setHidden(false, listOf("2"), CatalogKind.SERIES)
        assertEquals(listOf("2"), visibility.visible(categories, CatalogKind.SERIES).map { it.id })
        visibility = visibility.showAll(CatalogKind.SERIES)
        assertEquals(0, visibility.hiddenCount(CatalogKind.SERIES))
    }

    @Test
    fun testAnEmptiedSetEqualsAFreshOne() {
        val visibility = CategoryVisibility()
            .setHidden(true, listOf("9"), CatalogKind.LIVE)
            .setHidden(false, listOf("9"), CatalogKind.LIVE)
        assertEquals(CategoryVisibility(), visibility)
    }

    @Test
    fun testSurvivesEncodingAndDecoding() {
        val visibility = CategoryVisibility()
            .setHidden(true, listOf("2", "3"), CatalogKind.LIVE)
            .setHidden(true, listOf("7"), CatalogKind.SERIES)

        val decoded = CategoryVisibility.fromJson(parseJson(visibility.toJson().toString()))
        assertEquals(visibility, decoded)
        assertTrue(decoded.isHidden("7", CatalogKind.SERIES))
        assertFalse(decoded.isHidden("7", CatalogKind.LIVE))
    }
}
