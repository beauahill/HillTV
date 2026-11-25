package com.beauahill.hilltv.core

/**
 * The members of Foundation's `CharacterSet` and Swift's `Character` that
 * `CategoryName` and `ChannelName` rely on, written out so both cores cut
 * a name in the same place. Kotlin's own `isWhitespace` and
 * `isLetterOrDigit` draw the lines a little differently.
 *
 * Everything here works on code points, as the Swift does on scalars.
 */
internal object CharacterSets {
    /** `CharacterSet.whitespaces`: spaces and tab, no line breaks. */
    fun isWhitespace(codePoint: Int): Boolean = when (codePoint) {
        0x20, 0x09, 0xA0, 0x1680, 0x202F, 0x205F, 0x3000 -> true
        else -> codePoint in 0x2000..0x200B
    }

    /** `CharacterSet.whitespacesAndNewlines`. */
    fun isWhitespaceOrNewline(codePoint: Int): Boolean =
        isWhitespace(codePoint) || codePoint in 0x0A..0x0D || codePoint == 0x85 || codePoint == 0x2028 || codePoint == 0x2029

    /** Swift's `Character.isWhitespace`: Unicode's White_Space property, which has line breaks but not U+200B. */
    fun isWhiteSpaceProperty(codePoint: Int): Boolean = when (codePoint) {
        0x20, 0x85, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
        else -> codePoint in 0x09..0x0D || codePoint in 0x2000..0x200A
    }

    /** `CharacterSet.alphanumerics`: every letter, mark and number, not only the ASCII ones. */
    fun isAlphanumeric(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
        Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
        Character.MODIFIER_LETTER, Character.OTHER_LETTER,
        Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK,
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
        -> true
        else -> false
    }

    /** An accent or vowel sign written as its own code point. */
    fun isMark(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
        Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
        else -> false
    }

    /**
     * How many characters a reader would count in a run of letters, marks
     * and numbers: a mark belongs to the letter before it. Swift's `count`
     * works this way, and it matters for scripts that write vowels as marks,
     * where a short word is many code points.
     */
    fun characterCount(text: String): Int {
        var count = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (index == 0 || !isMark(codePoint)) count += 1
            index += Character.charCount(codePoint)
        }
        return count
    }

    fun codePoints(text: String): IntArray = text.codePoints().toArray()

    fun string(codePoints: IntArray, from: Int = 0, to: Int = codePoints.size): String =
        if (from >= to) "" else String(codePoints, from, to - from)

    /** `trimmingCharacters(in:)`: drops matching code points from both ends. */
    inline fun trim(text: String, matches: (Int) -> Boolean): String {
        var start = 0
        var end = text.length
        while (start < end) {
            val codePoint = text.codePointAt(start)
            if (!matches(codePoint)) break
            start += Character.charCount(codePoint)
        }
        while (end > start) {
            val codePoint = text.codePointBefore(end)
            if (!matches(codePoint)) break
            end -= Character.charCount(codePoint)
        }
        return text.substring(start, end)
    }

    fun trimWhitespace(text: String): String = trim(text, ::isWhitespace)
}
