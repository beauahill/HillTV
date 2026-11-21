import Foundation

// Xtream panels are inconsistent about JSON types. The same field can come back
// as "123", 123, 123.0, "", null, true or "1" depending on the panel software
// and version. These helpers accept all of them so one odd field never kills a
// whole channel list.

extension KeyedDecodingContainer {
    func lenientString(_ key: Key) -> String? {
        if let value = try? decodeIfPresent(String.self, forKey: key) {
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        }
        if let value = try? decodeIfPresent(Int.self, forKey: key) { return String(value) }
        if let value = try? decodeIfPresent(Double.self, forKey: key) {
            return value.rounded() == value ? String(Int(value)) : String(value)
        }
        if let value = try? decodeIfPresent(Bool.self, forKey: key) { return value ? "1" : "0" }
        return nil
    }

    func lenientInt(_ key: Key) -> Int? {
        if let value = try? decodeIfPresent(Int.self, forKey: key) { return value }
        if let value = try? decodeIfPresent(String.self, forKey: key) {
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            if let int = Int(trimmed) { return int }
            if let double = Double(trimmed), double.isFinite { return Int(double) }
            return nil
        }
        if let value = try? decodeIfPresent(Double.self, forKey: key), value.isFinite { return Int(value) }
        if let value = try? decodeIfPresent(Bool.self, forKey: key) { return value ? 1 : 0 }
        return nil
    }

    func lenientDouble(_ key: Key) -> Double? {
        if let value = try? decodeIfPresent(Double.self, forKey: key) { return value }
        if let value = try? decodeIfPresent(String.self, forKey: key) {
            return Double(value.trimmingCharacters(in: .whitespacesAndNewlines))
        }
        if let value = try? decodeIfPresent(Int.self, forKey: key) { return Double(value) }
        return nil
    }

    func lenientBool(_ key: Key) -> Bool {
        if let value = try? decodeIfPresent(Bool.self, forKey: key) { return value }
        if let value = lenientInt(key) { return value != 0 }
        if let value = try? decodeIfPresent(String.self, forKey: key) {
            return ["true", "yes", "y"].contains(value.lowercased())
        }
        return false
    }

    func lenientURL(_ key: Key) -> URL? {
        guard let string = lenientString(key) else { return nil }
        return URL(string: string) ?? string.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed).flatMap(URL.init(string:))
    }

    func lenientStringArray(_ key: Key) -> [String] {
        if let value = try? decodeIfPresent([String].self, forKey: key) { return value }
        if let value = lenientString(key) { return [value] }
        return []
    }
}

/// Decodes an array but drops elements that fail instead of failing the whole
/// array. A non-array payload (`{}`, `null`, `false`), which some panels send
/// for "nothing here", decodes as empty.
public struct LossyArray<Element: Decodable>: Decodable {
    public var elements: [Element]

    public init(_ elements: [Element]) {
        self.elements = elements
    }

    public init(from decoder: Decoder) throws {
        guard var container = try? decoder.unkeyedContainer() else {
            elements = []
            return
        }
        var result: [Element] = []
        if let count = container.count { result.reserveCapacity(count) }
        while !container.isAtEnd {
            if let element = try? container.decode(Element.self) {
                result.append(element)
            } else {
                // Step past the bad element so the loop can continue.
                _ = try? container.decode(DiscardedValue.self)
            }
        }
        elements = result
    }
}

/// Decodes and throws away any JSON value.
struct DiscardedValue: Decodable {
    init(from decoder: Decoder) throws {}
}
