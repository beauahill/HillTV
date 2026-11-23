package com.beauahill.hilltv.core

import com.beauahill.hilltv.core.GameChannelMatch.Strength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.time.Instant

class ScoreboardTests {
    private val league = SportsLeague("hockey", "test", "Test")

    /**
     * Same shape as the real feed, trimmed, with made-up teams. It includes
     * the mess a lossy decode has to survive: a numeric id and score, an
     * empty logo, an unparseable date, a one-team event and a stray string.
     */
    private val feed = """
    {
      "leagues": [{"abbreviation": "TST"}],
      "events": [
        {
          "id": "401", "date": "2026-11-22T02:00Z", "shortName": "GPO @ CBK",
          "status": {"clock": 808.0, "displayClock": "13:28", "period": 3,
                     "type": {"id": "2", "state": "in", "completed": false, "detail": "13:28 - 3rd Period", "shortDetail": "13:28 - 3rd"}},
          "competitions": [{
            "id": "401",
            "competitors": [
              {"id": "1", "homeAway": "home", "score": "3",
               "team": {"id": "1", "location": "Copper Basin", "name": "Kings", "abbreviation": "CBK",
                        "displayName": "Copper Basin Kings", "shortDisplayName": "Kings",
                        "logo": "https://img.example.com/logos/cbk.png"}},
              {"id": "2", "homeAway": "away", "score": "2",
               "team": {"id": "2", "location": "Granite Peaks", "name": "Otters", "abbreviation": "GPO",
                        "displayName": "Granite Peaks Otters", "shortDisplayName": "Otters",
                        "logo": "https://img.example.com/logos/gpo.png"}}
            ],
            "broadcasts": [{"market": "national", "names": ["ESPN", "ESPN+"]}, {"market": "home", "names": ["Kings App", "ESPN"]}]
          }]
        },
        {
          "id": 402, "date": "2026-11-22T18:00Z",
          "status": {"type": {"state": "pre", "shortDetail": "11/22 - 1:00 PM EST"}},
          "competitions": [{
            "competitors": [
              {"homeAway": "away", "score": "0",
               "team": {"location": "Harbor City", "name": "Gulls", "displayName": "Harbor City Gulls", "shortDisplayName": "Gulls"}},
              {"homeAway": "home", "score": 0,
               "team": {"location": "Prairie", "name": "Hawks", "displayName": "Prairie Hawks", "shortDisplayName": "Hawks", "logo": ""}}
            ],
            "broadcasts": []
          }]
        },
        {
          "id": "403", "date": "2026-11-20T01:15Z",
          "status": {"type": {"state": "post", "shortDetail": "Final"}},
          "competitions": [{
            "competitors": [
              {"homeAway": "home", "score": "26", "team": {"displayName": "Northline FC"}},
              {"homeAway": "away", "score": "3", "team": {"displayName": "Summit Rangers"}}
            ]
          }]
        },
        {"id": "404", "date": "not a date", "status": {}, "competitions": []},
        {"id": "405", "date": "2026-11-22T20:00Z",
         "competitions": [{"competitors": [{"homeAway": "home", "team": {"displayName": "Solo Team"}}]}]},
        "garbage"
      ]
    }
    """

    private fun games(): List<SportsGame> = Scoreboard.decode(feed, league)

    @Test
    fun testDecodesGoodEventsAndDropsBrokenOnes() {
        assertEquals(listOf("hockey/test/401", "hockey/test/402", "hockey/test/403"), games().map { it.id })
    }

    @Test
    fun testLiveGameFields() {
        val game = games().first()
        assertEquals(SportsGame.State.LIVE, game.state)
        assertEquals("13:28 - 3rd", game.statusText)
        assertEquals(Instant.ofEpochSecond(1_795_312_800), game.start)
        assertEquals(league, game.league)
        assertEquals("Copper Basin Kings", game.home.displayName)
        assertEquals("Kings", game.home.shortName)
        assertEquals("Copper Basin", game.home.location)
        assertEquals("Kings", game.home.nickname)
        assertEquals("CBK", game.home.abbreviation)
        assertEquals("3", game.home.score)
        assertEquals("https://img.example.com/logos/cbk.png", game.home.logoURL)
        assertEquals("Otters", game.away.shortName)
        assertEquals("2", game.away.score)
    }

    @Test
    fun testNetworksDropStreamingAppsAndDuplicates() {
        assertEquals(listOf("ESPN"), games()[0].networks)
        assertEquals(emptyList<String>(), games()[1].networks)
        assertEquals(emptyList<String>(), games()[2].networks)
    }

    @Test
    fun testHomeAndAwayFollowTheFeedNotTheOrder() {
        val game = games()[1]
        assertEquals(SportsGame.State.SCHEDULED, game.state)
        assertEquals("11/22 - 1:00 PM EST", game.statusText)
        assertEquals(Instant.ofEpochSecond(1_795_370_400), game.start)
        assertEquals("Hawks", game.home.shortName)
        assertEquals("Gulls", game.away.shortName)
        // A numeric score and an empty logo both decode.
        assertEquals("0", game.home.score)
        assertNull(game.home.logoURL)
    }

    @Test
    fun testSparseTeamFallsBackToDisplayName() {
        val game = games()[2]
        assertEquals(SportsGame.State.FINISHED, game.state)
        assertEquals("Northline FC", game.home.displayName)
        assertEquals("Northline FC", game.home.shortName)
        assertNull(game.home.nickname)
        assertNull(game.home.location)
        assertEquals("26", game.home.score)
    }

    @Test
    fun testEmptyAndOddPayloadsDecodeToNoGames() {
        assertEquals(emptyList<SportsGame>(), Scoreboard.decode("""{"events": []}""", league))
        assertEquals(emptyList<SportsGame>(), Scoreboard.decode("""{"events": null}""", league))
        assertEquals(emptyList<SportsGame>(), Scoreboard.decode("{}", league))
        assertThrows(XtreamError.Decoding::class.java) { Scoreboard.decode("<html>", league) }
    }

    @Test
    fun testParseDate() {
        val expected = Instant.ofEpochSecond(1_795_370_400)
        assertEquals(expected, Scoreboard.parseDate("2026-11-22T18:00Z"))
        assertEquals(expected.plusSeconds(30), Scoreboard.parseDate("2026-11-22T18:00:30Z"))
        assertEquals(expected, Scoreboard.parseDate("2026-11-22T13:00-05:00"))
        assertNull(Scoreboard.parseDate("not a date"))
    }

    @Test
    fun testTelevisionNetworks() {
        assertTrue(Scoreboard.isTelevisionNetwork("ESPN"))
        assertTrue(Scoreboard.isTelevisionNetwork("NBA TV"))
        assertFalse(Scoreboard.isTelevisionNetwork("ESPN+"))
        assertFalse(Scoreboard.isTelevisionNetwork("Bucks App"))
        assertFalse(Scoreboard.isTelevisionNetwork("Pelicans.com"))
        assertFalse(Scoreboard.isTelevisionNetwork("Prime Video"))
        assertFalse(Scoreboard.isTelevisionNetwork("BlazerVision"))
        assertFalse(Scoreboard.isTelevisionNetwork(""))
    }

    @Test
    fun testURL() {
        val nfl = SportsLeague("football", "nfl", "NFL")
        assertEquals("https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard", Scoreboard.url(nfl))
    }

    @Test
    fun testCollegeLeaguesAskForEveryGameNotJustRankedTeams() {
        val football = SportsLeague.defaults.first { it.slug == "college-football" }
        assertEquals(
            "https://site.api.espn.com/apis/site/v2/sports/football/college-football/scoreboard?groups=80",
            Scoreboard.url(football),
        )
        val basketball = SportsLeague.defaults.first { it.slug == "mens-college-basketball" }
        assertEquals("groups=50", URI(Scoreboard.url(basketball)).query)
    }

    // MARK: Sections

    private fun game(id: String, state: SportsGame.State, startingIn: Long) = SportsGame(
        id = id,
        league = league,
        start = at(startingIn),
        state = state,
        statusText = "",
        home = SportsTeam("Home $id", "Home $id"),
        away = SportsTeam("Away $id", "Away $id"),
    )

    @Test
    fun testSectionsTrimAWeekOfGamesToWhatMattersNow() {
        val all = listOf(
            game("far", SportsGame.State.SCHEDULED, 3 * 86_400),
            game("soon", SportsGame.State.SCHEDULED, 2 * 3600),
            game("old", SportsGame.State.FINISHED, -2 * 86_400),
            game("live-late", SportsGame.State.LIVE, -1800),
            game("done", SportsGame.State.FINISHED, -3 * 3600),
            game("live-early", SportsGame.State.LIVE, -7200),
            game("sooner", SportsGame.State.SCHEDULED, 600),
        )
        val sections = Scoreboard.sections(all, now = at(0))

        assertEquals(listOf("live-early", "live-late"), sections.live.map { it.id })
        assertEquals(listOf("sooner", "soon"), sections.upcoming.map { it.id })
        assertEquals(listOf("done"), sections.finished.map { it.id })
        assertEquals(listOf("live-early", "live-late", "sooner", "soon", "done"), sections.all.map { it.id })
        assertFalse(sections.isEmpty)
        assertTrue(Scoreboard.sections(emptyList(), now = at(0)).isEmpty)
    }
}

class GameMatcherTests {
    private val game = SportsGame(
        id = "g1",
        league = SportsLeague("hockey", "test", "Test"),
        start = at(0),
        state = SportsGame.State.LIVE,
        statusText = "2nd",
        home = SportsTeam("Copper Basin Kings", "Kings", location = "Copper Basin", nickname = "Kings"),
        away = SportsTeam("Granite Peaks Otters", "Otters", location = "Granite Peaks", nickname = "Otters"),
        networks = listOf("ESPN"),
    )

    private fun schedule(title: String, startingIn: Long): EPGSchedule {
        val json = """{"epg_listings":[{"id":"1","title":"$title","start_timestamp":"${BASE + startingIn}","stop_timestamp":"${BASE + startingIn + 10_800}"}]}"""
        return EPGSchedule(EPGEntry.decodeShortEPG(parseJson(json)))
    }

    private fun channel(id: Int, name: String) = LiveStream(id = id, name = name)

    @Test
    fun testRanksTeamMatchesAboveNetworkMatches() {
        val channels = listOf(
            channel(1, "US: ESPN HD"),
            channel(2, "ESPN 2 HD"),
            channel(3, "HOCKEY 03: Otters vs Kings"),
            channel(4, "Ridgeline Sports 1"),
            channel(5, "Ridgeline Sports 2"),
            channel(6, "Copper Basin vs Granite Peaks Classic"),
            channel(7, "ESPN News"),
            channel(8, "Kings Radio"),
            channel(3, "Otters vs Kings duplicate"),
        )
        val title = "Hockey: Granite Peaks Otters at Copper Basin Kings"
        val schedules = mapOf(
            4 to schedule(title, -600),
            5 to schedule(title, -86_400),
        )

        val matches = GameMatcher.matches(game, channels, schedules)

        assertEquals(listOf(3, 4, 1), matches.map { it.stream.id })
        assertEquals(listOf(Strength.TEAMS, Strength.TEAMS, Strength.NETWORK), matches.map { it.strength })
    }

    @Test
    fun testMatchingManyGamesAtOnceAgreesWithOneAtATime() {
        val other = SportsGame(
            id = "g2",
            league = SportsLeague("football", "test", "Test"),
            start = at(0),
            state = SportsGame.State.SCHEDULED,
            statusText = "",
            home = SportsTeam("Prairie Hawks", "Hawks", location = "Prairie", nickname = "Hawks"),
            away = SportsTeam("Harbor City Gulls", "Gulls", location = "Harbor City", nickname = "Gulls"),
        )
        val channels = listOf(
            channel(1, "US: ESPN HD"),
            channel(2, "FOOTBALL 01: Gulls vs Hawks"),
            channel(3, "HOCKEY 03: Otters vs Kings"),
        )

        val all = GameMatcher.matchesForAll(listOf(game, other, game), channels)

        assertEquals(2, all.size)
        assertEquals(GameMatcher.matches(game, channels), all["g1"])
        assertEquals(GameMatcher.matches(other, channels), all["g2"])
        assertEquals(listOf(3, 1), all["g1"]?.map { it.stream.id })
        assertEquals(listOf(2), all["g2"]?.map { it.stream.id })
    }

    /**
     * The word index only narrows which channels are checked. On a large,
     * messy channel list it must find exactly what checking every channel finds.
     */
    @Test
    fun testNarrowedMatchingAgreesWithCheckingEveryChannel() {
        val teams = listOf(
            "Copper Basin" to "Kings", "Granite Peaks" to "Otters", "Prairie" to "Hawks", "Harbor City" to "Gulls",
            "Tetons State" to "Elk", "Basin Tech" to "Miners", "Copper Basin" to "Comets", "Ridgeline" to "United",
        )
        val networks = listOf("ESPN", "NBA TV", "FOX", "Ridgeline Sports")
        val games = teams.mapIndexed { index, home ->
            val away = teams[(index + 3) % teams.size]
            SportsGame(
                id = "many-$index",
                league = SportsLeague("hockey", "test", "Test"),
                start = at(index * 1800L),
                state = SportsGame.State.SCHEDULED,
                statusText = "",
                home = SportsTeam("${home.first} ${home.second}", home.second, location = home.first, nickname = home.second),
                away = SportsTeam("${away.first} ${away.second}", away.second, location = away.first, nickname = away.second),
                networks = listOf(networks[index % networks.size]),
            )
        }

        // Names built from every pairing of the parts above, plus noise, so
        // there are near misses of every kind: one team, two cities, a
        // network with extra words, a word inside a longer word.
        val parts = teams.flatMap { listOf(it.first, it.second) } + networks +
            listOf("HD", "2", "News", "Vikings", "Radio", "US:", "Classic")
        val channels = ArrayList<LiveStream>()
        parts.forEachIndexed { a, first ->
            parts.forEachIndexed { b, second ->
                channels.add(channel(a * 100 + b, "$first vs $second"))
                channels.add(channel(10_000 + a * 100 + b, "$first $second"))
            }
        }
        channels.add(channel(5, "Repeated stream ID, dropped"))
        val schedules = mapOf(
            20_001 to schedule("Hockey: Granite Peaks Otters at Copper Basin Kings", -600),
            20_002 to schedule("Hockey: Granite Peaks Otters at Copper Basin Kings", -86_400),
            20_003 to schedule("Otters at Elk", 3 * 1800),
        )
        listOf(20_001, 20_002, 20_003).forEach { channels.add(channel(it, "Guide Only $it")) }

        val index = GameMatcher.ChannelIndex(channels, schedules)
        var strong = 0
        var weak = 0
        for (game in games) {
            val narrowed = GameMatcher.matches(game, index)
            assertEquals(game.id, GameMatcher.sweep(game, index), narrowed)
            strong += narrowed.count { it.strength == Strength.TEAMS }
            weak += narrowed.count { it.strength == Strength.NETWORK }
        }
        // Not an empty comparison: both kinds of match turn up, and far
        // fewer than the channels there are.
        assertTrue(strong > 8)
        assertTrue(weak > 4)
        assertTrue(strong + weak < channels.size)
        // Found through guide text alone: Otters at Elk is game 1, and game 6
        // (Otters at the Comets of Copper Basin) fits the first listing by
        // one team name plus the other's city. The same listing a day
        // earlier is outside the game's window.
        assertTrue(GameMatcher.matches(games[1], index).any { it.stream.id == 20_003 })
        assertTrue(GameMatcher.matches(games[6], index).any { it.stream.id == 20_001 })
        assertFalse(GameMatcher.matches(games[6], index).any { it.stream.id == 20_002 })
    }

    @Test
    fun testNoChannelsNoMatches() {
        assertEquals(emptyList<GameChannelMatch>(), GameMatcher.matches(game, emptyList()))
    }

    @Test
    fun testACityAloneIsEnoughForOneSideOnly() {
        val home = GameMatcher.TeamPhrases(game.home)
        val away = GameMatcher.TeamPhrases(game.away)
        assertEquals(listOf("copper basin kings", "kings"), home.names)
        assertEquals(listOf("copper basin"), home.places)

        assertTrue(GameMatcher.namesBothOf(home, away, "otters at copper basin"))
        assertTrue(GameMatcher.namesBothOf(home, away, "granite peaks kings"))
        assertFalse(GameMatcher.namesBothOf(home, away, "granite peaks at copper basin"))
        assertFalse(GameMatcher.namesBothOf(home, away, "kings postgame"))
        // Whole words only.
        assertFalse(GameMatcher.namesBothOf(home, away, "vikings and potters"))
    }

    @Test
    fun testSchoolNamesThatAreAlsoPlacesCountAsNames() {
        val college = SportsGame(
            id = "g2",
            league = SportsLeague("football", "test", "Test"),
            start = at(0),
            state = SportsGame.State.SCHEDULED,
            statusText = "",
            home = SportsTeam("Tetons State Elk", "Tetons State", location = "Tetons State", nickname = "Elk"),
            away = SportsTeam("Basin Tech Miners", "Basin Tech", location = "Basin Tech", nickname = "Miners"),
        )
        val channels = listOf(channel(9, "CFB 12: Basin Tech at Tetons State"))
        assertEquals(listOf(Strength.TEAMS), GameMatcher.matches(college, channels).map { it.strength })
        assertEquals(emptyList<String>(), GameMatcher.TeamPhrases(college.home).places)
    }

    @Test
    fun testNetworkMatchAllowsOnlyNoiseAroundTheName() {
        assertTrue(GameMatcher.isNetwork("espn", "us espn hd"))
        assertTrue(GameMatcher.isNetwork("nba tv", "nba tv hd"))
        assertTrue(GameMatcher.isNetwork("fox", "us fox east"))
        assertFalse(GameMatcher.isNetwork("espn", "espn 2 hd"))
        assertFalse(GameMatcher.isNetwork("espn", "espn news"))
        assertFalse(GameMatcher.isNetwork("espn", "espnu"))
        assertFalse(GameMatcher.isNetwork("cbs", "cbs sports network"))
        assertFalse(GameMatcher.isNetwork("espn", ""))
    }
}
