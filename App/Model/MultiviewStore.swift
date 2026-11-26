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
    private weak var app: AppModel?

    func attach(_ app: AppModel) {
        self.app = app
    }

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

    /// Adds a channel on an account with room: the one in use, or another
    /// saved account on the same server with a connection free (asking the
    /// panel can take a moment). When none has room the channel waits for
    /// the viewer's answer, unless they already chose "Try anyway". When the
    /// screen is full, it is dropped with a notice.
    ///
    /// `limit` is no longer used; the account in use and the others decide.
    func add(_ stream: LiveStream, limit _: Int) {
        guard !tiles.contains(stream.id) else { return }
        guard tiles.streams.count < Multiview.mostTiles else {
            show("Multiview shows four channels at most. Remove one to add another.")
            return
        }
        guard let app else { return }
        let snapshot = tiles
        Task { @MainActor in
            let key = await app.pickMultiviewAccount(for: snapshot)
            guard !tiles.contains(stream.id), tiles.streams.count < Multiview.mostTiles else { return }
            if let key {
                tiles.add(stream, limit: Multiview.mostTiles, account: key)
            } else if app.multiviewIgnoresLimit {
                tiles.add(stream, limit: Multiview.mostTiles, account: app.currentAccountKey)
            } else {
                waiting = stream
            }
        }
    }

    /// The viewer said "Try anyway": the waiting channel plays on the
    /// account in use, past what it reports.
    func addWaiting(limit _: Int) {
        guard let stream = waiting else { return }
        waiting = nil
        tiles.add(stream, limit: Multiview.mostTiles, account: app?.currentAccountKey)
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
