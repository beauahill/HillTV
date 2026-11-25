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
}

/** The channels in the multiview, in the order they were added. Immutable; each change returns a copy. */
data class MultiviewTiles(val streams: List<LiveStream> = emptyList()) {
    enum class AddResult {
        ADDED,
        ALREADY_THERE,

        /** The account's connections are all in use. */
        FULL,
    }

    val isEmpty: Boolean get() = streams.isEmpty()

    fun contains(streamID: Int): Boolean = streams.any { it.id == streamID }

    /** The tiles after the attempt, and what came of it. */
    fun add(stream: LiveStream, limit: Int): Pair<MultiviewTiles, AddResult> {
        if (contains(stream.id)) return this to AddResult.ALREADY_THERE
        if (streams.size >= limit) return this to AddResult.FULL
        return MultiviewTiles(streams + stream) to AddResult.ADDED
    }

    fun remove(streamID: Int): MultiviewTiles = MultiviewTiles(streams.filter { it.id != streamID })
}
