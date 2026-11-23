package com.beauahill.hilltv.core

/** One channel from an extended M3U playlist. */
data class M3UEntry(
    val name: String,
    val url: String,
    val tvgID: String?,
    val tvgName: String?,
    val logoURL: String?,
    val group: String?,
    /** Every key="value" attribute on the #EXTINF line, lowercased keys. */
    val attributes: Map<String, String>,
) {
    val id: String get() = url
}

/**
 * Parses extended M3U (`#EXTM3U` / `#EXTINF`) playlists. Not used by the UI
 * yet; it's here so M3U-only providers can be added next to Xtream.
 */
object M3UParser {
    private val scheme = Regex("""^[A-Za-z][A-Za-z0-9+.\-]*:""")
    private val attribute = Regex("""([A-Za-z0-9_\-]+)="([^"]*)"""")

    fun parse(text: String): List<M3UEntry> {
        val entries = ArrayList<M3UEntry>()
        var pendingInfo: Pair<String, Map<String, String>>? = null
        var pendingGroup: String? = null

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#EXTINF")) {
                pendingInfo = parseInfo(line)
            } else if (line.startsWith("#EXTGRP:")) {
                pendingGroup = line.removePrefix("#EXTGRP:").trim()
            } else if (line.startsWith("#")) {
                continue
            } else if (scheme.containsMatchIn(line) && line.none { it.isWhitespace() }) {
                val lastComponent = line.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
                val (name, attrs) = pendingInfo ?: (lastComponent to emptyMap())
                entries.add(
                    M3UEntry(
                        name = name.ifEmpty { attrs["tvg-name"] ?: lastComponent },
                        url = line,
                        tvgID = attrs["tvg-id"]?.ifEmpty { null },
                        tvgName = attrs["tvg-name"]?.ifEmpty { null },
                        logoURL = attrs["tvg-logo"]?.ifEmpty { null },
                        group = attrs["group-title"]?.ifEmpty { null } ?: pendingGroup,
                        attributes = attrs,
                    )
                )
                pendingInfo = null
                pendingGroup = null
            }
        }
        return entries
    }

    /**
     * `#EXTINF:-1 tvg-id="x" group-title="News, US",Display Name`
     * The display name is everything after the first comma that sits outside quotes.
     */
    internal fun parseInfo(line: String): Pair<String, Map<String, String>> {
        val body = line.substringAfter(':', "")
        var inQuotes = false
        var splitIndex = -1
        for ((index, ch) in body.withIndex()) {
            if (ch == '"') inQuotes = !inQuotes
            if (ch == ',' && !inQuotes) {
                splitIndex = index
                break
            }
        }
        val header = if (splitIndex >= 0) body.substring(0, splitIndex) else body
        val name = if (splitIndex >= 0) body.substring(splitIndex + 1) else ""

        val attributes = LinkedHashMap<String, String>()
        for (match in attribute.findAll(header)) {
            attributes[match.groupValues[1].lowercase()] = match.groupValues[2]
        }
        return name.trim() to attributes
    }
}
