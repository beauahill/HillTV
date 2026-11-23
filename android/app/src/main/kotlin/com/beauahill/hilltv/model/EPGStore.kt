package com.beauahill.hilltv.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.beauahill.hilltv.core.EPGSchedule
import com.beauahill.hilltv.core.XtreamClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.coroutines.coroutineContext

/**
 * Short EPG per channel, shared by Live TV and Home.
 *
 * Rows ask for a channel while they are on screen. Requests run a few at a
 * time and results are cached, empty ones included, so a channel with no
 * guide data isn't refetched every time it scrolls into view. See
 * `EPGSchedule.needsFetch` for when a channel is asked again.
 *
 * Main thread only.
 */
class EPGStore(val client: XtreamClient, private val scope: CoroutineScope) {
    val schedules = mutableStateMapOf<Int, EPGSchedule>()

    /** Moves forward twice a minute so progress bars and "min left" labels keep up. */
    var now: Instant by mutableStateOf(Instant.now())
        private set

    private val fetchedAt = HashMap<Int, Instant>()

    /** Rows on screen now, so listings that run out can be refreshed in place. */
    private val visible = HashSet<Int>()
    private val appearJobs = HashMap<Int, Job>()
    private val waiting = ArrayList<Int>()
    private val queued = HashSet<Int>()
    private var active = 0

    fun schedule(streamID: Int): EPGSchedule? = schedules[streamID]

    /** Runs until the calling coroutine is cancelled. Start it from a LaunchedEffect. */
    suspend fun runClock() {
        now = Instant.now()
        while (coroutineContext.isActive) {
            delay(30_000)
            now = Instant.now()
            // A TV app can stay open for days. Rows that stay on screen never
            // "appear" again, so refresh the ones whose listings have run out.
            for (streamID in visible.toList()) enqueue(streamID)
        }
    }

    /**
     * A row came on screen. Fetches only if it stays there for a moment, so
     * fast scrolling doesn't flood the panel.
     */
    fun rowAppeared(streamID: Int) {
        visible.add(streamID)
        if (!needsFetch(streamID) || streamID in queued || appearJobs[streamID] != null) return
        appearJobs[streamID] = scope.launch {
            delay(300)
            appearJobs.remove(streamID)
            enqueue(streamID)
        }
    }

    fun rowDisappeared(streamID: Int) {
        visible.remove(streamID)
        appearJobs.remove(streamID)?.cancel()
        if (waiting.remove(streamID)) queued.remove(streamID)
    }

    /** The focused channel: skip the wait and go to the front of the queue. */
    fun prioritize(streamID: Int) {
        appearJobs.remove(streamID)?.cancel()
        if (waiting.remove(streamID)) queued.remove(streamID)
        enqueue(streamID)
    }

    private fun needsFetch(streamID: Int): Boolean =
        EPGSchedule.needsFetch(schedules[streamID], fetchedAt[streamID], Instant.now())

    private fun enqueue(streamID: Int) {
        if (!needsFetch(streamID) || streamID in queued) return
        queued.add(streamID)
        waiting.add(streamID)
        pump()
    }

    private fun pump() {
        // Newest request first: that's what just scrolled into view or took focus.
        while (active < MAX_CONCURRENT && waiting.isNotEmpty()) {
            val streamID = waiting.removeAt(waiting.lastIndex)
            active += 1
            scope.launch {
                try {
                    val entries = try {
                        client.shortEPG(streamID, ENTRIES_PER_CHANNEL)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                    if (entries != null) {
                        schedules[streamID] = EPGSchedule(entries)
                    } else if (schedules[streamID] == null) {
                        // Failed with nothing cached: show "no guide data" rather than a spinner.
                        schedules[streamID] = EPGSchedule.empty
                    }
                    // Stamp failures too, so they wait out the retry interval.
                    fetchedAt[streamID] = Instant.now()
                } finally {
                    queued.remove(streamID)
                    active -= 1
                }
                pump()
            }
        }
    }

    private companion object {
        const val MAX_CONCURRENT = 4
        const val ENTRIES_PER_CHANNEL = 6
    }
}
