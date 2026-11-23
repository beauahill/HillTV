package com.beauahill.hilltv.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * One movie or episode the viewer has started. Stores IDs, never stream
 * URLs: those embed the account password, and history lives in plain
 * preferences while the account is stored encrypted.
 */
data class WatchEntry(
    val kind: Kind,
    /** Movie `stream_id` or episode `id`, as used in the play URL. */
    val streamID: String,
    val containerExtension: String,
    val title: String,
    val subtitle: String? = null,
    val artworkURL: String? = null,
    /** Seconds into the item. */
    val position: Double,
    /** Seconds, when the player knows it. Live-like or broken files report none. */
    val duration: Double?,
    val updatedAt: Instant = Instant.ofEpochMilli(System.currentTimeMillis()),
) {
    enum class Kind(val id: String) {
        MOVIE("movie"),
        EPISODE("episode"),
    }

    val id: String get() = "${kind.id}-$streamID"

    /** 0...1 when the duration is known. */
    val progress: Double?
        get() {
            val total = duration ?: return null
            if (!(total > 0)) return null
            return (position / total).coerceIn(0.0, 1.0)
        }

    fun toJson(): JsonObject = buildJsonObject {
        put("kind", kind.id)
        put("streamID", streamID)
        put("containerExtension", containerExtension)
        put("title", title)
        subtitle?.let { put("subtitle", it) }
        artworkURL?.let { put("artworkURL", it) }
        put("position", position)
        duration?.let { put("duration", it) }
        put("updatedAt", updatedAt.toEpochMilli())
    }

    companion object {
        fun fromJson(o: JsonObject): WatchEntry? {
            val kind = Kind.entries.firstOrNull { it.id == o.lenientString("kind") } ?: return null
            return WatchEntry(
                kind = kind,
                streamID = o.lenientString("streamID") ?: return null,
                containerExtension = o.lenientString("containerExtension") ?: "mp4",
                title = o.lenientString("title") ?: "",
                subtitle = o.lenientString("subtitle"),
                artworkURL = o.lenientString("artworkURL"),
                position = o.lenientDouble("position") ?: return null,
                duration = o.lenientDouble("duration"),
                updatedAt = Instant.ofEpochMilli(o.lenientLong("updatedAt") ?: 0),
            )
        }
    }
}

/** Newest-first list of partly watched items, small enough for preferences. */
data class WatchHistory(val entries: List<WatchEntry> = emptyList()) {
    /**
     * Saves progress and returns the new history. Finished items are removed;
     * items barely started are ignored and leave any earlier progress alone;
     * everything else moves to the front.
     */
    fun record(update: WatchEntry): WatchHistory {
        var entry = update
        if (!entry.position.isFinite() || entry.position < 0) return this
        val duration = entry.duration
        if (duration != null && (!duration.isFinite() || duration <= 0)) {
            entry = entry.copy(duration = null)
        }

        if (isFinished(entry)) return remove(entry.id)
        if (entry.position < MINIMUM_POSITION) return this

        val rest = entries.filter { it.id != entry.id }
        return WatchHistory((listOf(entry) + rest).take(MAX_ENTRIES))
    }

    fun remove(id: String): WatchHistory =
        if (entries.none { it.id == id }) this else WatchHistory(entries.filter { it.id != id })

    fun entry(id: String): WatchEntry? = entries.firstOrNull { it.id == id }

    fun toJson(): JsonObject = buildJsonObject {
        put("entries", buildJsonArray { entries.forEach { add(it.toJson()) } })
    }

    companion object {
        /** Most items kept. */
        const val MAX_ENTRIES = 30

        /** Seconds watched before an item counts as started. */
        const val MINIMUM_POSITION = 30.0

        /** At or past this fraction an item counts as finished and leaves the list. */
        const val FINISHED_FRACTION = 0.95

        internal fun isFinished(entry: WatchEntry): Boolean {
            val progress = entry.progress ?: return false
            return progress >= FINISHED_FRACTION
        }

        fun fromJson(root: JsonElement): WatchHistory =
            WatchHistory((root as? JsonObject)?.get("entries").lossyList(WatchEntry::fromJson))
    }
}
