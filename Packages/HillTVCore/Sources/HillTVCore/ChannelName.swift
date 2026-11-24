import Foundation

/// A provider's channel name split into the part worth reading and the
/// labels around it. "US | CBS 13 CHEYENNE HD" becomes the title
/// "CBS 13 CHEYENNE", the region "US" and the quality "HD".
///
/// This is for display only. Matching, search and favorites keep using the
/// name exactly as the provider sent it.
///
/// The rules are deliberately cautious, because a wrong cut hides part of a
/// real name:
/// - A leading region is removed only when it is a known country or language
///   marker and a separator follows it, so "ABC: World News" keeps "ABC".
/// - Quality labels are removed only from the end of the name.
/// - Labels that are also ordinary words ("RAW", "SD") count only when they
///   are bracketed or written in superscript, so "WWE RAW" stays whole.
/// - Letter case is left as sent.
/// - If nothing would be left, the whole name is the title.
public struct ChannelName: Equatable, Sendable {
    /// The name without its region and quality labels.
    public let title: String
    /// The country or language marker the name started with, as sent.
    public let region: String?
    /// Quality labels from the end of the name, in order, in plain capitals.
    public let quality: [String]

    /// Region and quality on one line, such as "US · HD". Nil when the name
    /// had neither.
    public var detail: String? {
        let parts = (region.map { [$0] } ?? []) + quality
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    public init(_ raw: String) {
        let whole = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        var text = Self.droppingDecoration(whole)

        var region: String?
        if let split = Self.splitRegion(text) {
            region = split.region
            text = split.rest
        }

        var words = text.split(whereSeparator: \.isWhitespace).map(String.init)
        var quality: [String] = []
        while let last = words.last {
            if let tag = Self.qualityTag(last) {
                quality.insert(tag, at: 0)
                words.removeLast()
            } else if last.unicodeScalars.allSatisfy({ Self.separators.contains($0) }) {
                // A separator left hanging, as in "ESPN - HD".
                words.removeLast()
            } else {
                break
            }
        }

        let title = words.joined(separator: " ")
        if title.isEmpty {
            self.title = whole
            self.region = nil
            self.quality = []
        } else {
            self.title = title
            self.region = region
            self.quality = quality
        }
    }

    // MARK: Decoration

    /// Rows like "##### NEWS #####" are section headings some providers put
    /// in the channel list. A run of two or more such characters at either
    /// end is dropped; a single one ("#1 News") is left alone.
    private static func droppingDecoration(_ text: String) -> String {
        var scalars = Array(text.unicodeScalars)
        func run(_ slice: some Sequence<Unicode.Scalar>) -> Int {
            var count = 0
            var length = 0
            for scalar in slice {
                if decoration.contains(scalar) {
                    count += 1
                } else if !CharacterSet.whitespaces.contains(scalar) {
                    break
                }
                length += 1
            }
            return count >= 2 ? length : 0
        }
        scalars.removeFirst(run(scalars))
        scalars.removeLast(run(scalars.reversed()))
        var result = String.UnicodeScalarView()
        result.append(contentsOf: scalars)
        return String(result).trimmingCharacters(in: .whitespaces)
    }

    private static let decoration = CharacterSet(charactersIn: "#=*_~-●■▬►◄▶◀✦★☆•")

    // MARK: Region

    private static let separators = CharacterSet(charactersIn: "|:-–—▎┃│•·✦★»>")
    private static let dashes = CharacterSet(charactersIn: "-–—")
    private static let brackets: [Character: Character] = ["[": "]", "(": ")", "|": "|", "【": "】", "{": "}"]

    /// How far into a name a region marker may end.
    private static let regionReach = 16

    private static func splitRegion(_ text: String) -> (region: String, rest: String)? {
        guard let first = text.first else { return nil }

        // "[US] CBS", "(UK) BBC One", "|FR| TF1"
        if let close = brackets[first] {
            let inner = text.dropFirst()
            if let end = inner.prefix(regionReach).firstIndex(of: close) {
                let marker = inner[..<end].trimmingCharacters(in: .whitespaces)
                if isRegion(marker) {
                    let rest = droppingLeadingSeparators(String(inner[inner.index(after: end)...]))
                    return rest.isEmpty ? nil : (marker, rest)
                }
            }
            return nil
        }

        // "US | CBS", "UK: BBC One", "FR ▎TF1", "DE - ZDF"
        let scalars = Array(text.unicodeScalars)
        guard let index = scalars.prefix(regionReach).firstIndex(where: { separators.contains($0) }) else { return nil }
        if dashes.contains(scalars[index]) {
            // "AL-JAZEERA" is a name, "AL - JAZEERA" would be a marker.
            let before = index > 0 && CharacterSet.whitespaces.contains(scalars[index - 1])
            let after = index + 1 < scalars.count && CharacterSet.whitespaces.contains(scalars[index + 1])
            guard before || after else { return nil }
        }
        let marker = string(scalars[..<index]).trimmingCharacters(in: .whitespaces)
        guard isRegion(marker) else { return nil }
        let rest = droppingLeadingSeparators(string(scalars[(index + 1)...]))
        return rest.isEmpty ? nil : (marker, rest)
    }

    private static func droppingLeadingSeparators(_ text: String) -> String {
        let scalars = text.unicodeScalars.drop { separators.contains($0) || CharacterSet.whitespaces.contains($0) }
        return string(scalars)
    }

    private static func string(_ scalars: some Sequence<Unicode.Scalar>) -> String {
        var view = String.UnicodeScalarView()
        view.append(contentsOf: scalars)
        return String(view)
    }

    /// One or two words, each a known country code, country or language.
    private static func isRegion(_ marker: String) -> Bool {
        let words = SearchIndex<String>.fold(marker).split(separator: " ").map(String.init)
        guard (1...2).contains(words.count) else { return false }
        return words.allSatisfy { regionCodes.contains($0) || CategoryGroup.regionWords.contains($0) }
    }

    /// Country and language codes seen in front of channel names. Not every
    /// ISO code: short ones that are also networks or words ("TV", "BT") are
    /// left out on purpose.
    private static let regionCodes: Set<String> = [
        "us", "usa", "uk", "gb", "ca", "can", "au", "aus", "nz", "ie", "en", "eng",
        "fr", "fra", "de", "deu", "ger", "es", "esp", "it", "ita", "pt", "por", "nl", "ned", "be", "ch", "at",
        "se", "swe", "no", "nor", "dk", "den", "fi", "fin", "is", "pl", "pol", "cz", "sk", "hu", "ro", "bg", "gr",
        "tr", "tur", "ru", "rus", "ua", "al", "alb", "rs", "hr", "ba", "si", "mk", "xk", "exyu",
        "il", "ir", "iq", "sa", "ae", "uae", "eg", "ma", "dz", "tn", "lb", "sy", "jo", "kw", "qa", "ar", "ara", "ku",
        "in", "ind", "pk", "pak", "bd", "lk", "np", "af", "afg",
        "cn", "jp", "kr", "th", "vn", "ph", "id", "my", "sg", "hk", "tw",
        "br", "bra", "mx", "mex", "co", "cl", "pe", "ve", "ec", "uy", "py", "bo", "cu", "do", "pr", "ht", "jm",
        "la", "lat", "latam", "za", "ng", "gh", "ke", "et", "so", "eu",
    ]

    // MARK: Quality

    /// Labels that only ever mean picture quality or encoding.
    private static let qualityTags: Set<String> = [
        "HD", "FHD", "UHD", "QHD", "4K", "8K", "HEVC", "H265", "H264", "HDR",
        "2160P", "1080P", "1080I", "720P", "480P", "50FPS", "60FPS",
    ]

    /// Labels that are also words or parts of names: "WWE RAW", "Bally
    /// Sports SD" (San Diego). They count only when bracketed or superscript.
    private static let markedQualityTags: Set<String> = ["SD", "RAW", "LQ", "HQ", "BACKUP"]

    private static let tagBrackets = CharacterSet(charactersIn: "()[]{}【】*")

    /// The quality label a word stands for, if it is one.
    private static func qualityTag(_ word: String) -> String? {
        // Compatibility mapping turns superscript "ᴴᴰ" into "HD".
        let plain = word.precomposedStringWithCompatibilityMapping
        let superscript = plain != word && word.unicodeScalars.allSatisfy { !$0.isASCII }
        let trimmed = plain.trimmingCharacters(in: tagBrackets)
        let bracketed = trimmed.count < plain.count
        let tag = trimmed.uppercased().replacingOccurrences(of: ".", with: "")
        if qualityTags.contains(tag) { return tag }
        if markedQualityTags.contains(tag), superscript || bracketed { return tag }
        return nil
    }
}

extension LiveStream {
    /// The channel's name split for display. `name` stays as the provider sent it.
    public var displayName: ChannelName { ChannelName(name) }
}
