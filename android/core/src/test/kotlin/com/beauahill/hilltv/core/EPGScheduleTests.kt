package com.beauahill.hilltv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EPGScheduleTests {
    private fun schedule(vararg spans: Triple<String, Long, Long>) = EPGSchedule(listings(*spans))

    @Test
    fun testSortsAndDropsZeroLengthEntries() {
        val result = schedule(
            Triple("late", 3600, 7200),
            Triple("broken", 600, 600),
            Triple("early", 0, 3600),
        )
        assertEquals(listOf("early", "late"), result.entries.map { it.title })
    }

    @Test
    fun testCurrentAndNext() {
        val result = schedule(
            Triple("news", 0, 3600),
            Triple("weather", 3600, 5400),
            Triple("sport", 5400, 9000),
        )
        assertEquals("news", result.current(at(1800))?.title)
        assertEquals("weather", result.next(at(1800))?.title)
        // A boundary belongs to the programme that is starting.
        assertEquals("weather", result.current(at(3600))?.title)
        assertEquals("sport", result.next(at(3600))?.title)
        assertNull(result.current(at(9000)))
        assertNull(result.next(at(9000)))
    }

    @Test
    fun testCurrentIsNilInsideAGap() {
        val result = schedule(
            Triple("news", 0, 1800),
            Triple("sport", 3600, 7200),
        )
        assertNull(result.current(at(2400)))
        assertEquals("sport", result.next(at(2400))?.title)
    }

    @Test
    fun testUpcomingStartsWithWhatIsOnAndRespectsLimit() {
        val result = schedule(
            Triple("aired", -3600, 0),
            Triple("news", 0, 3600),
            Triple("weather", 3600, 5400),
            Triple("sport", 5400, 9000),
        )
        assertEquals(listOf("news", "weather"), result.upcoming(at(60), 2).map { it.title })
        assertEquals(listOf("news", "weather", "sport"), result.upcoming(at(60), 10).map { it.title })
        assertEquals(emptyList<EPGEntry>(), result.upcoming(at(60), 0))
        assertEquals(emptyList<EPGEntry>(), result.upcoming(at(60), -1))
    }

    @Test
    fun testCovers() {
        val result = schedule(Triple("news", 0, 3600))
        assertTrue(result.covers(at(10)))
        assertFalse(result.covers(at(3600)))
        assertFalse(EPGSchedule.empty.covers(at(0)))
    }

    @Test
    fun testMinutesLeftRoundsUpAndNeverGoesNegative() {
        val news = schedule(Triple("news", 0, 3600)).entries.first()
        assertEquals(60, EPGSchedule.minutesLeft(news, at(0)))
        assertEquals(1, EPGSchedule.minutesLeft(news, at(3541)))
        assertEquals(0, EPGSchedule.minutesLeft(news, at(3600)))
        assertEquals(0, EPGSchedule.minutesLeft(news, at(4000)))
    }

    @Test
    fun testNeedsFetchWhenNeverFetched() {
        assertTrue(EPGSchedule.needsFetch(schedule = null, fetchedAt = null, now = at(0)))
        assertTrue(EPGSchedule.needsFetch(schedule = EPGSchedule.empty, fetchedAt = null, now = at(0)))
    }

    @Test
    fun testFreshListingsAreNotRefetched() {
        val result = schedule(Triple("news", 0, 3600))
        assertFalse(EPGSchedule.needsFetch(result, fetchedAt = at(-7200), now = at(1800)))
    }

    @Test
    fun testEmptyListingsWaitForTheRetryInterval() {
        assertFalse(EPGSchedule.needsFetch(EPGSchedule.empty, fetchedAt = at(0), now = at(599)))
        assertTrue(EPGSchedule.needsFetch(EPGSchedule.empty, fetchedAt = at(0), now = at(600)))
    }

    @Test
    fun testAiredListingsAreRefetchedAfterTheRetryInterval() {
        val result = schedule(Triple("news", 0, 3600))
        // Just fetched and already stale: the panel has nothing newer yet.
        assertFalse(EPGSchedule.needsFetch(result, fetchedAt = at(3600), now = at(3700)))
        assertTrue(EPGSchedule.needsFetch(result, fetchedAt = at(0), now = at(3700)))
    }
}
