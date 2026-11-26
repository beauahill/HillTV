package com.beauahill.hilltv.core

import java.util.Locale

/**
 * A provider's category name split into the part worth reading and the
 * labels around it. "VOD - NETFLIX [MULTISUB]" becomes the title "NETFLIX"
 * and the tag "MULTISUB".
 *
 * For display only, like [ChannelName]. Grouping, hiding and saved orders
 * keep using the category's ID and the name exactly as sent.
 *
 * It cuts only the forms seen on a real provider:
 * - A marker between bars at the start, "|NA| USA Sports". Shown as a tag.
 * - A kind word and a dash at the start, "VOD - " or "SRS - ". Dropped
 *   without a tag: the page already says whether these are movies or series.
 * - Bracketed tags at the end, "[EN]" or "[MULTISUB]". Shown as tags. A
 *   mistyped closing bracket, as in "[EN}", is accepted.
 * Letter case is left as sent, and if nothing would be left the whole name
 * is the title.
 */
class CategoryName(raw: String) {
    /** The name without its labels. */
    val title: String

    /** Labels cut from the name, in the order they appeared. */
    val tags: List<String>

    /** The tags on one line, such as "NA · EN". Null when there were none. */
    val detail: String? get() = if (tags.isEmpty()) null else tags.joinToString(" · ")

    /**
     * Title and tags as one line of text, such as "NETFLIX · MULTISUB",
     * for places that take a plain string.
     */
    val line: String get() = (listOf(title) + tags).joinToString(" · ")

    init {
        val whole = CharacterSets.trim(raw, CharacterSets::isWhitespaceOrNewline)
        var text = whole
        val found = ArrayList<String>()

        leadingBarMarker(text)?.let { (tag, rest) ->
            found.add(tag)
            text = rest
        }
        text = droppingKindPrefix(text)

        val trailing = ArrayList<String>()
        while (true) {
            val (tag, rest) = trailingBracketTag(text) ?: break
            trailing.add(0, tag)
            text = rest
        }
        found += trailing

        if (text.isEmpty()) {
            title = whole
            tags = emptyList()
        } else {
            title = text
            tags = found
        }
    }

    override fun equals(other: Any?): Boolean = other is CategoryName && title == other.title && tags == other.tags

    override fun hashCode(): Int = 31 * title.hashCode() + tags.hashCode()

    override fun toString(): String = "CategoryName(title=$title, tags=$tags)"

    // MARK: Rules

    private companion object {
        fun isTagCharacter(codePoint: Int): Boolean =
            CharacterSets.isAlphanumeric(codePoint) || codePoint == '-'.code || codePoint == '+'.code || codePoint == ' '.code

        fun isTag(text: String, longest: Int): Boolean {
            if (text.isEmpty() || CharacterSets.characterCount(text) > longest) return false
            val codePoints = CharacterSets.codePoints(text)
            return codePoints.all(::isTagCharacter) && codePoints.any(CharacterSets::isAlphanumeric)
        }

        /** "|NA| USA Sports" */
        fun leadingBarMarker(text: String): Pair<String, String>? {
            if (!text.startsWith('|')) return null
            val close = text.indexOf('|', startIndex = 1)
            if (close < 0) return null
            val tag = CharacterSets.trimWhitespace(text.substring(1, close))
            if (!isTag(tag, longest = 6)) return null
            val rest = CharacterSets.trimWhitespace(text.substring(close + 1))
            return if (rest.isEmpty()) null else tag to rest
        }

        /** Words a provider puts in front of a category to say what kind it is. */
        val kindPrefixes = listOf("VOD", "SRS")

        /**
         * "VOD - NEW ADDED", "SRS - HULU". The dash needs a space on both
         * sides, so a name that merely starts with the word is left alone.
         */
        fun droppingKindPrefix(text: String): String {
            val count = text.codePointCount(0, text.length)
            for (prefix in kindPrefixes) {
                for (dash in listOf("-", "–", "—")) {
                    val lead = "$prefix $dash "
                    if (count <= lead.length) continue
                    val end = text.offsetByCodePoints(0, lead.length)
                    if (text.substring(0, end).uppercase(Locale.ROOT) == lead) {
                        return CharacterSets.trimWhitespace(text.substring(end))
                    }
                }
            }
            return text
        }

        const val closers = "]})"

        /** "HULU [EN]", "NETFLIX [MULTISUB]", "HULU [EN}" */
        fun trailingBracketTag(text: String): Pair<String, String>? {
            if (text.isEmpty() || text.last() !in closers) return null
            val open = text.lastIndexOf('[')
            if (open < 0) return null
            val tag = CharacterSets.trimWhitespace(text.substring(open + 1, text.length - 1))
            if (!isTag(tag, longest = 12)) return null
            return tag to CharacterSets.trimWhitespace(text.substring(0, open))
        }
    }
}

/** The category's name split for display. `name` stays as the provider sent it. */
val XtreamCategory.displayName: CategoryName get() = CategoryName(name)
