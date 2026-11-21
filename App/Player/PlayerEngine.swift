import Foundation
import SwiftUI

/// Something the player can open.
struct PlayableItem: Identifiable, Hashable {
    let id: String
    let title: String
    var subtitle: String?
    let url: URL
    let isLive: Bool
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
                AVPlayerEngineView(item: item, userAgent: userAgent, errorMessage: $errorMessage)
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

    func body(content: Content) -> some View {
        content.fullScreenCover(item: $item) { item in
            PlayerView(item: item, engine: app.engine, userAgent: AppModel.userAgent)
        }
    }
}
