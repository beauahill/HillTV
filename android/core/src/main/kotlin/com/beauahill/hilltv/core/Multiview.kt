package com.beauahill.hilltv.core

/** Rules for watching several live channels at once. */
object Multiview {
    /** Most channels on screen together. */
    const val MOST_TILES = 4

    /**
     * How many channels may play together on this account. Every tile is
     * its own connection to the provider, so the account's limit is the
     * real ceiling. A panel that doesn't say gets the benefit of the doubt:
     * a tile it refuses shows an error instead.
     *
     * `ignoringLimit` is the viewer's choice to try more than the account
     * reports. What a panel reports and what it enforces can differ, and
     * only trying shows which.
     */
    fun tileLimit(maxConnections: Int?, ignoringLimit: Boolean = false): Int {
        if (maxConnections == null || ignoringLimit) return MOST_TILES
        return minOf(MOST_TILES, maxOf(1, maxConnections))
    }

    /** Multiview needs room for at least two channels. */
    fun isAvailable(maxConnections: Int?, ignoringLimit: Boolean = false): Boolean =
        tileLimit(maxConnections, ignoringLimit) >= 2

    data class Grid(val columns: Int, val rows: Int)

    /**
     * Columns and rows for a number of cells: one, two side by side, or a
     * two by two grid.
     */
    fun grid(cells: Int): Grid = when {
        cells <= 1 -> Grid(1, 1)
        cells == 2 -> Grid(2, 1)
        else -> Grid(2, 2)
    }

    data class Frame(val x: Double, val y: Double, val width: Double, val height: Double)

    /**
     * Where each cell goes: a 16:9 box centered in its part of the grid,
     * in reading order.
     */
    fun frames(cells: Int, width: Double, height: Double, spacing: Double): List<Frame> {
        if (cells <= 0 || width <= 0 || height <= 0) return emptyList()
        val grid = grid(cells)
        val cellWidth = (width - spacing * (grid.columns - 1)) / grid.columns
        val cellHeight = (height - spacing * (grid.rows - 1)) / grid.rows
        val boxWidth = minOf(cellWidth, cellHeight * 16 / 9)
        val boxHeight = boxWidth * 9 / 16
        return (0 until minOf(cells, grid.columns * grid.rows)).map { index ->
            val column = index % grid.columns
            val row = index / grid.columns
            Frame(
                x = column * (cellWidth + spacing) + (cellWidth - boxWidth) / 2,
                y = row * (cellHeight + spacing) + (cellHeight - boxHeight) / 2,
                width = boxWidth,
                height = boxHeight,
            )
        }
    }

    data class PictureCap(val width: Int, val height: Int)

    /**
     * The largest picture worth asking for with this many channels
     * playing. Null means no limit: one channel, or a tile shown full screen.
     *
     * The player passes this on as a ceiling. It only changes anything when
     * the provider's stream comes in more than one quality; a stream with
     * a single quality plays as it is.
     */
    fun pictureCap(tiles: Int, fullScreen: Boolean): PictureCap? {
        if (fullScreen || tiles <= 1) return null
        return if (tiles == 2) PictureCap(1280, 720) else PictureCap(960, 540)
    }

    /**
     * Whether another saved account can take one more channel. Only when the
     * panel reports both its connection count and how many are in use:
     * borrowing a connection someone else in the house is watching on would
     * knock their stream off. The channels this multiview already plays on
     * that account count too, since a panel can be slow to report a stream
     * that just started.
     */
    fun hasFreeConnection(maxConnections: Int?, activeConnections: Int?, playingHere: Int): Boolean {
        if (maxConnections == null || activeConnections == null) return false
        return maxConnections - maxOf(activeConnections, playingHere) > 0
    }

    /** Why no other saved account took a channel, for the multiview to say. */
    enum class BorrowRefusal {
        /** The panel said every connection is in use. */
        NONE_FREE,

        /** The panel doesn't report how many connections are in use. */
        NOT_REPORTED,

        /** Signing in to the account failed. */
        SIGN_IN_FAILED,
    }

    /** One sentence about the other accounts, worst case first. */
    fun borrowMessage(refusals: List<BorrowRefusal>): String? {
        if (refusals.isEmpty()) return null
        val one = refusals.size == 1
        val subject = if (one) "Your other account on this server" else "Your other accounts on this server"
        val reason = when {
            refusals.all { it == BorrowRefusal.NONE_FREE } ->
                if (one) "has no connection free right now" else "have no connection free right now"
            BorrowRefusal.SIGN_IN_FAILED in refusals ->
                if (one) "couldn't be signed in to" else "couldn't all be signed in to, or had no connection free"
            else -> "can't be used: the provider doesn't say how many of " + (if (one) "its" else "their") + " connections are in use"
        }
        return "$subject $reason, so this channel plays on the account in use."
    }

    /**
     * How many channels the account in use takes before other accounts are
     * asked. A panel that doesn't say gets the benefit of the doubt, as in
     * [tileLimit].
     */
    fun ownLimit(maxConnections: Int?): Int = maxOf(1, maxConnections ?: MOST_TILES)
}

/** The channels in the multiview, in the order they were added. Immutable; each change returns a copy. */
/**
 * The channels in the multiview, in the order they were added, and the saved
 * account each one plays on (`AccountBook.key`). Never a URL: those hold
 * passwords.
 */
data class MultiviewTiles(
    val streams: List<LiveStream> = emptyList(),
    private val accounts: Map<Int, String> = emptyMap(),
) {
    enum class AddResult {
        ADDED,
        ALREADY_THERE,

        /** The account's connections are all in use. */
        FULL,
    }

    val isEmpty: Boolean get() = streams.isEmpty()

    fun contains(streamID: Int): Boolean = streams.any { it.id == streamID }

    /** The account a channel plays on, null for one added without one. */
    fun account(streamID: Int): String? = accounts[streamID]

    /** How many channels play on each account. */
    val countByAccount: Map<String, Int> get() = accounts.values.groupingBy { it }.eachCount()

    /** The tiles after the attempt, and what came of it. */
    fun add(stream: LiveStream, limit: Int, account: String? = null): Pair<MultiviewTiles, AddResult> {
        if (contains(stream.id)) return this to AddResult.ALREADY_THERE
        if (streams.size >= limit) return this to AddResult.FULL
        val accounts = if (account != null) this.accounts + (stream.id to account) else this.accounts - stream.id
        return MultiviewTiles(streams + stream, accounts) to AddResult.ADDED
    }

    fun remove(streamID: Int): MultiviewTiles = MultiviewTiles(streams.filter { it.id != streamID }, accounts - streamID)

    /** For a removed account: its channels go. */
    fun removeAll(onAccount: String): MultiviewTiles {
        val gone = accounts.filterValues { it == onAccount }.keys
        return MultiviewTiles(streams.filter { it.id !in gone }, accounts - gone)
    }
}
