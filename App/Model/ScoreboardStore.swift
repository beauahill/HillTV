import Foundation
import HillTVCore
import Observation

/// Live scores for the Sports pill, and which channels probably carry each game.
///
/// Scores come from an unofficial public feed (see `Scoreboard` in core), so
/// every failure here is quiet: no scores means Sports shows plain channels.
/// Nothing runs unless the caller asks, so the feed is only polled while
/// games are on screen.
@MainActor
@Observable
final class ScoreboardStore {
    private(set) var games: [SportsGame] = []
    /// Channels that may carry each game, by game ID. See `GameMatcher`.
    private(set) var matches: [String: [GameChannelMatch]] = [:]
    private(set) var hasLoaded = false

    private var byLeague: [String: [SportsGame]] = [:]
    private let client: ScoreboardClient

    init(userAgent: String) {
        client = ScoreboardClient(userAgent: userAgent)
    }

    /// Asks every league once. Leagues that fail keep their last result.
    func refresh() async {
        let client = client
        let results = await withTaskGroup(of: (String, [SportsGame]?).self) { group in
            for league in SportsLeague.defaults {
                group.addTask {
                    (league.id, try? await client.games(for: league))
                }
            }
            var collected: [(String, [SportsGame]?)] = []
            for await result in group {
                collected.append(result)
            }
            return collected
        }
        if Task.isCancelled { return }
        for (id, list) in results {
            if let list { byLeague[id] = list }
        }
        games = SportsLeague.defaults.flatMap { byLeague[$0.id] ?? [] }
        hasLoaded = true
    }

    /// Works out the channels for `games` off the main thread.
    func rematch(_ games: [SportsGame], channels: [LiveStream], schedules: [Int: EPGSchedule]) async {
        guard !games.isEmpty, !channels.isEmpty else {
            matches = [:]
            return
        }
        let result = await Task.detached(priority: .utility) {
            GameMatcher.matches(forAll: games, channels: channels, schedules: schedules)
        }.value
        if Task.isCancelled { return }
        matches = result
    }
}
