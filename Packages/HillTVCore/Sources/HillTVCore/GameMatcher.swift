import Foundation

/// A channel that probably carries a game, and how sure the match is.
public struct GameChannelMatch: Hashable, Sendable, Identifiable {
    public enum Strength: Int, Sendable, Comparable {
        /// Only the network name matched ("ESPN"). Many channels can share
        /// one, and regional games differ, so never tune on this alone.
        case network = 1
        /// Both teams are named in the channel name or its guide listing.
        case teams = 2

        public static func < (lhs: Strength, rhs: Strength) -> Bool {
            lhs.rawValue < rhs.rawValue
        }
    }

    public let stream: LiveStream
    public let strength: Strength

    public var id: Int { stream.id }

    public init(stream: LiveStream, strength: Strength) {
        self.stream = stream
        self.strength = strength
    }
}

/// Ties games from the scores feed to the provider's channels. The provider
/// knows nothing about teams, so this works from text: channel names
/// ("NFL 03: Colts vs Texans"), guide titles, and network names.
public enum GameMatcher {
    /// Channels that may carry `game`, strongest first, provider order kept
    /// within each strength. `schedules` is whatever guide data is already
    /// loaded; channels without any are still matched by name.
    public static func matches(
        for game: SportsGame,
        channels: [LiveStream],
        schedules: [Int: EPGSchedule] = [:]
    ) -> [GameChannelMatch] {
        matches(for: game, in: ChannelIndex(channels, schedules: schedules))
    }

    /// The same for many games at once, keyed by game ID. Channel names and
    /// guide titles are folded once instead of once per game.
    ///
    /// Stops early if the calling task is cancelled, with only the games
    /// done so far. A caller that cancels must not use the result.
    public static func matches(
        forAll games: [SportsGame],
        channels: [LiveStream],
        schedules: [Int: EPGSchedule] = [:]
    ) -> [String: [GameChannelMatch]] {
        let index = ChannelIndex(channels, schedules: schedules)
        var result: [String: [GameChannelMatch]] = [:]
        for game in games where result[game.id] == nil {
            if Task.isCancelled { break }
            result[game.id] = matches(for: game, in: index)
        }
        return result
    }

    /// A channel with its searchable text prepared.
    struct IndexedChannel {
        struct Listing {
            let start: Date
            let end: Date
            let text: String
        }

        let stream: LiveStream
        let name: String
        let listings: [Listing]
    }

    /// Every channel with its text folded, plus a word index over them.
    ///
    /// A sports section can hold thousands of channels and a busy day a
    /// hundred games or more. Checking every game against every channel is
    /// then hundreds of thousands of text searches, which kept an Apple TV
    /// busy for a long time. The word index narrows each game to the few
    /// channels that mention one of its teams or networks at all.
    struct ChannelIndex {
        /// Provider order. Repeated stream IDs are dropped.
        let channels: [IndexedChannel]
        /// For each word: where in `channels` it appears, in a name or in
        /// guide text. Positions ascend.
        private let positions: [String: [Int]]

        init(_ streams: [LiveStream], schedules: [Int: EPGSchedule]) {
            var seen = Set<Int>()
            var channels: [IndexedChannel] = []
            var positions: [String: [Int]] = [:]
            for stream in streams where seen.insert(stream.id).inserted {
                let listings = (schedules[stream.id]?.entries ?? []).map { entry in
                    IndexedChannel.Listing(
                        start: entry.start,
                        end: entry.end,
                        text: GameMatcher.fold(entry.title + " " + (entry.summary ?? ""))
                    )
                }
                let channel = IndexedChannel(stream: stream, name: GameMatcher.fold(stream.name), listings: listings)
                var words = Set(channel.name.split(separator: " "))
                for listing in listings {
                    words.formUnion(listing.text.split(separator: " "))
                }
                for word in words {
                    positions[String(word), default: []].append(channels.count)
                }
                channels.append(channel)
            }
            self.channels = channels
            self.positions = positions
        }

        /// Channels that have the first word of at least one phrase, in
        /// provider order. A channel can't contain a phrase without it.
        func candidates(for phrases: [String]) -> [IndexedChannel] {
            var found = Set<Int>()
            for phrase in phrases {
                guard let first = phrase.split(separator: " ").first else { continue }
                found.formUnion(positions[String(first)] ?? [])
            }
            return found.sorted().map { channels[$0] }
        }
    }

    /// What to look for in a channel's text for one game.
    struct GameTerms {
        let home: TeamPhrases
        let away: TeamPhrases
        let networks: [String]
        // Listings that overlap the game, with some slack for pregame and overtime.
        let windowStart: Date
        let windowEnd: Date

        init(_ game: SportsGame) {
            home = TeamPhrases(game.home)
            away = TeamPhrases(game.away)
            networks = game.networks.map { GameMatcher.fold($0) }.filter { !$0.isEmpty }
            windowStart = game.start.addingTimeInterval(-30 * 60)
            windowEnd = game.start.addingTimeInterval(4 * 3600)
        }

        /// A match needs one of these somewhere: a team named outright (a
        /// city alone is never enough) or the network's name.
        var required: [String] { home.names + away.names + networks }
    }

    static func matches(for game: SportsGame, in index: ChannelIndex) -> [GameChannelMatch] {
        let terms = GameTerms(game)
        return matches(terms, among: index.candidates(for: terms.required))
    }

    /// Checks every channel. `matches(for:in:)` must give the same answer;
    /// the tests hold it to this.
    static func sweep(for game: SportsGame, in index: ChannelIndex) -> [GameChannelMatch] {
        matches(GameTerms(game), among: index.channels)
    }

    private static func matches(_ terms: GameTerms, among channels: [IndexedChannel]) -> [GameChannelMatch] {
        var strong: [GameChannelMatch] = []
        var weak: [GameChannelMatch] = []

        for channel in channels {
            let inGuide = channel.listings.contains { listing in
                listing.end > terms.windowStart && listing.start < terms.windowEnd
                    && names(bothOf: terms.home, terms.away, in: listing.text)
            }
            if inGuide || names(bothOf: terms.home, terms.away, in: channel.name) {
                strong.append(GameChannelMatch(stream: channel.stream, strength: .teams))
            } else if terms.networks.contains(where: { isNetwork($0, channelName: channel.name) }) {
                weak.append(GameChannelMatch(stream: channel.stream, strength: .network))
            }
        }
        return strong + weak
    }

    // MARK: Text matching

    static func fold(_ string: String) -> String {
        SearchIndex<String>.fold(string)
    }

    /// Ways a listing might name a team, folded. Abbreviations are left out:
    /// "NO", "IND" and "ATL" are too easily other words.
    struct TeamPhrases {
        /// Full name, short name and nickname: "copper basin kings", "kings".
        let names: [String]
        /// The city or state alone, when it isn't also the short name.
        /// Several teams can share one, across leagues too.
        let places: [String]

        init(_ team: SportsTeam) {
            var names: [String] = []
            for candidate in [team.displayName, team.shortName, team.nickname ?? ""] {
                let folded = GameMatcher.fold(candidate)
                // One or two characters match far too much.
                if folded.count >= 3, !names.contains(folded) { names.append(folded) }
            }
            self.names = names
            let place = GameMatcher.fold(team.location ?? "")
            places = (place.count >= 3 && !names.contains(place)) ? [place] : []
        }
    }

    /// True when `text` names both teams. A city alone is accepted for one
    /// side but not both: "Los Angeles at Boston" could be any sport.
    static func names(bothOf home: TeamPhrases, _ away: TeamPhrases, in text: String) -> Bool {
        let homeNamed = mentions(text, anyOf: home.names)
        let awayNamed = mentions(text, anyOf: away.names)
        guard homeNamed || awayNamed else { return false }
        return (homeNamed || mentions(text, anyOf: home.places)) && (awayNamed || mentions(text, anyOf: away.places))
    }

    /// True when `text` contains one of the phrases as whole words.
    static func mentions(_ text: String, anyOf phrases: [String]) -> Bool {
        let padded = " \(text) "
        return phrases.contains { padded.contains(" \($0) ") }
    }

    /// True when the channel name is the network plus nothing but noise
    /// ("US: ESPN HD"). Anything else after it means a different channel:
    /// "ESPN 2", "ESPN News", "CBS Sports Network", a local affiliate.
    static func isNetwork(_ network: String, channelName: String) -> Bool {
        let networkWords = network.split(separator: " ").map(String.init)
        var words = channelName.split(separator: " ").map(String.init)
        guard !networkWords.isEmpty, words.count >= networkWords.count else { return false }
        guard let start = words.indices.first(where: { index in
            index + networkWords.count <= words.count
                && Array(words[index..<(index + networkWords.count)]) == networkWords
        }) else { return false }
        words.removeSubrange(start..<(start + networkWords.count))
        return words.allSatisfy { noise.contains($0) }
    }

    private static let noise: Set<String> = [
        "hd", "fhd", "uhd", "sd", "4k", "hevc", "h265", "h264", "1080", "1080p", "720", "720p", "60fps", "50fps",
        "us", "usa", "uk", "ca", "east", "west", "backup", "vip", "raw", "multi", "feed", "tv", "channel", "live",
    ]
}
