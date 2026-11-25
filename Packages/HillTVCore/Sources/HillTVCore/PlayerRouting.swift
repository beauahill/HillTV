import Foundation

/// Which player can open a movie or episode, judged by its file type.
public enum MediaContainer {
    /// File types Apple's player opens. Anything else (mkv, avi, ts, wmv,
    /// flv, webm) needs KSPlayer. A list of what works, not of what doesn't,
    /// so a type nobody thought of goes to the player that can try it.
    public static let applePlayable: Set<String> = ["mp4", "m4v", "mov", "m3u8"]

    /// False when the URL's file type is one Apple's player can't open.
    /// A URL with no file type gets the benefit of the doubt.
    public static func applePlayerCanOpen(_ url: URL) -> Bool {
        let type = url.pathExtension.lowercased()
        return type.isEmpty || applePlayable.contains(type)
    }
}

/// Picks which of a player's position reports are worth saving, for players
/// that report many times a second.
///
/// When playback resumes from a saved point, the first reports can arrive
/// before the jump to that point has happened. Saving one of those would
/// replace the saved point with the first seconds of the film, so reports
/// are ignored until the position has reached it.
public struct ProgressTracker: Sendable {
    public struct Report: Equatable, Sendable {
        public let position: Double
        /// Nil when the player doesn't know the length.
        public let duration: Double?
    }

    /// The last report worth keeping. Save this once more when the player closes.
    public private(set) var latest: Report?

    private let resumeFrom: Double
    private let interval: Double
    private var reachedResumePoint: Bool
    private var lastSaved: Double?

    /// How far before the resume point a report still counts as having
    /// reached it. A player can only jump to a point where the picture can
    /// start, which may be some seconds earlier.
    static let resumeSlack: Double = 30

    /// - Parameters:
    ///   - resumeFrom: Seconds the player was asked to start from, if any.
    ///   - interval: Seconds the position must move before it is saved again.
    public init(resumeFrom: Double? = nil, interval: Double = 10) {
        let start = resumeFrom ?? 0
        self.resumeFrom = start.isFinite ? max(0, start) : 0
        self.interval = interval
        reachedResumePoint = self.resumeFrom <= Self.resumeSlack
    }

    /// Takes one report from the player. Returns it when it should be saved now.
    public mutating func report(position: Double, duration: Double) -> Report? {
        guard position.isFinite, position > 0 else { return nil }
        if !reachedResumePoint {
            guard position >= resumeFrom - Self.resumeSlack else { return nil }
            reachedResumePoint = true
        }
        let report = Report(position: position, duration: duration.isFinite && duration > 0 ? duration : nil)
        latest = report
        if let lastSaved, abs(position - lastSaved) < interval { return nil }
        lastSaved = position
        return report
    }
}
