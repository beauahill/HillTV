import Foundation

/// A fixed stretch of time shown by the guide grid, and the math to lay
/// programmes out inside it as consecutive cells.
public struct GuideWindow: Sendable, Equatable {
    public let start: Date
    public let end: Date

    public init(start: Date, end: Date) {
        self.start = start
        self.end = max(end, start)
    }

    /// Starts at the half hour at or before `now` and runs for `hours`.
    public static func starting(at now: Date, hours: Double, slotMinutes: Int = 30) -> GuideWindow {
        let slot = TimeInterval(slotMinutes * 60)
        let floored = (now.timeIntervalSince1970 / slot).rounded(.down) * slot
        let start = Date(timeIntervalSince1970: floored)
        return GuideWindow(start: start, end: start.addingTimeInterval(hours * 3600))
    }

    public var duration: TimeInterval { end.timeIntervalSince(start) }

    /// Where `date` falls in the window, from 0 at the start to 1 at the end.
    public func fraction(of date: Date) -> Double {
        guard duration > 0 else { return 0 }
        return min(1, max(0, date.timeIntervalSince(start) / duration))
    }

    /// Times for the slot labels along the top.
    public func slots(minutes: Int = 30) -> [Date] {
        let step = TimeInterval(minutes * 60)
        guard step > 0 else { return [] }
        return stride(from: start.timeIntervalSince1970, to: end.timeIntervalSince1970, by: step)
            .map { Date(timeIntervalSince1970: $0) }
    }

    /// A slice of the window: a programme, or a gap with no listing.
    public struct Cell: Sendable, Hashable, Identifiable {
        public let entry: EPGEntry?
        public let start: Date
        public let end: Date
        /// Share of the window's width, 0...1.
        public let width: Double

        public var id: String { "\(Int(start.timeIntervalSince1970))-\(entry?.id ?? "gap")" }
    }

    /// Cells that tile the whole window left to right. Programmes are
    /// clipped to the window, overlaps are trimmed so each starts where the
    /// previous ended, and gaps become empty cells. A channel with no
    /// listings gets one empty cell, so every row has something to focus.
    public func cells(for entries: [EPGEntry]) -> [Cell] {
        guard duration > 0 else { return [] }
        var cells: [Cell] = []
        var cursor = start

        func add(_ entry: EPGEntry?, from: Date, to: Date) {
            guard to > from else { return }
            cells.append(Cell(entry: entry, start: from, end: to, width: to.timeIntervalSince(from) / duration))
        }

        for entry in entries.sorted(by: { $0.start < $1.start }) {
            let from = max(entry.start, cursor)
            let to = min(entry.end, end)
            guard to > from else { continue }
            add(nil, from: cursor, to: from)
            add(entry, from: from, to: to)
            cursor = to
            if cursor >= end { break }
        }
        add(nil, from: cursor, to: end)
        return cells
    }
}
