import Foundation

/// The settings file the phone page saves from one TV and loads into another.
///
/// One versioned JSON shape, so the Google TV app can read the same file.
/// It never holds the password: the file sits in the phone's downloads, and
/// the login it names is typed or autofilled on the phone instead.
public struct SyncSettings: Codable, Equatable, Sendable {
    public static let format = "hilltv.settings"
    public static let currentVersion = 1
    /// Larger than any real file; anything bigger is refused unread.
    public static let maximumBytes = 2_000_000

    public struct Account: Codable, Equatable, Sendable {
        public var server: String
        public var username: String

        public init(server: String, username: String) {
            self.server = server
            self.username = username
        }
    }

    public var version: Int
    public var account: Account
    public var hiddenCategories: CategoryVisibility
    public var favorites: [LiveStream]
    public var channelOrder: ChannelOrder

    public init(
        account: Account,
        hiddenCategories: CategoryVisibility,
        favorites: [LiveStream],
        channelOrder: ChannelOrder
    ) {
        version = Self.currentVersion
        self.account = account
        self.hiddenCategories = hiddenCategories
        self.favorites = favorites
        self.channelOrder = channelOrder
    }

    enum CodingKeys: String, CodingKey {
        case format, version, account, hiddenCategories, favorites, channelOrder
    }

    public enum ReadError: Error, Equatable, LocalizedError {
        case tooLarge
        case notASettingsFile
        case newerVersion(Int)

        public var errorDescription: String? {
            switch self {
            case .tooLarge: return "That file is too large to be a HillTV settings file."
            case .notASettingsFile: return "That isn't a HillTV settings file."
            case .newerVersion(let version):
                return "That file was saved by a newer HillTV (format \(version)). Update this TV's app first."
            }
        }
    }

    /// Reads a file the phone sent. It is untrusted: size, format and
    /// version are checked, and each section decodes on its own, so one bad
    /// section leaves the others usable.
    public static func read(_ data: Data) throws -> SyncSettings {
        guard data.count <= maximumBytes else { throw ReadError.tooLarge }
        guard let file = try? JSONDecoder().decode(SyncSettings.self, from: data) else {
            throw ReadError.notASettingsFile
        }
        guard file.version <= currentVersion else { throw ReadError.newerVersion(file.version) }
        return file
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        guard (try? c.decode(String.self, forKey: .format)) == Self.format,
              let account = try? c.decode(Account.self, forKey: .account) else {
            throw ReadError.notASettingsFile
        }
        version = (try? c.decode(Int.self, forKey: .version)) ?? 1
        self.account = account
        hiddenCategories = (try? c.decode(CategoryVisibility.self, forKey: .hiddenCategories)) ?? CategoryVisibility()
        favorites = (try? c.decode(LossyArray<LiveStream>.self, forKey: .favorites))?.elements ?? []
        channelOrder = (try? c.decode(ChannelOrder.self, forKey: .channelOrder)) ?? ChannelOrder()
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(Self.format, forKey: .format)
        try c.encode(version, forKey: .version)
        try c.encode(account, forKey: .account)
        try c.encode(hiddenCategories, forKey: .hiddenCategories)
        try c.encode(favorites, forKey: .favorites)
        try c.encode(channelOrder, forKey: .channelOrder)
    }

    public func encoded() -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return (try? encoder.encode(self)) ?? Data()
    }

    /// Category IDs only mean something on the provider they came from.
    public static func sameServer(_ a: String, _ b: String) -> Bool {
        guard let left = XtreamAccount.normalizeServer(a), let right = XtreamAccount.normalizeServer(b) else {
            return false
        }
        return left.absoluteString.lowercased() == right.absoluteString.lowercased()
    }

    /// Which parts of a loaded file to apply.
    public struct Choices: Equatable, Sendable {
        public var hiddenCategories: Bool
        public var favorites: Bool
        public var channelOrder: Bool

        public init(hiddenCategories: Bool = true, favorites: Bool = false, channelOrder: Bool = false) {
            self.hiddenCategories = hiddenCategories
            self.favorites = favorites
            self.channelOrder = channelOrder
        }

        public var isEmpty: Bool { !hiddenCategories && !favorites && !channelOrder }
    }

    /// "Loaded 34 hidden categories and 12 favorites." for the phone and the TV.
    public func summary(applying choices: Choices) -> String {
        var parts: [String] = []
        if choices.hiddenCategories {
            let count = CatalogKind.allCases.reduce(0) { $0 + hiddenCategories.hiddenCount(in: $1) }
            parts.append(count == 1 ? "1 hidden category" : "\(count) hidden categories")
        }
        if choices.favorites {
            parts.append(favorites.count == 1 ? "1 favorite" : "\(favorites.count) favorites")
        }
        if choices.channelOrder {
            let lists = channelOrder.lists.count
            parts.append(lists == 1 ? "the order of 1 channel list" : "the order of \(lists) channel lists")
        }
        guard !parts.isEmpty else { return "Nothing was loaded." }
        let joined = parts.count > 1 ? parts.dropLast().joined(separator: ", ") + " and " + parts.last! : parts[0]
        return "Loaded \(joined)."
    }
}
