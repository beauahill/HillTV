import Foundation

// MARK: - Account

public struct XtreamAccount: Codable, Hashable, Sendable {
    /// Scheme + host + optional port + optional base path, no trailing slash.
    /// Example: http://line.example.com:8080
    public var serverURL: URL
    public var username: String
    public var password: String

    public init(serverURL: URL, username: String, password: String) {
        self.serverURL = serverURL
        self.username = username
        self.password = password
    }

    /// Turns whatever the user typed ("host:8080", "http://host:8080/",
    /// "https://host/panel/player_api.php?username=...") into a clean base URL.
    public static func normalizeServer(_ input: String) -> URL? {
        var text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }
        if !text.lowercased().hasPrefix("http://") && !text.lowercased().hasPrefix("https://") {
            text = "http://" + text
        }
        guard var components = URLComponents(string: text), let host = components.host, !host.isEmpty else {
            return nil
        }
        components.query = nil
        components.fragment = nil
        var path = components.path
        for suffix in ["/player_api.php", "/get.php", "/xmltv.php"] where path.lowercased().hasSuffix(suffix) {
            path.removeLast(suffix.count)
        }
        while path.hasSuffix("/") { path.removeLast() }
        components.path = path
        return components.url
    }
}

// MARK: - Auth

public struct XtreamAuthInfo: Decodable, Sendable {
    public let user: UserInfo
    public let server: ServerInfo?

    public var isAuthorized: Bool { user.auth && (user.status?.lowercased() ?? "active") == "active" }

    enum CodingKeys: String, CodingKey {
        case user = "user_info"
        case server = "server_info"
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        user = try container.decode(UserInfo.self, forKey: .user)
        server = try? container.decodeIfPresent(ServerInfo.self, forKey: .server)
    }

    public struct UserInfo: Decodable, Sendable {
        public let username: String?
        public let status: String?
        public let auth: Bool
        public let expiration: Date?
        public let isTrial: Bool
        public let activeConnections: Int?
        public let maxConnections: Int?
        public let allowedOutputFormats: [String]

        enum CodingKeys: String, CodingKey {
            case username, status, auth
            case expDate = "exp_date"
            case isTrial = "is_trial"
            case activeConnections = "active_cons"
            case maxConnections = "max_connections"
            case allowedOutputFormats = "allowed_output_formats"
        }

        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            username = c.lenientString(.username)
            status = c.lenientString(.status)
            auth = c.lenientBool(.auth)
            expiration = c.lenientInt(.expDate).flatMap { $0 > 0 ? Date(timeIntervalSince1970: TimeInterval($0)) : nil }
            isTrial = c.lenientBool(.isTrial)
            activeConnections = c.lenientInt(.activeConnections)
            maxConnections = c.lenientInt(.maxConnections)
            allowedOutputFormats = c.lenientStringArray(.allowedOutputFormats).map { $0.lowercased() }
        }
    }

    public struct ServerInfo: Decodable, Sendable {
        public let url: String?
        public let port: String?
        public let httpsPort: String?
        public let serverProtocol: String?
        public let timezone: String?

        enum CodingKeys: String, CodingKey {
            case url, port, timezone
            case httpsPort = "https_port"
            case serverProtocol = "server_protocol"
        }

        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            url = c.lenientString(.url)
            port = c.lenientString(.port)
            httpsPort = c.lenientString(.httpsPort)
            serverProtocol = c.lenientString(.serverProtocol)
            timezone = c.lenientString(.timezone)
        }
    }
}

// MARK: - Catalog

public struct XtreamCategory: Identifiable, Hashable, Sendable, Decodable {
    public let id: String
    public let name: String
    public let parentID: String?

    public init(id: String, name: String, parentID: String? = nil) {
        self.id = id
        self.name = name
        self.parentID = parentID
    }

    enum CodingKeys: String, CodingKey {
        case id = "category_id"
        case name = "category_name"
        case parentID = "parent_id"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        guard let id = c.lenientString(.id) else {
            throw DecodingError.keyNotFound(CodingKeys.id, .init(codingPath: c.codingPath, debugDescription: "category_id missing"))
        }
        self.id = id
        name = c.lenientString(.name) ?? "Untitled"
        parentID = c.lenientString(.parentID)
    }
}

public struct LiveStream: Identifiable, Hashable, Sendable, Codable {
    public let id: Int
    public let number: Int?
    public let name: String
    public let iconURL: URL?
    public let epgChannelID: String?
    public let categoryID: String?
    public let hasArchive: Bool
    public let archiveDays: Int?

    public init(id: Int, number: Int? = nil, name: String, iconURL: URL? = nil, epgChannelID: String? = nil,
                categoryID: String? = nil, hasArchive: Bool = false, archiveDays: Int? = nil) {
        self.id = id
        self.number = number
        self.name = name
        self.iconURL = iconURL
        self.epgChannelID = epgChannelID
        self.categoryID = categoryID
        self.hasArchive = hasArchive
        self.archiveDays = archiveDays
    }

    enum CodingKeys: String, CodingKey {
        case id = "stream_id"
        case number = "num"
        case name
        case iconURL = "stream_icon"
        case epgChannelID = "epg_channel_id"
        case categoryID = "category_id"
        case hasArchive = "tv_archive"
        case archiveDays = "tv_archive_duration"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        guard let id = c.lenientInt(.id) else {
            throw DecodingError.keyNotFound(CodingKeys.id, .init(codingPath: c.codingPath, debugDescription: "stream_id missing"))
        }
        self.id = id
        number = c.lenientInt(.number)
        name = c.lenientString(.name) ?? "Channel \(id)"
        iconURL = c.lenientURL(.iconURL)
        epgChannelID = c.lenientString(.epgChannelID)
        categoryID = c.lenientString(.categoryID)
        hasArchive = c.lenientBool(.hasArchive)
        archiveDays = c.lenientInt(.archiveDays)
    }

    // Encoding uses the same keys so favorites can be stored and read back.
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encodeIfPresent(number, forKey: .number)
        try c.encode(name, forKey: .name)
        try c.encodeIfPresent(iconURL?.absoluteString, forKey: .iconURL)
        try c.encodeIfPresent(epgChannelID, forKey: .epgChannelID)
        try c.encodeIfPresent(categoryID, forKey: .categoryID)
        try c.encode(hasArchive ? 1 : 0, forKey: .hasArchive)
        try c.encodeIfPresent(archiveDays, forKey: .archiveDays)
    }
}

public struct VODStream: Identifiable, Hashable, Sendable, Decodable {
    public let id: Int
    public let name: String
    public let posterURL: URL?
    public let rating: Double?
    public let categoryID: String?
    public let containerExtension: String
    public let added: Date?

    enum CodingKeys: String, CodingKey {
        case id = "stream_id"
        case name
        case posterURL = "stream_icon"
        case rating
        case categoryID = "category_id"
        case containerExtension = "container_extension"
        case added
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        guard let id = c.lenientInt(.id) else {
            throw DecodingError.keyNotFound(CodingKeys.id, .init(codingPath: c.codingPath, debugDescription: "stream_id missing"))
        }
        self.id = id
        name = c.lenientString(.name) ?? "Movie \(id)"
        posterURL = c.lenientURL(.posterURL)
        rating = c.lenientDouble(.rating)
        categoryID = c.lenientString(.categoryID)
        containerExtension = c.lenientString(.containerExtension)?.lowercased() ?? "mp4"
        added = c.lenientInt(.added).map { Date(timeIntervalSince1970: TimeInterval($0)) }
    }
}

public struct SeriesItem: Identifiable, Hashable, Sendable, Decodable {
    public let id: Int
    public let name: String
    public let coverURL: URL?
    public let plot: String?
    public let rating: Double?
    public let categoryID: String?
    public let releaseDate: String?
    public let genre: String?

    enum CodingKeys: String, CodingKey {
        case id = "series_id"
        case name, plot, rating, genre
        case coverURL = "cover"
        case categoryID = "category_id"
        case releaseDate
        case releaseDateAlt = "release_date"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        guard let id = c.lenientInt(.id) else {
            throw DecodingError.keyNotFound(CodingKeys.id, .init(codingPath: c.codingPath, debugDescription: "series_id missing"))
        }
        self.id = id
        name = c.lenientString(.name) ?? "Series \(id)"
        coverURL = c.lenientURL(.coverURL)
        plot = c.lenientString(.plot)
        rating = c.lenientDouble(.rating)
        categoryID = c.lenientString(.categoryID)
        releaseDate = c.lenientString(.releaseDate) ?? c.lenientString(.releaseDateAlt)
        genre = c.lenientString(.genre)
    }
}

public struct SeriesInfo: Sendable, Decodable {
    public let name: String?
    public let plot: String?
    public let coverURL: URL?
    /// Episodes grouped by season number, seasons sorted ascending.
    public let seasons: [Season]

    public struct Season: Identifiable, Hashable, Sendable {
        public let number: Int
        public let episodes: [Episode]
        public var id: Int { number }
    }

    enum CodingKeys: String, CodingKey {
        case info, episodes
    }

    enum InfoKeys: String, CodingKey {
        case name, plot, cover
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        if let info = try? c.nestedContainer(keyedBy: InfoKeys.self, forKey: .info) {
            name = info.lenientString(.name)
            plot = info.lenientString(.plot)
            coverURL = info.lenientURL(.cover)
        } else {
            name = nil
            plot = nil
            coverURL = nil
        }

        // Most panels send {"1": [...], "2": [...]}; some send [[...], [...]].
        var grouped: [Int: [Episode]] = [:]
        if let dict = try? c.decode([String: LossyArray<Episode>].self, forKey: .episodes) {
            for (key, value) in dict {
                let season = Int(key) ?? value.elements.first?.season ?? 0
                grouped[season, default: []].append(contentsOf: value.elements)
            }
        } else if let nested = try? c.decode([LossyArray<Episode>].self, forKey: .episodes) {
            for (index, value) in nested.enumerated() {
                let season = value.elements.first?.season ?? index + 1
                grouped[season, default: []].append(contentsOf: value.elements)
            }
        }
        seasons = grouped.keys.sorted().map { number in
            Season(number: number, episodes: grouped[number, default: []].sorted { ($0.episodeNumber ?? 0) < ($1.episodeNumber ?? 0) })
        }
    }
}

public struct Episode: Identifiable, Hashable, Sendable, Decodable {
    /// Episode stream id. Xtream uses this (not series_id) in the play URL.
    public let id: String
    public let episodeNumber: Int?
    public let season: Int?
    public let title: String
    public let containerExtension: String
    public let plot: String?
    public let imageURL: URL?
    public let durationSeconds: Int?

    enum CodingKeys: String, CodingKey {
        case id
        case episodeNumber = "episode_num"
        case season, title, info
        case containerExtension = "container_extension"
    }

    enum InfoKeys: String, CodingKey {
        case plot
        case movieImage = "movie_image"
        case durationSecs = "duration_secs"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        guard let id = c.lenientString(.id) else {
            throw DecodingError.keyNotFound(CodingKeys.id, .init(codingPath: c.codingPath, debugDescription: "episode id missing"))
        }
        self.id = id
        episodeNumber = c.lenientInt(.episodeNumber)
        season = c.lenientInt(.season)
        title = c.lenientString(.title) ?? "Episode \(episodeNumber.map(String.init) ?? id)"
        containerExtension = c.lenientString(.containerExtension)?.lowercased() ?? "mp4"
        if let info = try? c.nestedContainer(keyedBy: InfoKeys.self, forKey: .info) {
            plot = info.lenientString(.plot)
            imageURL = info.lenientURL(.movieImage)
            durationSeconds = info.lenientInt(.durationSecs)
        } else {
            plot = nil
            imageURL = nil
            durationSeconds = nil
        }
    }
}

// MARK: - EPG

/// One programme from `get_short_epg`. Titles and descriptions arrive base64
/// encoded; this type decodes them.
public struct EPGEntry: Identifiable, Hashable, Sendable, Decodable {
    public let id: String
    public let title: String
    public let summary: String?
    public let start: Date
    public let end: Date

    public func isOnAir(at date: Date = Date()) -> Bool {
        start <= date && date < end
    }

    public func progress(at date: Date = Date()) -> Double {
        let total = end.timeIntervalSince(start)
        guard total > 0 else { return 0 }
        return min(1, max(0, date.timeIntervalSince(start) / total))
    }

    enum CodingKeys: String, CodingKey {
        case id, title, description, start, end
        case startTimestamp = "start_timestamp"
        case stopTimestamp = "stop_timestamp"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let start = c.lenientInt(.startTimestamp).map { Date(timeIntervalSince1970: TimeInterval($0)) }
            ?? c.lenientString(.start).flatMap(EPGEntry.parsePanelDate)
        let end = c.lenientInt(.stopTimestamp).map { Date(timeIntervalSince1970: TimeInterval($0)) }
            ?? c.lenientString(.end).flatMap(EPGEntry.parsePanelDate)
        guard let start, let end else {
            throw DecodingError.dataCorrupted(.init(codingPath: c.codingPath, debugDescription: "EPG entry has no usable times"))
        }
        self.start = start
        self.end = end
        let rawTitle = c.lenientString(.title) ?? ""
        title = EPGEntry.decodeBase64(rawTitle) ?? rawTitle
        summary = c.lenientString(.description).map { EPGEntry.decodeBase64($0) ?? $0 }
        id = c.lenientString(.id) ?? "\(Int(start.timeIntervalSince1970))-\(title)"
    }

    static func decodeBase64(_ string: String) -> String? {
        guard !string.isEmpty, let data = Data(base64Encoded: string, options: .ignoreUnknownCharacters) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// Fallback for panels without timestamps. Assumes UTC, which is wrong for
    /// some panels; the timestamp fields are preferred when present.
    static func parsePanelDate(_ string: String) -> Date? {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return formatter.date(from: string)
    }
}

struct ShortEPGResponse: Decodable {
    let listings: [EPGEntry]

    enum CodingKeys: String, CodingKey {
        case listings = "epg_listings"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        listings = (try? c.decode(LossyArray<EPGEntry>.self, forKey: .listings).elements) ?? []
    }
}
