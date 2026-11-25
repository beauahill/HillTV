import Foundation

/// The handful of big buckets Live TV shows as pills. Providers send dozens
/// or hundreds of categories with names like "US | SPORTS HD" or
/// "FR ▎CINEMA"; each one is sorted into a bucket by keywords in its name.
///
/// Every category lands somewhere: anything unrecognized goes to `.other`.
public enum CategoryGroup: String, CaseIterable, Identifiable, Sendable, Codable {
    case sports
    case news
    case movies
    case shows
    case kids
    case music
    case world
    case other

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .sports: return "Sports"
        case .news: return "News"
        case .movies: return "Movies"
        case .shows: return "Shows"
        case .kids: return "Kids"
        case .music: return "Music"
        case .world: return "World"
        case .other: return "More"
        }
    }

    /// Sorts a provider category name into a bucket.
    ///
    /// Order of checks:
    /// 1. A foreign country or language marker wins, so "FR | SPORT" and
    ///    "LATINO NEWS" go to World rather than crowding Sports and News.
    ///    English-speaking markers ("US", "UK", "CA") are ignored here, so
    ///    "UK | NEWS" is still News.
    /// 2. Topic keywords, most specific first: a name matching two topics
    ///    ("KIDS MOVIES", "SPORTS NEWS") takes the earlier one.
    /// 3. A bare English-speaking marker ("USA", "UK HD") counts as Shows.
    /// 4. Otherwise More.
    public static func classify(_ name: String) -> CategoryGroup {
        let tokens = SearchIndex<String>.fold(name).split(separator: " ").map(String.init)
        guard !tokens.isEmpty else { return .other }
        let set = Set(tokens)

        if !set.isDisjoint(with: foreignWords) || foreignPrefixes.contains(tokens[0]) {
            return .world
        }
        for (group, words) in topics where !set.isDisjoint(with: words) {
            return group
        }
        // "24/7" folds to the two tokens "24" and "7": looping single-show channels.
        if set.contains("24"), set.contains("7") { return .shows }
        if !set.isDisjoint(with: englishWords) { return .shows }
        return .other
    }

    /// Buckets in pill order, each with its categories in the provider's
    /// order. Empty buckets are left out.
    public static func grouping(_ categories: [XtreamCategory]) -> [GroupedCategories] {
        var buckets: [CategoryGroup: [XtreamCategory]] = [:]
        for category in categories {
            buckets[classify(category.name), default: []].append(category)
        }
        return allCases.compactMap { group in
            guard let items = buckets[group], !items.isEmpty else { return nil }
            return GroupedCategories(group: group, categories: items)
        }
    }

    // Checked in this order. Words are whole tokens after folding.
    private static let topics: [(CategoryGroup, Set<String>)] = [
        (.kids, ["kid", "kids", "children", "child", "cartoon", "cartoons", "toon", "toons", "junior", "baby",
                 "disney", "nick", "nickelodeon"]),
        (.sports, ["sport", "sports", "nfl", "nba", "nhl", "mlb", "mls", "ncaa", "ncaaf", "ncaab", "ufc", "wwe", "f1",
                   "soccer", "football", "basketball", "baseball", "hockey", "golf", "tennis", "cricket", "rugby",
                   "boxing", "mma", "racing", "motorsport", "motorsports", "wrestling", "ppv", "espn", "dazn",
                   "bein", "league", "cup", "olympics", "event", "events", "match", "matches"]),
        (.news, ["news", "weather", "business"]),
        (.music, ["music", "musica", "radio", "mtv", "concert", "concerts", "karaoke"]),
        (.movies, ["movie", "movies", "cinema", "film", "films"]),
        (.shows, ["entertainment", "series", "show", "shows", "documentary", "documentaries", "docs", "general",
                  "lifestyle", "comedy", "reality", "cooking", "food", "travel", "nature", "science", "history",
                  "discovery", "drama", "local", "locals", "regional", "network", "networks"]),
    ]

    static let englishWords: Set<String> = [
        "us", "usa", "uk", "ca", "canada", "america", "american", "english", "australia", "ireland",
    ]

    /// Country and language names that mark a category as not in English.
    static let foreignWords: Set<String> = [
        "international", "latino", "latina", "latin", "latam", "spanish", "espanol", "mexico", "mexican",
        "arabic", "arab", "india", "indian", "hindi", "punjabi", "tamil", "telugu", "malayalam", "urdu",
        "pakistan", "pakistani", "bangla", "bengali", "france", "french", "germany", "german", "deutsch",
        "italy", "italian", "italia", "spain", "portugal", "portuguese", "brazil", "brasil", "turkey", "turkish",
        "greece", "greek", "poland", "polish", "russia", "russian", "ukraine", "ukrainian", "china", "chinese",
        "japan", "japanese", "korea", "korean", "vietnam", "vietnamese", "thai", "thailand", "philippines",
        "filipino", "africa", "african", "nigeria", "somali", "ethiopia", "iran", "persian", "kurdish",
        "albania", "albanian", "romania", "romanian", "bulgaria", "bulgarian", "serbia", "croatia", "bosnia",
        "exyu", "netherlands", "dutch", "belgium", "sweden", "swedish", "norway", "denmark", "finland",
        "nordic", "scandinavia", "israel", "hebrew", "armenia", "armenian", "afghan", "afghanistan",
        "caribbean", "haiti", "colombia", "argentina", "chile", "peru", "venezuela", "europe", "asia",
    ]

    /// Every country and language word above, for `ChannelName`.
    static let regionWords = englishWords.union(foreignWords)

    /// Two-letter country codes, counted only as the first word ("FR | CINEMA").
    /// Elsewhere they are too easily ordinary words. Codes that are common
    /// English words even in first place ("NO", "IN", "BE") are left out.
    private static let foreignPrefixes: Set<String> = [
        "fr", "de", "es", "it", "pt", "tr", "ar", "pl", "ru", "gr", "nl", "se", "dk", "fi",
        "ro", "bg", "al", "pk", "br", "mx", "cn", "jp", "kr", "ph", "ir", "il", "af",
    ]
}

public struct GroupedCategories: Sendable, Equatable, Identifiable {
    public let group: CategoryGroup
    public let categories: [XtreamCategory]

    public var id: String { group.id }

    public init(group: CategoryGroup, categories: [XtreamCategory]) {
        self.group = group
        self.categories = categories
    }
}

/// Which catalog a category belongs to. IDs are only unique within one.
public enum CatalogKind: String, CaseIterable, Identifiable, Sendable, Codable {
    case live
    case movies
    case series

    public var id: String { rawValue }
}

/// The categories the user chose to hide, per catalog. Small enough to keep
/// in UserDefaults: it stores IDs only.
public struct CategoryVisibility: Codable, Equatable, Sendable {
    /// Keyed by `CatalogKind.rawValue` so the encoded form is a plain object.
    private var hidden: [String: Set<String>] = [:]

    public init() {}

    public func isHidden(_ categoryID: String, in kind: CatalogKind) -> Bool {
        hidden[kind.rawValue]?.contains(categoryID) ?? false
    }

    public func hiddenCount(in kind: CatalogKind) -> Int {
        hidden[kind.rawValue]?.count ?? 0
    }

    /// `categories` without the hidden ones, order kept.
    public func visible(_ categories: [XtreamCategory], in kind: CatalogKind) -> [XtreamCategory] {
        guard let set = hidden[kind.rawValue], !set.isEmpty else { return categories }
        return categories.filter { !set.contains($0.id) }
    }

    public mutating func setHidden(_ isHidden: Bool, ids: some Sequence<String>, in kind: CatalogKind) {
        var set = hidden[kind.rawValue] ?? []
        if isHidden {
            set.formUnion(ids)
        } else {
            set.subtract(ids)
        }
        hidden[kind.rawValue] = set.isEmpty ? nil : set
    }

    public mutating func toggle(_ categoryID: String, in kind: CatalogKind) {
        setHidden(!isHidden(categoryID, in: kind), ids: [categoryID], in: kind)
    }

    public mutating func showAll(in kind: CatalogKind) {
        hidden[kind.rawValue] = nil
    }
}
