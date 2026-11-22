import XCTest
@testable import HillTVCore

final class ScoreboardTests: XCTestCase {
    private let league = SportsLeague(sport: "hockey", slug: "test", name: "Test")

    /// Same shape as the real feed, trimmed, with made-up teams. It includes
    /// the mess a lossy decode has to survive: a numeric id and score, an
    /// empty logo, an unparseable date, a one-team event and a stray string.
    private let feed = #"""
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
    """#

    private func games() throws -> [SportsGame] {
        try Scoreboard.decode(Data(feed.utf8), league: league)
    }

    func testDecodesGoodEventsAndDropsBrokenOnes() throws {
        XCTAssertEqual(try games().map(\.id), ["hockey/test/401", "hockey/test/402", "hockey/test/403"])
    }

    func testLiveGameFields() throws {
        let game = try XCTUnwrap(games().first)
        XCTAssertEqual(game.state, .live)
        XCTAssertEqual(game.statusText, "13:28 - 3rd")
        XCTAssertEqual(game.start, Date(timeIntervalSince1970: 1_795_312_800))
        XCTAssertEqual(game.league, league)
        XCTAssertEqual(game.home.displayName, "Copper Basin Kings")
        XCTAssertEqual(game.home.shortName, "Kings")
        XCTAssertEqual(game.home.location, "Copper Basin")
        XCTAssertEqual(game.home.nickname, "Kings")
        XCTAssertEqual(game.home.abbreviation, "CBK")
        XCTAssertEqual(game.home.score, "3")
        XCTAssertEqual(game.home.logoURL, URL(string: "https://img.example.com/logos/cbk.png"))
        XCTAssertEqual(game.away.shortName, "Otters")
        XCTAssertEqual(game.away.score, "2")
    }

    func testNetworksDropStreamingAppsAndDuplicates() throws {
        XCTAssertEqual(try games()[0].networks, ["ESPN"])
        XCTAssertEqual(try games()[1].networks, [])
        XCTAssertEqual(try games()[2].networks, [])
    }

    func testHomeAndAwayFollowTheFeedNotTheOrder() throws {
        let game = try games()[1]
        XCTAssertEqual(game.state, .scheduled)
        XCTAssertEqual(game.statusText, "11/22 - 1:00 PM EST")
        XCTAssertEqual(game.start, Date(timeIntervalSince1970: 1_795_370_400))
        XCTAssertEqual(game.home.shortName, "Hawks")
        XCTAssertEqual(game.away.shortName, "Gulls")
        // A numeric score and an empty logo both decode.
        XCTAssertEqual(game.home.score, "0")
        XCTAssertNil(game.home.logoURL)
    }

    func testSparseTeamFallsBackToDisplayName() throws {
        let game = try games()[2]
        XCTAssertEqual(game.state, .finished)
        XCTAssertEqual(game.home.displayName, "Northline FC")
        XCTAssertEqual(game.home.shortName, "Northline FC")
        XCTAssertNil(game.home.nickname)
        XCTAssertNil(game.home.location)
        XCTAssertEqual(game.home.score, "26")
    }

    func testEmptyAndOddPayloadsDecodeToNoGames() throws {
        XCTAssertEqual(try Scoreboard.decode(Data(#"{"events": []}"#.utf8), league: league), [])
        XCTAssertEqual(try Scoreboard.decode(Data(#"{"events": null}"#.utf8), league: league), [])
        XCTAssertEqual(try Scoreboard.decode(Data("{}".utf8), league: league), [])
        XCTAssertThrowsError(try Scoreboard.decode(Data("<html>".utf8), league: league))
    }

    func testParseDate() {
        let expected = Date(timeIntervalSince1970: 1_795_370_400)
        XCTAssertEqual(Scoreboard.parseDate("2026-11-22T18:00Z"), expected)
        XCTAssertEqual(Scoreboard.parseDate("2026-11-22T18:00:30Z"), expected.addingTimeInterval(30))
        XCTAssertEqual(Scoreboard.parseDate("2026-11-22T13:00-05:00"), expected)
        XCTAssertNil(Scoreboard.parseDate("not a date"))
    }

    func testTelevisionNetworks() {
        XCTAssertTrue(Scoreboard.isTelevisionNetwork("ESPN"))
        XCTAssertTrue(Scoreboard.isTelevisionNetwork("NBA TV"))
        XCTAssertFalse(Scoreboard.isTelevisionNetwork("ESPN+"))
        XCTAssertFalse(Scoreboard.isTelevisionNetwork("Bucks App"))
        XCTAssertFalse(Scoreboard.isTelevisionNetwork("Pelicans.com"))
        XCTAssertFalse(Scoreboard.isTelevisionNetwork("Prime Video"))
        XCTAssertFalse(Scoreboard.isTelevisionNetwork("BlazerVision"))
        XCTAssertFalse(Scoreboard.isTelevisionNetwork(""))
    }

    func testURL() {
        let nfl = SportsLeague(sport: "football", slug: "nfl", name: "NFL")
        XCTAssertEqual(Scoreboard.url(for: nfl).absoluteString, "https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard")
    }

    // MARK: Sections

    private let base: TimeInterval = 1_800_000_000

    private func game(_ id: String, _ state: SportsGame.State, startingIn offset: TimeInterval) -> SportsGame {
        SportsGame(
            id: id,
            league: league,
            start: Date(timeIntervalSince1970: base + offset),
            state: state,
            statusText: "",
            home: SportsTeam(displayName: "Home \(id)", shortName: "Home \(id)"),
            away: SportsTeam(displayName: "Away \(id)", shortName: "Away \(id)")
        )
    }

    func testSectionsTrimAWeekOfGamesToWhatMattersNow() {
        let all = [
            game("far", .scheduled, startingIn: 3 * 86_400),
            game("soon", .scheduled, startingIn: 2 * 3600),
            game("old", .finished, startingIn: -2 * 86_400),
            game("live-late", .live, startingIn: -1800),
            game("done", .finished, startingIn: -3 * 3600),
            game("live-early", .live, startingIn: -7200),
            game("sooner", .scheduled, startingIn: 600),
        ]
        let sections = Scoreboard.sections(all, now: Date(timeIntervalSince1970: base))

        XCTAssertEqual(sections.live.map(\.id), ["live-early", "live-late"])
        XCTAssertEqual(sections.upcoming.map(\.id), ["sooner", "soon"])
        XCTAssertEqual(sections.finished.map(\.id), ["done"])
        XCTAssertEqual(sections.all.map(\.id), ["live-early", "live-late", "sooner", "soon", "done"])
        XCTAssertFalse(sections.isEmpty)
        XCTAssertTrue(Scoreboard.sections([], now: Date(timeIntervalSince1970: base)).isEmpty)
    }
}

final class GameMatcherTests: XCTestCase {
    private let base: TimeInterval = 1_800_000_000

    private var game: SportsGame {
        SportsGame(
            id: "g1",
            league: SportsLeague(sport: "hockey", slug: "test", name: "Test"),
            start: Date(timeIntervalSince1970: base),
            state: .live,
            statusText: "2nd",
            home: SportsTeam(displayName: "Copper Basin Kings", shortName: "Kings", location: "Copper Basin", nickname: "Kings"),
            away: SportsTeam(displayName: "Granite Peaks Otters", shortName: "Otters", location: "Granite Peaks", nickname: "Otters"),
            networks: ["ESPN"]
        )
    }

    private func schedule(_ title: String, startingIn offset: TimeInterval) throws -> EPGSchedule {
        let json = #"{"epg_listings":[{"id":"1","title":"\#(title)","start_timestamp":"\#(Int(base + offset))","stop_timestamp":"\#(Int(base + offset + 10_800))"}]}"#
        return EPGSchedule(try JSONDecoder().decode(ShortEPGResponse.self, from: Data(json.utf8)).listings)
    }

    func testRanksTeamMatchesAboveNetworkMatches() throws {
        let channels = [
            LiveStream(id: 1, name: "US: ESPN HD"),
            LiveStream(id: 2, name: "ESPN 2 HD"),
            LiveStream(id: 3, name: "HOCKEY 03: Otters vs Kings"),
            LiveStream(id: 4, name: "Ridgeline Sports 1"),
            LiveStream(id: 5, name: "Ridgeline Sports 2"),
            LiveStream(id: 6, name: "Copper Basin vs Granite Peaks Classic"),
            LiveStream(id: 7, name: "ESPN News"),
            LiveStream(id: 8, name: "Kings Radio"),
            LiveStream(id: 3, name: "Otters vs Kings duplicate"),
        ]
        let title = "Hockey: Granite Peaks Otters at Copper Basin Kings"
        let schedules = [
            4: try schedule(title, startingIn: -600),
            5: try schedule(title, startingIn: -86_400),
        ]

        let matches = GameMatcher.matches(for: game, channels: channels, schedules: schedules)

        XCTAssertEqual(matches.map(\.stream.id), [3, 4, 1])
        XCTAssertEqual(matches.map(\.strength), [.teams, .teams, .network])
    }

    func testMatchingManyGamesAtOnceAgreesWithOneAtATime() throws {
        let other = SportsGame(
            id: "g2",
            league: SportsLeague(sport: "football", slug: "test", name: "Test"),
            start: Date(timeIntervalSince1970: base),
            state: .scheduled,
            statusText: "",
            home: SportsTeam(displayName: "Prairie Hawks", shortName: "Hawks", location: "Prairie", nickname: "Hawks"),
            away: SportsTeam(displayName: "Harbor City Gulls", shortName: "Gulls", location: "Harbor City", nickname: "Gulls")
        )
        let channels = [
            LiveStream(id: 1, name: "US: ESPN HD"),
            LiveStream(id: 2, name: "FOOTBALL 01: Gulls vs Hawks"),
            LiveStream(id: 3, name: "HOCKEY 03: Otters vs Kings"),
        ]

        let all = GameMatcher.matches(forAll: [game, other, game], channels: channels)

        XCTAssertEqual(all.count, 2)
        XCTAssertEqual(all["g1"], GameMatcher.matches(for: game, channels: channels))
        XCTAssertEqual(all["g2"], GameMatcher.matches(for: other, channels: channels))
        XCTAssertEqual(all["g1"]?.map(\.stream.id), [3, 1])
        XCTAssertEqual(all["g2"]?.map(\.stream.id), [2])
    }

    func testNoChannelsNoMatches() {
        XCTAssertEqual(GameMatcher.matches(for: game, channels: []), [])
    }

    func testACityAloneIsEnoughForOneSideOnly() {
        let home = GameMatcher.TeamPhrases(game.home)
        let away = GameMatcher.TeamPhrases(game.away)
        XCTAssertEqual(home.names, ["copper basin kings", "kings"])
        XCTAssertEqual(home.places, ["copper basin"])

        XCTAssertTrue(GameMatcher.names(bothOf: home, away, in: "otters at copper basin"))
        XCTAssertTrue(GameMatcher.names(bothOf: home, away, in: "granite peaks kings"))
        XCTAssertFalse(GameMatcher.names(bothOf: home, away, in: "granite peaks at copper basin"))
        XCTAssertFalse(GameMatcher.names(bothOf: home, away, in: "kings postgame"))
        // Whole words only.
        XCTAssertFalse(GameMatcher.names(bothOf: home, away, in: "vikings and potters"))
    }

    func testSchoolNamesThatAreAlsoPlacesCountAsNames() {
        let college = SportsGame(
            id: "g2",
            league: SportsLeague(sport: "football", slug: "test", name: "Test"),
            start: Date(timeIntervalSince1970: base),
            state: .scheduled,
            statusText: "",
            home: SportsTeam(displayName: "Tetons State Elk", shortName: "Tetons State", location: "Tetons State", nickname: "Elk"),
            away: SportsTeam(displayName: "Basin Tech Miners", shortName: "Basin Tech", location: "Basin Tech", nickname: "Miners")
        )
        let channels = [LiveStream(id: 9, name: "CFB 12: Basin Tech at Tetons State")]
        XCTAssertEqual(GameMatcher.matches(for: college, channels: channels).map(\.strength), [.teams])
        XCTAssertEqual(GameMatcher.TeamPhrases(college.home).places, [])
    }

    func testNetworkMatchAllowsOnlyNoiseAroundTheName() {
        XCTAssertTrue(GameMatcher.isNetwork("espn", channelName: "us espn hd"))
        XCTAssertTrue(GameMatcher.isNetwork("nba tv", channelName: "nba tv hd"))
        XCTAssertTrue(GameMatcher.isNetwork("fox", channelName: "us fox east"))
        XCTAssertFalse(GameMatcher.isNetwork("espn", channelName: "espn 2 hd"))
        XCTAssertFalse(GameMatcher.isNetwork("espn", channelName: "espn news"))
        XCTAssertFalse(GameMatcher.isNetwork("espn", channelName: "espnu"))
        XCTAssertFalse(GameMatcher.isNetwork("cbs", channelName: "cbs sports network"))
        XCTAssertFalse(GameMatcher.isNetwork("espn", channelName: ""))
    }
}
