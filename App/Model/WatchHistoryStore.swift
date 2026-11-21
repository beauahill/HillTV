import Foundation
import HillTVCore
import Observation

/// Continue-watching state for the signed-in account, stored as small JSON
/// in UserDefaults. Tagged with server and username so a different account
/// never resumes the previous one's IDs, and cleared on sign out.
@MainActor
@Observable
final class WatchHistoryStore {
    private(set) var history = WatchHistory()
    private var accountKey: String?

    private static let key = "watchHistory.v1"

    private struct Stored: Codable {
        let accountKey: String
        let history: WatchHistory
    }

    var entries: [WatchEntry] { history.entries }

    func entry(id: String) -> WatchEntry? {
        history.entry(id: id)
    }

    func activate(account: XtreamAccount) {
        let key = "\(account.serverURL.absoluteString)|\(account.username)"
        accountKey = key
        if let data = UserDefaults.standard.data(forKey: Self.key),
           let stored = try? JSONDecoder().decode(Stored.self, from: data),
           stored.accountKey == key {
            history = stored.history
        } else {
            history = WatchHistory()
        }
    }

    func record(_ entry: WatchEntry) {
        guard accountKey != nil else { return }
        history.record(entry)
        persist()
    }

    func remove(id: String) {
        history.remove(id: id)
        persist()
    }

    func clear() {
        history = WatchHistory()
        accountKey = nil
        UserDefaults.standard.removeObject(forKey: Self.key)
    }

    private func persist() {
        guard let accountKey,
              let data = try? JSONEncoder().encode(Stored(accountKey: accountKey, history: history)) else { return }
        UserDefaults.standard.set(data, forKey: Self.key)
    }
}
