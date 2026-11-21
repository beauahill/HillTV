import Foundation

/// One channel's listings, ready for "what's on now" rows and a short
/// timeline. Every question takes `now` so the answers are testable.
public struct EPGSchedule: Sendable, Equatable {
    /// Sorted by start time, with zero-length entries dropped.
    public let entries: [EPGEntry]

    public static let empty = EPGSchedule([])

    public init(_ entries: [EPGEntry]) {
        self.entries = entries
            .filter { $0.end > $0.start }
            .sorted { $0.start < $1.start }
    }

    /// The programme airing at `now`, if the listings cover it.
    public func current(at now: Date) -> EPGEntry? {
        entries.first { $0.isOnAir(at: now) }
    }

    /// The first programme that starts after `now`.
    public func next(after now: Date) -> EPGEntry? {
        entries.first { $0.start > now }
    }

    /// What's on now followed by what comes after, for a timeline.
    public func upcoming(from now: Date, limit: Int) -> [EPGEntry] {
        Array(entries.filter { $0.end > now }.prefix(max(0, limit)))
    }

    /// False once everything listed has finished airing (or nothing was listed).
    public func covers(_ now: Date) -> Bool {
        entries.contains { $0.end > now }
    }

    /// Whole minutes until `entry` ends, rounded up, never negative.
    public static func minutesLeft(in entry: EPGEntry, at now: Date) -> Int {
        let seconds = entry.end.timeIntervalSince(now)
        guard seconds > 0 else { return 0 }
        return Int((seconds / 60).rounded(.up))
    }

    /// Whether to ask the panel again. Listings that still cover `now` are
    /// kept. Empty or fully aired listings are retried, but only after
    /// `retryInterval`, so channels with no guide data don't refetch every
    /// time their row scrolls into view.
    public static func needsFetch(
        schedule: EPGSchedule?,
        fetchedAt: Date?,
        now: Date,
        retryInterval: TimeInterval = 600
    ) -> Bool {
        guard let schedule, let fetchedAt else { return true }
        if schedule.covers(now) { return false }
        return now.timeIntervalSince(fetchedAt) >= retryInterval
    }
}
