import Foundation

/// The viewer's own order for channel lists, by provider category.
///
/// Stored as small JSON next to the other settings, so it keeps as little
/// as it can: for each list, only the channel IDs from the top down to the
/// lowest row a move has touched. Everything below that follows the
/// provider's order. Moving a few channels to the top of a category of a
/// thousand stores a few IDs, not a thousand.
///
/// A channel the provider drops loses its place. A channel the provider
/// adds appears below the stored part, where the provider put it.
public struct ChannelOrder: Codable, Equatable, Sendable {
    /// List key (a provider category ID) to the IDs at the top of that list.
    public private(set) var lists: [String: [Int]] = [:]

    public init() {}

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        lists = (try? container.decode([String: [Int]].self)) ?? [:]
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(lists)
    }

    public var isEmpty: Bool { lists.isEmpty }

    /// Whether the viewer has changed this list's order.
    public func hasOrder(for key: String) -> Bool {
        lists[key] != nil
    }

    /// `items` in the viewer's order: the stored IDs first, then the rest as given.
    public func apply<Item>(to items: [Item], key: String, id: (Item) -> Int) -> [Item] {
        guard let stored = lists[key], !stored.isEmpty else { return items }
        var byID: [Int: Item] = [:]
        for item in items where byID[id(item)] == nil {
            byID[id(item)] = item
        }
        var placed = Set<Int>()
        var result: [Item] = []
        result.reserveCapacity(items.count)
        for storedID in stored {
            if let item = byID[storedID], placed.insert(storedID).inserted {
                result.append(item)
            }
        }
        for item in items where !placed.contains(id(item)) {
            result.append(item)
        }
        return result
    }

    public func apply(to streams: [LiveStream], key: String) -> [LiveStream] {
        apply(to: streams, key: key) { $0.id }
    }

    /// Moves one channel so it ends up at `index`.
    ///
    /// - Parameter shown: The list's IDs in the order the viewer sees them,
    ///   which is `apply` of the provider's list.
    public mutating func move(_ id: Int, to index: Int, in shown: [Int], key: String) {
        guard let from = shown.firstIndex(of: id), !shown.isEmpty else { return }
        let to = min(max(index, 0), shown.count - 1)
        guard from != to else { return }
        let moved = Self.moving(shown, from: from, to: to)
        // The part already stored sits at the top of `shown`; keep all of it.
        let present = Set(shown)
        let alreadyStored = (lists[key] ?? []).filter { present.contains($0) }.count
        let length = max(alreadyStored, max(from, to) + 1)
        lists[key] = Array(moved.prefix(length))
    }

    /// Back to the provider's order.
    public mutating func reset(_ key: String) {
        lists[key] = nil
    }

    /// `items` with the element at `from` taken out and put back at `to`.
    public static func moving<Item>(_ items: [Item], from: Int, to: Int) -> [Item] {
        guard items.indices.contains(from), !items.isEmpty else { return items }
        var result = items
        let item = result.remove(at: from)
        result.insert(item, at: min(max(to, 0), result.count))
        return result
    }
}
