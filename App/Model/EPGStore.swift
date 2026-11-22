import Foundation
import HillTVCore
import Observation

/// Short EPG per channel, shared by Live TV and Home.
///
/// Rows ask for a channel while they are on screen. Requests run a few at a
/// time and results are cached, empty ones included, so a channel with no
/// guide data isn't refetched every time it scrolls into view. See
/// `EPGSchedule.needsFetch` for when a channel is asked again.
@MainActor
@Observable
final class EPGStore {
    let client: XtreamClient

    private(set) var schedules: [Int: EPGSchedule] = [:]
    /// Moves forward twice a minute so progress bars and "min left" labels keep up.
    private(set) var now = Date()

    private var fetchedAt: [Int: Date] = [:]
    /// Rows on screen now, so listings that run out can be refreshed in place.
    private var visible: Set<Int> = []
    private var appearTasks: [Int: Task<Void, Never>] = [:]
    private var waiting: [Int] = []
    private var queued: Set<Int> = []
    private var active = 0

    private static let maxConcurrent = 4
    private static let entriesPerChannel = 6

    init(client: XtreamClient) {
        self.client = client
    }

    func schedule(for streamID: Int) -> EPGSchedule? {
        schedules[streamID]
    }

    /// Runs until the calling task is cancelled. Start it from a `.task`.
    func runClock() async {
        now = Date()
        while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(30))
            now = Date()
            // tvOS keeps apps around for days. Rows that stay on screen never
            // "appear" again, so refresh the ones whose listings have run out.
            for streamID in visible {
                enqueue(streamID)
            }
        }
    }

    /// A row came on screen. Fetches only if it stays there for a moment, so
    /// fast scrolling doesn't flood the panel.
    func rowAppeared(_ streamID: Int) {
        visible.insert(streamID)
        guard needsFetch(streamID), !queued.contains(streamID), appearTasks[streamID] == nil else { return }
        appearTasks[streamID] = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled, let self else { return }
            self.appearTasks[streamID] = nil
            self.enqueue(streamID)
        }
    }

    func rowDisappeared(_ streamID: Int) {
        visible.remove(streamID)
        appearTasks[streamID]?.cancel()
        appearTasks[streamID] = nil
        if let index = waiting.firstIndex(of: streamID) {
            waiting.remove(at: index)
            queued.remove(streamID)
        }
    }

    /// The focused channel: skip the wait and go to the front of the queue.
    func prioritize(_ streamID: Int) {
        appearTasks[streamID]?.cancel()
        appearTasks[streamID] = nil
        if let index = waiting.firstIndex(of: streamID) {
            waiting.remove(at: index)
            queued.remove(streamID)
        }
        enqueue(streamID)
    }

    private func needsFetch(_ streamID: Int) -> Bool {
        EPGSchedule.needsFetch(schedule: schedules[streamID], fetchedAt: fetchedAt[streamID], now: Date())
    }

    private func enqueue(_ streamID: Int) {
        guard needsFetch(streamID), !queued.contains(streamID) else { return }
        queued.insert(streamID)
        waiting.append(streamID)
        pump()
    }

    private func pump() {
        let client = client
        let limit = Self.entriesPerChannel
        // Newest request first: that's what just scrolled into view or took focus.
        while active < Self.maxConcurrent, let streamID = waiting.popLast() {
            active += 1
            Task { [weak self] in
                let entries = try? await client.shortEPG(streamID: streamID, limit: limit)
                guard let self else { return }
                if let entries {
                    self.schedules[streamID] = EPGSchedule(entries)
                } else if self.schedules[streamID] == nil {
                    // Failed with nothing cached: show "no guide data" rather than a spinner.
                    self.schedules[streamID] = EPGSchedule.empty
                }
                // Stamp failures too, so they wait out the retry interval.
                self.fetchedAt[streamID] = Date()
                self.queued.remove(streamID)
                self.active -= 1
                self.pump()
            }
        }
    }
}
