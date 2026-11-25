package com.beauahill.hilltv.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * The handful of big buckets Live TV shows as pills. Providers send dozens
 * or hundreds of categories with names like "US | SPORTS HD" or
 * "FR ▎CINEMA"; each one is sorted into a bucket by keywords in its name.
 *
 * Every category lands somewhere: anything unrecognized goes to [OTHER].
 */
enum class CategoryGroup(val id: String, val title: String) {
    SPORTS("sports", "Sports"),
    NEWS("news", "News"),
    MOVIES("movies", "Movies"),
    SHOWS("shows", "Shows"),
    KIDS("kids", "Kids"),
    MUSIC("music", "Music"),
    WORLD("world", "World"),
    OTHER("other", "More");

    companion object {
        /**
         * Sorts a provider category name into a bucket.
         *
         * Order of checks:
         * 1. A foreign country or language marker wins, so "FR | SPORT" and
         *    "LATINO NEWS" go to World rather than crowding Sports and News.
         *    English-speaking markers ("US", "UK", "CA") are ignored here, so
         *    "UK | NEWS" is still News.
         * 2. Topic keywords, most specific first: a name matching two topics
         *    ("KIDS MOVIES", "SPORTS NEWS") takes the earlier one.
         * 3. A bare English-speaking marker ("USA", "UK HD") counts as Shows.
         * 4. Otherwise More.
         */
        fun classify(name: String): CategoryGroup {
            val tokens = SearchIndex.fold(name).split(' ').filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return OTHER
            val set = tokens.toSet()

            if (set.any { it in foreignWords } || tokens[0] in foreignPrefixes) return WORLD
            for ((group, words) in topics) {
                if (set.any { it in words }) return group
            }
            // "24/7" folds to the two tokens "24" and "7": looping single-show channels.
            if ("24" in set && "7" in set) return SHOWS
            if (set.any { it in englishWords }) return SHOWS
            return OTHER
        }

        /**
         * Buckets in pill order, each with its categories in the provider's
         * order. Empty buckets are left out.
         */
        fun grouping(categories: List<XtreamCategory>): List<GroupedCategories> {
            val buckets = HashMap<CategoryGroup, MutableList<XtreamCategory>>()
            for (category in categories) {
                buckets.getOrPut(classify(category.name)) { mutableListOf() }.add(category)
            }
            return entries.mapNotNull { group ->
                val items = buckets[group]
                if (items.isNullOrEmpty()) null else GroupedCategories(group, items)
            }
        }

        // Checked in this order. Words are whole tokens after folding.
        private val topics: List<Pair<CategoryGroup, Set<String>>> = listOf(
            KIDS to setOf(
                "kid", "kids", "children", "child", "cartoon", "cartoons", "toon", "toons", "junior", "baby",
                "disney", "nick", "nickelodeon",
            ),
            SPORTS to setOf(
                "sport", "sports", "nfl", "nba", "nhl", "mlb", "mls", "ncaa", "ncaaf", "ncaab", "ufc", "wwe", "f1",
                "soccer", "football", "basketball", "baseball", "hockey", "golf", "tennis", "cricket", "rugby",
                "boxing", "mma", "racing", "motorsport", "motorsports", "wrestling", "ppv", "espn", "dazn",
                "bein", "league", "cup", "olympics", "event", "events", "match", "matches",
            ),
            NEWS to setOf("news", "weather", "business"),
            MUSIC to setOf("music", "musica", "radio", "mtv", "concert", "concerts", "karaoke"),
            MOVIES to setOf("movie", "movies", "cinema", "film", "films"),
            SHOWS to setOf(
                "entertainment", "series", "show", "shows", "documentary", "documentaries", "docs", "general",
                "lifestyle", "comedy", "reality", "cooking", "food", "travel", "nature", "science", "history",
                "discovery", "drama", "local", "locals", "regional", "network", "networks",
            ),
        )

        private val englishWords = setOf(
            "us", "usa", "uk", "ca", "canada", "america", "american", "english", "australia", "ireland",
        )

        /** Country and language names that mark a category as not in English. */
        private val foreignWords = setOf(
            "international", "latino", "latina", "latin", "latam", "spanish", "espanol", "mexico", "mexican",
            "arabic", "arab", "india", "indian", "hindi", "punjabi", "tamil", "telugu", "malayalam", "urdu",
            "pakistan", "pakistani", "bangla", "bengali", "france", "french", "germany", "german", "deutsch",
            "italy", "italian", "italia", "spain", "portugal", "portuguese", "brazil", "brasil", "turkey", "turkish",
            "greece", "greek", "poland", "polish", "russia", "russian", "ukraine", "ukrainian", "china", "chinese",
            "japan", "japanese", "korea", "korean", "vietnam", "vietnamese", "thai", "thailand", "philippines",
            "filipino", "africa", "african", "nigeria", "somali", "ethiopia", "iran", "persian", "kurdish",
            "albania", "albanian", "romania", "romanian", "bulgaria", "bulgarian", "serbia", "croatia", "bosnia",
            "exyu", "netherlands", "dutch", "belgium", "sweden", "swedish", "norway", "denmark", "finland",
            "nordic", "scandinavia", "israel", "hebrew", "armenia", "armenian", "afghan", "afghanistan",
            "caribbean", "haiti", "colombia", "argentina", "chile", "peru", "venezuela", "europe", "asia",
        )

        /** Every country and language word above, for `ChannelName`. */
        internal val regionWords: Set<String> = englishWords + foreignWords

        /**
         * Two-letter country codes, counted only as the first word ("FR | CINEMA").
         * Elsewhere they are too easily ordinary words. Codes that are common
         * English words even in first place ("NO", "IN", "BE") are left out.
         */
        private val foreignPrefixes = setOf(
            "fr", "de", "es", "it", "pt", "tr", "ar", "pl", "ru", "gr", "nl", "se", "dk", "fi",
            "ro", "bg", "al", "pk", "br", "mx", "cn", "jp", "kr", "ph", "ir", "il", "af",
        )
    }
}

data class GroupedCategories(val group: CategoryGroup, val categories: List<XtreamCategory>) {
    val id: String get() = group.id
}

/** Which catalog a category belongs to. IDs are only unique within one. */
enum class CatalogKind(val id: String) {
    LIVE("live"),
    MOVIES("movies"),
    SERIES("series"),
}

/**
 * The categories the user chose to hide, per catalog. Small enough to keep
 * in preferences: it stores IDs only. Immutable; each change returns a copy.
 */
data class CategoryVisibility(
    /** Keyed by [CatalogKind.id]. A catalog with nothing hidden has no key. */
    private val hidden: Map<String, Set<String>> = emptyMap(),
) {
    fun isHidden(categoryID: String, kind: CatalogKind): Boolean = hidden[kind.id]?.contains(categoryID) ?: false

    fun hiddenCount(kind: CatalogKind): Int = hidden[kind.id]?.size ?: 0

    /** `categories` without the hidden ones, order kept. */
    fun visible(categories: List<XtreamCategory>, kind: CatalogKind): List<XtreamCategory> {
        val set = hidden[kind.id]
        if (set.isNullOrEmpty()) return categories
        return categories.filter { it.id !in set }
    }

    fun setHidden(isHidden: Boolean, ids: Iterable<String>, kind: CatalogKind): CategoryVisibility {
        val set = (hidden[kind.id] ?: emptySet()).let { if (isHidden) it + ids else it - ids.toSet() }
        return CategoryVisibility(if (set.isEmpty()) hidden - kind.id else hidden + (kind.id to set))
    }

    fun toggle(categoryID: String, kind: CatalogKind): CategoryVisibility =
        setHidden(!isHidden(categoryID, kind), listOf(categoryID), kind)

    fun showAll(kind: CatalogKind): CategoryVisibility = CategoryVisibility(hidden - kind.id)

    fun toJson(): JsonObject = buildJsonObject {
        for ((kind, ids) in hidden) {
            put(kind, buildJsonArray { ids.sorted().forEach { add(JsonPrimitive(it)) } })
        }
    }

    companion object {
        fun fromJson(root: JsonElement): CategoryVisibility {
            val o = root as? JsonObject ?: return CategoryVisibility()
            val hidden = HashMap<String, Set<String>>()
            for (kind in CatalogKind.entries) {
                val ids = (o[kind.id] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                    ?.toSet()
                if (!ids.isNullOrEmpty()) hidden[kind.id] = ids
            }
            return CategoryVisibility(hidden)
        }
    }
}
