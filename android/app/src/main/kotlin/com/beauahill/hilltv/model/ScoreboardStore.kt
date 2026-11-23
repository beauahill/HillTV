package com.beauahill.hilltv.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.beauahill.hilltv.core.EPGSchedule
import com.beauahill.hilltv.core.GameChannelMatch
import com.beauahill.hilltv.core.GameMatcher
import com.beauahill.hilltv.core.LiveStream
import com.beauahill.hilltv.core.ScoreboardClient
import com.beauahill.hilltv.core.SportsGame
import com.beauahill.hilltv.core.SportsLeague
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live scores for the Sports pill, and which channels probably carry each game.
 *
 * Scores come from an unofficial public feed (see `Scoreboard` in core), so
 * every failure here is quiet: no scores means Sports shows plain channels.
 * Nothing runs unless the caller asks, so the feed is only polled while
 * games are on screen.
 */
class ScoreboardStore(userAgent: String) {
    var games: List<SportsGame> by mutableStateOf(emptyList())
        private set

    /** Channels that may carry each game, by game ID. See `GameMatcher`. */
    var matches: Map<String, List<GameChannelMatch>> by mutableStateOf(emptyMap())
        private set
    var hasLoaded by mutableStateOf(false)
        private set

    /**
     * True once channels have been matched to games at least once. Until
     * then an empty `matches` means "still looking", not "nothing found".
     */
    var hasMatched by mutableStateOf(false)
        private set

    private val byLeague = HashMap<String, List<SportsGame>>()

    /** Counts matching runs. A run that is no longer the newest stops and must not publish. */
    private val matchRun = AtomicInteger(0)
    private val client = ScoreboardClient(userAgent)

    /** Asks every league once. Leagues that fail keep their last result. */
    suspend fun refresh() {
        val results = coroutineScope {
            SportsLeague.defaults.map { league ->
                async {
                    league.id to try {
                        client.games(league)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll()
        }
        for ((id, list) in results) {
            if (list != null) byLeague[id] = list
        }
        games = SportsLeague.defaults.flatMap { byLeague[it.id] ?: emptyList() }
        hasLoaded = true
    }

    /**
     * Works out the channels for `games` off the main thread. A new call
     * replaces one still running, so two never work at the same time.
     */
    suspend fun rematch(games: List<SportsGame>, channels: List<LiveStream>, schedules: Map<Int, EPGSchedule>) {
        val run = matchRun.incrementAndGet()
        if (games.isEmpty() || channels.isEmpty()) {
            matches = emptyMap()
            return
        }
        val result = withContext(Dispatchers.Default) {
            GameMatcher.matchesForAll(games, channels, schedules) { matchRun.get() != run }
        }
        // Replaced while it ran: the newer run publishes instead.
        if (matchRun.get() != run) return
        matches = result
        hasMatched = true
    }
}
