package com.beauahill.hilltv.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

// Live scores and team logos come from ESPN's public scoreboard feed, one
// request per league. It is unofficial: no key, no contract, and it can
// change or stop without notice. Everything here decodes leniently and the
// app treats a failure as "no scores", never as an error the user must fix.

data class SportsLeague(
    /** Path component, like "football". */
    val sport: String,
    /** Path component, like "nfl". */
    val slug: String,
    /** Short label for a game row, like "NFL". */
    val name: String,
    /** Extra query string, like "groups=80". The college feeds list only ranked teams' games without one. */
    val query: String? = null,
) {
    val id: String get() = "$sport/$slug"

    companion object {
        val defaults: List<SportsLeague> = listOf(
            SportsLeague("football", "nfl", "NFL"),
            // groups=80 is every FBS game; groups=50 is every Division I game.
            SportsLeague("football", "college-football", "College Football", "groups=80"),
            SportsLeague("basketball", "nba", "NBA"),
            SportsLeague("basketball", "mens-college-basketball", "College Basketball", "groups=50"),
            SportsLeague("hockey", "nhl", "NHL"),
            SportsLeague("baseball", "mlb", "MLB"),
            SportsLeague("soccer", "usa.1", "MLS"),
            SportsLeague("soccer", "eng.1", "Premier League"),
        )
    }
}

data class SportsTeam(
    /** "Granite Peaks Otters" */
    val displayName: String,
    /** "Otters", or a short club name for soccer. */
    val shortName: String,
    /** "Granite Peaks" */
    val location: String? = null,
    /** "Otters" */
    val nickname: String? = null,
    val abbreviation: String? = null,
    val logoURL: String? = null,
    /** As sent, so "0" before kickoff. Null when the feed has none. */
    val score: String? = null,
)

data class SportsGame(
    val id: String,
    val league: SportsLeague,
    val start: Instant,
    val state: State,
    /** The feed's own short status: "13:28 - 3rd", "Halftime", "Final". */
    val statusText: String,
    val home: SportsTeam,
    val away: SportsTeam,
    /** TV networks carrying the game, streaming-only services removed. */
    val networks: List<String> = emptyList(),
) {
    enum class State { SCHEDULED, LIVE, FINISHED }
}

object Scoreboard {
    /**
     * What the Sports screen shows, in order: games in progress, games
     * starting within `lookahead`, then games that finished recently.
     * The feed returns a whole week for some leagues, so this trims it.
     */
    data class Sections(
        val live: List<SportsGame>,
        val upcoming: List<SportsGame>,
        val finished: List<SportsGame>,
    ) {
        val all: List<SportsGame> get() = live + upcoming + finished
        val isEmpty: Boolean get() = live.isEmpty() && upcoming.isEmpty() && finished.isEmpty()
    }

    fun sections(
        games: List<SportsGame>,
        now: Instant,
        lookaheadSeconds: Long = 12 * 3600,
        lookbackSeconds: Long = 6 * 3600,
    ): Sections {
        val sorted = games.sortedWith(compareBy<SportsGame> { it.start }.thenBy { it.id })
        return Sections(
            live = sorted.filter { it.state == SportsGame.State.LIVE },
            upcoming = sorted.filter { it.state == SportsGame.State.SCHEDULED && it.start <= now.plusSeconds(lookaheadSeconds) },
            finished = sorted.filter { it.state == SportsGame.State.FINISHED && it.start >= now.minusSeconds(lookbackSeconds) },
        )
    }

    fun url(league: SportsLeague): String {
        val base = "https://site.api.espn.com/apis/site/v2/sports/${league.sport}/${league.slug}/scoreboard"
        return league.query?.let { "$base?$it" } ?: base
    }

    /**
     * Decodes one league's feed. Events that are missing a team or a start
     * time are dropped; the rest still come through. Throws when the payload
     * is not a JSON object at all.
     */
    fun decode(text: String, league: SportsLeague): List<SportsGame> {
        val root = parseJson(text) as? JsonObject ?: throw XtreamError.Decoding("scoreboard is not an object")
        return root["events"].lossyList { FeedEvent.fromJson(it) }.mapNotNull { it.game(league) }
    }

    private val dateFormats = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmXXX"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"),
    )

    /** "2026-11-22T18:00Z" (the feed omits seconds) or the full ISO form. */
    internal fun parseDate(string: String): Instant? {
        for (format in dateFormats) {
            try {
                return OffsetDateTime.parse(string, format).toInstant()
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    /** Streaming services and team apps aren't channels a provider carries. */
    internal fun isTelevisionNetwork(name: String): Boolean {
        val folded = SearchIndex.fold(name)
        if (folded.isEmpty() || name.contains('+') || name.contains('.')) return false
        val words = folded.split(' ').toSet()
        if ("app" in words || folded.endsWith("vision")) return false
        return folded !in streamingOnly
    }

    private val streamingOnly = setOf(
        "prime video", "prime video local", "peacock", "apple tv", "netflix", "youtube", "max", "paramount",
    )
}

class ScoreboardClient(
    private val userAgent: String = "HillTV",
    private val http: OkHttpClient = XtreamClient.sharedHttp,
) {
    suspend fun games(league: SportsLeague): List<SportsGame> {
        val request = Request.Builder()
            .url(Scoreboard.url(league))
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .build()
        val response = try {
            http.newCall(request).await()
        } catch (error: IOException) {
            throw XtreamError.Network(error.message ?: "Couldn't reach the scores feed.")
        }
        return response.use {
            if (!it.isSuccessful) throw XtreamError.HttpStatus(it.code)
            withContext(Dispatchers.IO) {
                val text = try {
                    it.body.string()
                } catch (error: IOException) {
                    throw XtreamError.Network(error.message ?: "The connection was lost.")
                }
                Scoreboard.decode(text, league)
            }
        }
    }
}

// MARK: - Feed shape

internal class FeedEvent(
    val id: String,
    val date: Instant,
    val state: SportsGame.State,
    val statusText: String,
    val competitors: List<FeedCompetitor>,
    val networks: List<String>,
) {
    fun game(league: SportsLeague): SportsGame? {
        if (competitors.size != 2) return null
        // The feed marks sides; fall back to its order (home first) if it doesn't.
        val home = competitors.firstOrNull { it.isHome == true } ?: competitors[0]
        val away = competitors.firstOrNull { it.isHome == false } ?: competitors[1]
        if (home.team == away.team) return null
        return SportsGame(
            id = "${league.id}/$id",
            league = league,
            start = date,
            state = state,
            statusText = statusText,
            home = home.team,
            away = away.team,
            networks = networks,
        )
    }

    companion object {
        fun fromJson(o: JsonObject): FeedEvent? {
            val id = o.lenientString("id") ?: return null
            val date = o.lenientString("date")?.let(Scoreboard::parseDate) ?: return null

            var state = SportsGame.State.SCHEDULED
            var text = ""
            val type = o.obj("status")?.obj("type")
            if (type != null) {
                state = when (type.lenientString("state") ?: "") {
                    "in" -> SportsGame.State.LIVE
                    "post" -> SportsGame.State.FINISHED
                    else -> SportsGame.State.SCHEDULED
                }
                text = type.lenientString("shortDetail") ?: type.lenientString("detail") ?: type.lenientString("description") ?: ""
            }

            val competition = o["competitions"].lossyList { c ->
                val competitors = c["competitors"].lossyList(FeedCompetitor::fromJson)
                val seen = HashSet<String>()
                val networks = c["broadcasts"].lossyList { b -> b.lenientStringArray("names") }
                    .flatten()
                    .filter { Scoreboard.isTelevisionNetwork(it) && seen.add(it) }
                competitors to networks
            }.firstOrNull()

            return FeedEvent(id, date, state, text, competition?.first ?: emptyList(), competition?.second ?: emptyList())
        }
    }
}

internal class FeedCompetitor(val isHome: Boolean?, val team: SportsTeam) {
    companion object {
        fun fromJson(o: JsonObject): FeedCompetitor? {
            val isHome = when (o.lenientString("homeAway") ?: "") {
                "home" -> true
                "away" -> false
                else -> null
            }
            val t = o.obj("team") ?: return null
            val nickname = t.lenientString("name")
            val location = t.lenientString("location")
            val displayName = t.lenientString("displayName") ?: nickname ?: location ?: return null
            return FeedCompetitor(
                isHome,
                SportsTeam(
                    displayName = displayName,
                    shortName = t.lenientString("shortDisplayName") ?: nickname ?: displayName,
                    location = location,
                    nickname = nickname,
                    abbreviation = t.lenientString("abbreviation"),
                    logoURL = t.lenientURL("logo"),
                    score = o.lenientString("score"),
                ),
            )
        }
    }
}
