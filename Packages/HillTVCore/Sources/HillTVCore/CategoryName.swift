import Foundation

/// A provider's category name split into the part worth reading and the
/// labels around it. "VOD - NETFLIX [MULTISUB]" becomes the title "NETFLIX"
/// and the tag "MULTISUB".
///
/// For display only, like `ChannelName`. Grouping, hiding and saved orders
/// keep using the category's ID and the name exactly as sent.
///
/// It cuts only the forms seen on a real provider:
/// - A marker between bars at the start, "|NA| USA Sports". Shown as a tag.
/// - A kind word and a dash at the start, "VOD - " or "SRS - ". Dropped
///   without a tag: the page already says whether these are movies or series.
/// - Bracketed tags at the end, "[EN]" or "[MULTISUB]". Shown as tags. A
///   mistyped closing bracket, as in "[EN}", is accepted.
/// Letter case is left as sent, and if nothing would be left the whole name
/// is the title.
public struct CategoryName: Equatable, Sendable {
    /// The name without its labels.
    public let title: String
    /// Labels cut from the name, in the order they appeared.
    public let tags: [String]

    /// The tags on one line, such as "NA · EN". Nil when there were none.
    public var detail: String? {
        tags.isEmpty ? nil : tags.joined(separator: " · ")
    }

    /// Title and tags as one line of text, such as "NETFLIX · MULTISUB",
    /// for places that take a plain string.
    public var line: String {
        ([title] + tags).joined(separator: " · ")
    }

    public init(_ raw: String) {
        let whole = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        var text = whole
        var tags: [String] = []

        if let marker = Self.leadingBarMarker(text) {
            tags.append(marker.tag)
            text = marker.rest
        }
        text = Self.droppingKindPrefix(text)

        var trailing: [String] = []
        while let cut = Self.trailingBracketTag(text) {
            trailing.insert(cut.tag, at: 0)
            text = cut.rest
        }
        tags += trailing

        if text.isEmpty {
            title = whole
            self.tags = []
        } else {
            title = text
            self.tags = tags
        }
    }

    // MARK: Rules

    private static let tagCharacters = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-+ "))

    private static func isTag(_ text: String, longest: Int) -> Bool {
        !text.isEmpty && text.count <= longest
            && text.unicodeScalars.allSatisfy { tagCharacters.contains($0) }
            && text.unicodeScalars.contains { CharacterSet.alphanumerics.contains($0) }
    }

    /// "|NA| USA Sports"
    private static func leadingBarMarker(_ text: String) -> (tag: String, rest: String)? {
        guard text.first == "|" else { return nil }
        let inner = text.dropFirst()
        guard let close = inner.firstIndex(of: "|") else { return nil }
        let tag = inner[..<close].trimmingCharacters(in: .whitespaces)
        guard isTag(tag, longest: 6) else { return nil }
        let rest = inner[inner.index(after: close)...].trimmingCharacters(in: .whitespaces)
        return rest.isEmpty ? nil : (tag, rest)
    }

    /// Words a provider puts in front of a category to say what kind it is.
    private static let kindPrefixes = ["VOD", "SRS"]

    /// "VOD - NEW ADDED", "SRS - HULU". The dash needs a space on both
    /// sides, so a name that merely starts with the word is left alone.
    private static func droppingKindPrefix(_ text: String) -> String {
        for prefix in kindPrefixes {
            for dash in ["-", "–", "—"] {
                let lead = "\(prefix) \(dash) "
                if text.count > lead.count, text.prefix(lead.count).uppercased() == lead {
                    return text.dropFirst(lead.count).trimmingCharacters(in: .whitespaces)
                }
            }
        }
        return text
    }

    private static let closers: Set<Character> = ["]", "}", ")"]

    /// "HULU [EN]", "NETFLIX [MULTISUB]", "HULU [EN}"
    private static func trailingBracketTag(_ text: String) -> (tag: String, rest: String)? {
        guard let last = text.last, closers.contains(last), let open = text.lastIndex(of: "[") else { return nil }
        let tag = text[text.index(after: open)...].dropLast().trimmingCharacters(in: .whitespaces)
        guard isTag(tag, longest: 12) else { return nil }
        let rest = text[..<open].trimmingCharacters(in: .whitespaces)
        return (tag, rest)
    }
}

extension XtreamCategory {
    /// The category's name split for display. `name` stays as the provider sent it.
    public var displayName: CategoryName { CategoryName(name) }
}
