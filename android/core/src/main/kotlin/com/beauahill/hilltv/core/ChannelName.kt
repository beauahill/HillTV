package com.beauahill.hilltv.core

import java.text.Normalizer
import java.util.Locale

/**
 * A provider's channel name split into the part worth reading and the
 * labels around it. "US | CBS 13 CHEYENNE HD" becomes the title
 * "CBS 13 CHEYENNE", the region "US" and the quality "HD".
 *
 * This is for display only. Matching, search and favorites keep using the
 * name exactly as the provider sent it.
 *
 * The rules are deliberately cautious, because a wrong cut hides part of a
 * real name:
 * - A leading region is removed only when it is a known country or language
 *   marker and a separator follows it, so "ABC: World News" keeps "ABC".
 * - Quality labels are removed only from the end of the name.
 * - Labels that are also ordinary words ("RAW", "SD") count only when they
 *   are bracketed or written in superscript, so "WWE RAW" stays whole.
 * - Letter case is left as sent.
 * - If nothing would be left, the whole name is the title.
 */
class ChannelName(raw: String) {
    /** The name without its region and quality labels. */
    val title: String

    /** The country or language marker the name started with, as sent. */
    val region: String?

    /** Quality labels from the end of the name, in order, in plain capitals. */
    val quality: List<String>

    /**
     * Region and quality on one line, such as "US · HD". Null when the name
     * had neither.
     */
    val detail: String?
        get() {
            val parts = listOfNotNull(region) + quality
            return if (parts.isEmpty()) null else parts.joinToString(" · ")
        }

    init {
        val whole = CharacterSets.trim(raw, CharacterSets::isWhitespaceOrNewline)
        var text = droppingDecoration(whole)

        var foundRegion: String? = null
        splitRegion(text)?.let { (marker, rest) ->
            foundRegion = marker
            text = rest
        }

        val words = words(text)
        val found = ArrayList<String>()
        while (words.isNotEmpty()) {
            val last = words.last()
            val tag = qualityTag(last)
            if (tag != null) {
                found.add(0, tag)
                words.removeAt(words.lastIndex)
            } else if (CharacterSets.codePoints(last).all(::isSeparator)) {
                // A separator left hanging, as in "ESPN - HD".
                words.removeAt(words.lastIndex)
            } else {
                break
            }
        }

        val joined = words.joinToString(" ")
        if (joined.isEmpty()) {
            title = whole
            region = null
            quality = emptyList()
        } else {
            title = joined
            region = foundRegion
            quality = found
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ChannelName && title == other.title && region == other.region && quality == other.quality

    override fun hashCode(): Int = 31 * (31 * title.hashCode() + (region?.hashCode() ?: 0)) + quality.hashCode()

    override fun toString(): String = "ChannelName(title=$title, region=$region, quality=$quality)"

    private companion object {
        fun set(characters: String): Set<Int> = characters.codePoints().toArray().toSet()

        /** The text between runs of white space. */
        fun words(text: String): MutableList<String> {
            val result = ArrayList<String>()
            val word = StringBuilder()
            for (codePoint in CharacterSets.codePoints(text)) {
                if (CharacterSets.isWhiteSpaceProperty(codePoint)) {
                    if (word.isNotEmpty()) {
                        result.add(word.toString())
                        word.setLength(0)
                    }
                } else {
                    word.appendCodePoint(codePoint)
                }
            }
            if (word.isNotEmpty()) result.add(word.toString())
            return result
        }

        // MARK: Decoration

        val decoration = set("#=*_~-●■▬►◄▶◀✦★☆•")

        /**
         * Rows like "##### NEWS #####" are section headings some providers put
         * in the channel list. A run of two or more such characters at either
         * end is dropped; a single one ("#1 News") is left alone.
         */
        fun droppingDecoration(text: String): String {
            val codePoints = CharacterSets.codePoints(text)

            /** How many code points the run takes, walking from `start` towards `end`. */
            fun run(start: Int, end: Int, step: Int): Int {
                var count = 0
                var length = 0
                var index = start
                while (index != end) {
                    val codePoint = codePoints[index]
                    if (codePoint in decoration) {
                        count += 1
                    } else if (!CharacterSets.isWhitespace(codePoint)) {
                        break
                    }
                    length += 1
                    index += step
                }
                return if (count >= 2) length else 0
            }

            val from = run(0, codePoints.size, 1)
            val to = codePoints.size - run(codePoints.size - 1, from - 1, -1)
            return CharacterSets.trimWhitespace(CharacterSets.string(codePoints, from, to))
        }

        // MARK: Region

        val separators = set("|:-–—▎┃│•·✦★»>")
        val dashes = set("-–—")
        val brackets = mapOf('['.code to ']'.code, '('.code to ')'.code, '|'.code to '|'.code, '【'.code to '】'.code, '{'.code to '}'.code)

        fun isSeparator(codePoint: Int): Boolean = codePoint in separators

        /** How far into a name a region marker may end. */
        const val regionReach = 16

        fun splitRegion(text: String): Pair<String, String>? {
            val codePoints = CharacterSets.codePoints(text)
            if (codePoints.isEmpty()) return null

            // "[US] CBS", "(UK) BBC One", "|FR| TF1"
            val close = brackets[codePoints[0]]
            if (close != null) {
                // The closing bracket has to come within `regionReach` of the opening one.
                val limit = minOf(codePoints.size, 1 + regionReach)
                val end = (1 until limit).firstOrNull { codePoints[it] == close } ?: return null
                val marker = CharacterSets.trimWhitespace(CharacterSets.string(codePoints, 1, end))
                if (!isRegion(marker)) return null
                val rest = droppingLeadingSeparators(codePoints, end + 1)
                return if (rest.isEmpty()) null else marker to rest
            }

            // "US | CBS", "UK: BBC One", "FR ▎TF1", "DE - ZDF"
            val index = (0 until minOf(codePoints.size, regionReach)).firstOrNull { isSeparator(codePoints[it]) } ?: return null
            if (codePoints[index] in dashes) {
                // "AL-JAZEERA" is a name, "AL - JAZEERA" would be a marker.
                val before = index > 0 && CharacterSets.isWhitespace(codePoints[index - 1])
                val after = index + 1 < codePoints.size && CharacterSets.isWhitespace(codePoints[index + 1])
                if (!before && !after) return null
            }
            val marker = CharacterSets.trimWhitespace(CharacterSets.string(codePoints, 0, index))
            if (!isRegion(marker)) return null
            val rest = droppingLeadingSeparators(codePoints, index + 1)
            return if (rest.isEmpty()) null else marker to rest
        }

        fun droppingLeadingSeparators(codePoints: IntArray, from: Int): String {
            var start = from
            while (start < codePoints.size && (isSeparator(codePoints[start]) || CharacterSets.isWhitespace(codePoints[start]))) {
                start += 1
            }
            return CharacterSets.string(codePoints, start)
        }

        /** One or two words, each a known country code, country or language. */
        fun isRegion(marker: String): Boolean {
            val words = SearchIndex.fold(asFoundationReads(marker) ?: return false).split(' ').filter { it.isNotEmpty() }
            if (words.size !in 1..2) return false
            return words.all { it in regionCodes || it in CategoryGroup.regionWords }
        }

        /**
         * `SearchIndex.fold` is more generous here than it is on the Apple TV,
         * where Foundation does the folding. Here it also reads superscripts
         * and other look-alike letters as plain ones ("ᵁˢ" as "us") and drops
         * every kind of mark. A marker written that way is not a region there,
         * so it must not be one here.
         *
         * Returns the marker in a form both fold alike, or null when Foundation
         * would leave a character in it that no region word has.
         */
        fun asFoundationReads(marker: String): String? {
            if (marker.all { it.code < 0x80 }) return marker
            val result = StringBuilder(marker.length)
            for (codePoint in CharacterSets.codePoints(marker)) {
                when {
                    // ASCII, and the full-width forms both sides read as ASCII.
                    codePoint < 0x80 || codePoint in 0xFF00..0xFFEF -> result.appendCodePoint(codePoint)
                    // A word break there, whatever it would unfold to here ("℀" is not "a c").
                    !CharacterSets.isAlphanumeric(codePoint) -> result.append(' ')
                    foldsDifferently(codePoint) -> return null
                    else -> result.appendCodePoint(codePoint)
                }
            }
            return result.toString()
        }

        /** True for a letter, mark or number Foundation keeps as it is and `SearchIndex.fold` changes or drops. */
        fun foldsDifferently(codePoint: Int): Boolean {
            when (Character.getType(codePoint).toByte()) {
                Character.COMBINING_SPACING_MARK, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> return true
            }
            val text = String(Character.toChars(codePoint))
            return Normalizer.normalize(text, Normalizer.Form.NFKD) != Normalizer.normalize(text, Normalizer.Form.NFD)
        }

        /**
         * Country and language codes seen in front of channel names. Not every
         * ISO code: short ones that are also networks or words ("TV", "BT") are
         * left out on purpose.
         */
        val regionCodes = setOf(
            "us", "usa", "uk", "gb", "ca", "can", "au", "aus", "nz", "ie", "en", "eng",
            "fr", "fra", "de", "deu", "ger", "es", "esp", "it", "ita", "pt", "por", "nl", "ned", "be", "ch", "at",
            "se", "swe", "no", "nor", "dk", "den", "fi", "fin", "is", "pl", "pol", "cz", "sk", "hu", "ro", "bg", "gr",
            "tr", "tur", "ru", "rus", "ua", "al", "alb", "rs", "hr", "ba", "si", "mk", "xk", "exyu",
            "il", "ir", "iq", "sa", "ae", "uae", "eg", "ma", "dz", "tn", "lb", "sy", "jo", "kw", "qa", "ar", "ara", "ku",
            "in", "ind", "pk", "pak", "bd", "lk", "np", "af", "afg",
            "cn", "jp", "kr", "th", "vn", "ph", "id", "my", "sg", "hk", "tw",
            "br", "bra", "mx", "mex", "co", "cl", "pe", "ve", "ec", "uy", "py", "bo", "cu", "do", "pr", "ht", "jm",
            "la", "lat", "latam", "za", "ng", "gh", "ke", "et", "so", "eu",
            // Continents, as in "|NA| USA Sports".
            "na", "oc",
        )

        // MARK: Quality

        /** Labels that only ever mean picture quality or encoding. */
        val qualityTags = setOf(
            "HD", "FHD", "UHD", "QHD", "4K", "8K", "HEVC", "H265", "H264", "HDR",
            "2160P", "1080P", "1080I", "720P", "480P", "50FPS", "60FPS",
        )

        /**
         * Labels that are also words or parts of names: "WWE RAW", "Bally
         * Sports SD" (San Diego). They count only when bracketed or superscript.
         */
        val markedQualityTags = setOf("SD", "RAW", "LQ", "HQ", "BACKUP")

        val tagBrackets = set("()[]{}【】*")

        /** The quality label a word stands for, if it is one. */
        fun qualityTag(word: String): String? {
            // Compatibility mapping turns superscript "ᴴᴰ" into "HD". A word that only
            // differs by how its accents are composed has not changed, as in Swift,
            // where strings that spell the same text compare equal.
            val plain = Normalizer.normalize(word, Normalizer.Form.NFKC)
            val superscript = plain != Normalizer.normalize(word, Normalizer.Form.NFC) && word.codePoints().allMatch { it > 0x7F }
            val trimmed = CharacterSets.trim(plain) { it in tagBrackets }
            val bracketed = trimmed.length < plain.length
            val tag = trimmed.uppercase(Locale.ROOT).replace(".", "")
            if (tag in qualityTags) return tag
            if (tag in markedQualityTags && (superscript || bracketed)) return tag
            return null
        }
    }
}

/** The channel's name split for display. `name` stays as the provider sent it. */
val LiveStream.displayName: ChannelName get() = ChannelName(name)
