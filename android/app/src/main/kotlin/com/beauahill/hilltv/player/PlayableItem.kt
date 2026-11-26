package com.beauahill.hilltv.player

import com.beauahill.hilltv.core.VODStream
import com.beauahill.hilltv.core.WatchEntry
import com.beauahill.hilltv.core.XtreamClient

/** Something the player can open. */
data class PlayableItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val url: String,
    val isLive: Boolean,
    /** When set, the player saves progress under this entry (its position is ignored). */
    val history: WatchEntry? = null,
    /** Seconds to start from, for resuming. */
    val startAt: Double? = null,
) {
    companion object {
        /** Rebuilds the stream URL from the IDs saved in history. Stream URLs are never stored: they hold the password. */
        fun resuming(entry: WatchEntry, client: XtreamClient): PlayableItem? {
            val url = when (entry.kind) {
                WatchEntry.Kind.MOVIE -> {
                    val id = entry.streamID.toIntOrNull() ?: return null
                    client.movieURL(id, entry.containerExtension)
                }
                WatchEntry.Kind.EPISODE -> client.episodeURL(entry.streamID, entry.containerExtension)
            }
            return PlayableItem(
                id = entry.id,
                title = entry.title,
                subtitle = entry.subtitle,
                url = url,
                isLive = false,
                history = entry,
                startAt = entry.position,
            )
        }

        /** A movie from the start or from `startAt`, with a history entry so progress is saved. */
        fun movie(
            movie: VODStream,
            title: String,
            subtitle: String?,
            artworkURL: String?,
            client: XtreamClient,
            startAt: Double?,
        ): PlayableItem = PlayableItem(
            id = "movie-${movie.id}",
            title = title,
            subtitle = subtitle,
            url = client.movieURL(movie),
            isLive = false,
            history = WatchEntry(
                kind = WatchEntry.Kind.MOVIE,
                streamID = movie.id.toString(),
                containerExtension = movie.containerExtension,
                title = title,
                subtitle = subtitle,
                artworkURL = artworkURL,
                position = 0.0,
                duration = null,
            ),
            startAt = startAt,
        )
    }
}
