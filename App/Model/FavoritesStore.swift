import Foundation
import HillTVCore
import Observation

/// Favorite live channels, stored as small JSON in UserDefaults. tvOS caps
/// UserDefaults around 500 KB and can purge Caches, so keep anything large
/// (channel lists, EPG) in memory or Caches and refetch when missing.
@MainActor
@Observable
final class FavoritesStore {
    private(set) var channels: [LiveStream] = []
    private static let key = "favoriteChannels.v1"

    init() {
        if let data = UserDefaults.standard.data(forKey: Self.key),
           let saved = try? JSONDecoder().decode([LiveStream].self, from: data) {
            channels = saved
        }
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

    private func persist() {
        if let data = try? JSONEncoder().encode(channels) {
            UserDefaults.standard.set(data, forKey: Self.key)
        }
    }
}
