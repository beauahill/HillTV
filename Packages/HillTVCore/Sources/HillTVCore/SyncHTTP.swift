import Foundation

/// The small web server's request handling, for the phone page the TV
/// serves while its Sync screen is open. Networking lives in the app; the
/// parsing and the rules about who may do what live here, where they can be
/// tested.

/// One HTTP request, read from a connection.
public struct SyncRequest: Equatable, Sendable {
    public var method: String
    /// The path without the query, such as "/api/state".
    public var path: String
    public var query: [String: String]
    /// Header names in lower case.
    public var headers: [String: String]
    public var body: Data
}

/// Collects a request as it arrives in pieces.
public struct SyncRequestReader: Sendable {
    public enum Result: Equatable, Sendable {
        case needMore
        case done(SyncRequest)
        /// The request can't be read; answer with this status and close.
        case failed(status: Int)
    }

    /// Requests larger than this are refused.
    public let maximumBytes: Int
    private var buffer = Data()

    public init(maximumBytes: Int = SyncSettings.maximumBytes + 16_384) {
        self.maximumBytes = maximumBytes
    }

    public mutating func add(_ data: Data) -> Result {
        buffer.append(data)
        guard let headerEnd = buffer.range(of: Data("\r\n\r\n".utf8)) else {
            return buffer.count > 16_384 ? .failed(status: 431) : .needMore
        }
        guard let head = String(data: buffer[buffer.startIndex..<headerEnd.lowerBound], encoding: .utf8) else {
            return .failed(status: 400)
        }
        var lines = head.components(separatedBy: "\r\n")
        let requestLine = lines.removeFirst().split(separator: " ", omittingEmptySubsequences: true)
        guard requestLine.count == 3, requestLine[2].hasPrefix("HTTP/1.") else { return .failed(status: 400) }

        var headers: [String: String] = [:]
        for line in lines {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let name = line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
            headers[name] = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
        }

        let length: Int
        if let declared = headers["content-length"] {
            guard let value = Int(declared), value >= 0 else { return .failed(status: 400) }
            length = value
        } else {
            length = 0
        }
        guard length <= maximumBytes else { return .failed(status: 413) }
        let bodyStart = headerEnd.upperBound
        guard buffer.count - (bodyStart - buffer.startIndex) >= length else { return .needMore }
        let body = buffer[bodyStart..<(bodyStart + length)]

        let target = String(requestLine[1])
        let parts = target.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false)
        let path = String(parts[0]).removingPercentEncoding ?? String(parts[0])
        let query = parts.count > 1 ? Self.parseQuery(String(parts[1])) : [:]
        return .done(SyncRequest(
            method: String(requestLine[0]).uppercased(),
            path: path,
            query: query,
            headers: headers,
            body: Data(body)
        ))
    }

    static func parseQuery(_ text: String) -> [String: String] {
        var result: [String: String] = [:]
        for pair in text.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            let key = String(kv[0]).replacingOccurrences(of: "+", with: " ").removingPercentEncoding ?? String(kv[0])
            let value = kv.count > 1
                ? (String(kv[1]).replacingOccurrences(of: "+", with: " ").removingPercentEncoding ?? String(kv[1]))
                : ""
            result[key] = value
        }
        return result
    }
}

/// One HTTP response.
public struct SyncResponse: Equatable, Sendable {
    public var status: Int
    public var contentType: String
    public var body: Data
    public var extraHeaders: [(String, String)] = []

    public init(status: Int, contentType: String, body: Data, extraHeaders: [(String, String)] = []) {
        self.status = status
        self.contentType = contentType
        self.body = body
        self.extraHeaders = extraHeaders
    }

    public static func == (lhs: SyncResponse, rhs: SyncResponse) -> Bool {
        lhs.status == rhs.status && lhs.contentType == rhs.contentType && lhs.body == rhs.body
            && lhs.extraHeaders.map { "\($0.0):\($0.1)" } == rhs.extraHeaders.map { "\($0.0):\($0.1)" }
    }

    /// `{"ok": …, "message": …}`, which the page shows as plain text.
    public static func message(_ text: String, ok: Bool, status: Int? = nil) -> SyncResponse {
        let json = (try? JSONSerialization.data(withJSONObject: ["ok": ok, "message": text], options: [.sortedKeys])) ?? Data()
        return SyncResponse(status: status ?? (ok ? 200 : 400), contentType: "application/json; charset=utf-8", body: json)
    }

    public static func json(_ object: [String: Any], status: Int = 200) -> SyncResponse {
        let data = (try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])) ?? Data()
        return SyncResponse(status: status, contentType: "application/json; charset=utf-8", body: data)
    }

    static func reason(_ status: Int) -> String {
        switch status {
        case 200: return "OK"
        case 400: return "Bad Request"
        case 403: return "Forbidden"
        case 404: return "Not Found"
        case 405: return "Method Not Allowed"
        case 409: return "Conflict"
        case 413: return "Payload Too Large"
        case 423: return "Locked"
        case 431: return "Request Header Fields Too Large"
        default: return status < 500 ? "Error" : "Server Error"
        }
    }

    /// The bytes to send. Every response closes the connection and is never cached.
    public func serialized() -> Data {
        var head = "HTTP/1.1 \(status) \(Self.reason(status))\r\n"
        head += "Content-Type: \(contentType)\r\n"
        head += "Content-Length: \(body.count)\r\n"
        head += "Cache-Control: no-store\r\n"
        head += "Connection: close\r\n"
        for (name, value) in extraHeaders {
            head += "\(name): \(value)\r\n"
        }
        head += "\r\n"
        return Data(head.utf8) + body
    }
}

/// What a request asks for, once the code has been checked.
public enum SyncRoute: Equatable, Sendable {
    /// The phone page itself. It holds nothing secret, so it needs no code.
    case page
    case state
    case signIn(server: String, username: String, password: String)
    case downloadSettings
    case uploadSettings(Data, SyncSettings.Choices)
    /// Ready to send as is: a refusal, a bad request or "not found".
    case reply(SyncResponse)
}

/// Decides what each request may do. Every /api request needs the code the
/// TV shows. After a few wrong codes the TV stops answering until its Sync
/// screen is opened again, so the code can't be guessed.
public struct SyncGate: Sendable {
    public let code: String
    public static let allowedMisses = 5
    public private(set) var misses = 0

    public var isLocked: Bool { misses >= Self.allowedMisses }

    public init(code: String) {
        self.code = code
    }

    /// A six-digit code for the TV to show.
    public static func makeCode() -> String {
        String(format: "%06d", Int.random(in: 0...999_999))
    }

    public mutating func route(_ request: SyncRequest) -> SyncRoute {
        if request.method == "GET", request.path == "/" || request.path == "/index.html" {
            return .page
        }
        guard request.path.hasPrefix("/api/") else {
            return .reply(.message("Not found.", ok: false, status: 404))
        }
        if isLocked {
            return .reply(.message("Too many wrong codes. Close Sync on the TV and open it again.", ok: false, status: 423))
        }
        let given = request.headers["x-hilltv-code"] ?? request.query["code"] ?? ""
        guard given == code else {
            misses += 1
            let message = isLocked
                ? "Too many wrong codes. Close Sync on the TV and open it again."
                : "That code doesn't match the one on the TV."
            return .reply(.message(message, ok: false, status: isLocked ? 423 : 403))
        }

        switch (request.method, request.path) {
        case ("GET", "/api/state"):
            return .state
        case ("POST", "/api/sign-in"):
            guard let object = try? JSONSerialization.jsonObject(with: request.body) as? [String: Any],
                  let server = (object["server"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
                  let username = (object["username"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
                  let password = object["password"] as? String,
                  !server.isEmpty, !username.isEmpty, !password.isEmpty else {
                return .reply(.message("Fill in the server, username and password.", ok: false))
            }
            return .signIn(server: server, username: username, password: password)
        case ("GET", "/api/settings"):
            return .downloadSettings
        case ("POST", "/api/settings"):
            let choices = SyncSettings.Choices(
                hiddenCategories: request.query["hidden"] == "1",
                favorites: request.query["favorites"] == "1",
                channelOrder: request.query["order"] == "1"
            )
            guard !choices.isEmpty else {
                return .reply(.message("Tick at least one thing to load.", ok: false))
            }
            return .uploadSettings(request.body, choices)
        case (_, "/api/state"), (_, "/api/sign-in"), (_, "/api/settings"):
            return .reply(.message("Not allowed.", ok: false, status: 405))
        default:
            return .reply(.message("Not found.", ok: false, status: 404))
        }
    }
}
