import Foundation
import HillTVCore
import Security

/// Saves the Xtream login in the Keychain. If the Keychain write fails (it
/// shouldn't, but re-signed sideloads occasionally have odd entitlements), it
/// falls back to UserDefaults so the box doesn't ask for a login every launch.
enum AccountStore {
    private static let service = "com.beauahill.hilltv.account"
    private static let accountKey = "xtream"
    private static let fallbackKey = "xtreamAccountFallback"

    private static var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: accountKey,
        ]
    }

    static func load() -> XtreamAccount? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        if SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
           let data = item as? Data,
           let account = try? JSONDecoder().decode(XtreamAccount.self, from: data) {
            return account
        }
        if let data = UserDefaults.standard.data(forKey: fallbackKey) {
            return try? JSONDecoder().decode(XtreamAccount.self, from: data)
        }
        return nil
    }

    static func save(_ account: XtreamAccount) {
        guard let data = try? JSONEncoder().encode(account) else { return }
        delete()

        var query = baseQuery
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock

        let status = SecItemAdd(query as CFDictionary, nil)
        if status != errSecSuccess {
            print("[AccountStore] Keychain save failed (\(status)); using UserDefaults fallback")
            UserDefaults.standard.set(data, forKey: fallbackKey)
        }
    }

    static func delete() {
        SecItemDelete(baseQuery as CFDictionary)
        UserDefaults.standard.removeObject(forKey: fallbackKey)
    }
}
