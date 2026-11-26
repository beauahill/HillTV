import Foundation
import HillTVCore
import Observation

/// Continue-watching state, one list per account, stored as small JSON in
/// UserDefaults. Removing an account deletes its list.
///
/// It used to be one list tagged with the account it belonged to. The first
/// launch after the update files that list under its account.
@MainActor
@Observable
final class WatchHistoryStore {
    private(set) var history = WatchHistory()
    private var accountKey: String?

    private static let key = "watchHistory.v2"
    private static let legacyKey = "watchHistory.v1"

    private struct Legacy: Codable {
        let accountKey: String
        let history: WatchHistory
    }

    var entries: [WatchEntry] { history.entries }

    func entry(id: String) -> WatchEntry? {
        history.entry(id: id)
    }

    func activate(account: XtreamAccount) {
        let key = AccountBook.key(account)
        accountKey = key
        var all = Self.loadAll()
        if let data = UserDefaults.standard.data(forKey: Self.legacyKey) {
            if let legacy = try? JSONDecoder().decode(Legacy.self, from: data), all[legacy.accountKey] == nil {
                all[legacy.accountKey] = legacy.history
                Self.saveAll(all)
            }
            UserDefaults.standard.removeObject(forKey: Self.legacyKey)
        }
        history = all[key] ?? WatchHistory()
    }

    func deactivate() {
        history = WatchHistory()
        accountKey = nil
    }

    /// For a removed account.
    func forget(accountKey key: String) {
        var all = Self.loadAll()
        all[key] = nil
        Self.saveAll(all)
        if accountKey == key { history = WatchHistory() }
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

    private static func loadAll() -> [String: WatchHistory] {
        guard let data = UserDefaults.standard.data(forKey: key),
              let all = try? JSONDecoder().decode([String: WatchHistory].self, from: data) else { return [:] }
        return all
    }

    private static func saveAll(_ all: [String: WatchHistory]) {
        if let data = try? JSONEncoder().encode(all) {
            UserDefaults.standard.set(data, forKey: key)
        }
    }

    private func persist() {
        guard let accountKey else { return }
        var all = Self.loadAll()
        all[accountKey] = history.entries.isEmpty ? nil : history
        Self.saveAll(all)
    }
}
