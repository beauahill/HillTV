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

    /// From inside the multiview.
    func add(_ stream: LiveStream, limit: Int) {
        if tiles.add(stream, limit: limit) == .full {
            show("Multiview is full. Remove a channel to add another.")
        }
    }

    func remove(_ streamID: Int) {
        tiles.remove(streamID)
    }

    func clear() {
        tiles.removeAll()
        isShowing = false
        notice = nil
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
