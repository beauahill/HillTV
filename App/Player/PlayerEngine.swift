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
    /// KSPlayer (FFmpeg) for live channels: it plays the raw MPEG-TS stream,
    /// which runs closer to the broadcast than HLS. Only available when the
    /// app is built with project-ksplayer.yml.
    ///
    /// This choice is about live channels. Movies and episodes don't follow
    /// it: see `PlayerView.usesKSPlayer`.
    case ksPlayer

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .avPlayer: return "Apple player"
        case .ksPlayer: return "KSPlayer for live channels"
        }
    }

    var detail: String {
        switch self {
        case .avPlayer:
            return KSPlayerSupport.isLinked
                ? "Live channels play in Apple's player as HLS (.m3u8), which runs further behind the broadcast. Movies and episodes use it too, except files it can't open, such as mkv. Those play in KSPlayer."
                : "Apple's player for everything. Live channels are requested as HLS (.m3u8), which runs further behind the broadcast. It can't open some movie files, such as mkv."
        case .ksPlayer:
            return "Live channels play the raw stream (MPEG-TS) through KSPlayer, for less delay behind the broadcast. Its on-screen controls look different. Movies and episodes still use the Apple player when it can open the file. Other files, such as mkv, play in KSPlayer whichever is picked here."
        }
    }

    var isAvailable: Bool {
        switch self {
        case .avPlayer: return true
        case .ksPlayer: return KSPlayerSupport.isLinked
        }
    }

    /// Always the Apple player. A build that includes KSPlayer offers it in
    /// Settings but never switches to it by itself.
    static var defaultEngine: PlayerEngineKind {
        .avPlayer
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
    /// Set when Apple's player couldn't open a movie or episode and KSPlayer took over.
    @State private var fellBack = false
    @Environment(\.dismiss) private var dismiss

    /// Live channels follow the choice in Settings. Movies and episodes use
    /// Apple's player whenever it can open the file, because it has the
    /// native transport bar. KSPlayer takes the file types it can't open
    /// (mkv, avi), and any file it tried and couldn't start.
    private var usesKSPlayer: Bool {
        guard KSPlayerSupport.isLinked else { return false }
        if item.isLive { return engine == .ksPlayer }
        return fellBack || !MediaContainer.applePlayerCanOpen(item.url)
    }

    /// A movie or episode Apple's player can't start gets one try in
    /// KSPlayer before the error screen. The file type in the URL comes from
    /// the panel and can be wrong.
    private var canFallBack: Bool {
        KSPlayerSupport.isLinked && !item.isLive
    }

    var body: some View {
        ZStack {
            Color.black
            if let errorMessage {
                PlaybackErrorView(item: item, usedKSPlayer: usesKSPlayer, message: errorMessage) { dismiss() }
            } else if usesKSPlayer {
                KSPlayerEngineView(
                    item: item,
                    userAgent: userAgent,
                    settings: settings,
                    errorMessage: $errorMessage,
                    onProgress: onProgress
                )
            } else {
                AVPlayerEngineView(
                    item: item,
                    userAgent: userAgent,
                    settings: settings,
                    errorMessage: $errorMessage,
                    onProgress: onProgress,
                    onFailedToStart: canFallBack ? { _ in fellBack = true } : nil
                )
            }
        }
        .ignoresSafeArea()
    }
}

private struct PlaybackErrorView: View {
    let item: PlayableItem
    let usedKSPlayer: Bool
    let message: String
    let close: () -> Void

    /// What might help, when something would.
    private var hint: String? {
        if usedKSPlayer { return nil }
        if KSPlayerSupport.isLinked {
            // Files Apple's player can't open already went to KSPlayer, so
            // only live channels have another player to try.
            return item.isLive ? "Try switching the player to KSPlayer in Settings." : nil
        }
        if !item.isLive, !MediaContainer.applePlayerCanOpen(item.url) {
            return "This build only has Apple's player, which can't open .\(item.url.pathExtension.lowercased()) files. A build that includes KSPlayer can."
        }
        return "Apple's player only handles HLS and mp4. A build that includes KSPlayer plays more formats."
    }

    var body: some View {
        VStack(spacing: 30) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 90))
            Text("Couldn't play \(item.title)")
                .font(.title2.bold())
            Text(message)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            if let hint {
                Text(hint)
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
