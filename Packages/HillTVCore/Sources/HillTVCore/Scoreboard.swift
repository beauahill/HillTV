import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

// Live scores and team logos come from ESPN's public scoreboard feed, one
// request per league. It is unofficial: no key, no contract, and it can
// change or stop without notice. Everything here decodes leniently and the
// app treats a failure as "no scores", never as an error the user must fix.

public struct SportsLeague: Hashable, Sendable, Identifiable {
    /// Path component, like "football".
    public let sport: String
    /// Path component, like "nfl".
    public let slug: String
    /// Short label for a game row, like "NFL".
    public let name: String

    public var id: String { "\(sport)/\(slug)" }

    public init(sport: String, slug: String, name: String) {
        self.sport = sport
        self.slug = slug
        self.name = name
    }

    public static let defaults: [SportsLeague] = [
        SportsLeague(sport: "football", slug: "nfl", name: "NFL"),
        SportsLeague(sport: "football", slug: "college-football", name: "College Football"),
        SportsLeague(sport: "basketball", slug: "nba", name: "NBA"),
        SportsLeague(sport: "basketball", slug: "mens-college-basketball", name: "College Basketball"),
        SportsLeague(sport: "hockey", slug: "nhl", name: "NHL"),
        SportsLeague(sport: "baseball", slug: "mlb", name: "MLB"),
        SportsLeague(sport: "soccer", slug: "usa.1", name: "MLS"),
        SportsLeague(sport: "soccer", slug: "eng.1", name: "Premier League"),
    ]
}

public struct SportsTeam: Hashable, Sendable {
    /// "Granite Peaks Otters"
    public let displayName: String
    /// "Otters", or a short club name for soccer.
    public let shortName: String
    /// "Granite Peaks"
    public let location: String?
    /// "Otters"
    public let nickname: String?
    public let abbreviation: String?
    public let logoURL: URL?
    /// As sent, so "0" before kickoff. Nil when the feed has none.
    public let score: String?

    public init(displayName: String, shortName: String, location: String? = nil, nickname: String? = nil,
                abbreviation: String? = nil, logoURL: URL? = nil, score: String? = nil) {
        self.displayName = displayName
        self.shortName = shortName
        self.location = location
        self.nickname = nickname
        self.abbreviation = abbreviation
        self.logoURL = logoURL
        self.score = score
    }
}

public struct SportsGame: Identifiable, Hashable, Sendable {
    public enum State: String, Sendable {
        case scheduled
        case live
        case finished
    }

    public let id: String
    public let league: SportsLeague
    public let start: Date
    public let state: State
    /// The feed's own short status: "13:28 - 3rd", "Halftime", "Final".
    public let statusText: String
    public let home: SportsTeam
    public let away: SportsTeam
    /// TV networks carrying the game, streaming-only services removed.
    public let networks: [String]

    public init(id: String, league: SportsLeague, start: Date, state: State, statusText: String,
                home: SportsTeam, away: SportsTeam, networks: [String] = []) {
        self.id = id
        self.league = league
        self.start = start
        self.state = state
        self.statusText = statusText
        self.home = home
        self.away = away
        self.networks = networks
    }
}

public enum Scoreboard {
    /// What the Sports screen shows, in order: games in progress, games
    /// starting within `lookahead`, then games that finished recently.
    /// The feed returns a whole week for some leagues, so this trims it.
    public struct Sections: Equatable, Sendable {
        public let live: [SportsGame]
        public let upcoming: [SportsGame]
        public let finished: [SportsGame]

        public var all: [SportsGame] { live + upcoming + finished }
        public var isEmpty: Bool { live.isEmpty && upcoming.isEmpty && finished.isEmpty }
    }

    public static func sections(
        _ games: [SportsGame],
        now: Date,
        lookahead: TimeInterval = 12 * 3600,
        lookback: TimeInterval = 6 * 3600
    ) -> Sections {
        let sorted = games.sorted { ($0.start, $0.id) < ($1.start, $1.id) }
        return Sections(
            live: sorted.filter { $0.state == .live },
            upcoming: sorted.filter { $0.state == .scheduled && $0.start <= now.addingTimeInterval(lookahead) },
            finished: sorted.filter { $0.state == .finished && $0.start >= now.addingTimeInterval(-lookback) }
        )
    }

    public static func url(for league: SportsLeague) -> URL {
        URL(string: "https://site.api.espn.com/apis/site/v2/sports/\(league.sport)/\(league.slug)/scoreboard")!
    }

    /// Decodes one league's feed. Events that are missing a team or a start
    /// time are dropped; the rest still come through.
    public static func decode(_ data: Data, league: SportsLeague) throws -> [SportsGame] {
        let response = try JSONDecoder().decode(FeedResponse.self, from: data)
        return response.events.compactMap { $0.game(in: league) }
    }

    /// "2026-11-22T18:00Z" (the feed omits seconds) or the full ISO form.
    static func parseDate(_ string: String) -> Date? {
        for format in ["yyyy-MM-dd'T'HH:mmXXXXX", "yyyy-MM-dd'T'HH:mm:ssXXXXX"] {
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.timeZone = TimeZone(identifier: "UTC")
            formatter.dateFormat = format
            if let date = formatter.date(from: string) { return date }
        }
        return nil
    }

    /// Streaming services and team apps aren't channels a provider carries.
    static func isTelevisionNetwork(_ name: String) -> Bool {
        let folded = SearchIndex<String>.fold(name)
        if folded.isEmpty || name.contains("+") || name.contains(".") { return false }
        let words = Set(folded.split(separator: " ").map(String.init))
        if words.contains("app") || folded.hasSuffix("vision") { return false }
        return !streamingOnly.contains(folded)
    }

    private static let streamingOnly: Set<String> = [
        "prime video", "prime video local", "peacock", "apple tv", "netflix", "youtube", "max", "paramount",
    ]
}

public final class ScoreboardClient: @unchecked Sendable {
    private let session: URLSession
    private let userAgent: String

    public init(userAgent: String = "HillTV", session: URLSession = .shared) {
        self.userAgent = userAgent
        self.session = session
    }

    public func games(for league: SportsLeague) async throws -> [SportsGame] {
        var request = URLRequest(url: Scoreboard.url(for: league), timeoutInterval: 20)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await session.data(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw XtreamError.httpStatus(http.statusCode)
        }
        return try Scoreboard.decode(data, league: league)
    }
}

// MARK: - Feed shape

struct FeedResponse: Decodable {
    let events: [FeedEvent]

    enum CodingKeys: String, CodingKey {
        case events
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        events = (try? c.decode(LossyArray<FeedEvent>.self, forKey: .events).elements) ?? []
    }
}

struct FeedEvent: Decodable {
    let id: String
    let date: Date
    let state: SportsGame.State
    let statusText: String
    let competitors: [FeedCompetitor]
    let networks: [String]

    enum CodingKeys: String, CodingKey {
        case id, date, status, competitions
    }

    enum StatusKeys: String, CodingKey {
        case type
    }

    enum StatusTypeKeys: String, CodingKey {
        case state, shortDetail, detail, description
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        guard let id = c.lenientString(.id),
              let date = c.lenientString(.date).flatMap(Scoreboard.parseDate) else {
            throw DecodingError.dataCorrupted(.init(codingPath: c.codingPath, debugDescription: "event has no id or date"))
        }
        self.id = id
        self.date = date

        var state = SportsGame.State.scheduled
        var text = ""
        if let status = try? c.nestedContainer(keyedBy: StatusKeys.self, forKey: .status),
           let type = try? status.nestedContainer(keyedBy: StatusTypeKeys.self, forKey: .type) {
            switch type.lenientString(.state) ?? "" {
            case "in": state = .live
            case "post": state = .finished
            default: state = .scheduled
            }
            text = type.lenientString(.shortDetail) ?? type.lenientString(.detail) ?? type.lenientString(.description) ?? ""
        }
        self.state = state
        statusText = text

        let competitions = (try? c.decode(LossyArray<FeedCompetition>.self, forKey: .competitions).elements) ?? []
        let competition = competitions.first
        competitors = competition?.competitors ?? []
        networks = competition?.networks ?? []
    }

    func game(in league: SportsLeague) -> SportsGame? {
        guard competitors.count == 2 else { return nil }
        // The feed marks sides; fall back to its order (home first) if it doesn't.
        let home = competitors.first { $0.isHome == true } ?? competitors[0]
        let away = competitors.first { $0.isHome == false } ?? competitors[1]
        guard home.team != away.team else { return nil }
        return SportsGame(
            id: "\(league.id)/\(id)",
            league: league,
            start: date,
            state: state,
            statusText: statusText,
            home: home.team,
            away: away.team,
            networks: networks
        )
    }
}

struct FeedCompetition: Decodable {
    let competitors: [FeedCompetitor]
    let networks: [String]

    enum CodingKeys: String, CodingKey {
        case competitors, broadcasts
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        competitors = (try? c.decode(LossyArray<FeedCompetitor>.self, forKey: .competitors).elements) ?? []
        let broadcasts = (try? c.decode(LossyArray<FeedBroadcast>.self, forKey: .broadcasts).elements) ?? []
        var seen = Set<String>()
        networks = broadcasts.flatMap(\.names).filter { Scoreboard.isTelevisionNetwork($0) && seen.insert($0).inserted }
    }
}

struct FeedBroadcast: Decodable {
    let names: [String]

    enum CodingKeys: String, CodingKey {
        case names
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        names = c.lenientStringArray(.names)
    }
}

struct FeedCompetitor: Decodable {
    let isHome: Bool?
    let team: SportsTeam

    enum CodingKeys: String, CodingKey {
        case homeAway, score, team
    }

    enum TeamKeys: String, CodingKey {
        case location, name, abbreviation, displayName, shortDisplayName, logo
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        switch c.lenientString(.homeAway) ?? "" {
        case "home": isHome = true
        case "away": isHome = false
        default: isHome = nil
        }
        let t = try c.nestedContainer(keyedBy: TeamKeys.self, forKey: .team)
        let nickname = t.lenientString(.name)
        let location = t.lenientString(.location)
        guard let displayName = t.lenientString(.displayName) ?? nickname ?? location else {
            throw DecodingError.dataCorrupted(.init(codingPath: t.codingPath, debugDescription: "team has no name"))
        }
        team = SportsTeam(
            displayName: displayName,
            shortName: t.lenientString(.shortDisplayName) ?? nickname ?? displayName,
            location: location,
            nickname: nickname,
            abbreviation: t.lenientString(.abbreviation),
            logoURL: t.lenientURL(.logo),
            score: c.lenientString(.score)
        )
    }
}
