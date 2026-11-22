import Foundation
import HillTVCore
import SwiftUI

/// Something the player can open.
struct PlayableItem: Identifiable, Hashable {
    let id: String
    let title: String
    var subtitle: String?
    let url: URL
    let isLive: Bool
    /// When set, the player saves progress under this entry (its position is ignored).
    var history: WatchEntry?
    /// Seconds to start from, for resuming.
    var startAt: Double?
}

extension PlayableItem {
    /// Rebuilds the stream URL from the IDs saved in history.
    static func resuming(_ entry: WatchEntry, client: XtreamClient) -> PlayableItem? {
        let url: URL
        switch entry.kind {
        case .movie:
            guard let id = Int(entry.streamID) else { return nil }
            url = client.movieURL(streamID: id, containerExtension: entry.containerExtension)
        case .episode:
            url = client.episodeURL(episodeID: entry.streamID, containerExtension: entry.containerExtension)
        }
        return PlayableItem(
            id: entry.id,
            title: entry.title,
            subtitle: entry.subtitle,
            url: url,
            isLive: false,
            history: entry,
            startAt: entry.position
        )
    }
}

extension PlayableItem {
    /// A movie from the start or from `startAt`, with a history entry so progress is saved.
    static func movie(
        _ movie: VODStream,
        title: String,
        subtitle: String?,
        artworkURL: URL?,
        client: XtreamClient,
        startAt: Double?
    ) -> PlayableItem {
        let template = WatchEntry(
            kind: .movie,
            streamID: String(movie.id),
            containerExtension: movie.containerExtension,
            title: title,
            subtitle: subtitle,
            artworkURL: artworkURL,
            position: 0,
            duration: nil
        )
        return PlayableItem(
            id: "movie-\(movie.id)",
            title: title,
            subtitle: subtitle,
            url: client.movieURL(movie),
            isLive: false,
            history: template,
            startAt: startAt
        )
    }
}

enum PlayerEngineKind: String, CaseIterable, Identifiable {
    /// Apple's player. HLS (.m3u8) and mp4 only, but hardware-perfect and
    /// gets the native tvOS transport bar and info panel for free.
    case avPlayer
    /// KSPlayer (FFmpeg). Plays raw MPEG-TS, mkv, AC3/DTS and most odd streams.
    /// Only available when the app is built with project-ksplayer.yml.
    case ksPlayer

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .avPlayer: return "Apple (AVPlayer)"
        case .ksPlayer: return "KSPlayer (FFmpeg)"
        }
    }

    var detail: String {
        switch self {
        case .avPlayer:
            return "Native player. Live channels are requested as HLS (.m3u8). Movies in mkv or with DTS audio may not play."
        case .ksPlayer:
            return "FFmpeg-based. Live channels are requested as MPEG-TS for faster starts. Plays mkv and most audio formats."
        }
    }

    var isAvailable: Bool {
        switch self {
        case .avPlayer: return true
        case .ksPlayer: return KSPlayerSupport.isLinked
        }
    }

    static var defaultEngine: PlayerEngineKind {
        KSPlayerSupport.isLinked ? .ksPlayer : .avPlayer
    }
}

/// Full-screen player that picks the engine and shows a readable error if
/// playback fails, instead of a silent black screen.
struct PlayerView: View {
    let item: PlayableItem
    let engine: PlayerEngineKind
    let userAgent: String
    /// Live buffer and live delay, from Settings.
    var settings = PlaybackSettings()
    /// Called with (position, duration) in seconds while a movie or episode plays, and once on close.
    var onProgress: (@MainActor (Double, Double?) -> Void)?

    @State private var errorMessage: String?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            Color.black
            if let errorMessage {
                PlaybackErrorView(item: item, engine: engine, message: errorMessage) { dismiss() }
            } else if engine == .ksPlayer && KSPlayerSupport.isLinked {
                KSPlayerEngineView(item: item, userAgent: userAgent)
            } else {
                AVPlayerEngineView(
                    item: item,
                    userAgent: userAgent,
                    settings: settings,
                    errorMessage: $errorMessage,
                    onProgress: onProgress
                )
            }
        }
        .ignoresSafeArea()
    }
}

private struct PlaybackErrorView: View {
    let item: PlayableItem
    let engine: PlayerEngineKind
    let message: String
    let close: () -> Void

    var body: some View {
        VStack(spacing: 30) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 90))
            Text("Couldn't play \(item.title)")
                .font(.title2.bold())
            Text(message)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            if engine == .avPlayer {
                Text(KSPlayerSupport.isLinked
                     ? "Try switching the player to KSPlayer in Settings."
                     : "AVPlayer only handles HLS and mp4. A KSPlayer build plays more formats.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            Text(item.url.lastPathComponent)
                .font(.caption.monospaced())
                .foregroundStyle(.tertiary)
            Button("Close", action: close)
        }
        .padding(80)
        .frame(maxWidth: 1200)
    }
}

extension View {
    /// Presents `PlayerView` full screen whenever `item` is set.
    func playerCover(item: Binding<PlayableItem?>) -> some View {
        modifier(PlayerCoverModifier(item: item))
    }
}

private struct PlayerCoverModifier: ViewModifier {
    @Binding var item: PlayableItem?
    @Environment(AppModel.self) private var app

    private func progressHandler(for item: PlayableItem) -> (@MainActor (Double, Double?) -> Void)? {
        guard !item.isLive, let template = item.history else { return nil }
        let store = app.watchHistory
        return { position, duration in
            var entry = template
            entry.position = position
            entry.duration = duration
            entry.updatedAt = Date()
            store.record(entry)
        }
    }

    func body(content: Content) -> some View {
        content.fullScreenCover(item: $item) { item in
            PlayerView(
                item: item,
                engine: app.engine,
                userAgent: AppModel.userAgent,
                settings: app.playback,
                onProgress: progressHandler(for: item)
            )
        }
    }
}
