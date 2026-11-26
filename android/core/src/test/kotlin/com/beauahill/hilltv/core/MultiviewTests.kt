package com.beauahill.hilltv.core

import com.beauahill.hilltv.core.MultiviewTiles.AddResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiviewTests {
    @Test
    fun testTileLimitFollowsTheAccount() {
        assertEquals(1, Multiview.tileLimit(maxConnections = 1))
        assertEquals(2, Multiview.tileLimit(maxConnections = 2))
        assertEquals(3, Multiview.tileLimit(maxConnections = 3))
        assertEquals(4, Multiview.tileLimit(maxConnections = 10))
        // Nonsense from the panel still leaves the one channel a player needs.
        assertEquals(1, Multiview.tileLimit(maxConnections = 0))
        assertEquals(1, Multiview.tileLimit(maxConnections = -1))
        // A panel that doesn't say is not treated as one connection.
        assertEquals(4, Multiview.tileLimit(maxConnections = null))
    }

    @Test
    fun testViewerCanChooseToTryPastTheReportedLimit() {
        assertEquals(4, Multiview.tileLimit(maxConnections = 1, ignoringLimit = true))
        assertEquals(4, Multiview.tileLimit(maxConnections = 2, ignoringLimit = true))
        assertEquals(4, Multiview.tileLimit(maxConnections = null, ignoringLimit = true))
        assertTrue(Multiview.isAvailable(maxConnections = 1, ignoringLimit = true))
        // Off is the default, and the account's count stands.
        assertEquals(1, Multiview.tileLimit(maxConnections = 1, ignoringLimit = false))
    }

    @Test
    fun testAvailability() {
        assertFalse(Multiview.isAvailable(maxConnections = 1))
        assertTrue(Multiview.isAvailable(maxConnections = 2))
        assertTrue(Multiview.isAvailable(maxConnections = null))
    }

    @Test
    fun testGrid() {
        assertEquals(Multiview.Grid(1, 1), Multiview.grid(0))
        assertEquals(Multiview.Grid(1, 1), Multiview.grid(1))
        assertEquals(Multiview.Grid(2, 1), Multiview.grid(2))
        assertEquals(Multiview.Grid(2, 2), Multiview.grid(3))
        assertEquals(Multiview.Grid(2, 2), Multiview.grid(4))
    }

    @Test
    fun testOneCellFillsA16By9Screen() {
        assertEquals(
            listOf(Multiview.Frame(x = 0.0, y = 0.0, width = 1920.0, height = 1080.0)),
            Multiview.frames(cells = 1, width = 1920.0, height = 1080.0, spacing = 8.0),
        )
    }

    @Test
    fun testTwoCellsSitSideBySideInTheMiddle() {
        val frames = Multiview.frames(cells = 2, width = 1920.0, height = 1080.0, spacing = 8.0)
        assertEquals(2, frames.size)
        assertEquals(956.0, frames[0].width, 0.0)
        assertEquals(537.75, frames[0].height, 0.0)
        assertEquals(0.0, frames[0].x, 0.0)
        assertEquals(964.0, frames[1].x, 0.0)
        // Centered top to bottom.
        assertEquals((1080 - 537.75) / 2, frames[0].y, 0.0)
        assertEquals(frames[0].y, frames[1].y, 0.0)
    }

    @Test
    fun testFourCellsMakeAGrid() {
        val frames = Multiview.frames(cells = 4, width = 1920.0, height = 1080.0, spacing = 8.0)
        assertEquals(4, frames.size)
        for (frame in frames) {
            assertEquals(16.0 / 9.0, frame.width / frame.height, 0.001)
            assertTrue(frame.x >= 0)
            assertTrue(frame.x + frame.width <= 1920.001)
            assertTrue(frame.y + frame.height <= 1080.001)
        }
        assertEquals(frames[0].y, frames[1].y, 0.0)
        assertEquals(frames[0].x, frames[2].x, 0.0)
        assertTrue(frames[1].x > frames[0].x + frames[0].width)
        assertTrue(frames[2].y > frames[0].y + frames[0].height)
        // Three cells use the same grid and leave the last place empty.
        assertEquals(frames.take(3), Multiview.frames(cells = 3, width = 1920.0, height = 1080.0, spacing = 8.0))
    }

    @Test
    fun testFramesWithNothingToLayOut() {
        assertEquals(emptyList<Multiview.Frame>(), Multiview.frames(cells = 0, width = 1920.0, height = 1080.0, spacing = 8.0))
        assertEquals(emptyList<Multiview.Frame>(), Multiview.frames(cells = 2, width = 0.0, height = 0.0, spacing = 8.0))
        // More than the grid holds: only the grid's places come back.
        assertEquals(4, Multiview.frames(cells = 9, width = 1920.0, height = 1080.0, spacing = 8.0).size)
    }

    @Test
    fun testPictureCapShrinksAsChannelsAreAdded() {
        assertNull(Multiview.pictureCap(tiles = 1, fullScreen = false))
        assertEquals(Multiview.PictureCap(1280, 720), Multiview.pictureCap(tiles = 2, fullScreen = false))
        assertEquals(Multiview.PictureCap(960, 540), Multiview.pictureCap(tiles = 3, fullScreen = false))
        assertEquals(Multiview.PictureCap(960, 540), Multiview.pictureCap(tiles = 4, fullScreen = false))
        // A tile shown full screen gets the full picture back.
        assertNull(Multiview.pictureCap(tiles = 4, fullScreen = true))
    }

    @Test
    fun testTiles() {
        var tiles = MultiviewTiles()
        assertTrue(tiles.isEmpty)

        fun add(id: Int, name: String): AddResult {
            val (next, result) = tiles.add(LiveStream(id = id, name = name), limit = 2)
            tiles = next
            return result
        }

        assertEquals(AddResult.ADDED, add(1, "One"))
        assertEquals(AddResult.ALREADY_THERE, add(1, "One"))
        assertEquals(AddResult.ADDED, add(2, "Two"))
        assertEquals(AddResult.FULL, add(3, "Three"))
        // Already there wins over full, so opening one that is playing never reads as an error.
        assertEquals(AddResult.ALREADY_THERE, add(2, "Two"))
        assertEquals(listOf(1, 2), tiles.streams.map { it.id })
        assertTrue(tiles.contains(2))
        tiles = tiles.remove(1)
        assertEquals(listOf(2), tiles.streams.map { it.id })
        assertEquals(AddResult.ADDED, add(3, "Three"))
        // Swift's removeAll(): a fresh value.
        tiles = MultiviewTiles()
        assertTrue(tiles.isEmpty)
    }

    @Test
    fun testFreeConnectionOnAnotherAccount() {
        assertTrue(Multiview.hasFreeConnection(1, 0, 0))
        assertFalse(Multiview.hasFreeConnection(1, 1, 0))
        assertTrue(Multiview.hasFreeConnection(2, 1, 1))
        // A stream started here that the panel hasn't counted yet.
        assertFalse(Multiview.hasFreeConnection(1, 0, 1))
        // Someone else in the house is watching on it.
        assertFalse(Multiview.hasFreeConnection(2, 2, 0))
        // A panel that doesn't say is never borrowed from.
        assertFalse(Multiview.hasFreeConnection(null, 0, 0))
        assertFalse(Multiview.hasFreeConnection(3, null, 0))
    }

    @Test
    fun testOwnLimit() {
        assertEquals(1, Multiview.ownLimit(1))
        assertEquals(2, Multiview.ownLimit(2))
        assertEquals(4, Multiview.ownLimit(null))
        assertEquals(1, Multiview.ownLimit(0))
    }

    @Test
    fun testTilesRememberTheirAccount() {
        var tiles = MultiviewTiles()
        tiles = tiles.add(LiveStream(id = 1, name = "One"), 4, "a").first
        tiles = tiles.add(LiveStream(id = 2, name = "Two"), 4, "b").first
        tiles = tiles.add(LiveStream(id = 3, name = "Three"), 4, "a").first
        assertEquals("b", tiles.account(2))
        assertEquals(mapOf("a" to 2, "b" to 1), tiles.countByAccount)
        tiles = tiles.remove(1)
        assertEquals(mapOf("a" to 1, "b" to 1), tiles.countByAccount)
        assertEquals(null, tiles.account(1))
        tiles = MultiviewTiles()
        assertEquals(emptyMap<String, Int>(), tiles.countByAccount)
    }

    @Test
    fun testRemovingAnAccountDropsItsChannels() {
        var tiles = MultiviewTiles()
        tiles = tiles.add(LiveStream(id = 1, name = "One"), 4, "a").first
        tiles = tiles.add(LiveStream(id = 2, name = "Two"), 4, "b").first
        tiles = tiles.add(LiveStream(id = 3, name = "Three"), 4, "b").first
        tiles = tiles.removeAll("b")
        assertEquals(listOf(1), tiles.streams.map { it.id })
        assertEquals(mapOf("a" to 1), tiles.countByAccount)
    }

    @Test
    fun testBorrowMessage() {
        assertEquals(null, Multiview.borrowMessage(emptyList()))
        assertEquals(
            "Your other account on this server has no connection free right now, so this channel plays on the account in use.",
            Multiview.borrowMessage(listOf(Multiview.BorrowRefusal.NONE_FREE)),
        )
        assertEquals(
            "Your other account on this server can't be used: the provider doesn't say how many of its connections are in use, so this channel plays on the account in use.",
            Multiview.borrowMessage(listOf(Multiview.BorrowRefusal.NOT_REPORTED)),
        )
        assertEquals(
            "Your other accounts on this server couldn't all be signed in to, or had no connection free, so this channel plays on the account in use.",
            Multiview.borrowMessage(listOf(Multiview.BorrowRefusal.NONE_FREE, Multiview.BorrowRefusal.SIGN_IN_FAILED)),
        )
    }
}
