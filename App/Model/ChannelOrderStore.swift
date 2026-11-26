import Foundation
import HillTVCore
import Observation

/// The order the viewer gave their channel lists, for the signed-in account.
///
/// Small JSON in UserDefaults, one entry per account, kept on sign out like
/// hidden categories. `ChannelOrder` stores only the top of each list the
/// viewer has changed, so it stays small. Favorites are not in here: their
/// order is the order of `FavoritesStore.channels`.
@MainActor
@Observable
final class ChannelOrderStore {
    private(set) var order = ChannelOrder()
    private var accountKey: String?

    private static let key = "channelOrder.v1"

    func activate(account: XtreamAccount) {
        let key = "\(account.serverURL.absoluteString)|\(account.username)"
        accountKey = key
        order = Self.loadAll()[key] ?? ChannelOrder()
    }

    /// Signing out forgets who is active but leaves the saved order alone.
    func deactivate() {
        accountKey = nil
        order = ChannelOrder()
    }

    func hasOrder(for categoryID: String) -> Bool {
        order.hasOrder(for: categoryID)
    }

    /// A category's channels in the viewer's order.
    func apply(_ streams: [LiveStream], categoryID: String) -> [LiveStream] {
        order.apply(to: streams, key: categoryID)
    }

    /// - Parameter shown: The category's channels as they are listed now.
    func move(_ streamID: Int, to index: Int, in shown: [LiveStream], categoryID: String) {
        order.move(streamID, to: index, in: shown.map(\.id), key: categoryID)
        persist()
    }

    func reset(_ categoryID: String) {
        order.reset(categoryID)
        persist()
    }

    /// From a settings file the phone loaded. Replaces every saved order.
    func replace(with order: ChannelOrder) {
        self.order = order
        persist()
    }

    private static func loadAll() -> [String: ChannelOrder] {
        guard let data = UserDefaults.standard.data(forKey: key),
              let all = try? JSONDecoder().decode([String: ChannelOrder].self, from: data) else { return [:] }
        return all
    }

    private func persist() {
        guard let accountKey else { return }
        var all = Self.loadAll()
        all[accountKey] = order.isEmpty ? nil : order
        if let data = try? JSONEncoder().encode(all) {
            UserDefaults.standard.set(data, forKey: Self.key)
        }
    }
}
