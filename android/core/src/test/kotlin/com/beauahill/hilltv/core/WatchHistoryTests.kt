package com.beauahill.hilltv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchHistoryTests {
    private fun entry(id: String, position: Double, duration: Double? = 6000.0, kind: WatchEntry.Kind = WatchEntry.Kind.MOVIE) =
        WatchEntry(kind = kind, streamID = id, containerExtension = "mkv", title = "Item $id", position = position, duration = duration)

    @Test
    fun testRecordsAndMovesRewatchedToFront() {
        var history = WatchHistory()
        history = history.record(entry("1", 100.0))
        history = history.record(entry("2", 200.0))
        assertEquals(listOf("2", "1"), history.entries.map { it.streamID })

        history = history.record(entry("1", 300.0))
        assertEquals(listOf("1", "2"), history.entries.map { it.streamID })
        assertEquals(300.0, history.entry("movie-1")?.position)
    }

    @Test
    fun testIgnoresBarelyStartedAndKeepsEarlierProgress() {
        var history = WatchHistory()
        history = history.record(entry("1", 10.0))
        assertTrue(history.entries.isEmpty())

        history = history.record(entry("1", 900.0))
        history = history.record(entry("1", 5.0))
        assertEquals(900.0, history.entry("movie-1")?.position)
    }

    @Test
    fun testFinishedItemsLeaveTheList() {
        var history = WatchHistory()
        history = history.record(entry("1", 900.0))
        history = history.record(entry("1", 5800.0))
        assertNull(history.entry("movie-1"))
    }

    @Test
    fun testUnknownOrBrokenDurationIsKeptWithoutProgress() {
        var history = WatchHistory()
        history = history.record(entry("1", 900.0, duration = null))
        history = history.record(entry("2", 900.0, duration = Double.NaN))
        history = history.record(entry("3", 900.0, duration = Double.POSITIVE_INFINITY))
        history = history.record(entry("4", Double.NaN))
        assertEquals(listOf("3", "2", "1"), history.entries.map { it.streamID })
        assertTrue(history.entries.all { it.duration == null && it.progress == null })
    }

    @Test
    fun testTrimsToMaxEntries() {
        var history = WatchHistory()
        for (index in 0 until WatchHistory.MAX_ENTRIES + 5) {
            history = history.record(entry(index.toString(), 100.0))
        }
        assertEquals(WatchHistory.MAX_ENTRIES, history.entries.size)
        assertEquals((WatchHistory.MAX_ENTRIES + 4).toString(), history.entries.first().streamID)
    }

    @Test
    fun testMoviesAndEpisodesWithSameIDAreSeparate() {
        var history = WatchHistory()
        history = history.record(entry("7", 100.0, kind = WatchEntry.Kind.MOVIE))
        history = history.record(entry("7", 100.0, kind = WatchEntry.Kind.EPISODE))
        assertEquals(2, history.entries.size)
    }

    @Test
    fun testRoundTripsThroughJSON() {
        val history = WatchHistory().record(
            WatchEntry(
                kind = WatchEntry.Kind.EPISODE, streamID = "9001", containerExtension = "mp4", title = "Show",
                subtitle = "S1E2", artworkURL = "http://img.example.com/c.jpg", position = 120.0, duration = 2400.0,
            )
        )
        assertEquals(1, history.entries.size)
        assertEquals(history, WatchHistory.fromJson(parseJson(history.toJson().toString())))
    }

    @Test
    fun testResumeURLsFromIDs() {
        val client = XtreamClient(XtreamAccount("http://line.example.com:8080", "u", "p+w"))
        assertEquals("http://line.example.com:8080/movie/u/p+w/5001.mkv", client.movieURL(5001, "mkv"))
        assertEquals("http://line.example.com:8080/series/u/p+w/9001.mp4", client.episodeURL("9001", "mp4"))
    }
}
