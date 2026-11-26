package com.beauahill.hilltv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuideWindowTests {
    private val window = GuideWindow(at(0), at(3 * 3600))

    @Test
    fun testStartingFloorsToHalfHour() {
        val window = GuideWindow.starting(at(17 * 60), hours = 3.0)
        assertEquals(at(0), window.start)
        assertEquals(3 * 3600.0, window.duration, 0.0)
        assertEquals(6, window.slots().size)
    }

    @Test
    fun testCellsClipFillGapsAndTileTheWindow() {
        val cells = window.cells(
            listings(
                Triple("early", -1800, 1800),     // spans the window start
                Triple("mid", 3600, 5400),        // gap before it
                Triple("late", 9000, 14400),      // runs past the end
                Triple("outside", 20000, 21000),  // fully outside
            )
        )
        assertEquals(listOf("early", null, "mid", null, "late"), cells.map { it.entry?.title })
        assertEquals(window.start, cells.first().start)
        assertEquals(window.end, cells.last().end)
        assertEquals(1.0, cells.sumOf { it.width }, 0.0001)
        assertEquals(1800.0 / (3 * 3600), cells[0].width, 0.0001)
    }

    @Test
    fun testOverlapsAreTrimmedAndZeroLengthDropped() {
        val cells = window.cells(
            listings(
                Triple("a", 0, 3600),
                Triple("b", 1800, 5400),   // overlaps a
                Triple("zero", 6000, 6000),
            )
        )
        assertEquals(listOf("a", "b", null), cells.map { it.entry?.title })
        assertEquals(at(3600), cells[1].start)
    }

    @Test
    fun testNoListingsGivesOneFocusableCell() {
        val cells = window.cells(emptyList())
        assertEquals(1, cells.size)
        assertNull(cells[0].entry)
        assertEquals(1.0, cells[0].width, 0.0001)
    }

    @Test
    fun testFractionClamps() {
        assertEquals(0.0, window.fraction(at(-100)), 0.0)
        assertEquals(0.5, window.fraction(at(5400)), 0.0001)
        assertEquals(1.0, window.fraction(at(99999)), 0.0)
    }
}
