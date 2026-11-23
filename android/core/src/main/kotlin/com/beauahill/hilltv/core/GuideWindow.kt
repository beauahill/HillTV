package com.beauahill.hilltv.core

import java.time.Instant
import kotlin.math.floor

/**
 * A fixed stretch of time shown by the guide grid, and the math to lay
 * programmes out inside it as consecutive cells.
 */
class GuideWindow(val start: Instant, end: Instant) {
    val end: Instant = maxOf(end, start)

    /** Seconds. */
    val duration: Double get() = seconds(start, end)

    /** Where `date` falls in the window, from 0 at the start to 1 at the end. */
    fun fraction(of: Instant): Double {
        if (duration <= 0) return 0.0
        return (seconds(start, of) / duration).coerceIn(0.0, 1.0)
    }

    /** Times for the slot labels along the top. */
    fun slots(minutes: Int = 30): List<Instant> {
        val step = minutes * 60L
        if (step <= 0) return emptyList()
        val result = ArrayList<Instant>()
        var at = start
        while (at < end) {
            result.add(at)
            at = at.plusSeconds(step)
        }
        return result
    }

    /** A slice of the window: a programme, or a gap with no listing. */
    data class Cell(
        val entry: EPGEntry?,
        val start: Instant,
        val end: Instant,
        /** Share of the window's width, 0...1. */
        val width: Double,
    ) {
        val id: String get() = "${start.epochSecond}-${entry?.id ?: "gap"}"
    }

    /**
     * Cells that tile the whole window left to right. Programmes are
     * clipped to the window, overlaps are trimmed so each starts where the
     * previous ended, and gaps become empty cells. A channel with no
     * listings gets one empty cell, so every row has something to focus.
     */
    fun cells(entries: List<EPGEntry>): List<Cell> {
        if (duration <= 0) return emptyList()
        val cells = ArrayList<Cell>()
        var cursor = start

        fun add(entry: EPGEntry?, from: Instant, to: Instant) {
            if (to <= from) return
            cells.add(Cell(entry, from, to, seconds(from, to) / duration))
        }

        for (entry in entries.sortedBy { it.start }) {
            val from = maxOf(entry.start, cursor)
            val to = minOf(entry.end, end)
            if (to <= from) continue
            add(null, cursor, from)
            add(entry, from, to)
            cursor = to
            if (cursor >= end) break
        }
        add(null, cursor, end)
        return cells
    }

    override fun equals(other: Any?): Boolean = other is GuideWindow && other.start == start && other.end == end

    override fun hashCode(): Int = 31 * start.hashCode() + end.hashCode()

    companion object {
        /** Starts at the half hour at or before `now` and runs for `hours`. */
        fun starting(at: Instant, hours: Double, slotMinutes: Int = 30): GuideWindow {
            val slot = slotMinutes * 60.0
            val floored = floor(at.epochSecond / slot) * slot
            val start = Instant.ofEpochSecond(floored.toLong())
            return GuideWindow(start, start.plusMillis((hours * 3_600_000).toLong()))
        }
    }
}
