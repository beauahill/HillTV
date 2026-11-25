import Foundation
import HillTVCore
import Observation

/// The channels in the multiview and whether it is on screen. Kept for as
/// long as the app runs, so closing the multiview to find another channel
/// doesn't lose the ones already picked. Not saved between launches.
@MainActor
@Observable
final class MultiviewStore {
    private(set) var tiles = MultiviewTiles()
    /// `MainShell` shows the multiview full screen while this is true.
    var isShowing = false
    /// A short message shown over the multiview, such as "full".
    private(set) var notice: String?
    /// A channel that would go past the connections the account reports.
    /// The multiview asks the viewer about it, then adds or drops it.
    private(set) var waiting: LiveStream?

    private var noticeTask: Task<Void, Never>?

    /// From a hold-Select menu: adds the channel and opens the multiview.
    /// Opening waits for the menu to close, because a cover presented while
    /// a menu is still closing can be dropped.
    func addAndOpen(_ stream: LiveStream, limit: Int) {
        add(stream, limit: limit)
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(350))
            isShowing = true
        }
    }

    /// Adds a channel if there is room. When the account's reported
    /// connections are what's in the way, the channel waits for the viewer's
    /// answer instead; when the screen is full, it is dropped with a notice.
    func add(_ stream: LiveStream, limit: Int) {
        guard tiles.add(stream, limit: limit) == .full else { return }
        if limit < Multiview.mostTiles {
            waiting = stream
        } else {
            show("Multiview shows four channels at most. Remove one to add another.")
        }
    }

    /// The viewer said yes: add the waiting channel under the new limit.
    func addWaiting(limit: Int) {
        guard let stream = waiting else { return }
        waiting = nil
        add(stream, limit: limit)
    }

    /// The viewer said no, or closed the question.
    func dropWaiting() {
        waiting = nil
    }

    func remove(_ streamID: Int) {
        tiles.remove(streamID)
    }

    func clear() {
        tiles.removeAll()
        isShowing = false
        notice = nil
        waiting = nil
    }

    private func show(_ message: String) {
        notice = message
        noticeTask?.cancel()
        noticeTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(6))
            guard !Task.isCancelled else { return }
            self?.notice = nil
        }
    }
}
