import Foundation
import HillTVCore
import Observation

/// Favorite live channels for the account in use, stored as small JSON in
/// UserDefaults, one list per account. tvOS caps UserDefaults around 500 KB
/// and can purge Caches, so keep anything large (channel lists, EPG) in
/// memory or Caches and refetch when missing.
///
/// Favorites used to be one list for every account. The first account
/// activated after the update (the one in use) takes that list over.
@MainActor
@Observable
final class FavoritesStore {
    private(set) var channels: [LiveStream] = []
    private var accountKey: String?

    private static let key = "favoriteChannels.v2"
    private static let legacyKey = "favoriteChannels.v1"

    func activate(account: XtreamAccount) {
        let key = AccountBook.key(account)
        accountKey = key
        var all = Self.loadAll()
        if all[key] == nil,
           let data = UserDefaults.standard.data(forKey: Self.legacyKey),
           let legacy = try? JSONDecoder().decode([LiveStream].self, from: data) {
            all[key] = legacy
            Self.saveAll(all)
            UserDefaults.standard.removeObject(forKey: Self.legacyKey)
        }
        channels = all[key] ?? []
    }

    func deactivate() {
        accountKey = nil
        channels = []
    }

    func contains(_ streamID: Int) -> Bool {
        channels.contains { $0.id == streamID }
    }

    func toggle(_ stream: LiveStream) {
        if let index = channels.firstIndex(where: { $0.id == stream.id }) {
            channels.remove(at: index)
        } else {
            channels.append(stream)
        }
        persist()
    }

    /// Puts a favorite at `index`. The order here is the order everywhere
    /// favorites are listed: the Favorites page, Live TV, the Guide and Home.
    func move(_ streamID: Int, to index: Int) {
        guard let from = channels.firstIndex(where: { $0.id == streamID }) else { return }
        channels = ChannelOrder.moving(channels, from: from, to: index)
        persist()
    }

    /// From a settings file the phone loaded. Replaces this account's
    /// favorites, keeping the first of any channel listed twice.
    func replace(with streams: [LiveStream]) {
        var seen = Set<Int>()
        channels = streams.filter { seen.insert($0.id).inserted }
        persist()
    }

    private static func loadAll() -> [String: [LiveStream]] {
        guard let data = UserDefaults.standard.data(forKey: key),
              let all = try? JSONDecoder().decode([String: [LiveStream]].self, from: data) else { return [:] }
        return all
    }

    private static func saveAll(_ all: [String: [LiveStream]]) {
        if let data = try? JSONEncoder().encode(all) {
            UserDefaults.standard.set(data, forKey: key)
        }
    }

    private func persist() {
        guard let accountKey else { return }
        var all = Self.loadAll()
        all[accountKey] = channels.isEmpty ? nil : channels
        Self.saveAll(all)
    }
}
