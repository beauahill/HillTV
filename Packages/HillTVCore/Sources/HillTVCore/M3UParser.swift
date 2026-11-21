import Foundation

/// One channel from an extended M3U playlist.
public struct M3UEntry: Identifiable, Hashable, Sendable {
    public let name: String
    public let url: URL
    public let tvgID: String?
    public let tvgName: String?
    public let logoURL: URL?
    public let group: String?
    /// Every key="value" attribute on the #EXTINF line, lowercased keys.
    public let attributes: [String: String]

    public var id: String { url.absoluteString }
}

/// Parses extended M3U (`#EXTM3U` / `#EXTINF`) playlists. Not used by the UI
/// yet; it's here so M3U-only providers can be added next to Xtream.
public enum M3UParser {
    public static func parse(_ text: String) -> [M3UEntry] {
        var entries: [M3UEntry] = []
        var pendingInfo: (name: String, attributes: [String: String])?
        var pendingGroup: String?

        text.enumerateLines { rawLine, _ in
            let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !line.isEmpty else { return }

            if line.hasPrefix("#EXTINF") {
                pendingInfo = parseInfo(line)
            } else if line.hasPrefix("#EXTGRP:") {
                pendingGroup = String(line.dropFirst("#EXTGRP:".count)).trimmingCharacters(in: .whitespaces)
            } else if line.hasPrefix("#") {
                return
            } else if let url = URL(string: line), url.scheme != nil {
                let info = pendingInfo ?? (name: url.lastPathComponent, attributes: [:])
                let attrs = info.attributes
                entries.append(M3UEntry(
                    name: info.name.isEmpty ? (attrs["tvg-name"] ?? url.lastPathComponent) : info.name,
                    url: url,
                    tvgID: attrs["tvg-id"].flatMap { $0.isEmpty ? nil : $0 },
                    tvgName: attrs["tvg-name"].flatMap { $0.isEmpty ? nil : $0 },
                    logoURL: attrs["tvg-logo"].flatMap { URL(string: $0) },
                    group: attrs["group-title"].flatMap { $0.isEmpty ? nil : $0 } ?? pendingGroup,
                    attributes: attrs
                ))
                pendingInfo = nil
                pendingGroup = nil
            }
        }
        return entries
    }

    /// `#EXTINF:-1 tvg-id="x" group-title="News, US",Display Name`
    /// The display name is everything after the first comma that sits outside quotes.
    static func parseInfo(_ line: String) -> (name: String, attributes: [String: String]) {
        let body = line.drop(while: { $0 != ":" }).dropFirst()
        var inQuotes = false
        var splitIndex: Substring.Index?
        for index in body.indices {
            let ch = body[index]
            if ch == "\"" { inQuotes.toggle() }
            if ch == ",", !inQuotes {
                splitIndex = index
                break
            }
        }
        let header = splitIndex.map { body[body.startIndex..<$0] } ?? body
        let name = splitIndex.map { String(body[body.index(after: $0)...]) } ?? ""

        var attributes: [String: String] = [:]
        let pattern = #"([A-Za-z0-9_\-]+)="([^"]*)""#
        if let regex = try? NSRegularExpression(pattern: pattern) {
            let headerString = String(header)
            let range = NSRange(headerString.startIndex..., in: headerString)
            for match in regex.matches(in: headerString, range: range) {
                guard let keyRange = Range(match.range(at: 1), in: headerString),
                      let valueRange = Range(match.range(at: 2), in: headerString) else { continue }
                attributes[headerString[keyRange].lowercased()] = String(headerString[valueRange])
            }
        }
        return (name.trimmingCharacters(in: .whitespaces), attributes)
    }
}
