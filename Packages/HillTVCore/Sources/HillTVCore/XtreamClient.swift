import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public enum XtreamError: Error, LocalizedError, Equatable {
    case invalidServerURL
    case unauthorized
    case accountInactive(String)
    case httpStatus(Int)
    case decoding(String)
    case network(String)

    public var errorDescription: String? {
        switch self {
        case .invalidServerURL: return "That server address doesn't look right."
        case .unauthorized: return "The server rejected the username or password."
        case .accountInactive(let status): return "The account status is \(status)."
        case .httpStatus(let code): return "The server returned HTTP \(code)."
        case .decoding(let detail): return "Couldn't read the server's response. \(detail)"
        case .network(let detail): return detail
        }
    }
}

/// Container format requested for live channels. AVPlayer only plays HLS, so
/// the AVPlayer engine asks for m3u8. FFmpeg-based engines handle raw MPEG-TS,
/// which starts faster and is what most panels serve natively.
public enum LiveFormat: String, CaseIterable, Sendable {
    case hls = "m3u8"
    case mpegts = "ts"
}

public final class XtreamClient: @unchecked Sendable {
    public let account: XtreamAccount
    public let userAgent: String
    private let session: URLSession
    private let decoder = JSONDecoder()

    public init(account: XtreamAccount, userAgent: String = "HillTV/0.1", session: URLSession = .shared) {
        self.account = account
        self.userAgent = userAgent
        self.session = session
    }

    // MARK: API

    /// Calls player_api.php with no action. Returns account + server info.
    public func authenticate() async throws -> XtreamAuthInfo {
        let info: XtreamAuthInfo
        do {
            info = try await get(XtreamAuthInfo.self, action: nil)
        } catch XtreamError.decoding {
            // A bad login on many panels returns an empty body or "[]".
            throw XtreamError.unauthorized
        }
        guard info.user.auth else { throw XtreamError.unauthorized }
        if let status = info.user.status, status.lowercased() != "active" {
            throw XtreamError.accountInactive(status)
        }
        return info
    }

    public func liveCategories() async throws -> [XtreamCategory] {
        try await get(LossyArray<XtreamCategory>.self, action: "get_live_categories").elements
    }

    public func liveStreams(categoryID: String? = nil) async throws -> [LiveStream] {
        try await get(LossyArray<LiveStream>.self, action: "get_live_streams", query: categoryQuery(categoryID)).elements
    }

    public func vodCategories() async throws -> [XtreamCategory] {
        try await get(LossyArray<XtreamCategory>.self, action: "get_vod_categories").elements
    }

    public func vodStreams(categoryID: String? = nil) async throws -> [VODStream] {
        try await get(LossyArray<VODStream>.self, action: "get_vod_streams", query: categoryQuery(categoryID)).elements
    }

    public func vodInfo(streamID: Int) async throws -> VODInfo {
        try await get(VODInfo.self, action: "get_vod_info", query: [URLQueryItem(name: "vod_id", value: String(streamID))])
    }

    public func seriesCategories() async throws -> [XtreamCategory] {
        try await get(LossyArray<XtreamCategory>.self, action: "get_series_categories").elements
    }

    public func series(categoryID: String? = nil) async throws -> [SeriesItem] {
        try await get(LossyArray<SeriesItem>.self, action: "get_series", query: categoryQuery(categoryID)).elements
    }

    public func seriesInfo(seriesID: Int) async throws -> SeriesInfo {
        try await get(SeriesInfo.self, action: "get_series_info", query: [URLQueryItem(name: "series_id", value: String(seriesID))])
    }

    /// Now/next style listings for one channel. Cheap; fine to call on focus.
    public func shortEPG(streamID: Int, limit: Int = 4) async throws -> [EPGEntry] {
        let query = [
            URLQueryItem(name: "stream_id", value: String(streamID)),
            URLQueryItem(name: "limit", value: String(limit)),
        ]
        return try await get(ShortEPGResponse.self, action: "get_short_epg", query: query).listings
    }

    // MARK: Stream URLs

    public func liveURL(streamID: Int, format: LiveFormat) -> URL {
        streamURL(kind: "live", id: String(streamID), ext: format.rawValue)
    }

    public func movieURL(_ movie: VODStream) -> URL {
        movieURL(streamID: movie.id, containerExtension: movie.containerExtension)
    }

    /// For resuming from watch history, which stores IDs rather than URLs
    /// (stream URLs embed the password).
    public func movieURL(streamID: Int, containerExtension: String) -> URL {
        streamURL(kind: "movie", id: String(streamID), ext: containerExtension)
    }

    public func episodeURL(_ episode: Episode) -> URL {
        episodeURL(episodeID: episode.id, containerExtension: episode.containerExtension)
    }

    public func episodeURL(episodeID: String, containerExtension: String) -> URL {
        streamURL(kind: "series", id: Self.encodePathSegment(episodeID), ext: containerExtension)
    }

    /// Full XMLTV guide. Large (often 10-100 MB); download and parse off the main thread.
    public var xmltvURL: URL {
        var components = URLComponents(url: account.serverURL.appendingPathComponent("xmltv.php"), resolvingAgainstBaseURL: false)!
        components.queryItems = credentialQuery
        return Self.fixPlusEncoding(components)
    }

    // MARK: Internals

    func apiURL(action: String?, query: [URLQueryItem] = []) -> URL {
        var components = URLComponents(url: account.serverURL.appendingPathComponent("player_api.php"), resolvingAgainstBaseURL: false)!
        var items = credentialQuery
        if let action { items.append(URLQueryItem(name: "action", value: action)) }
        items.append(contentsOf: query)
        components.queryItems = items
        return Self.fixPlusEncoding(components)
    }

    func streamURL(kind: String, id: String, ext: String) -> URL {
        let user = Self.encodePathSegment(account.username)
        let pass = Self.encodePathSegment(account.password)
        var base = account.serverURL.absoluteString
        while base.hasSuffix("/") { base.removeLast() }
        // Built as a string so the credentials keep exactly the encoding above.
        return URL(string: "\(base)/\(kind)/\(user)/\(pass)/\(id).\(ext)")!
    }

    private var credentialQuery: [URLQueryItem] {
        [URLQueryItem(name: "username", value: account.username), URLQueryItem(name: "password", value: account.password)]
    }

    private func categoryQuery(_ categoryID: String?) -> [URLQueryItem] {
        guard let categoryID else { return [] }
        return [URLQueryItem(name: "category_id", value: categoryID)]
    }

    private func get<T: Decodable>(_ type: T.Type, action: String?, query: [URLQueryItem] = []) async throws -> T {
        var request = URLRequest(url: apiURL(action: action, query: query), timeoutInterval: 45)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw XtreamError.network(error.localizedDescription)
        }

        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            if http.statusCode == 401 || http.statusCode == 403 { throw XtreamError.unauthorized }
            throw XtreamError.httpStatus(http.statusCode)
        }

        do {
            return try decoder.decode(T.self, from: data)
        } catch {
            throw XtreamError.decoding(String(describing: error).prefix(300).description)
        }
    }

    /// URLComponents leaves "+" alone in queries, and PHP decodes "+" as a
    /// space. A password containing "+" would silently break without this.
    static func fixPlusEncoding(_ components: URLComponents) -> URL {
        var components = components
        components.percentEncodedQuery = components.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        return components.url!
    }

    static func encodePathSegment(_ value: String) -> String {
        var allowed = CharacterSet.urlPathAllowed
        allowed.remove(charactersIn: "/?#;")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }
}
