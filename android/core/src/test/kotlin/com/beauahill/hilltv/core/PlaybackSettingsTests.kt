package com.beauahill.hilltv.core

import com.beauahill.hilltv.core.PlaybackSettings.LiveBuffer
import com.beauahill.hilltv.core.PlaybackSettings.LiveDelay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSettingsTests {
    private fun decode(json: String) = PlaybackSettings.fromJson(parseJson(json))

    @Test
    fun testDefaultsMatchThePlayerBeforeSettingsExisted() {
        val settings = PlaybackSettings()
        assertEquals(LiveBuffer.STANDARD, settings.liveBuffer)
        assertEquals(4.0, settings.liveBuffer.seconds, 0.0)
        assertEquals(LiveDelay.STANDARD, settings.liveDelay)
        assertFalse(settings.preservesLiveOffset)
        assertNull(settings.liveOffsetTarget(30.0))
        assertEquals(4.0, settings.liveBufferSeconds(null), 0.0)
    }

    @Test
    fun testBufferSecondsAreInAscendingOrder() {
        assertEquals(listOf(1.0, 2.0, 4.0, 10.0, 30.0), LiveBuffer.entries.map { it.seconds })
    }

    @Test
    fun testRoundTrip() {
        val settings = PlaybackSettings(liveBuffer = LiveBuffer.THIRTY_SECONDS, liveDelay = LiveDelay.CLOSEST)
        assertEquals(settings, decode(settings.toJson().toString()))
    }

    @Test
    fun testMissingKeysFallBackToDefaults() {
        assertEquals(PlaybackSettings(), decode("{}"))

        val partial = decode("""{"liveDelay":"keepUp"}""")
        assertEquals(LiveDelay.KEEP_UP, partial.liveDelay)
        assertEquals(LiveBuffer.STANDARD, partial.liveBuffer)
    }

    @Test
    fun testAnUnknownValueResetsOnlyItsOwnSetting() {
        val settings = decode("""{"liveBuffer":"oneHour","liveDelay":"closest","somethingNew":true}""")
        assertEquals(LiveBuffer.STANDARD, settings.liveBuffer)
        assertEquals(LiveDelay.CLOSEST, settings.liveDelay)

        val wrongType = decode("""{"liveBuffer":10,"liveDelay":"keepUp"}""")
        assertEquals(LiveBuffer.STANDARD, wrongType.liveBuffer)
        assertEquals(LiveDelay.KEEP_UP, wrongType.liveDelay)
    }

    @Test
    fun testOnlyClosestMovesTheLiveOffset() {
        var settings = PlaybackSettings(liveDelay = LiveDelay.KEEP_UP)
        assertTrue(settings.preservesLiveOffset)
        assertNull(settings.liveOffsetTarget(30.0))

        settings = settings.copy(liveDelay = LiveDelay.CLOSEST)
        assertTrue(settings.preservesLiveOffset)
        assertEquals(15.0, settings.liveOffsetTarget(30.0))
    }

    @Test
    fun testClosestTargetIsHalfButNotUnderFourSecondsOrPastTheRecommendation() {
        val settings = PlaybackSettings(liveDelay = LiveDelay.CLOSEST)
        assertEquals(15.0, settings.liveOffsetTarget(30.0))
        assertEquals(4.5, settings.liveOffsetTarget(9.0))
        assertEquals(4.0, settings.liveOffsetTarget(6.0))
        // Already closer than four seconds: leave it where the stream wants it.
        assertEquals(3.0, settings.liveOffsetTarget(3.0))
    }

    @Test
    fun testAnUnknownRecommendationLeavesThePlayerAlone() {
        val settings = PlaybackSettings(liveDelay = LiveDelay.CLOSEST)
        assertNull(settings.liveOffsetTarget(null))
        assertNull(settings.liveOffsetTarget(0.0))
        assertNull(settings.liveOffsetTarget(-5.0))
        assertNull(settings.liveOffsetTarget(Double.NaN))
        assertNull(settings.liveOffsetTarget(Double.POSITIVE_INFINITY))
    }

    @Test
    fun testAChosenDistanceCapsTheLiveBuffer() {
        var settings = PlaybackSettings(liveBuffer = LiveBuffer.THIRTY_SECONDS)
        assertEquals(15.0, settings.liveBufferSeconds(15.0), 0.0)
        assertEquals(30.0, settings.liveBufferSeconds(60.0), 0.0)
        assertEquals(30.0, settings.liveBufferSeconds(null), 0.0)
        assertEquals(30.0, settings.liveBufferSeconds(0.0), 0.0)

        settings = settings.copy(liveBuffer = LiveBuffer.ONE_SECOND)
        assertEquals(1.0, settings.liveBufferSeconds(15.0), 0.0)
    }
}
