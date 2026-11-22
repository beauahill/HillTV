import Foundation

/// The user's playback choices. Stored as small JSON.
///
/// The defaults reproduce the player's behavior from before these settings
/// existed, so someone who never opens Settings sees no change.
public struct PlaybackSettings: Codable, Equatable, Sendable {
    /// How much of a live channel to load ahead. Movies and episodes are
    /// left to the player, which sizes their buffer by itself.
    public enum LiveBuffer: String, Codable, CaseIterable, Identifiable, Sendable {
        case oneSecond
        case twoSeconds
        case standard
        case tenSeconds
        case thirtySeconds

        public var id: String { rawValue }

        public var seconds: Double {
            switch self {
            case .oneSecond: return 1
            case .twoSeconds: return 2
            case .standard: return 4
            case .tenSeconds: return 10
            case .thirtySeconds: return 30
            }
        }
    }

    /// How far behind the broadcast a live channel plays.
    public enum LiveDelay: String, Codable, CaseIterable, Identifiable, Sendable {
        /// The player picks the distance and falls further behind each time
        /// the stream stalls to load.
        case standard
        /// After a stall the player skips ahead to the distance it had, so
        /// the delay doesn't grow over an evening.
        case keepUp
        /// As `keepUp`, and starts nearer the live edge too.
        case closest

        public var id: String { rawValue }
    }

    public var liveBuffer: LiveBuffer = .standard
    public var liveDelay: LiveDelay = .standard

    public init() {}

    enum CodingKeys: String, CodingKey {
        case liveBuffer, liveDelay
    }

    /// A missing key or a value this version doesn't know falls back to the
    /// default for that setting alone, so adding or renaming an option later
    /// never resets the others.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        liveBuffer = (try? c.decode(String.self, forKey: .liveBuffer)).flatMap(LiveBuffer.init(rawValue:)) ?? .standard
        liveDelay = (try? c.decode(String.self, forKey: .liveDelay)).flatMap(LiveDelay.init(rawValue:)) ?? .standard
    }

    /// Whether the player should return to its distance from live after a stall.
    public var preservesLiveOffset: Bool {
        liveDelay != .standard
    }

    /// Seconds behind the live edge to aim for, or nil to leave the player's
    /// own choice alone. Only `closest` moves it: to half of what the stream
    /// recommends, but not under four seconds and never further back than
    /// the recommendation itself. `recommended` is what the player reports
    /// once the stream has loaded; nil, zero or a non-number means unknown.
    public func liveOffsetTarget(recommended: Double?) -> Double? {
        guard liveDelay == .closest, let recommended, recommended.isFinite, recommended > 0 else { return nil }
        return min(recommended, max(recommended / 2, 4))
    }

    /// The live buffer to ask for. A live stream can only hold as much ahead
    /// as it is playing behind the live edge, so a chosen distance caps it.
    public func liveBufferSeconds(offsetTarget: Double?) -> Double {
        guard let offsetTarget, offsetTarget > 0 else { return liveBuffer.seconds }
        return min(liveBuffer.seconds, offsetTarget)
    }
}
