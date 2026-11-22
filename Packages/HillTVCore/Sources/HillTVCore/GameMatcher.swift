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
        matches(for: game, in: index(channels, schedules: schedules))
    }

    /// The same for many games at once, keyed by game ID. Channel names and
    /// guide titles are folded once instead of once per game.
    public static func matches(
        forAll games: [SportsGame],
        channels: [LiveStream],
        schedules: [Int: EPGSchedule] = [:]
    ) -> [String: [GameChannelMatch]] {
        let indexed = index(channels, schedules: schedules)
        var result: [String: [GameChannelMatch]] = [:]
        for game in games where result[game.id] == nil {
            result[game.id] = matches(for: game, in: indexed)
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

    /// Folds every channel's name and guide text. Repeated stream IDs are dropped.
    static func index(_ channels: [LiveStream], schedules: [Int: EPGSchedule]) -> [IndexedChannel] {
        var seen = Set<Int>()
        return channels.compactMap { channel in
            guard seen.insert(channel.id).inserted else { return nil }
            let listings = (schedules[channel.id]?.entries ?? []).map { entry in
                IndexedChannel.Listing(
                    start: entry.start,
                    end: entry.end,
                    text: fold(entry.title + " " + (entry.summary ?? ""))
                )
            }
            return IndexedChannel(stream: channel, name: fold(channel.name), listings: listings)
        }
    }

    static func matches(for game: SportsGame, in channels: [IndexedChannel]) -> [GameChannelMatch] {
        let home = TeamPhrases(game.home)
        let away = TeamPhrases(game.away)
        let networks = game.networks.map { fold($0) }.filter { !$0.isEmpty }
        // Listings that overlap the game, with some slack for pregame and overtime.
        let windowStart = game.start.addingTimeInterval(-30 * 60)
        let windowEnd = game.start.addingTimeInterval(4 * 3600)

        var strong: [GameChannelMatch] = []
        var weak: [GameChannelMatch] = []

        for channel in channels {
            let inGuide = channel.listings.contains { listing in
                listing.end > windowStart && listing.start < windowEnd && names(bothOf: home, away, in: listing.text)
            }
            if inGuide || names(bothOf: home, away, in: channel.name) {
                strong.append(GameChannelMatch(stream: channel.stream, strength: .teams))
            } else if networks.contains(where: { isNetwork($0, channelName: channel.name) }) {
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
