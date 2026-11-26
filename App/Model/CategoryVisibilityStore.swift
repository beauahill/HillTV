import Foundation
import HillTVCore
import Observation

/// The categories the user chose to hide, for the signed-in account.
///
/// Stored as small JSON in UserDefaults (IDs only), one entry per account.
/// Unlike watch history this is kept on sign out: hiding a few hundred
/// categories with a remote is not something to do twice. It lives on this
/// Apple TV only; nothing syncs between devices.
@MainActor
@Observable
final class CategoryVisibilityStore {
    private(set) var visibility = CategoryVisibility()
    private var accountKey: String?

    private static let key = "hiddenCategories.v1"

    func activate(account: XtreamAccount) {
        let key = "\(account.serverURL.absoluteString)|\(account.username)"
        accountKey = key
        visibility = Self.loadAll()[key] ?? CategoryVisibility()
    }

    /// Signing out forgets who is active but leaves the saved choices alone.
    func deactivate() {
        accountKey = nil
        visibility = CategoryVisibility()
    }

    func isHidden(_ categoryID: String?, in kind: CatalogKind) -> Bool {
        guard let categoryID else { return false }
        return visibility.isHidden(categoryID, in: kind)
    }

    func visible(_ categories: [XtreamCategory], in kind: CatalogKind) -> [XtreamCategory] {
        visibility.visible(categories, in: kind)
    }

    func hiddenCount(in kind: CatalogKind) -> Int {
        visibility.hiddenCount(in: kind)
    }

    func toggle(_ categoryID: String, in kind: CatalogKind) {
        visibility.toggle(categoryID, in: kind)
        persist()
    }

    func setHidden(_ isHidden: Bool, ids: [String], in kind: CatalogKind) {
        visibility.setHidden(isHidden, ids: ids, in: kind)
        persist()
    }

    func showAll(in kind: CatalogKind) {
        visibility.showAll(in: kind)
        persist()
    }

    /// From a settings file the phone loaded. Replaces every choice.
    func replace(with visibility: CategoryVisibility) {
        self.visibility = visibility
        persist()
    }

    private static func loadAll() -> [String: CategoryVisibility] {
        guard let data = UserDefaults.standard.data(forKey: key),
              let all = try? JSONDecoder().decode([String: CategoryVisibility].self, from: data) else { return [:] }
        return all
    }

    private func persist() {
        guard let accountKey else { return }
        var all = Self.loadAll()
        all[accountKey] = visibility == CategoryVisibility() ? nil : visibility
        if let data = try? JSONEncoder().encode(all) {
            UserDefaults.standard.set(data, forKey: Self.key)
        }
    }
}
