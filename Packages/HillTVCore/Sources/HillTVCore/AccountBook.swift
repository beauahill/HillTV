import Foundation

/// The logins saved on this TV and which one is in use.
///
/// Saved whole in the Keychain (it holds passwords). Every per-account store
/// (watch history, favorites, hidden categories, channel order) is keyed by
/// `key(_:)`, so the format of that key must never change.
public struct AccountBook: Codable, Equatable, Sendable {
    public private(set) var accounts: [XtreamAccount]
    public private(set) var currentKey: String?

    public init(accounts: [XtreamAccount] = [], currentKey: String? = nil) {
        self.accounts = []
        self.currentKey = nil
        for account in accounts { upsert(account, makeCurrent: false) }
        if let currentKey, self.accounts.contains(where: { Self.key($0) == currentKey }) {
            self.currentKey = currentKey
        } else {
            self.currentKey = self.accounts.first.map(Self.key)
        }
    }

    /// "http://line.example.com:8080|viewer". The same key the stores
    /// have always used, so nothing saved before this book existed is lost.
    public static func key(_ account: XtreamAccount) -> String {
        "\(account.serverURL.absoluteString)|\(account.username)"
    }

    public var current: XtreamAccount? {
        accounts.first { Self.key($0) == currentKey }
    }

    public var isEmpty: Bool { accounts.isEmpty }

    /// Adds a login, or updates the saved one with the same server and
    /// username (a new password), keeping its place in the list.
    public mutating func upsert(_ account: XtreamAccount, makeCurrent: Bool = true) {
        let key = Self.key(account)
        if let index = accounts.firstIndex(where: { Self.key($0) == key }) {
            accounts[index] = account
        } else {
            accounts.append(account)
        }
        if makeCurrent || currentKey == nil { currentKey = key }
    }

    /// Returns false for a key that isn't saved.
    @discardableResult
    public mutating func select(_ key: String) -> Bool {
        guard accounts.contains(where: { Self.key($0) == key }) else { return false }
        currentKey = key
        return true
    }

    /// Removing the account in use moves to the first one left, or to none.
    public mutating func remove(_ key: String) {
        accounts.removeAll { Self.key($0) == key }
        if currentKey == key || !accounts.contains(where: { Self.key($0) == currentKey }) {
            currentKey = accounts.first.map(Self.key)
        }
    }

    /// Other saved accounts on the same server as `account`, in list order.
    /// Only those can play the same channels: stream IDs belong to a server.
    public func others(onServerOf account: XtreamAccount) -> [XtreamAccount] {
        let key = Self.key(account)
        return accounts.filter {
            Self.key($0) != key && SyncSettings.sameServer($0.serverURL.absoluteString, account.serverURL.absoluteString)
        }
    }

    /// The first launch after this update: the one login saved the old way
    /// becomes a book of one, in use.
    public static func migrating(legacy: XtreamAccount?) -> AccountBook {
        AccountBook(accounts: legacy.map { [$0] } ?? [])
    }

    enum CodingKeys: String, CodingKey { case accounts, currentKey }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let accounts = (try? c.decode(LossyArray<XtreamAccount>.self, forKey: .accounts))?.elements ?? []
        let currentKey = try? c.decode(String.self, forKey: .currentKey)
        self.init(accounts: accounts, currentKey: currentKey)
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(accounts, forKey: .accounts)
        try c.encodeIfPresent(currentKey, forKey: .currentKey)
    }
}

extension XtreamAccount {
    /// "viewer · line.example.com", enough to tell two subscriptions apart.
    public var label: String {
        "\(username) · \(serverURL.host ?? serverURL.absoluteString)"
    }

    /// The first letter of the username, for the rail's badge.
    public var initial: String {
        username.first.map { String($0).uppercased() } ?? "?"
    }
}
