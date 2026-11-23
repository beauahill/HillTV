package com.beauahill.hilltv.core

import java.time.Instant
import kotlin.math.ceil

/**
 * One channel's listings, ready for "what's on now" rows and a short
 * timeline. Every question takes `now` so the answers are testable.
 */
class EPGSchedule(entries: List<EPGEntry>) {
    /** Sorted by start time, with zero-length entries dropped. */
    val entries: List<EPGEntry> = entries.filter { it.end > it.start }.sortedBy { it.start }

    /** The programme airing at `now`, if the listings cover it. */
    fun current(at: Instant): EPGEntry? = entries.firstOrNull { it.isOnAir(at) }

    /** The first programme that starts after `now`. */
    fun next(after: Instant): EPGEntry? = entries.firstOrNull { it.start > after }

    /** What's on now followed by what comes after, for a timeline. */
    fun upcoming(from: Instant, limit: Int): List<EPGEntry> =
        entries.filter { it.end > from }.take(maxOf(0, limit))

    /** False once everything listed has finished airing (or nothing was listed). */
    fun covers(now: Instant): Boolean = entries.any { it.end > now }

    override fun equals(other: Any?): Boolean = other is EPGSchedule && other.entries == entries

    override fun hashCode(): Int = entries.hashCode()

    companion object {
        val empty = EPGSchedule(emptyList())

        /** Whole minutes until `entry` ends, rounded up, never negative. */
        fun minutesLeft(entry: EPGEntry, at: Instant): Int {
            val left = seconds(at, entry.end)
            if (left <= 0) return 0
            return ceil(left / 60).toInt()
        }

        /**
         * Whether to ask the panel again. Listings that still cover `now` are
         * kept. Empty or fully aired listings are retried, but only after
         * `retryInterval`, so channels with no guide data don't refetch every
         * time their row scrolls into view.
         */
        fun needsFetch(
            schedule: EPGSchedule?,
            fetchedAt: Instant?,
            now: Instant,
            retryIntervalSeconds: Double = 600.0,
        ): Boolean {
            if (schedule == null || fetchedAt == null) return true
            if (schedule.covers(now)) return false
            return seconds(fetchedAt, now) >= retryIntervalSeconds
        }
    }
}
