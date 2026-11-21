import Foundation

/// One movie or episode the viewer has started. Stores IDs, never stream
/// URLs: those embed the account password, and history lives in plain
/// UserDefaults while the account lives in the Keychain.
public struct WatchEntry: Codable, Hashable, Sendable, Identifiable {
    public enum Kind: String, Codable, Sendable {
        case movie
        case episode
    }

    public let kind: Kind
    /// Movie `stream_id` or episode `id`, as used in the play URL.
    public let streamID: String
    public let containerExtension: String
    public var title: String
    public var subtitle: String?
    public var artworkURL: URL?
    /// Seconds into the item.
    public var position: Double
    /// Seconds, when the player knows it. Live-like or broken files report none.
    public var duration: Double?
    public var updatedAt: Date

    public var id: String { "\(kind.rawValue)-\(streamID)" }

    /// 0...1 when the duration is known.
    public var progress: Double? {
        guard let duration, duration > 0 else { return nil }
        return min(1, max(0, position / duration))
    }

    public init(
        kind: Kind,
        streamID: String,
        containerExtension: String,
        title: String,
        subtitle: String? = nil,
        artworkURL: URL? = nil,
        position: Double,
        duration: Double?,
        updatedAt: Date = Date()
    ) {
        self.kind = kind
        self.streamID = streamID
        self.containerExtension = containerExtension
        self.title = title
        self.subtitle = subtitle
        self.artworkURL = artworkURL
        self.position = position
        self.duration = duration
        self.updatedAt = updatedAt
    }
}

/// Newest-first list of partly watched items, small enough for UserDefaults.
public struct WatchHistory: Codable, Sendable, Equatable {
    public private(set) var entries: [WatchEntry]

    /// Most items kept.
    public static let maxEntries = 30
    /// Seconds watched before an item counts as started.
    public static let minimumPosition: Double = 30
    /// At or past this fraction an item counts as finished and leaves the list.
    public static let finishedFraction = 0.95

    public init(entries: [WatchEntry] = []) {
        self.entries = entries
    }

    /// Saves progress. Finished items are removed; items barely started are
    /// ignored and leave any earlier progress alone; everything else moves to
    /// the front.
    public mutating func record(_ update: WatchEntry) {
        var entry = update
        guard entry.position.isFinite, entry.position >= 0 else { return }
        if let duration = entry.duration, !duration.isFinite || duration <= 0 {
            entry.duration = nil
        }

        if Self.isFinished(entry) {
            remove(id: entry.id)
            return
        }
        guard entry.position >= Self.minimumPosition else { return }

        remove(id: entry.id)
        entries.insert(entry, at: 0)
        if entries.count > Self.maxEntries {
            entries.removeLast(entries.count - Self.maxEntries)
        }
    }

    public mutating func remove(id: String) {
        entries.removeAll { $0.id == id }
    }

    public func entry(id: String) -> WatchEntry? {
        entries.first { $0.id == id }
    }

    static func isFinished(_ entry: WatchEntry) -> Bool {
        guard let progress = entry.progress else { return false }
        return progress >= finishedFraction
    }
}
