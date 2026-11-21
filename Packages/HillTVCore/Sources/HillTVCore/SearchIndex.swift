import Foundation

/// Case- and accent-insensitive name search over a catalog. Names are folded
/// once when the index is built, so each keystroke is a cheap scan.
public struct SearchIndex<Item: Sendable>: Sendable {
    private struct Entry: Sendable {
        let folded: String
        let item: Item
    }

    private let entries: [Entry]

    public var count: Int { entries.count }

    public init(items: [Item], name: (Item) -> String) {
        entries = items.map { Entry(folded: Self.fold(name($0)), item: $0) }
    }

    /// Items whose name contains every word of the query. Names that start
    /// with the query rank first, then names with a word starting with it,
    /// then other matches. Catalog order is kept within each group.
    public func search(_ query: String, limit: Int) -> [Item] {
        let foldedQuery = Self.fold(query)
        let tokens = foldedQuery.split(separator: " ")
        guard !tokens.isEmpty, limit > 0 else { return [] }

        var buckets: [[Item]] = [[], [], []]
        for entry in entries {
            guard tokens.allSatisfy({ entry.folded.contains($0) }) else { continue }
            if entry.folded.hasPrefix(foldedQuery) {
                buckets[0].append(entry.item)
            } else if entry.folded.contains(" " + tokens[0]) {
                buckets[1].append(entry.item)
            } else {
                buckets[2].append(entry.item)
            }
            // The first bucket alone can fill the limit; stop scanning early.
            if buckets[0].count >= limit { break }
        }
        return Array(buckets.joined().prefix(limit))
    }

    /// Lowercased, accents removed, punctuation turned into single spaces.
    /// "Ciné+ | FR: Amélie" becomes "cine fr amelie".
    public static func fold(_ string: String) -> String {
        let folded = string.folding(options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive], locale: nil)
        var result = ""
        result.reserveCapacity(folded.count)
        var lastWasSpace = true
        for scalar in folded.unicodeScalars {
            if CharacterSet.alphanumerics.contains(scalar) {
                result.unicodeScalars.append(scalar)
                lastWasSpace = false
            } else if !lastWasSpace {
                result.append(" ")
                lastWasSpace = true
            }
        }
        if result.hasSuffix(" ") { result.removeLast() }
        return result.lowercased()
    }
}
