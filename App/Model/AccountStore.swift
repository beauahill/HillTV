import Foundation
import HillTVCore
import Security

/// Saves the logins (`AccountBook`) in the Keychain. If the Keychain write
/// fails (it shouldn't, but re-signed sideloads occasionally have odd
/// entitlements), it falls back to UserDefaults so the box doesn't ask for a
/// login every launch.
///
/// Before several accounts, one login was saved under a different Keychain
/// item. The first load after the update moves it into a book of one and
/// deletes the old item. From then on the book is always saved, even empty,
/// so a removed last account can't come back from the old item.
enum AccountStore {
    private static let service = "com.beauahill.hilltv.account"
    private static let bookKey = "xtream.book"
    private static let bookFallbackKey = "xtreamAccountBookFallback"
    private static let legacyKey = "xtream"
    private static let legacyFallbackKey = "xtreamAccountFallback"

    private static func query(_ account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    static func loadBook() -> AccountBook {
        if let data = read(bookKey) ?? UserDefaults.standard.data(forKey: bookFallbackKey),
           let book = try? JSONDecoder().decode(AccountBook.self, from: data) {
            return book
        }
        let legacy = (read(legacyKey) ?? UserDefaults.standard.data(forKey: legacyFallbackKey))
            .flatMap { try? JSONDecoder().decode(XtreamAccount.self, from: $0) }
        let book = AccountBook.migrating(legacy: legacy)
        if legacy != nil {
            save(book)
        }
        return book
    }

    static func save(_ book: AccountBook) {
        guard let data = try? JSONEncoder().encode(book) else { return }
        SecItemDelete(query(bookKey) as CFDictionary)
        var item = query(bookKey)
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        let status = SecItemAdd(item as CFDictionary, nil)
        if status == errSecSuccess {
            UserDefaults.standard.removeObject(forKey: bookFallbackKey)
        } else {
            print("[AccountStore] Keychain save failed (\(status)); using UserDefaults fallback")
            UserDefaults.standard.set(data, forKey: bookFallbackKey)
        }
        // The old single-login item is only ever read once, to migrate.
        SecItemDelete(query(legacyKey) as CFDictionary)
        UserDefaults.standard.removeObject(forKey: legacyFallbackKey)
    }

    private static func read(_ account: String) -> Data? {
        var item = query(account)
        item[kSecReturnData as String] = true
        item[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(item as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }
}
