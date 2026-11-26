package com.beauahill.hilltv.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The user's playback choices. Stored as small JSON.
 *
 * The defaults reproduce the player's behavior from before these settings
 * existed, so someone who never opens Settings sees no change.
 */
data class PlaybackSettings(
    val liveBuffer: LiveBuffer = LiveBuffer.STANDARD,
    val liveDelay: LiveDelay = LiveDelay.STANDARD,
) {
    /**
     * How much of a live channel to load ahead. Movies and episodes are
     * left to the player, which sizes their buffer by itself.
     */
    enum class LiveBuffer(val id: String, val seconds: Double) {
        ONE_SECOND("oneSecond", 1.0),
        TWO_SECONDS("twoSeconds", 2.0),
        STANDARD("standard", 4.0),
        TEN_SECONDS("tenSeconds", 10.0),
        THIRTY_SECONDS("thirtySeconds", 30.0),
    }

    /** How far behind the broadcast a live channel plays. */
    enum class LiveDelay(val id: String) {
        /** The player picks the distance. */
        STANDARD("standard"),

        /** After a stall the player returns to the distance it had, so the delay doesn't grow over an evening. */
        KEEP_UP("keepUp"),

        /** As `KEEP_UP`, and starts nearer the live edge too. */
        CLOSEST("closest"),
    }

    /** Whether the player should return to its distance from live after a stall. */
    val preservesLiveOffset: Boolean get() = liveDelay != LiveDelay.STANDARD

    /**
     * Seconds behind the live edge to aim for, or null to leave the player's
     * own choice alone. Only `CLOSEST` moves it: to half of what the stream
     * recommends, but not under four seconds and never further back than
     * the recommendation itself. `recommended` is what the player reports
     * once the stream has loaded; null, zero or a non-number means unknown.
     */
    fun liveOffsetTarget(recommended: Double?): Double? {
        if (liveDelay != LiveDelay.CLOSEST || recommended == null || !recommended.isFinite() || recommended <= 0) return null
        return minOf(recommended, maxOf(recommended / 2, 4.0))
    }

    /**
     * The live buffer to ask for. A live stream can only hold as much ahead
     * as it is playing behind the live edge, so a chosen distance caps it.
     */
    fun liveBufferSeconds(offsetTarget: Double?): Double {
        if (offsetTarget == null || offsetTarget <= 0) return liveBuffer.seconds
        return minOf(liveBuffer.seconds, offsetTarget)
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("liveBuffer", liveBuffer.id)
        put("liveDelay", liveDelay.id)
    }

    companion object {
        /**
         * A missing key or a value this version doesn't know falls back to the
         * default for that setting alone, so adding or renaming an option later
         * never resets the others.
         */
        fun fromJson(root: JsonElement): PlaybackSettings {
            val o = root as? JsonObject ?: throw XtreamError.Decoding("settings are not an object")
            fun string(key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return PlaybackSettings(
                liveBuffer = LiveBuffer.entries.firstOrNull { it.id == string("liveBuffer") } ?: LiveBuffer.STANDARD,
                liveDelay = LiveDelay.entries.firstOrNull { it.id == string("liveDelay") } ?: LiveDelay.STANDARD,
            )
        }
    }
}
