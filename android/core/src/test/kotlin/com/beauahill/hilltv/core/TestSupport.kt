package com.beauahill.hilltv.core

import java.time.Instant

internal const val BASE = 1_800_000_000L // a half-hour boundary

internal fun at(offset: Long): Instant = Instant.ofEpochSecond(BASE + offset)

/** Listings built the way the panel sends them, from (title, start offset, end offset). */
internal fun listings(vararg spans: Triple<String, Long, Long>): List<EPGEntry> {
    val rows = spans.joinToString(",") { (title, start, end) ->
        """{"id":"$title","title":"$title","start_timestamp":"${BASE + start}","stop_timestamp":"${BASE + end}"}"""
    }
    return EPGEntry.decodeShortEPG(parseJson("""{"epg_listings":[$rows]}"""))
}
