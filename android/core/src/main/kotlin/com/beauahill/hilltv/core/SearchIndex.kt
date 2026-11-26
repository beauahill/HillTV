package com.beauahill.hilltv.core

import java.text.Normalizer

/**
 * Case- and accent-insensitive name search over a catalog. Names are folded
 * once when the index is built, so each keystroke is a cheap scan.
 */
class SearchIndex<Item>(items: List<Item>, name: (Item) -> String) {
    private class Entry<Item>(val folded: String, val item: Item)

    private val entries: List<Entry<Item>> = items.map { Entry(fold(name(it)), it) }

    val count: Int get() = entries.size

    /**
     * Items whose name contains every word of the query. Names that start
     * with the query rank first, then names with a word starting with it,
     * then other matches. Catalog order is kept within each group.
     */
    fun search(query: String, limit: Int): List<Item> {
        val foldedQuery = fold(query)
        val tokens = foldedQuery.split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty() || limit <= 0) return emptyList()

        val buckets = List(3) { ArrayList<Item>() }
        val wordStart = " " + tokens[0]
        for (entry in entries) {
            if (!tokens.all { entry.folded.contains(it) }) continue
            when {
                entry.folded.startsWith(foldedQuery) -> buckets[0].add(entry.item)
                entry.folded.contains(wordStart) -> buckets[1].add(entry.item)
                else -> buckets[2].add(entry.item)
            }
            // The first bucket alone can fill the limit; stop scanning early.
            if (buckets[0].size >= limit) break
        }
        return buckets.flatten().take(limit)
    }

    companion object {
        private val marks = Regex("""\p{M}+""")

        /**
         * Lowercased, accents removed, punctuation turned into single spaces.
         * "Ciné+ | FR: Amélie" becomes "cine fr amelie".
         */
        fun fold(string: String): String {
            val decomposed = marks.replace(Normalizer.normalize(string, Normalizer.Form.NFKD), "")
            val result = StringBuilder(decomposed.length)
            var lastWasSpace = true
            var index = 0
            while (index < decomposed.length) {
                val codePoint = decomposed.codePointAt(index)
                index += Character.charCount(codePoint)
                if (Character.isLetterOrDigit(codePoint)) {
                    result.appendCodePoint(Character.toLowerCase(codePoint))
                    lastWasSpace = false
                } else if (!lastWasSpace) {
                    result.append(' ')
                    lastWasSpace = true
                }
            }
            if (result.endsWith(" ")) result.setLength(result.length - 1)
            return result.toString()
        }
    }
}
